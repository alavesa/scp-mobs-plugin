package fi.alavesa.scpmobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SCPs themselves, ticked statelessly by scoreboard tag so they survive
 * restarts and chunk reloads.
 *
 * SCP-173 - an invisible zombie provides real pathfinding; an item_display
 * passenger carries the statue model. It is frozen while any non-blinking
 * survival/adventure player is looking at it with line of sight; the moment
 * every watcher blinks or looks away, it moves - fast - and snaps the neck of
 * whoever it reaches.
 *
 * SCP-106 - no pathfinding at all: an item_display + interaction pair that
 * the plugin walks slowly through walls toward the nearest player, corroding
 * as it goes. Touch means the pocket dimension (a staff-set location) - or,
 * if none is set, severe corrosion. It cannot be harmed.
 */
public final class ScpTask implements Runnable {

    public static final String TAG_173 = "scp.173";
    public static final String TAG_106 = "scp.106";
    public static final String TAG_DISPLAY = "scp.display";

    private final ScpMobsPlugin plugin;
    private final BlinkManager blink;
    private final NamespacedKey partnerKey;
    private final Map<UUID, Integer> touchCooldown = new HashMap<>();
    private int tick;

    public ScpTask(ScpMobsPlugin plugin, BlinkManager blink) {
        this.plugin = plugin;
        this.blink = blink;
        this.partnerKey = new NamespacedKey(plugin, "partner");
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
        touchCooldown.entrySet().removeIf(e -> e.getValue() < tick);
        if (tick % 100 == 0) {
            for (World world : Bukkit.getWorlds()) {
                for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                    if (display.getScoreboardTags().contains(TAG_DISPLAY)
                        && !display.getScoreboardTags().contains(TAG_106)
                        && display.getVehicle() == null) {
                        display.remove(); // orphaned statue shell (base mob lost)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- SCP-173

    private void tick173(Wolf statue) {
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
        // keep the statue model facing the way the zombie faces
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

    // ------------------------------------------------------------- SCP-106

    private void tick106(ItemDisplay body) {
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
        // corrosion trail + occasional groan
        if (tick % 4 == 0) {
            body.getWorld().spawnParticle(Particle.SQUID_INK, loc, 3, 0.25, 0.6, 0.25, 0.01);
            body.getWorld().spawnParticle(Particle.ASH, loc, 6, 0.3, 0.6, 0.3, 0);
        }
        if (tick % 160 == 0) {
            body.getWorld().playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT_LAND, 1.2f, 0.35f);
        }
        // two-frame shuffle animation
        String frame = (tick % 20 < 10) ? "scp106_walk1" : "scp106_walk2";
        setModel(body, Material.BLACK_CONCRETE, frame);
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
            victim.sendActionBar(Component.text("The world corrodes away.", NamedTextColor.GRAY, net.kyori.adventure.text.format.TextDecoration.ITALIC));
        }
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
