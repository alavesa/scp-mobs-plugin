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
        getServer().getScheduler().runTaskTimer(this, () -> blink.tick(), 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, task, 40L, 2L);
        getLogger().info("ScpMobs enabled - blink " + (blink.isEnabled() ? "on" : "off"));
    }

    private void saveDefaultConfigSafely() {
        getConfig().addDefault("blink.enabled", true);
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
                    }
                    case "106" -> {
                        task.spawn106(player.getLocation());
                        sender.sendMessage(Component.text("SCP-106 is loose.", NamedTextColor.GRAY));
                    }
                    default -> { return error(sender, "Known SCPs: 173, 106"); }
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
            case 1 -> Stream.of("spawn", "remove", "pd", "blink")
                .filter(o -> o.startsWith(args[0].toLowerCase())).toList();
            case 2 -> switch (args[0].toLowerCase()) {
                case "spawn" -> Stream.of("173", "106").filter(o -> o.startsWith(args[1])).toList();
                case "pd" -> Stream.of("set", "clear").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                case "blink" -> Stream.of("on", "off").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/scpmob spawn <173|106> | remove | pd set|clear | blink on|off", NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
