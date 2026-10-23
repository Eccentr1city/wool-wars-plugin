package com.woolwars.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.model.ControlPoint;
import com.woolwars.model.TeamColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

/**
 * Automatic placement of control points and team spawns on safe ground, in any dimension.
 */
public class Placement {
    private static final int SEARCH_RADIUS = 24;

    private final WoolWarsPlugin plugin;

    public Placement(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Replaces all control points with {@code count} new ones around {@code center}: one point goes right there,
     * several go evenly around a circle of radius {@code spread}. Returns how many were placed.
     */
    public int autoPoints(Location center, int count, double spread) {
        plugin.game().clearPoints();
        World world = center.getWorld();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        int placed = 0;
        for (int i = 0; i < count; i++) {
            int x = center.getBlockX();
            int z = center.getBlockZ();
            if (count > 1) {
                double angle = start + i * Math.PI * 2 / count;
                x += (int) Math.round(Math.cos(angle) * spread);
                z += (int) Math.round(Math.sin(angle) * spread);
            }
            Block ground = findGroundNear(world, x, z, center.getBlockY());
            if (ground == null) {
                continue;
            }
            // Clear grass, flowers, snow layers etc. out of the wool slot.
            ground.getRelative(0, 1, 0).setType(Material.AIR);
            if (plugin.game().addPoint(ground) == null) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * Places a spawn for each of {@code teams}, evenly spaced on a circle of {@code radius} around the control
     * points (or around {@code fallbackCenter} if there are none), facing inward. Returns the teams it couldn't place.
     */
    public List<TeamColor> autoSpawns(List<TeamColor> teams, double radius, Location fallbackCenter) {
        Location center = pointsCenter();
        if (center == null) {
            center = fallbackCenter;
        }
        World world = center.getWorld();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        List<TeamColor> failed = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            double angle = start + i * Math.PI * 2 / teams.size();
            Block ground = findSpawnGround(world, center, angle, radius, Math.PI / teams.size());
            if (ground == null) {
                failed.add(teams.get(i));
                continue;
            }
            Location spawn = ground.getLocation().add(0.5, 1, 0.5);
            Vector toCenter = center.toVector().subtract(spawn.toVector());
            spawn.setDirection(toCenter);
            spawn.setPitch(0);
            plugin.game().setSpawn(teams.get(i), spawn);
        }
        return failed;
    }

    /**
     * Spawn distance from the config. "auto" sizes it so the run to the control point takes about
     * spawn-travel-fraction of the scoring timer at run-speed blocks per second.
     */
    public double defaultSpawnRadius() {
        String raw = plugin.getConfig().getString("auto.spawn-radius", "auto");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            double seconds = plugin.game().intervalTicks() / 20.0;
            double fraction = plugin.getConfig().getDouble("auto.spawn-travel-fraction", 0.25);
            double speed = plugin.getConfig().getDouble("auto.run-speed", 4.0);
            return Math.max(24, Math.round(seconds * fraction * speed));
        }
    }

    /**
     * Ground at {@code angle} and {@code radius} from the center. If that spot is unusable (ocean, lava lake),
     * swings the angle back and forth by up to {@code maxSwing}, then tries a bit closer in.
     */
    private Block findSpawnGround(World world, Location center, double angle, double radius, double maxSwing) {
        double swingStep = Math.toRadians(10);
        for (double scale : new double[] {1, 0.85, 0.7}) {
            for (double swing = 0; swing <= maxSwing + 1e-9; swing += swingStep) {
                for (double a : swing == 0 ? new double[] {angle} : new double[] {angle + swing, angle - swing}) {
                    int x = center.getBlockX() + (int) Math.round(Math.cos(a) * radius * scale);
                    int z = center.getBlockZ() + (int) Math.round(Math.sin(a) * radius * scale);
                    Block ground = findGroundNear(world, x, z, center.getBlockY());
                    if (ground != null) {
                        return ground;
                    }
                }
            }
        }
        return null;
    }

    /** Average position of the control points in the first point's world. */
    private Location pointsCenter() {
        List<ControlPoint> points = plugin.game().points();
        if (points.isEmpty() || points.get(0).world() == null) {
            return null;
        }
        World world = points.get(0).world();
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (ControlPoint p : points) {
            if (world.equals(p.world())) {
                x += p.x();
                y += p.y();
                z += p.z();
                n++;
            }
        }
        return new Location(world, x / n, y / n, z / n);
    }

    /** Searches outward in rings from (x, z) for a safe ground block. */
    public Block findGroundNear(World world, int x, int z, int refY) {
        for (int r = 0; r <= SEARCH_RADIUS; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    Block ground = findGround(world, x + dx, z + dz, refY);
                    if (ground != null) {
                        return ground;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A solid, non-hazardous block with two free blocks above it in column (x, z), or null.
     * Uses the surface where there's open sky; under a ceiling (the Nether) it scans down from near refY.
     */
    private Block findGround(World world, int x, int z, int refY) {
        if (!world.hasCeiling()) {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block block = world.getBlockAt(x, y, z);
            return isSafeGround(block) ? block : null;
        }
        int top = Math.min(refY + 16, world.getMinHeight() + world.getLogicalHeight() - 3);
        for (int y = top; y > world.getMinHeight() + 1; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (isSafeGround(block)) {
                return block;
            }
        }
        return null;
    }

    static boolean isSafeGround(Block block) {
        Material type = block.getType();
        if (!type.isSolid() || block.isLiquid() || type == Material.BEDROCK || type == Material.MAGMA_BLOCK
                || type == Material.CACTUS || type.name().endsWith("_LEAVES")) {
            return false;
        }
        Block above = block.getRelative(0, 1, 0);
        Block above2 = block.getRelative(0, 2, 0);
        return isOpen(above) && isOpen(above2);
    }

    private static boolean isOpen(Block block) {
        Material type = block.getType();
        return type.isAir() || (block.isPassable() && !block.isLiquid() && type != Material.FIRE
                && type != Material.SOUL_FIRE && type != Material.POWDER_SNOW);
    }
}
