package fi.alavesa.scpmobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Objective;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The SCPs, ticked statelessly by scoreboard tag so they survive restarts.
 *
 * SCP-173 hunts by TELEPORTING: while unobserved it hops up to 10 blocks
 * toward its prey (one hop per blink), scraping loudly with every jump, and
 * kills on arrival. It never walks - except inside a player-placed cage,
 * where the docile statue trundles after its captor. Observation is sticky:
 * once you are looking at it, a wall edge clipping your line of sight will
 * not free it - only actually looking away (or blinking) does. Glass does
 * not protect it: mob line of sight sees through transparent blocks.
 *
 * SCP-106 walks through walls toward the nearest player - faster in the
 * open, slowed while phasing through solid matter. When his containment is
 * breached he emerges near random players, RISING out of the floor, and
 * rarely teleports directly beneath an unsuspecting victim.
 *
 * SCP-650 is the inverse of 173: it never harms anyone. It stands still
 * while watched and, the moment nobody is looking, silently relocates to a
 * spot a few blocks BEHIND its nearest target, facing them. The only sound
 * it ever makes is the little breath a player hears on re-acquiring it.
 *
 * SCP-049 walks slowly toward the nearest visible player; its touch starts
 * a short blind "surgery" that ends in death, and the dead get back up as
 * SCP-049-2 patients. It never harms its patients or the other SCPs.
 *
 * SCP-939 is blind. It hears movement (position deltas between ticks) and
 * pain, and charges the LAST HEARD POSITION - go quiet and step aside and
 * it savages the empty spot. While idle it plays a lure that is almost,
 * but not quite, a human voice. Sneaking players are silent to it.
 *
 * SCP-999 is the tickle monster: waddles to the nearest player, squishes
 * happily, and its aura heals, clears Darkness/Nausea and bleeds the lab
 * datapack's infection clocks one extra point per second - like the gel.
 */
public final class ScpTask implements Runnable {

    public static final String TAG_173 = "scp.173";
    public static final String TAG_173_HITBOX = "scp.173.hitbox";
    public static final String TAG_173_CAGED = "scp.173.caged";
    public static final String TAG_CAGE3D = "scp.cage3d";
    public static final String TAG_106 = "scp.106";
    public static final String TAG_650 = "scp.650";
    public static final String TAG_049 = "scp.049";
    public static final String TAG_049_PATIENT = "scp049.patient";
    public static final String TAG_939 = "scp.939";
    public static final String TAG_999 = "scp.999";
    public static final String TAG_DISPLAY = "scp.display";

    public static final int CAGING_TICKS = 15 * 20;
    private static final double HOP_RANGE = 10.0;
    private static final int HOP_COOLDOWN = 10;
    private static final double UNCAGE_DISTANCE_SQ = 24 * 24;

    private static final class Caging {
        UUID statue;
        int progress;
        int lastHeld;
    }

    private final ScpMobsPlugin plugin;
    private final BlinkManager blink;
    private final NamespacedKey partnerKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey riseKey;
    private final Map<UUID, Integer> touchCooldown = new HashMap<>();
    private final Map<UUID, Caging> caging = new HashMap<>();
    private final Map<UUID, Set<UUID>> watchers = new HashMap<>();
    private final Map<UUID, Integer> lastHop = new HashMap<>();
    // SCP-650: armed = has been seen since its last move; startle = who still owes a cue
    private final Set<UUID> armed650 = new HashSet<>();
    private final Map<UUID, Set<UUID>> startle650 = new HashMap<>();
    // SCP-049: victim -> running operation, victim -> reanimation window
    private final Map<UUID, Surgery> surgeries = new HashMap<>();
    private final Map<UUID, Integer> condemned = new HashMap<>();
    private final Map<UUID, Integer> doctorBusy = new HashMap<>();
    // SCP-939: player position deltas double as its ears
    private final Map<UUID, Location> lastSeenPos = new HashMap<>();
    private final Map<UUID, Location> lastNoise = new HashMap<>();
    private final Map<UUID, Integer> lastNoiseTick = new HashMap<>();
    private final Map<UUID, Location> heard = new HashMap<>();
    private final Map<UUID, Integer> heardTick = new HashMap<>();
    private final Map<UUID, Integer> biteCooldown = new HashMap<>();
    private final Map<UUID, Integer> nextLure = new HashMap<>();
    // SCP-999 pacing
    private final Map<UUID, Integer> nextSquish = new HashMap<>();
    private final Map<UUID, Integer> nextWander = new HashMap<>();
    /** Players currently lost in SCP-106's pocket dimension, with their way home. */
    private final Map<UUID, PdSession> pdSessions = new HashMap<>();
    /** Debounce so a doorway doesn't immediately teleport the player back. */
    private final Map<UUID, Integer> doorCooldown = new HashMap<>();
    private int tick;

    /** A victim's stay in the pocket dimension: where they came from and when it runs out. */
    private record PdSession(Location returnLoc, int deadline) { }

    private static final class Surgery {
        final UUID doctor;
        final int until;
        Surgery(UUID doctor, int until) {
            this.doctor = doctor;
            this.until = until;
        }
    }

    public ScpTask(ScpMobsPlugin plugin, BlinkManager blink) {
        this.plugin = plugin;
        this.blink = blink;
        this.partnerKey = new NamespacedKey(plugin, "partner");
        this.ownerKey = new NamespacedKey(plugin, "cage_owner");
        this.riseKey = new NamespacedKey(plugin, "rise_to");
    }

    @Override
    public void run() {
        tick += 2; // scheduled every 2 ticks
        trackNoise();
        for (World world : Bukkit.getWorlds()) {
            for (Wolf wolf : world.getEntitiesByClass(Wolf.class)) {
                Set<String> tags = wolf.getScoreboardTags();
                if (tags.contains(TAG_173)) tick173(wolf);
                else if (tags.contains(TAG_650)) tick650(wolf);
                else if (tags.contains(TAG_049)) tick049(wolf);
                else if (tags.contains(TAG_939)) tick939(wolf);
                else if (tags.contains(TAG_999)) tick999(wolf);
            }
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getScoreboardTags().contains(TAG_106)) tick106(display);
            }
        }
        tickCaging();
        tickSurgeries();
        World pw = plugin.pocketWorld();
        if (pw != null && !pw.getPlayers().isEmpty()) tickPocketWorld(pw);
        if (!pdSessions.isEmpty()) tickPocket();
        if (tick % 600 == 0) tickBreachSpawns();
        touchCooldown.entrySet().removeIf(e -> e.getValue() < tick);
        biteCooldown.entrySet().removeIf(e -> e.getValue() < tick);
        condemned.entrySet().removeIf(e -> e.getValue() < tick);
        if (tick % 100 == 0) sweepOrphans();
    }

    private void sweepOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getScoreboardTags().contains(TAG_DISPLAY)
                    && !display.getScoreboardTags().contains(TAG_106)
                    && display.getVehicle() == null) {
                    display.remove();
                }
            }
            for (Interaction interaction : world.getEntitiesByClass(Interaction.class)) {
                if (interaction.getScoreboardTags().contains(TAG_173_HITBOX)
                    && interaction.getVehicle() == null) {
                    interaction.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------- SCP-173

    private void tick173(Wolf statue) {
        if (statue.getScoreboardTags().contains(TAG_173_CAGED)) {
            tickCaged(statue);
            return;
        }
        statue.setAI(false); // uncaged 173 NEVER walks - it teleports
        List<Player> nearby = statue.getLocation().getNearbyPlayers(32).stream()
            .filter(this::isFairGame)
            .toList();
        if (nearby.isEmpty()) {
            watchers.remove(statue.getUniqueId());
            return;
        }
        boolean observed = updateWatchers(statue, nearby);
        if (observed) return;

        Player target = nearby.stream()
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(statue.getLocation()),
                b.getLocation().distanceSquared(statue.getLocation())))
            .orElse(null);
        if (target == null) return;

        // already in reach: the kill does not wait for a hop
        if (target.getLocation().distanceSquared(statue.getLocation()) < 3.3) {
            snap(statue, target);
            return;
        }
        Integer last = lastHop.get(statue.getUniqueId());
        if (last != null && tick - last < HOP_COOLDOWN) return;
        lastHop.put(statue.getUniqueId(), tick);
        hop(statue, target);
    }

    /**
     * Sticky observation. A player WATCHES the statue if they face it and
     * either have line of sight (glass is transparent to this check) or were
     * already watching - so a wall edge grazing the sightline never silently
     * frees it. Looking away or blinking is what releases it.
     */
    private boolean updateWatchers(Wolf statue, List<Player> nearby) {
        Set<UUID> old = watchers.computeIfAbsent(statue.getUniqueId(), id -> new HashSet<>());
        Set<UUID> now = new HashSet<>();
        for (Player player : nearby) {
            if (blink.isBlinking(player)) continue;
            if (!isFacing(player, statue)) continue;
            if (player.hasLineOfSight(statue) || old.contains(player.getUniqueId())) {
                now.add(player.getUniqueId());
            }
        }
        watchers.put(statue.getUniqueId(), now);
        return !now.isEmpty();
    }

    private boolean isFacing(Player player, Entity entity) {
        Vector toEntity = entity.getLocation().add(0, 1.0, 0)
            .toVector().subtract(player.getEyeLocation().toVector());
        if (toEntity.lengthSquared() < 0.01) return true;
        return player.getEyeLocation().getDirection().dot(toEntity.normalize()) > 0.25;
    }

    /** One teleport hop: up to 10 blocks toward the prey, never through walls. */
    private void hop(Wolf statue, Player target) {
        Location from = statue.getLocation().clone().add(0, 0.9, 0);
        Vector toTarget = target.getLocation().add(0, 0.9, 0).toVector().subtract(from.toVector());
        double distance = toTarget.length();
        double wanted = Math.min(HOP_RANGE, distance - 0.9);
        if (wanted < 0.5) return;
        Vector dir = toTarget.clone().normalize();

        double travel = clearance(statue.getWorld(), from, dir, wanted);
        if (travel < 1.2) {
            // cornered: probe rotated directions and take the most useful gap
            double bestScore = -1;
            Vector bestDir = null;
            double bestTravel = 0;
            for (int degrees : new int[]{45, -45, 90, -90, 135, -135, 180}) {
                Vector probe = rotate(dir, Math.toRadians(degrees));
                double c = clearance(statue.getWorld(), from, probe, 6);
                double score = c * (1.0 - Math.abs(degrees) / 360.0);
                if (c >= 2.0 && score > bestScore) {
                    bestScore = score;
                    bestDir = probe;
                    bestTravel = c;
                }
            }
            if (bestDir == null) return; // boxed in - it waits
            dir = bestDir;
            travel = Math.min(6, bestTravel);
        }

        Location dest = from.clone().add(dir.clone().multiply(travel)).subtract(0, 0.9, 0);
        for (int i = 0; i < 4 && dest.getBlock().isPassable()
            && dest.clone().subtract(0, 1, 0).getBlock().isPassable(); i++) {
            dest.subtract(0, 1, 0);
        }
        dest.setYaw(yawToward(dest, target.getLocation()));
        dest.setPitch(0);
        teleportStatue(statue, dest);
        // the scrape-clunk: every move makes a noise
        statue.getWorld().playSound(dest, Sound.BLOCK_STONE_STEP, 1.4f, 0.5f);
        statue.getWorld().playSound(dest, Sound.BLOCK_STONE_BREAK, 0.9f, 0.6f);
        if (target.getLocation().distanceSquared(dest) < 3.3) {
            snap(statue, target);
        }
    }

    private double clearance(World world, Location from, Vector dir, double max) {
        RayTraceResult hit = world.rayTraceBlocks(from, dir, max, FluidCollisionMode.NEVER, true);
        if (hit == null) return max;
        return Math.max(0, hit.getHitPosition().distance(from.toVector()) - 0.8);
    }

    private Vector rotate(Vector v, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return new Vector(v.getX() * cos - v.getZ() * sin, v.getY(), v.getX() * sin + v.getZ() * cos);
    }

    private float yawToward(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Teleporting a vehicle ejects its passengers - move the whole stack. */
    private void teleportStatue(Wolf statue, Location dest) {
        List<Entity> passengers = new ArrayList<>(statue.getPassengers());
        statue.teleport(dest);
        for (Entity passenger : passengers) {
            passenger.teleport(dest);
            statue.addPassenger(passenger);
        }
        syncPassengers(statue);
    }

    /** A caged statue is docile: it walks (its only walking) after its captor. */
    private void tickCaged(Wolf statue) {
        String ownerId = statue.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(UUID.fromString(ownerId));
        boolean lost = owner == null || !owner.isOnline() || owner.isDead()
            || owner.getWorld() != statue.getWorld()
            || owner.getLocation().distanceSquared(statue.getLocation()) > UNCAGE_DISTANCE_SQ;
        if (lost) {
            autoUncage(statue);
            return;
        }
        if (owner.getLocation().distanceSquared(statue.getLocation()) > 9) {
            statue.setAI(true);
            statue.getPathfinder().moveTo(owner, 1.15);
            if (tick % 8 == 0) {
                statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_STONE_STEP, 0.9f, 0.6f);
            }
        } else {
            statue.setAI(false);
        }
        if (tick % 80 == 0) {
            statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.8f, 0.7f);
        }
        syncPassengers(statue);
    }

    /** The captor died or wandered off: the cage clatters to the floor. */
    private void autoUncage(Wolf statue) {
        statue.removeScoreboardTag(TAG_173_CAGED);
        statue.getPersistentDataContainer().remove(ownerKey);
        for (Entity passenger : new ArrayList<>(statue.getPassengers())) {
            if (passenger.getScoreboardTags().contains(TAG_CAGE3D)) {
                passenger.remove();
            }
        }
        statue.getWorld().dropItemNaturally(statue.getLocation(), CageListener.makeCage());
        statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1f, 0.7f);
    }

    private void syncPassengers(Wolf statue) {
        for (Entity passenger : statue.getPassengers()) {
            if (passenger instanceof ItemDisplay display) {
                display.setRotation(statue.getLocation().getYaw(), 0f);
            }
        }
    }

    /** SCPs hunt survival/adventure players; creative only if the config says so. */
    private boolean isFairGame(Player player) {
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) return true;
        return player.getGameMode() == GameMode.CREATIVE
            && plugin.getConfig().getBoolean("mobs.target-creative", false);
    }

    private void snap(Wolf statue, Player victim) {
        Location at = victim.getLocation().clone();
        at.setYaw(yawToward(statue.getLocation(), victim.getLocation()));
        at.setPitch(0);
        Vector back = victim.getLocation().toVector()
            .subtract(statue.getLocation().toVector());
        if (back.lengthSquared() > 0.01) {
            at.subtract(back.normalize().multiply(0.8));
        }
        teleportStatue(statue, at);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_SKELETON_HURT, 1.4f, 0.5f);
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_BONE_BLOCK_BREAK, 1.4f, 0.7f);
        victim.damage(1000.0, statue);
        lastHop.put(statue.getUniqueId(), tick + 20); // savor the moment
    }

    // ------------------------------------------------------------- caging

    /** Called from CageListener for every held-right-click tick on a statue. */
    public void progressCaging(Player player, Wolf statue) {
        if (statue.getScoreboardTags().contains(TAG_173_CAGED)) return;
        Caging session = caging.computeIfAbsent(player.getUniqueId(), id -> new Caging());
        if (!statue.getUniqueId().equals(session.statue)) {
            session.statue = statue.getUniqueId();
            session.progress = 0;
        }
        session.lastHeld = tick;
    }

    private void tickCaging() {
        Iterator<Map.Entry<UUID, Caging>> it = caging.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Caging> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Caging session = entry.getValue();
            if (player == null || !player.isOnline()) { it.remove(); continue; }
            if (tick - session.lastHeld > 8) {
                it.remove();
                Msg.actionbar(player, Component.text("The cage slips.", NamedTextColor.GRAY, TextDecoration.ITALIC));
                continue;
            }
            if (!(Bukkit.getEntity(session.statue) instanceof Wolf statue) || statue.isDead()) {
                it.remove();
                continue;
            }
            session.progress += 2;
            if (session.progress % 20 == 0) {
                int left = (CAGING_TICKS - session.progress) / 20 + 1;
                Msg.actionbar(player, Component.text("Caging SCP-173... " + left + "s",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
                player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.6f, 0.8f);
            }
            if (session.progress >= CAGING_TICKS) {
                it.remove();
                completeCaging(player, statue);
            }
        }
    }

    private void completeCaging(Player player, Wolf statue) {
        if (!CageListener.consumeCage(player)) {
            Msg.actionbar(player, Component.text("The cage is gone.", NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        statue.addScoreboardTag(TAG_173_CAGED);
        statue.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
            player.getUniqueId().toString());
        Location at = statue.getLocation().clone();
        at.setPitch(0);
        ItemDisplay cage = spawnModel(at, Material.IRON_BARS, "scp173_cage3d", 2.35f, 0.45f);
        cage.addScoreboardTag(TAG_CAGE3D);
        statue.addPassenger(cage);
        statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9f, 1.2f);
        statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.7f);
        Msg.actionbar(player, Component.text("SCP-173 is contained. It follows you now.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Sneak + empty hand on a caged statue: the cage comes off. */
    public void uncage(Player player, Wolf statue) {
        statue.removeScoreboardTag(TAG_173_CAGED);
        statue.getPersistentDataContainer().remove(ownerKey);
        for (Entity passenger : new ArrayList<>(statue.getPassengers())) {
            if (passenger.getScoreboardTags().contains(TAG_CAGE3D)) {
                passenger.remove();
            }
        }
        player.getInventory().addItem(CageListener.makeCage()).values()
            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.6f);
        Msg.actionbar(player, Component.text("It is watching you.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    // ------------------------------------------------------------- SCP-106

    private void tick106(ItemDisplay body) {
        if (tickRising(body)) return;
        // Breached 106 has privileges: rarely, he goes under someone's floor.
        if (tick % 600 == 0 && plugin.getConfig().getBoolean("breach.106", false)
            && ThreadLocalRandom.current().nextDouble()
               < plugin.getConfig().getDouble("scp106.ambush-chance", 0.08)) {
            ambush(body);
            return;
        }
        Player target = body.getLocation().getNearbyPlayers(40).stream()
            .filter(this::isFairGame)
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(body.getLocation()),
                b.getLocation().distanceSquared(body.getLocation())))
            .orElse(null);

        Location loc = body.getLocation();
        if (target != null) {
            Vector step = target.getLocation().add(0, 0.9, 0).toVector()
                .subtract(loc.toVector());
            double distance = step.length();
            if (distance > 0.05) {
                // brisk in the open, laboring while phasing through solid matter
                boolean inWall = !loc.getBlock().isPassable();
                double speed = plugin.getConfig().getDouble(
                    inWall ? "scp106.wall-speed" : "scp106.speed", inWall ? 0.12 : 0.35);
                step.normalize().multiply(Math.min(speed, distance));
                loc.add(step);
                loc.setYaw((float) Math.toDegrees(Math.atan2(-step.getX(), step.getZ())));
            }
            body.teleport(loc);
            Interaction partner = partnerOf(body);
            if (partner != null) partner.teleport(loc.clone().subtract(0, 0.9, 0));
            if (distance < 1.2) touch106(body, target);
        }
        if (tick % 4 == 0) {
            body.getWorld().spawnParticle(Particle.SQUID_INK, loc, 3, 0.25, 0.6, 0.25, 0.01);
            body.getWorld().spawnParticle(Particle.ASH, loc, 6, 0.3, 0.6, 0.3, 0);
        }
        if (tick % 160 == 0) {
            body.getWorld().playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT_LAND, 1.2f, 0.35f);
        }
        String frame = (tick % 20 < 10) ? "scp106_walk1" : "scp106_walk2";
        setModel(body, Material.BLACK_CONCRETE, frame);
    }

    /** The entrance: rising slowly out of the floor. Returns true while rising. */
    private boolean tickRising(ItemDisplay body) {
        Double riseTo = body.getPersistentDataContainer().get(riseKey, PersistentDataType.DOUBLE);
        if (riseTo == null) return false;
        Location loc = body.getLocation();
        if (loc.getY() >= riseTo - 0.01) {
            body.getPersistentDataContainer().remove(riseKey);
            return false;
        }
        loc.add(0, 0.08, 0);
        body.teleport(loc);
        Interaction partner = partnerOf(body);
        if (partner != null) partner.teleport(loc.clone().subtract(0, 0.9, 0));
        body.getWorld().spawnParticle(Particle.SQUID_INK, loc.clone().subtract(0, 0.6, 0),
            8, 0.4, 0.15, 0.4, 0.02);
        body.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, loc.clone().subtract(0, 0.8, 0),
            10, 0.4, 0.1, 0.4, Material.BLACK_CONCRETE.createBlockData());
        if (tick % 20 == 0) {
            body.getWorld().playSound(loc, Sound.BLOCK_GRAVEL_BREAK, 1f, 0.5f);
        }
        return true;
    }

    private void startRise(ItemDisplay body, double surfaceY) {
        body.getPersistentDataContainer().set(riseKey, PersistentDataType.DOUBLE, surfaceY + 0.9);
        Location below = body.getLocation();
        below.setY(surfaceY - 1.6);
        body.teleport(below);
        Interaction partner = partnerOf(body);
        if (partner != null) partner.teleport(below.clone().subtract(0, 0.9, 0));
        body.getWorld().playSound(below, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.35f);
    }

    /** Teleport beneath a random player in the same world - then rise. */
    private void ambush(ItemDisplay body) {
        List<Player> candidates = body.getWorld().getPlayers().stream()
            .filter(this::isFairGame)
            .filter(p -> p.getLocation().distanceSquared(body.getLocation()) > 64)
            .toList();
        if (candidates.isEmpty()) return;
        Player unlucky = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location dest = unlucky.getLocation().clone();
        dest.setPitch(0);
        body.teleport(dest.clone().add(0, 0.9, 0));
        Interaction partner = partnerOf(body);
        if (partner != null) partner.teleport(dest);
        startRise(body, dest.getY());
        dest.getWorld().playSound(dest, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.8f, 0.4f);
    }

    private Interaction partnerOf(ItemDisplay body) {
        String id = body.getPersistentDataContainer().get(partnerKey, PersistentDataType.STRING);
        if (id == null) return null;
        return Bukkit.getEntity(UUID.fromString(id)) instanceof Interaction i ? i : null;
    }

    private void touch106(ItemDisplay body, Player victim) {
        Integer until = touchCooldown.get(victim.getUniqueId());
        if (until != null && until > tick) return;
        touchCooldown.put(victim.getUniqueId(), tick + 100);
        victim.damage(12.0, body);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 200, 2));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.4f);
        Location pd = plugin.pocketDimension();
        if (pd == null || !victim.isValid() || victim.isDead()) return;

        Location home = victim.getLocation().clone();  // where 106 grabbed them - their way back
        victim.teleport(pd);

        // With exits set, the pocket dimension becomes a race: find a way out before it
        // digests you. With none set it's the old one-way corrosion (no session, no timer).
        if (plugin.pocketExits().isEmpty()) {
            Msg.actionbar(victim, Component.text("The world corrodes away.", NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        int deadline = tick + Math.max(5, plugin.pocketEscapeSeconds()) * 20;
        pdSessions.put(victim.getUniqueId(), new PdSession(home, deadline));
        Msg.actionbar(victim, Component.text("The world corrodes away. Find a way out.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Sweep every player lost in the pocket dimension: escape if they reach an exit,
     *  otherwise SCP-106 digests them when the clock runs out. */
    private void tickPocket() {
        double r = plugin.pocketExitRadius();
        double r2 = r * r;
        List<Location> exits = plugin.pocketExits();
        var it = pdSessions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            PdSession s = e.getValue();
            if (p == null || !p.isOnline()) { it.remove(); continue; }
            if (p.isDead()) { it.remove(); continue; }

            // Reached an exit? Home you go.
            Location loc = p.getLocation();
            boolean escaped = false;
            for (Location exit : exits) {
                if (exit.getWorld() == null || !exit.getWorld().equals(loc.getWorld())) continue;
                if (exit.distanceSquared(loc) <= r2) { escaped = true; break; }
            }
            if (escaped) {
                it.remove();
                p.removePotionEffect(PotionEffectType.DARKNESS);
                p.removePotionEffect(PotionEffectType.BLINDNESS);
                touchCooldown.put(p.getUniqueId(), tick + 100);  // grace so 106 can't instantly re-grab
                if (s.returnLoc() != null && s.returnLoc().getWorld() != null) p.teleport(s.returnLoc());
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
                Msg.actionbar(p, Component.text("You claw back into the world.",
                    NamedTextColor.GREEN, TextDecoration.ITALIC));
                continue;
            }

            // Out of time? SCP-106 keeps you.
            if (tick >= s.deadline()) {
                it.remove();
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_DEATH, 1f, 0.5f);
                Msg.actionbar(p, Component.text("SCP-106 drags you under.",
                    NamedTextColor.DARK_RED, TextDecoration.ITALIC));
                p.damage(1000.0);
                continue;
            }

            // Otherwise: dread and a ticking clock.
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, false));
            if (tick % 20 == 0) {
                int secs = Math.max(0, (s.deadline() - tick) / 20);
                Msg.actionbar(p, Component.text("The corrosion closes in - " + secs + "s",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
            }
        }
    }

    /** Everyone inside the pocket dimension: tar underfoot drags and blinds them, and
     *  linked doorways whisk them between rooms. Runs for admins and victims alike. */
    private void tickPocketWorld(World pw) {
        Material tar = plugin.pocketTarBlock();
        int slow = Math.max(0, plugin.pocketTarSlowness());
        double dr = plugin.pocketDoorRadius();
        double dr2 = dr * dr;
        List<Location[]> doors = plugin.pocketDoors();
        doorCooldown.entrySet().removeIf(e -> e.getValue() < tick);

        for (Player p : pw.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;

            // Doorways: step near one end, arrive at the other (facing its yaw).
            if (!doors.isEmpty() && !doorCooldown.containsKey(p.getUniqueId())) {
                Location loc = p.getLocation();
                for (Location[] d : doors) {
                    Location from = null, to = null;
                    if (d[0].distanceSquared(loc) <= dr2) { from = d[0]; to = d[1]; }
                    else if (d[1].distanceSquared(loc) <= dr2) { from = d[1]; to = d[0]; }
                    if (to != null) {
                        doorCooldown.put(p.getUniqueId(), tick + 30);
                        p.teleport(to);
                        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.8f);
                        break;
                    }
                }
            }

            // Tar: the block at the feet or just below corrodes movement and sight.
            Material at = p.getLocation().getBlock().getType();
            Material below = p.getLocation().getBlock().getRelative(0, -1, 0).getType();
            if (at == tar || below == tar) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, slow, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, false));
            }
        }
    }

    // ------------------------------------------------------------- SCP-650

    /**
     * The inverse statue. Watched: frozen. Unwatched: it is suddenly a few
     * blocks behind its nearest target, facing them, with no sound and no
     * particles. It never harms anyone - the horror is entirely optical.
     */
    private void tick650(Wolf statue) {
        statue.setAI(false); // it never walks - it is simply elsewhere
        UUID id = statue.getUniqueId();
        List<Player> nearby = statue.getLocation().getNearbyPlayers(32).stream()
            .filter(this::isFairGame)
            .toList();
        if (nearby.isEmpty()) {
            watchers.remove(id);
            return;
        }
        Set<UUID> before = new HashSet<>(watchers.getOrDefault(id, Set.of()));
        boolean observed = updateWatchers(statue, nearby);
        if (observed) {
            armed650.add(id);
            startleNewWatchers(statue, before);
            return;
        }
        // one silent transfer per sighting - it does not chain-hop while unseen
        if (!armed650.remove(id)) return;
        nearby.stream()
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(statue.getLocation()),
                b.getLocation().distanceSquared(statue.getLocation())))
            .ifPresent(target -> relocate650(statue, target));
    }

    /** The only sound it ever earns: a quiet breath, per player, per move. */
    private void startleNewWatchers(Wolf statue, Set<UUID> before) {
        Set<UUID> pending = startle650.get(statue.getUniqueId());
        if (pending == null) return;
        for (UUID watching : watchers.getOrDefault(statue.getUniqueId(), Set.of())) {
            if (before.contains(watching) || !pending.add(watching)) continue;
            Player player = Bukkit.getPlayer(watching);
            if (player != null) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BREATH, 0.5f, 0.65f);
            }
        }
    }

    /** 2-4 blocks straight behind the target, raytraced so it never clips into walls. */
    private void relocate650(Wolf statue, Player target) {
        Vector back = target.getEyeLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1.0e-4) return; // staring straight down - no "behind"
        back.normalize().multiply(-1);
        double wanted = 2.0 + ThreadLocalRandom.current().nextDouble(2.0);
        Location from = target.getLocation().clone().add(0, 0.9, 0);
        double travel = Math.min(wanted, clearance(statue.getWorld(), from, back, wanted));
        if (travel < 2.0) return; // no valid spot behind them - it waits
        Location dest = from.clone().add(back.multiply(travel)).subtract(0, 0.9, 0);
        for (int i = 0; i < 3 && dest.getBlock().isPassable()
            && dest.clone().subtract(0, 1, 0).getBlock().isPassable(); i++) {
            dest.subtract(0, 1, 0);
        }
        // needs solid ground and two blocks of headroom, or it skips the move
        if (!dest.getBlock().isPassable()
            || !dest.clone().add(0, 1, 0).getBlock().isPassable()
            || dest.clone().subtract(0, 1, 0).getBlock().isPassable()) {
            return;
        }
        dest.setYaw(yawToward(dest, target.getLocation()));
        dest.setPitch(0);
        teleportStatue(statue, dest); // deliberately silent: no sound, no particles
        startle650.put(statue.getUniqueId(), new HashSet<>());
    }

    // ------------------------------------------------------------- SCP-049

    /**
     * The Plague Doctor: a slow deliberate walk toward the nearest visible
     * player, and a touch that is always lethal - a 2.5s blind "surgery"
     * beat, then a kill credited to the doctor. The dead rise as SCP-049-2.
     */
    private void tick049(Wolf doctor) {
        prepareWalker(doctor);
        UUID id = doctor.getUniqueId();
        Integer busy = doctorBusy.get(id);
        if (busy != null) {
            if (tick < busy) { // standing over the patient, working
                doctor.getPathfinder().stopPathfinding();
                syncPassengers(doctor);
                return;
            }
            doctorBusy.remove(id);
        }
        // only players are ever targets: 049-2 patients and other SCPs are safe
        Player target = doctor.getLocation().getNearbyPlayers(24).stream()
            .filter(this::isFairGame)
            .filter(p -> !surgeries.containsKey(p.getUniqueId())
                && !condemned.containsKey(p.getUniqueId()))
            .filter(doctor::hasLineOfSight)
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(doctor.getLocation()),
                b.getLocation().distanceSquared(doctor.getLocation())))
            .orElse(null);
        if (target == null) {
            doctor.getPathfinder().stopPathfinding();
        } else if (target.getLocation().distanceSquared(doctor.getLocation()) < 2.1) {
            beginSurgery(doctor, target);
        } else {
            doctor.getPathfinder().moveTo(target, 1.0);
        }
        syncPassengers(doctor);
    }

    private void beginSurgery(Wolf doctor, Player victim) {
        surgeries.put(victim.getUniqueId(), new Surgery(doctor.getUniqueId(), tick + 50));
        doctorBusy.put(doctor.getUniqueId(), tick + 70);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 4));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 0));
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.7f, 0.5f);
        Msg.actionbar(victim, Component.text("You feel... unclean.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Runs the operating table: surgeries end, and they end one way. */
    private void tickSurgeries() {
        Iterator<Map.Entry<UUID, Surgery>> it = surgeries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Surgery> entry = it.next();
            Player victim = Bukkit.getPlayer(entry.getKey());
            if (victim == null || !victim.isOnline() || victim.isDead()) {
                it.remove();
                continue;
            }
            if (tick < entry.getValue().until) continue;
            it.remove();
            condemned.put(victim.getUniqueId(), tick + 200);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ZOMBIE_INFECT, 0.8f, 0.6f);
            Entity doctor = Bukkit.getEntity(entry.getValue().doctor);
            if (doctor != null) victim.damage(1000.0, doctor);
            else victim.damage(1000.0);
        }
    }

    /** Called from the death listener: the doctor's dead get back up. */
    public void reanimate(Player victim) {
        Integer window = condemned.remove(victim.getUniqueId());
        if (window == null || tick > window) return;
        Location at = victim.getLocation();
        at.getWorld().spawn(at, Zombie.class, zombie -> {
            zombie.setAdult();
            zombie.setPersistent(true);
            zombie.setRemoveWhenFarAway(false);
            zombie.setShouldBurnInDay(false);
            zombie.customName(Component.text("SCP-049-2", NamedTextColor.DARK_GREEN));
            zombie.setCustomNameVisible(false);
            zombie.addScoreboardTag(TAG_049_PATIENT);
        });
        at.getWorld().playSound(at, Sound.ENTITY_ZOMBIE_AMBIENT, 1.2f, 0.6f);
    }

    // ------------------------------------------------------------- SCP-939

    private void tick939(Wolf hound) {
        prepareWalker(hound);
        UUID id = hound.getUniqueId();
        listen939(hound);
        Location target = heard.get(id);
        if (target != null && (target.getWorld() != hound.getWorld()
            || tick - heardTick.getOrDefault(id, 0) > 200)) {
            heard.remove(id);
            heardTick.remove(id);
            target = null;
        }
        if (target == null) {
            hound.getPathfinder().stopPathfinding();
            lure939(hound);
            syncPassengers(hound);
            return;
        }
        // it hunts the SOUND, not the player - go quiet and it overshoots
        if (hound.getLocation().distanceSquared(target) > 2.6) {
            hound.getPathfinder().moveTo(target, 1.0);
        } else {
            for (Player prey : hound.getLocation().getNearbyPlayers(2.0)) {
                if (isFairGame(prey)) bite(hound, prey);
            }
            if (tick - heardTick.getOrDefault(id, 0) > 40) { // sniffs, finds nothing, forgets
                heard.remove(id);
                heardTick.remove(id);
            }
        }
        syncPassengers(hound);
    }

    /** It has no eyes. It hears recent movement or pain within 24 blocks. */
    private void listen939(Wolf hound) {
        Location best = null;
        double bestDistSq = 24 * 24;
        for (Player player : hound.getWorld().getPlayers()) {
            if (!isFairGame(player)) continue;
            Integer at = lastNoiseTick.get(player.getUniqueId());
            if (at == null || tick - at > 2) continue;
            Location noise = lastNoise.get(player.getUniqueId());
            if (noise == null || noise.getWorld() != hound.getWorld()) continue;
            double d = noise.distanceSquared(hound.getLocation());
            if (d < bestDistSq) {
                bestDistSq = d;
                best = noise;
            }
        }
        if (best != null) {
            heard.put(hound.getUniqueId(), best.clone());
            heardTick.put(hound.getUniqueId(), tick);
        }
    }

    private void bite(Wolf hound, Player prey) {
        Integer until = biteCooldown.get(prey.getUniqueId());
        if (until != null && until > tick) return;
        biteCooldown.put(prey.getUniqueId(), tick + 24);
        prey.damage(6.0, hound);
        prey.getWorld().playSound(prey.getLocation(), Sound.ENTITY_FOX_BITE, 1f, 0.55f);
    }

    /** Every 15-30 idle seconds: something that is almost a voice. */
    private void lure939(Wolf hound) {
        UUID id = hound.getUniqueId();
        Integer next = nextLure.get(id);
        if (next == null) {
            nextLure.put(id, tick + 300 + ThreadLocalRandom.current().nextInt(300));
            return;
        }
        if (tick < next) return;
        nextLure.put(id, tick + 300 + ThreadLocalRandom.current().nextInt(300));
        hound.getWorld().playSound(hound.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.8f,
            0.5f + ThreadLocalRandom.current().nextFloat() * 0.2f);
    }

    /**
     * Position deltas double as SCP-939's ears. Sneaking is silent - even
     * sneak-walking - so stillness or a crouch is genuine invisibility.
     */
    private void trackNoise() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location now = player.getLocation();
            Location prev = lastSeenPos.put(player.getUniqueId(), now.clone());
            if (prev == null || prev.getWorld() != now.getWorld()) continue;
            double dx = now.getX() - prev.getX();
            double dz = now.getZ() - prev.getZ();
            double dy = now.getY() - prev.getY();
            if (!player.isSneaking() && (dx * dx + dz * dz > 0.0036 || dy > 0.25)) {
                registerNoise(player);
            }
        }
    }

    /** Also called from the damage listener: pain is loud. */
    public void registerNoise(Player player) {
        lastNoise.put(player.getUniqueId(), player.getLocation().clone());
        lastNoiseTick.put(player.getUniqueId(), tick);
    }

    // ------------------------------------------------------------- SCP-999

    private void tick999(Wolf blob) {
        prepareWalker(blob);
        UUID id = blob.getUniqueId();
        Player friend = blob.getLocation().getNearbyPlayers(8).stream()
            .filter(this::isFairGame)
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(blob.getLocation()),
                b.getLocation().distanceSquared(blob.getLocation())))
            .orElse(null);
        if (friend != null) {
            if (friend.getLocation().distanceSquared(blob.getLocation()) > 4.8) {
                blob.getPathfinder().moveTo(friend, 1.0);
            } else {
                blob.getPathfinder().stopPathfinding();
            }
            Integer next = nextSquish.get(id);
            if (next == null || tick >= next) {
                nextSquish.put(id, tick + 60 + ThreadLocalRandom.current().nextInt(60));
                blob.getWorld().playSound(blob.getLocation(), Sound.ENTITY_SLIME_SQUISH, 0.5f,
                    1.2f + ThreadLocalRandom.current().nextFloat() * 0.4f);
            }
        } else {
            Integer next = nextWander.get(id);
            if (next == null || tick >= next) {
                nextWander.put(id, tick + 120 + ThreadLocalRandom.current().nextInt(120));
                Location stroll = blob.getLocation().add(
                    ThreadLocalRandom.current().nextInt(-5, 6), 0,
                    ThreadLocalRandom.current().nextInt(-5, 6));
                blob.getPathfinder().moveTo(stroll, 0.8);
            }
        }
        if (tick % 20 == 0) {
            for (Player player : blob.getLocation().getNearbyPlayers(3)) {
                soothe(player);
            }
        }
        syncPassengers(blob);
    }

    /**
     * The aura, once per second: Regeneration I, clears Darkness and Nausea,
     * and bleeds the lab datapack's infection clocks by one extra point -
     * the same trick the gel item does. The objectives may not exist if the
     * lab datapack isn't installed, so every lookup is null-guarded.
     */
    private void soothe(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45, 0, true, false));
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.removePotionEffect(PotionEffectType.NAUSEA);
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String infection : List.of("lab.inf", "lab.cola", "lab.z008")) {
            Objective objective = board.getObjective(infection);
            if (objective == null) continue;
            var score = objective.getScore(player.getName());
            if (score.isScoreSet() && score.getScore() > 0) {
                score.setScore(score.getScore() - 1);
            }
        }
    }

    // ------------------------------------------------------------- walkers

    /**
     * Walkers steer through the Pathfinder API. Aware stays TRUE because
     * unaware mobs do not tick navigation at all; vanilla wandering is
     * stopped by stripping every AI goal instead - re-stripped each tick,
     * because goal registration comes back whenever the entity reloads.
     */
    private void prepareWalker(Wolf mob) {
        if (!mob.hasAI()) mob.setAI(true);
        Bukkit.getMobGoals().removeAllGoals(mob);
    }

    /** A player left: drop every per-player breadcrumb we keep. */
    public void forget(UUID player) {
        lastSeenPos.remove(player);
        lastNoise.remove(player);
        lastNoiseTick.remove(player);
        surgeries.remove(player);
        condemned.remove(player);
        biteCooldown.remove(player);
    }

    // ------------------------------------------------------------- breaches

    /** While SCP-106's containment is breached, he emerges near random players. */
    private void tickBreachSpawns() {
        if (!plugin.getConfig().getBoolean("breach.106", false)) return;
        long present = Bukkit.getWorlds().stream()
            .flatMap(w -> w.getEntitiesByClass(ItemDisplay.class).stream())
            .filter(d -> d.getScoreboardTags().contains(TAG_106))
            .count();
        if (present >= plugin.getConfig().getInt("scp106.breach-max", 2)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.35) return;
        List<Player> candidates = Bukkit.getOnlinePlayers().stream()
            .filter(this::isFairGame).map(p -> (Player) p).toList();
        if (candidates.isEmpty()) return;
        Player unlucky = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double reach = 8 + ThreadLocalRandom.current().nextDouble(6);
        Location at = unlucky.getLocation().clone()
            .add(Math.cos(angle) * reach, 0, Math.sin(angle) * reach);
        ItemDisplay body = spawn106(at);
        startRise(body, at.getY());
    }

    /** Removes every SCP-106 (recontainment): they corrode back into the floor. */
    public int recontain106() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : new ArrayList<>(world.getEntitiesByClass(ItemDisplay.class))) {
                if (!display.getScoreboardTags().contains(TAG_106)) continue;
                Interaction partner = partnerOf(display);
                if (partner != null) partner.remove();
                display.getWorld().spawnParticle(Particle.SQUID_INK,
                    display.getLocation(), 30, 0.3, 0.8, 0.3, 0.02);
                display.remove();
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------- spawning

    public void spawn173(Location location) {
        location = location.clone();
        location.setPitch(0);
        Wolf statue = spawnScpWolf(location, TAG_173, "SCP-173", 0.45, false);
        ItemDisplay display = spawnModel(location, Material.DIORITE, "scp173", 2.0f, 0.45f);
        statue.addPassenger(display);
        Interaction hitbox = location.getWorld().spawn(location, Interaction.class, i -> {
            i.setInteractionWidth(1.7f);
            i.setInteractionHeight(2.4f);
            i.setPersistent(true);
            i.addScoreboardTag(TAG_173_HITBOX);
        });
        statue.addPassenger(hitbox);
    }

    public void spawn650(Location location) {
        location = location.clone();
        location.setPitch(0);
        Wolf statue = spawnScpWolf(location, TAG_650, "SCP-650", 0.45, false);
        statue.addPassenger(spawnModel(location, Material.COAL_BLOCK, "scp650", 2.1f, 0.5f));
    }

    public void spawn049(Location location) {
        location = location.clone();
        location.setPitch(0);
        Wolf doctor = spawnScpWolf(location, TAG_049, "SCP-049", 0.18, true);
        doctor.addPassenger(spawnModel(location, Material.BLACK_WOOL, "scp049", 2.0f, 0.45f));
    }

    public void spawn939(Location location) {
        location = location.clone();
        location.setPitch(0);
        Wolf hound = spawnScpWolf(location, TAG_939, "SCP-939", 0.32, true);
        hound.addPassenger(spawnModel(location, Material.RED_CONCRETE, "scp939", 1.7f, 0.3f));
    }

    public void spawn999(Location location) {
        location = location.clone();
        location.setPitch(0);
        Wolf blob = spawnScpWolf(location, TAG_999, "SCP-999", 0.18, true);
        blob.addPassenger(spawnModel(location, Material.ORANGE_CONCRETE, "scp999", 1.5f, 0.25f));
    }

    /** The invisible wolf every walking/standing SCP rides on. Location is pitch-0. */
    private Wolf spawnScpWolf(Location location, String tag, String name, double speed, boolean walks) {
        return location.getWorld().spawn(location, Wolf.class, wolf -> {
            wolf.setAdult();
            wolf.setInvisible(true);
            wolf.setSilent(true);
            wolf.setPersistent(true);
            wolf.setRemoveWhenFarAway(false);
            wolf.setAI(walks);
            wolf.customName(Component.text(name, NamedTextColor.RED));
            wolf.setCustomNameVisible(false);
            wolf.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed);
            wolf.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(48);
            wolf.addScoreboardTag(tag);
            if (walks) Bukkit.getMobGoals().removeAllGoals(wolf);
        });
    }

    public ItemDisplay spawn106(Location location) {
        location = location.clone();
        location.setPitch(0);
        ItemDisplay body = spawnModel(location.clone().add(0, 0.9, 0),
            Material.BLACK_CONCRETE, "scp106_walk1", 2.1f, 0f);
        body.addScoreboardTag(TAG_106);
        Interaction hitbox = location.getWorld().spawn(location, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(2.1f);
            i.setPersistent(true);
            i.customName(Component.text("SCP-106", NamedTextColor.RED));
            i.setCustomNameVisible(false);
            i.addScoreboardTag(TAG_106 + ".hitbox");
        });
        body.getPersistentDataContainer().set(partnerKey, PersistentDataType.STRING,
            hitbox.getUniqueId().toString());
        return body;
    }

    private ItemDisplay spawnModel(Location location, Material base, String cmd, float scale, float rise) {
        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(true);
            display.setTeleportDuration(2);
            display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 10));
            display.setTransformation(new Transformation(
                new Vector3f(0, rise, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(scale, scale, scale), new AxisAngle4f(0, 0, 0, 1)));
            display.addScoreboardTag(TAG_DISPLAY);
            setModel(display, base, cmd);
        });
    }

    private void setModel(ItemDisplay display, Material base, String cmd) {
        ItemStack current = display.getItemStack();
        if (current != null && current.hasItemMeta()
            && current.getItemMeta().getCustomModelDataComponent().getStrings().contains(cmd)) {
            return;
        }
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setStrings(List.of(cmd));
        meta.setCustomModelDataComponent(component);
        item.setItemMeta(meta);
        display.setItemStack(item);
    }
}
