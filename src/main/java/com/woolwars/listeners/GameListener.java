package com.woolwars.listeners;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.model.TeamColor;
import com.woolwars.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class GameListener implements Listener {
    /** Auto-supplied items never drop; you'd just get them back on respawn. */
    private static final Set<Material> NEVER_DROP = Set.of(
            Material.TORCH, Material.IRON_PICKAXE, Material.BOW, Material.ARROW, Material.COMPASS);

    private final WoolWarsPlugin plugin;

    public GameListener(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Bedrock already shrugs off survival mining, explosions and pistons; this covers creative players. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.game().findPoint(event.getBlock()) != null) {
            event.setCancelled(true);
            Msg.error(event.getPlayer(), "That's a control point base. Use /ww point remove to remove it.");
        }
    }

    /** Keep inventory by default; optionally drop a fraction of each non-supplied stack. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        TeamColor team = plugin.teams().teamOf(player);
        if (team == null) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);

        double fraction = Math.clamp(plugin.getConfig().getDouble("death-drop-fraction", 0), 0, 1);
        if (fraction <= 0) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        Location where = player.getLocation();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir() || NEVER_DROP.contains(item.getType()) || item.getType() == team.wool()) {
                continue;
            }
            double expected = fraction * item.getAmount();
            int drop = (int) expected + (ThreadLocalRandom.current().nextDouble() < expected - (int) expected ? 1 : 0);
            if (drop <= 0) {
                continue;
            }
            ItemStack dropped = item.clone();
            dropped.setAmount(drop);
            where.getWorld().dropItemNaturally(where, dropped);
            item.setAmount(item.getAmount() - drop);
            inv.setItem(slot, item.getAmount() > 0 ? item : null);
        }
    }

    /** Team spawn always wins over beds and world spawn; a little fire resistance when respawning in the Nether. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        TeamColor team = plugin.teams().teamOf(event.getPlayer());
        if (team == null) {
            return;
        }
        Location spawn = plugin.game().spawnLocation(team);
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.supplies().applyTeamResistance(event.getPlayer()));
        if (event.getRespawnLocation().getWorld().getEnvironment() == World.Environment.NETHER) {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    event.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 200, 0)));
        }
    }
}
