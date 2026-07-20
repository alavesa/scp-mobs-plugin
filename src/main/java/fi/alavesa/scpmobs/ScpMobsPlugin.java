package fi.alavesa.scpmobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

public final class ScpMobsPlugin extends JavaPlugin implements Listener {

    private BlinkManager blink;
    private ScpTask task;
    /** Half-linked doorway: first /scpmob pd door stores endpoint A here until the second call. */
    private final java.util.Map<java.util.UUID, Location> pendingDoor = new java.util.HashMap<>();

    /** Resource key of the datapack-registered pocket dimension world. */
    public static final NamespacedKey POCKET_KEY = new NamespacedKey("scp", "pocket");

    @Override
    public void onEnable() {
        saveDefaultConfigSafely();
        installDimensionDatapack();
        blink = new BlinkManager();
        blink.setEnabled(getConfig().getBoolean("blink.enabled", true));
        task = new ScpTask(this, blink);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new CageListener(task), this);
        getServer().getScheduler().runTaskTimer(this, () -> blink.tick(), 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, task, 40L, 2L);
        getLogger().info("ScpMobs enabled - blink " + (blink.isEnabled() ? "on" : "off")
            + ", pocket dimension " + (pocketWorld() != null ? "ready" : "PENDING RESTART"));
    }

    /** The pocket dimension world, or null until the datapack is registered (needs one restart). */
    public World pocketWorld() {
        return Bukkit.getWorld(POCKET_KEY);
    }

    // The dimension is a real datapack dimension (like the nether/end) so it gets native
    // eternal-dark fog and its own sky. Bukkit can't register a dimension at runtime, so we
    // drop the datapack into the main world's datapacks folder; it loads on the next restart.
    private void installDimensionDatapack() {
        try {
            File world = new File(Bukkit.getWorldContainer(), Bukkit.getWorlds().get(0).getName());
            File root = new File(world, "datapacks/scp_pocket");
            boolean fresh = !root.exists();
            new File(root, "data/scp/dimension_type").mkdirs();
            new File(root, "data/scp/dimension").mkdirs();
            writeIfAbsent(new File(root, "pack.mcmeta"), PACK_MCMETA);
            writeIfAbsent(new File(root, "data/scp/dimension_type/pocket.json"), DIM_TYPE_JSON);
            writeIfAbsent(new File(root, "data/scp/dimension/pocket.json"), DIM_JSON);
            if (fresh || pocketWorld() == null) {
                getLogger().warning("SCP-106 pocket dimension datapack installed at "
                    + root.getPath() + " - RESTART the server once to register the 'scp:pocket' dimension.");
            }
        } catch (Exception e) {
            getLogger().warning("Could not install the pocket-dimension datapack: " + e.getMessage());
        }
    }

    private void writeIfAbsent(File f, String content) throws java.io.IOException {
        if (f.exists()) return;
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String PACK_MCMETA =
        "{\"pack\":{\"pack_format\":81,\"supported_formats\":{\"min_inclusive\":4,\"max_inclusive\":9999},"
        + "\"description\":\"SCP-106 pocket dimension\"}}";

    private static final String DIM_TYPE_JSON = """
        {
          "ultrawarm": false,
          "natural": false,
          "piglin_safe": false,
          "respawn_anchor_works": false,
          "bed_works": false,
          "has_raids": false,
          "has_skylight": false,
          "has_ceiling": false,
          "coordinate_scale": 1.0,
          "ambient_light": 0.05,
          "logical_height": 256,
          "effects": "minecraft:the_end",
          "infiniburn": "#minecraft:infiniburn_overworld",
          "min_y": 0,
          "height": 256,
          "monster_spawn_block_light_limit": 0,
          "monster_spawn_light_level": 0,
          "fixed_time": 18000
        }""";

    private static final String DIM_JSON = """
        {
          "type": "scp:pocket",
          "generator": {
            "type": "minecraft:flat",
            "settings": {
              "biome": "minecraft:deep_dark",
              "lakes": false,
              "features": false,
              "layers": [
                {"block": "minecraft:bedrock", "height": 1},
                {"block": "minecraft:sculk", "height": 6}
              ]
            }
          }
        }""";

    private void saveDefaultConfigSafely() {
        getConfig().addDefault("blink.enabled", true);
        getConfig().addDefault("scp106.speed", 0.35);
        getConfig().addDefault("scp106.wall-speed", 0.12);
        getConfig().addDefault("mobs.target-creative", false);
        getConfig().addDefault("breach.173", false);
        getConfig().addDefault("breach.106", false);
        getConfig().addDefault("scp106.breach-max", 2);
        getConfig().addDefault("scp106.ambush-chance", 0.08);
        getConfig().addDefault("pd.escape-seconds", 90);
        getConfig().addDefault("pd.exit-radius", 2.5);
        getConfig().addDefault("pd.door-radius", 1.6);
        getConfig().addDefault("pd.tar-block", "SCULK");
        getConfig().addDefault("pd.tar-slowness", 2);   // potion amplifier: 2 = Slowness III
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public Location pocketDimension() {
        if (!getConfig().isSet("pd.world")) return null;
        var world = Bukkit.getWorld(getConfig().getString("pd.world", ""));
        if (world == null) world = pocketWorld();   // name didn't resolve - fall back to the real dimension
        if (world == null) return null;
        return new Location(world,
            getConfig().getDouble("pd.x"), getConfig().getDouble("pd.y"), getConfig().getDouble("pd.z"),
            (float) getConfig().getDouble("pd.yaw"), 0f);
    }

    /** The real ways OUT of the pocket dimension. Reaching one escapes; if none
     *  are set the PD has no exit (the old "just corrode away" behaviour). */
    public java.util.List<Location> pocketExits() {
        java.util.List<Location> out = new java.util.ArrayList<>();
        for (String s : getConfig().getStringList("pd.exits")) {
            String[] p = s.split(",");
            if (p.length < 4) continue;
            var w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            try {
                out.add(new Location(w, Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public void addPocketExit(Location l) {
        java.util.List<String> list = getConfig().getStringList("pd.exits");
        list.add(l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ());
        getConfig().set("pd.exits", list);
        saveConfig();
    }

    public void clearPocketExits() {
        getConfig().set("pd.exits", null);
        saveConfig();
    }

    public double pocketExitRadius() { return getConfig().getDouble("pd.exit-radius", 2.5); }
    public int pocketEscapeSeconds() { return getConfig().getInt("pd.escape-seconds", 90); }
    public double pocketDoorRadius() { return getConfig().getDouble("pd.door-radius", 1.6); }
    public int pocketTarSlowness() { return getConfig().getInt("pd.tar-slowness", 2); }

    /** The block that behaves as corroding tar (slows + darkens). Defaults to sculk. */
    public Material pocketTarBlock() {
        Material m = Material.matchMaterial(getConfig().getString("pd.tar-block", "SCULK"));
        return m != null && m.isBlock() ? m : Material.SCULK;
    }

    /** Linked doorways inside the pocket dimension. Each is {a, b}; stepping on one
     *  end teleports to the other, so admins can wire up rooms. */
    public java.util.List<Location[]> pocketDoors() {
        java.util.List<Location[]> out = new java.util.ArrayList<>();
        World w = pocketWorld();
        if (w == null) return out;
        for (String s : getConfig().getStringList("pd.doors")) {
            String[] p = s.split(",");
            if (p.length < 8) continue;
            try {
                Location a = new Location(w, Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]), Float.parseFloat(p[3]), 0f);
                Location b = new Location(w, Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                    Double.parseDouble(p[6]), Float.parseFloat(p[7]), 0f);
                out.add(new Location[]{a, b});
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public void addPocketDoor(Location a, Location b) {
        java.util.List<String> list = getConfig().getStringList("pd.doors");
        list.add(a.getX() + "," + a.getY() + "," + a.getZ() + "," + a.getYaw() + ","
               + b.getX() + "," + b.getY() + "," + b.getZ() + "," + b.getYaw());
        getConfig().set("pd.doors", list);
        saveConfig();
    }

    public void clearPocketDoors() {
        getConfig().set("pd.doors", null);
        saveConfig();
    }

    // SCPs cannot be harmed; the statue must never burn or drown either.
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) task.registerNoise(player); // pain is loud
        var tags = event.getEntity().getScoreboardTags();
        if (tags.contains(ScpTask.TAG_173) || tags.contains(ScpTask.TAG_106)
            || tags.contains(ScpTask.TAG_106 + ".hitbox") || tags.contains(ScpTask.TAG_DISPLAY)
            || tags.contains(ScpTask.TAG_650) || tags.contains(ScpTask.TAG_049)
            || tags.contains(ScpTask.TAG_939) || tags.contains(ScpTask.TAG_999)) {
            event.setCancelled(true);
        }
    }

    // Hitting SCP-999 does nothing (the damage above is cancelled) - it giggles.
    @EventHandler
    public void onPoke(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(ScpTask.TAG_999)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        event.getEntity().getWorld().playSound(event.getEntity().getLocation(),
            org.bukkit.Sound.ENTITY_SLIME_SQUISH, 0.8f, 1.6f);
        Msg.actionbar(player, Component.text("It giggles.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    // Dying under SCP-049's hands is not the end of the story.
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        task.reanimate(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        blink.forget(event.getPlayer().getUniqueId());
        task.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scpmobs.admin")) return error(sender, "No permission.");
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 2) return usage(sender);
                switch (args[1].toLowerCase()) {
                    case "173", "scp173" -> {
                        task.spawn173(player.getLocation());
                        sender.sendMessage(Component.text("SCP-173 is here. Don't blink.", NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    case "106", "scp106" -> {
                        task.spawn106(player.getLocation());
                        sender.sendMessage(Component.text("SCP-106 is loose.", NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    case "650", "scp650" -> {
                        task.spawn650(player.getLocation());
                        sender.sendMessage(Component.text("SCP-650 is placed. Keep track of where it was.",
                            NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    case "049", "scp049" -> {
                        task.spawn049(player.getLocation());
                        sender.sendMessage(Component.text("SCP-049 senses a pestilence nearby.",
                            NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    case "939", "scp939" -> {
                        task.spawn939(player.getLocation());
                        sender.sendMessage(Component.text("SCP-939 is listening. Stop moving.",
                            NamedTextColor.GRAY));
                        warnCreative(player);
                    }
                    case "999", "scp999" -> {
                        task.spawn999(player.getLocation());
                        sender.sendMessage(Component.text("SCP-999 wobbles over, delighted.",
                            NamedTextColor.GRAY));
                    }
                    default -> { return error(sender, "Known SCPs: 173, 106, 650, 049, 939, 999"); }
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
                int c173 = 0, c106 = 0, c650 = 0, c049 = 0, c939 = 0, c999 = 0, patients = 0;
                for (World world : Bukkit.getWorlds()) {
                    for (Wolf wolf : world.getEntitiesByClass(Wolf.class)) {
                        var tags = wolf.getScoreboardTags();
                        if (tags.contains(ScpTask.TAG_173)) c173++;
                        else if (tags.contains(ScpTask.TAG_650)) c650++;
                        else if (tags.contains(ScpTask.TAG_049)) c049++;
                        else if (tags.contains(ScpTask.TAG_939)) c939++;
                        else if (tags.contains(ScpTask.TAG_999)) c999++;
                    }
                    for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                        if (display.getScoreboardTags().contains(ScpTask.TAG_106)) c106++;
                    }
                    for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                        if (zombie.getScoreboardTags().contains(ScpTask.TAG_049_PATIENT)) patients++;
                    }
                }
                sender.sendMessage(Component.text("Active: 173 x" + c173 + ", 106 x" + c106
                    + ", 650 x" + c650 + ", 049 x" + c049 + ", 939 x" + c939 + ", 999 x" + c999
                    + ", 049-2 x" + patients, NamedTextColor.GRAY));
                return true;
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                int removed = 0;
                for (Entity entity : player.getLocation().getNearbyEntities(16, 16, 16)) {
                    var tags = entity.getScoreboardTags();
                    if (tags.contains(ScpTask.TAG_173) || tags.contains(ScpTask.TAG_106)
                        || tags.contains(ScpTask.TAG_106 + ".hitbox") || tags.contains(ScpTask.TAG_DISPLAY)
                        || tags.contains(ScpTask.TAG_650) || tags.contains(ScpTask.TAG_049)
                        || tags.contains(ScpTask.TAG_939) || tags.contains(ScpTask.TAG_999)
                        || tags.contains(ScpTask.TAG_049_PATIENT)) {
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
                if (args[1].equalsIgnoreCase("clearexits")) {
                    clearPocketExits();
                    sender.sendMessage(Component.text("Pocket dimension exits cleared - no way out remains.",
                        NamedTextColor.GRAY));
                    return true;
                }
                if (args[1].equalsIgnoreCase("exit")) {
                    if (!(sender instanceof Player p2)) return error(sender, "Players only.");
                    addPocketExit(p2.getLocation());
                    sender.sendMessage(Component.text("Added a pocket-dimension exit here (now "
                        + pocketExits().size() + " total). Reach within " + pocketExitRadius()
                        + " blocks to escape.", NamedTextColor.GRAY));
                    return true;
                }
                if (args[1].equalsIgnoreCase("time")) {
                    if (args.length < 3) return error(sender, "Usage: /scpmob pd time <seconds>");
                    int secs;
                    try { secs = Integer.parseInt(args[2]); }
                    catch (NumberFormatException e) { return error(sender, "Not a number: " + args[2]); }
                    secs = Math.max(5, Math.min(600, secs));
                    getConfig().set("pd.escape-seconds", secs);
                    saveConfig();
                    sender.sendMessage(Component.text("Pocket-dimension escape time set to " + secs
                        + " seconds.", NamedTextColor.GRAY));
                    return true;
                }
                if (args[1].equalsIgnoreCase("tp")) {
                    if (!(sender instanceof Player p3)) return error(sender, "Players only.");
                    World pw = pocketWorld();
                    if (pw == null) return error(sender, "The pocket dimension isn't registered yet - "
                        + "restart the server once after installing this version.");
                    Location dest = pocketDimension();
                    if (dest == null || dest.getWorld() == null || !dest.getWorld().equals(pw)) {
                        dest = pw.getSpawnLocation();
                    }
                    p3.teleport(dest);
                    sender.sendMessage(Component.text("Teleported into SCP-106's pocket dimension.",
                        NamedTextColor.GRAY));
                    return true;
                }
                if (args[1].equalsIgnoreCase("cleardoors")) {
                    clearPocketDoors();
                    pendingDoor.clear();
                    sender.sendMessage(Component.text("All pocket-dimension doorways cleared.",
                        NamedTextColor.GRAY));
                    return true;
                }
                if (args[1].equalsIgnoreCase("door")) {
                    if (!(sender instanceof Player p4)) return error(sender, "Players only.");
                    World pw = pocketWorld();
                    if (pw == null || !p4.getWorld().equals(pw)) {
                        return error(sender, "Stand inside the pocket dimension (/scpmob pd tp) to link a doorway.");
                    }
                    Location a = pendingDoor.remove(p4.getUniqueId());
                    if (a == null) {
                        pendingDoor.put(p4.getUniqueId(), p4.getLocation());
                        sender.sendMessage(Component.text("Doorway endpoint A marked here. Run /scpmob pd door "
                            + "again at the other room to link them.", NamedTextColor.GRAY));
                    } else {
                        addPocketDoor(a, p4.getLocation());
                        sender.sendMessage(Component.text("Doorway linked (" + pocketDoors().size()
                            + " total). Stepping on either end now teleports to the other.", NamedTextColor.GRAY));
                    }
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
                case "spawn" -> Stream.of("173", "106", "650", "049", "939", "999")
                    .filter(o -> o.startsWith(args[1])).toList();
                case "give" -> Stream.of("cage").filter(o -> o.startsWith(args[1].toLowerCase())).toList();
                case "breach", "contain" -> Stream.of("173", "106", "all").filter(o -> o.startsWith(args[1])).toList();
                case "pd" -> Stream.of("set", "clear", "exit", "clearexits", "time", "tp", "door", "cleardoors")
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList();
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
            "/scpmob spawn <173|106|650|049|939|999> | give cage [player] | breach|contain <173|106|all> | status | remove | blink on|off", NamedTextColor.AQUA));
        sender.sendMessage(Component.text(
            "/scpmob pd set|clear|tp | exit|clearexits | time <s> | door|cleardoors  (SCP-106 pocket dimension)", NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
