package fi.alavesa.scpmobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The SCPs, ticked statelessly by scoreboard tag so they survive restarts.
 *
 * SCP-173 - invisible wolf pathfinder + statue display + interaction hitbox.
 * Frozen while watched, lethal when not. Can be CONTAINED: hold right-click
 * on it with a 173 Cage for 15 seconds and a 3D cage locks around it - a
 * caged statue is docile and follows whoever caged it. Sneak + empty hand
 * takes the cage back off. Nobody said caging it alone was survivable.
 *
 * SCP-106 - a display + interaction pair walked through walls toward the
 * nearest player. Touch: heavy corrosion + the pocket dimension. While his
 * containment is BREACHED (/scpmob breach 106), he also emerges from the
 * floor near random players on his own.
 */
public final class ScpTask implements Runnable {

    public static final String TAG_173 = "scp.173";
    public static final String TAG_173_HITBOX = "scp.173.hitbox";
    public static final String TAG_173_CAGED = "scp.173.caged";
    public static final String TAG_CAGE3D = "scp.cage3d";
    public static final String TAG_106 = "scp.106";
    public static final String TAG_DISPLAY = "scp.display";

    public static final int CAGING_TICKS = 15 * 20;

    private static final class Caging {
        UUID statue;
        int progress;
        int lastHeld;
    }

    private final ScpMobsPlugin plugin;
    private final BlinkManager blink;
    private final NamespacedKey partnerKey;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Integer> touchCooldown = new HashMap<>();
    private final Map<UUID, Caging> caging = new HashMap<>();
    private int tick;

    public ScpTask(ScpMobsPlugin plugin, BlinkManager blink) {
        this.plugin = plugin;
        this.blink = blink;
        this.partnerKey = new NamespacedKey(plugin, "partner");
        this.ownerKey = new NamespacedKey(plugin, "cage_owner");
    }

    @Override
    public void run() {
        tick += 2; // scheduled every 2 ticks
        for (World world : Bukkit.getWorlds()) {
            for (Wolf wolf : world.getEntitiesByClass(Wolf.class)) {
                if (wolf.getScoreboardTags().contains(TAG_173)) tick173(wolf);
            }
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getScoreboardTags().contains(TAG_106)) tick106(display);
            }
        }
        tickCaging();
        if (tick % 600 == 0) tickBreachSpawns();
        touchCooldown.entrySet().removeIf(e -> e.getValue() < tick);
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
        List<Player> nearby = statue.getLocation().getNearbyPlayers(32).stream()
            .filter(this::isFairGame)
            .toList();
        if (nearby.isEmpty()) {
            statue.setAI(false);
            return;
        }
        boolean observed = nearby.stream().anyMatch(p -> isWatching(p, statue));
        Player target = nearby.stream()
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(statue.getLocation()),
                b.getLocation().distanceSquared(statue.getLocation())))
            .orElse(null);

        if (observed) {
            statue.setAI(false);
        } else {
            statue.setAI(true);
            if (target != null) {
                statue.getPathfinder().moveTo(target, 1.9);
                if (tick % 6 == 0) {
                    statue.getWorld().playSound(statue.getLocation(),
                        Sound.BLOCK_STONE_STEP, 1.3f, 0.55f);
                }
                if (target.getLocation().distanceSquared(statue.getLocation()) < 2.1) {
                    snap(statue, target);
                }
            }
        }
        syncPassengers(statue);
    }

    /** A caged statue is docile: it trundles after whoever caged it. */
    private void tickCaged(Wolf statue) {
        String ownerId = statue.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(UUID.fromString(ownerId));
        if (owner != null && owner.isOnline()
            && owner.getWorld() == statue.getWorld()
            && owner.getLocation().distanceSquared(statue.getLocation()) < 1600) {
            if (owner.getLocation().distanceSquared(statue.getLocation()) > 9) {
                statue.setAI(true);
                statue.getPathfinder().moveTo(owner, 1.15);
            } else {
                statue.setAI(false);
            }
        } else {
            statue.setAI(false);
        }
        if (tick % 80 == 0) {
            statue.getWorld().playSound(statue.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.8f, 0.7f);
        }
        syncPassengers(statue);
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

    private boolean isWatching(Player player, Entity entity) {
        if (blink.isBlinking(player)) return false;
        if (!player.hasLineOfSight(entity)) return false;
        Vector toEntity = entity.getLocation().add(0, 1.0, 0)
            .toVector().subtract(player.getEyeLocation().toVector());
        if (toEntity.lengthSquared() < 0.01) return true;
        return player.getEyeLocation().getDirection().dot(toEntity.normalize()) > 0.25;
    }

    private void snap(Wolf statue, Player victim) {
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_SKELETON_HURT, 1.4f, 0.5f);
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_BONE_BLOCK_BREAK, 1.4f, 0.7f);
        victim.damage(1000.0, statue);
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
                player.sendActionBar(Component.text("The cage slips.", NamedTextColor.GRAY, TextDecoration.ITALIC));
                continue;
            }
            if (!(Bukkit.getEntity(session.statue) instanceof Wolf statue) || statue.isDead()) {
                it.remove();
                continue;
            }
            session.progress += 2;
            if (session.progress % 20 == 0) {
                int left = (CAGING_TICKS - session.progress) / 20 + 1;
                player.sendActionBar(Component.text("Caging SCP-173... " + left + "s",
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
            player.sendActionBar(Component.text("The cage is gone.", NamedTextColor.GRAY, TextDecoration.ITALIC));
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
        player.sendActionBar(Component.text("SCP-173 is contained. It follows you now.",
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
        player.sendActionBar(Component.text("It is watching you.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    // ------------------------------------------------------------- SCP-106

    private void tick106(ItemDisplay body) {
        // Breached 106 has extra privileges: rarely, he steps out of the floor
        // right beneath a random unsuspecting player.
        if (tick % 600 == 0 && plugin.getConfig().getBoolean("breach.106", false)
            && ThreadLocalRandom.current().nextDouble()
               < plugin.getConfig().getDouble("scp106.ambush-chance", 0.08)) {
            ambush(body);
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
                double speed = plugin.getConfig().getDouble("scp106.speed", 0.22);
                step.normalize().multiply(Math.min(speed, distance)); // relentless, through walls
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

    /** Teleport beneath a random player in the same world. No warning given. */
    private void ambush(ItemDisplay body) {
        List<Player> candidates = body.getWorld().getPlayers().stream()
            .filter(this::isFairGame)
            .filter(p -> p.getLocation().distanceSquared(body.getLocation()) > 64)
            .toList();
        if (candidates.isEmpty()) return;
        Player unlucky = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location dest = unlucky.getLocation().clone().add(0, 0.1, 0);
        dest.setPitch(0);
        body.teleport(dest.clone().add(0, 0.9, 0));
        Interaction partner = partnerOf(body);
        if (partner != null) partner.teleport(dest);
        dest.getWorld().spawnParticle(Particle.SQUID_INK, dest.clone().add(0, 0.6, 0), 50, 0.4, 0.7, 0.4, 0.03);
        dest.getWorld().playSound(dest, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.35f);
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
        if (pd != null && victim.isValid() && !victim.isDead()) {
            victim.teleport(pd);
            victim.sendActionBar(Component.text("The world corrodes away.", NamedTextColor.GRAY, TextDecoration.ITALIC));
        }
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
        spawn106(at);
        at.getWorld().spawnParticle(Particle.SQUID_INK, at.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.02);
        at.getWorld().playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.4f, 0.4f);
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
        Wolf statue = location.getWorld().spawn(location, Wolf.class, wolf -> {
            wolf.setAdult();
            wolf.setInvisible(true);
            wolf.setSilent(true);
            wolf.setPersistent(true);
            wolf.setRemoveWhenFarAway(false);
            wolf.customName(Component.text("SCP-173", NamedTextColor.RED));
            wolf.setCustomNameVisible(false);
            wolf.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.45);
            wolf.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(48);
            wolf.addScoreboardTag(TAG_173);
        });
        ItemDisplay display = spawnModel(location, Material.DIORITE, "scp173", 2.0f, 0.45f);
        statue.addPassenger(display);
        Interaction hitbox = location.getWorld().spawn(location, Interaction.class, i -> {
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.9f);
            i.setPersistent(true);
            i.addScoreboardTag(TAG_173_HITBOX);
        });
        statue.addPassenger(hitbox);
    }

    public void spawn106(Location location) {
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
