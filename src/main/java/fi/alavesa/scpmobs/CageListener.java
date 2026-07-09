package fi.alavesa.scpmobs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

/**
 * The 173 Cage. Hold right-click on SCP-173 with the cage in hand for 15
 * seconds and a 3D cage locks around the statue; a caged statue follows its
 * captor. Sneak + empty hand takes the cage back off. Blinking mid-attempt
 * is the captor's problem.
 */
public final class CageListener implements Listener {

    public static final String CMD_CAGE = "scp173_cage";

    private final ScpTask task;

    public CageListener(ScpTask task) {
        this.task = task;
    }

    public static ItemStack makeCage() {
        ItemStack item = new ItemStack(Material.IRON_BARS);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("173 Cage", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Certified for one Euclid statue.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Hold it against the subject. Keep holding.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setStrings(List.of(CMD_CAGE));
        meta.setCustomModelDataComponent(component);
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isCage(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_BARS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getCustomModelDataComponent().getStrings().contains(CMD_CAGE);
    }

    /** Removes one cage from the player's inventory; false if they lost it. */
    public static boolean consumeCage(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isCage(stack)) {
                stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private Wolf resolveStatue(Entity clicked) {
        if (clicked instanceof Wolf wolf && wolf.getScoreboardTags().contains(ScpTask.TAG_173)) {
            return wolf;
        }
        if (clicked instanceof Interaction interaction
            && interaction.getScoreboardTags().contains(ScpTask.TAG_173_HITBOX)
            && interaction.getVehicle() instanceof Wolf wolf) {
            return wolf;
        }
        return null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Wolf statue = resolveStatue(event.getRightClicked());
        if (statue == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        boolean caged = statue.getScoreboardTags().contains(ScpTask.TAG_173_CAGED);
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!caged && isCage(hand)) {
            task.progressCaging(player, statue);
        } else if (caged && player.isSneaking() && hand.getType().isAir()) {
            task.uncage(player, statue);
        }
    }

    /** The cage is a modified iron-bars item - never let it be placed as a block. */
    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (isCage(event.getItemInHand())) event.setCancelled(true);
    }
}
