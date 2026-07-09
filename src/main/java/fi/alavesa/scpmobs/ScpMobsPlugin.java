package fi.alavesa.scpmobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Stream;

public final class ScpMobsPlugin extends JavaPlugin implements Listener {

    private BlinkManager blink;
    private ScpTask task;

    @Override
    public void onEnable() {
        saveDefaultConfigSafely();
        blink = new BlinkManager();
        blink.setEnabled(getConfig().getBoolean("blink.enabled", true));
        task = new ScpTask(this, blink);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CageListener(task), this);
        getServer().getScheduler().runTaskTimer(this, () -> blink.tick(), 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, task, 40L, 2L);
        getLogger().info("ScpMobs enabled - blink " + (blink.isEnabled() ? "on" : "off"));
    }

    private void saveDefaultConfigSafely() {
        getConfig().addDefault("blink.enabled", true);
        getConfig().addDefault("scp106.speed", 0.22);
        getConfig().addDefault("mobs.target-creative", false);
        getConfig().addDefault("breach.173", false);
        getConfig().addDefault("breach.106", false);
        getConfig().addDefault("scp106.breach-max", 2);
        getConfig().addDefault("scp106.ambush-chance", 0.08);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public Location pocketDimension() {
        if (!getConfig().isSet("pd.world")) return null;
        var world = Bukkit.getWorld(getConfig().getString("pd.world", ""));
        if (world == null) return null;
        return new Location(world,
            getConfig().getDouble("pd.x"), getConfig().getDouble("pd.y"), getConfig().getDouble("pd.z"),
            (float) getConfig().getDouble("pd.yaw"), 0f);
    }

    // SCPs cannot be harmed; the statue must never burn or drown either.
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        var tags = event.getEntity().getScoreboardTags();
        if (tags.contains(ScpTask.TAG_173) || tags.contains(ScpTask.TAG_106)
            || tags.contains(ScpTask.TAG_106 + ".hitbox") || tags.contains(ScpTask.TAG_DISPLAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        blink.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scpmobs.admin")) return error(sender, "No permission.");
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 2) return usage(sender);
                switch (args[1]) {
                    case "173" -> {
                        task.spawn173(player.getLocation());
                        sender.sendMessage(Component.text("SCP-173 is here. Don't blink.", NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    case "106" -> {
                        task.spawn106(player.getLocation());
                        sender.sendMessage(Component.text("SCP-106 is loose.", NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    default -> { return error(sender, "Known SCPs: 173, 106"); }
                }
                return true;
            }
            case "give" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("cage")) {
                    return error(sender, "/scpmob give cage [player]");
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                    : (sender instanceof Player p ? p : null);
                if (target == null) return error(sender, "Player not found.");
                target.getInventory().addItem(CageListener.makeCage()).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(Component.text("Gave a 173 Cage to " + target.getName()
                    + ". Hold right-click on the statue for 15 seconds - and bring a friend who can keep their eyes open.",
                    NamedTextColor.GRAY));
                return true;
            }
            case "breach", "contain" -> {
                if (args.length < 2) return usage(sender);
                boolean breach = args[0].equalsIgnoreCase("breach");
                List<String> which = switch (args[1]) {
                    case "173" -> List.of("173");
                    case "106" -> List.of("106");
                    case "all" -> List.of("173", "106");
                    default -> List.of();
                };
                if (which.isEmpty()) return error(sender, "Use 173, 106 or all.");
                for (String scp : which) {
                    getConfig().set("breach." + scp, breach);
                    if (!breach && scp.equals("106")) {
                        int gone = task.recontain106();
                        if (gone > 0) sender.sendMessage(Component.text(
                            gone + " instance(s) of SCP-106 corroded away.", NamedTextColor.GRAY));
                    }
                    announce(scp, breach);
                }
                saveConfig();
                return true;
            }
            case "status" -> {
                for (String scp : List.of("173", "106")) {
                    boolean breached = getConfig().getBoolean("breach." + scp, false);
                    sender.sendMessage(Component.text("SCP-" + scp + ": "
                        + (breached ? "CONTAINMENT BREACHED" : "contained"),
                        breached ? NamedTextColor.RED : NamedTextColor.GREEN));
                }
                return true;
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                int removed = 0;
                for (Entity entity : player.getLocation().getNearbyEntities(16, 16, 16)) {
                    var tags = entity.getScoreboardTags();
                    if (tags.contains(ScpTask.TAG_173) || tags.contains(ScpTask.TAG_106)
                        || tags.contains(ScpTask.TAG_106 + ".hitbox") || tags.contains(ScpTask.TAG_DISPLAY)) {
                        entity.getPassengers().forEach(Entity::remove);
                        entity.remove();
                        removed++;
                    }
                }
                sender.sendMessage(Component.text("Removed " + removed + " SCP entit" + (removed == 1 ? "y" : "ies")
                    + " within 16 blocks.", NamedTextColor.GRAY));
                return true;
            }
            case "pd" -> {
                if (args.length < 2) return usage(sender);
                if (args[1].equalsIgnoreCase("clear")) {
                    getConfig().set("pd", null);
                    saveConfig();
                    sender.sendMessage(Component.text("Pocket dimension cleared - SCP-106 corrodes instead.",
                        NamedTextColor.GRAY));
                    return true;
                }
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                Location at = player.getLocation();
                getConfig().set("pd.world", at.getWorld().getName());
                getConfig().set("pd.x", at.getX());
                getConfig().set("pd.y", at.getY());
                getConfig().set("pd.z", at.getZ());
                getConfig().set("pd.yaw", (double) at.getYaw());
                saveConfig();
                sender.sendMessage(Component.text("Pocket dimension set to where you stand.", NamedTextColor.GRAY));
                return true;
            }
            case "blink" -> {
                if (args.length < 2) return usage(sender);
                boolean on = args[1].equalsIgnoreCase("on");
                blink.setEnabled(on);
                getConfig().set("blink.enabled", on);
                saveConfig();
                sender.sendMessage(Component.text("Blink meter " + (on ? "on" : "off") + ".", NamedTextColor.GRAY));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> Stream.of("spawn", "give", "remove", "pd", "blink", "breach", "contain", "status")
                .filter(o -> o.startsWith(args[0].toLowerCase())).toList();
            case 2 -> switch (args[0].toLowerCase()) {
                case "spawn" -> Stream.of("173", "106").filter(o -> o.startsWith(args[1])).toList();
                case "give" -> Stream.of("cage").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                case "breach", "contain" -> Stream.of("173", "106", "all").filter(o -> o.startsWith(args[1])).toList();
                case "pd" -> Stream.of("set", "clear").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                case "blink" -> Stream.of("on", "off").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private void announce(String scp, boolean breach) {
        var title = net.kyori.adventure.title.Title.title(
            Component.text(breach ? "CONTAINMENT BREACH" : "CONTAINMENT RESTORED",
                breach ? NamedTextColor.DARK_RED : NamedTextColor.GREEN),
            Component.text("SCP-" + scp + (breach ? " has left containment." : " is secured."),
                NamedTextColor.GRAY));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showTitle(title);
            online.playSound(online.getLocation(),
                breach ? org.bukkit.Sound.ENTITY_ELDER_GUARDIAN_CURSE : org.bukkit.Sound.BLOCK_BEACON_ACTIVATE,
                1f, breach ? 0.5f : 1f);
        }
    }

    private void warnCreative(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
            && !getConfig().getBoolean("mobs.target-creative", false)) {
            player.sendMessage(Component.text(
                "Note: SCPs ignore creative/spectator players - switch to survival to test them "
                + "(or set mobs.target-creative: true).", NamedTextColor.YELLOW));
        }
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/scpmob spawn <173|106> | give cage [player] | breach|contain <173|106|all> | status | remove | pd set|clear | blink on|off", NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
