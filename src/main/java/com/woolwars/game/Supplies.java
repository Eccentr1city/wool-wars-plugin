package com.woolwars.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.model.ControlPoint;
import com.woolwars.model.TeamColor;
import com.woolwars.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Per-player upkeep: auto-supplied items (refilled the tick you run out, capped at one slot each),
 * the tracker compass, start-of-game gear, and the optional mining fatigue around control points.
 */
public class Supplies {
    private static final int OFFHAND_SLOT = 40;
    private static final Component COMPASS_NAME = Component.text("Control Point Tracker", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false);

    private final WoolWarsPlugin plugin;

    private record Kit(Material material, Supplier<ItemStack> make) {
    }

    public Supplies(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Every tick: hand out anything a team player is missing and point their compass at the active point. */
    public void supplyAll() {
        ControlPoint active = plugin.game().activePoint();
        for (Player player : Bukkit.getOnlinePlayers()) {
            TeamColor team = plugin.teams().teamOf(player);
            if (team == null || player.isDead()) {
                continue;
            }
            for (Kit kit : kitFor(team)) {
                if (!has(player, kit.material())) {
                    // A full inventory just doesn't get it; dropping the leftover would litter every tick.
                    player.getInventory().addItem(kit.make().get());
                }
            }
            if (active != null && active.world() != null) {
                pointCompass(player, active.slotCenter());
            }
        }
    }

    /** Every few ticks: collapse each auto-supplied item down to a single slot so inventories don't fill up. */
    public void dedupeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            TeamColor team = plugin.teams().teamOf(player);
            if (team == null) {
                continue;
            }
            List<String> removed = new ArrayList<>();
            for (Kit kit : kitFor(team)) {
                if (dedupe(player.getInventory(), kit.material())) {
                    removed.add(kit.material().name().toLowerCase().replace('_', ' '));
                }
            }
            if (!removed.isEmpty()) {
                Msg.info(player, Component.text("Extra " + String.join(", ", removed)
                        + " removed (auto-supplied items keep one slot)", NamedTextColor.GRAY));
            }
        }
    }

    /** One-off gear handed out when a game starts. */
    public void giveStartGear() {
        if (!plugin.game().netherGearEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.teams().teamOf(player) != null) {
                give(player, new ItemStack(Material.OBSIDIAN, 10));
                give(player, new ItemStack(Material.FLINT_AND_STEEL));
            }
        }
    }

    /** Empties team players' inventories (armor and offhand too) so everyone starts a game even. */
    public void clearInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.teams().teamOf(player) != null) {
                // Closing first returns crafting-grid items to the inventory, so they get cleared too.
                player.closeInventory();
                player.getInventory().clear();
                player.setItemOnCursor(null);
            }
        }
    }

    /** Keeps each team's balancing resistance topped up; also covers players who just respawned. */
    public void applyTeamResistance() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTeamResistance(player);
        }
    }

    public void applyTeamResistance(Player player) {
        TeamColor team = plugin.teams().teamOf(player);
        int level = team == null ? 0 : plugin.game().resistance(team);
        if (level > 0 && !player.isDead()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, level - 1, true, false, true));
        }
    }

    public void applyMiningFatigue() {
        if (!plugin.getConfig().getBoolean("mining-fatigue.enabled", false)) {
            return;
        }
        int level = Math.max(1, plugin.getConfig().getInt("mining-fatigue.level", 3));
        double radius = plugin.getConfig().getDouble("mining-fatigue.radius", 12);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.teams().teamOf(player) == null) {
                continue;
            }
            for (ControlPoint point : plugin.game().points()) {
                if (point.world() == null || !point.world().equals(player.getWorld())) {
                    continue;
                }
                if (player.getLocation().distanceSquared(point.slotCenter()) <= radius * radius) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 30, level - 1, true, false, true));
                    break;
                }
            }
        }
    }

    private List<Kit> kitFor(TeamColor team) {
        List<Kit> kit = new ArrayList<>();
        kit.add(new Kit(team.wool(), () -> new ItemStack(team.wool(), 64)));
        if (plugin.getConfig().getBoolean("supply.torches", true)) {
            kit.add(new Kit(Material.TORCH, () -> new ItemStack(Material.TORCH, 64)));
        }
        if (plugin.getConfig().getBoolean("supply.pickaxe", true)) {
            kit.add(new Kit(Material.IRON_PICKAXE, this::pickaxe));
        }
        if (plugin.getConfig().getBoolean("supply.bow", true)) {
            kit.add(new Kit(Material.BOW, () -> {
                ItemStack bow = new ItemStack(Material.BOW);
                bow.addEnchantment(Enchantment.INFINITY, 1);
                return bow;
            }));
            // Infinity still needs one arrow in the inventory.
            kit.add(new Kit(Material.ARROW, () -> new ItemStack(Material.ARROW)));
        }
        if (plugin.getConfig().getBoolean("supply.compass", true) && !plugin.game().points().isEmpty()) {
            kit.add(new Kit(Material.COMPASS, () -> {
                ItemStack compass = new ItemStack(Material.COMPASS);
                compass.editMeta(meta -> meta.displayName(COMPASS_NAME));
                return compass;
            }));
        }
        return kit;
    }

    private ItemStack pickaxe() {
        ItemStack pick = new ItemStack(Material.IRON_PICKAXE);
        pick.addEnchantment(Enchantment.FORTUNE, 2);
        pick.addEnchantment(Enchantment.UNBREAKING, 3);
        if (plugin.game().netherGearEnabled()) {
            // Netherrack is slow to mine with plain iron.
            pick.addEnchantment(Enchantment.EFFICIENCY, 4);
        }
        return pick;
    }

    private void pointCompass(Player player, Location target) {
        PlayerInventory inv = player.getInventory();
        for (int slot : slots()) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.COMPASS) {
                continue;
            }
            CompassMeta meta = (CompassMeta) item.getItemMeta();
            // Only rewrite when the target moved; rewriting every tick makes the held item twitch.
            if (meta.hasLodestone() && sameBlock(meta.getLodestone(), target)) {
                continue;
            }
            meta.setLodestone(target);
            meta.setLodestoneTracked(false);
            meta.displayName(COMPASS_NAME);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }
    }

    private static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && Objects.equals(a.getWorld(), b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private static boolean has(Player player, Material material) {
        if (player.getItemOnCursor().getType() == material) {
            return true;
        }
        PlayerInventory inv = player.getInventory();
        for (int slot : slots()) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() == material) {
                return true;
            }
        }
        return false;
    }

    /** Keeps one stack of {@code material}: the offhand one if any, else the first in the hotbar, else the first. */
    private static boolean dedupe(PlayerInventory inv, Material material) {
        List<Integer> found = new ArrayList<>();
        for (int slot : slots()) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() == material) {
                found.add(slot);
            }
        }
        if (found.size() <= 1) {
            return false;
        }
        int keep = found.contains(OFFHAND_SLOT) ? OFFHAND_SLOT
                : found.stream().filter(s -> s < 9).findFirst().orElse(found.get(0));
        for (int slot : found) {
            if (slot != keep) {
                inv.setItem(slot, null);
            }
        }
        return true;
    }

    /** Main inventory (0-35, hotbar is 0-8) plus offhand. */
    private static int[] slots() {
        int[] slots = new int[37];
        for (int i = 0; i < 36; i++) {
            slots[i] = i;
        }
        slots[36] = OFFHAND_SLOT;
        return slots;
    }

    static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
    }
}
