package com.woolwars.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

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
 *
 * Spots far from players are usually ungenerated terrain, and generating it on the main thread freezes the
 * server for seconds. So every search first loads its chunks asynchronously ({@link #withArea}), then looks at
 * blocks on the main thread once they're ready. The public methods return futures that complete on the main thread.
 */
public class Placement {
    private static final int SEARCH_RADIUS = 24;

    private final WoolWarsPlugin plugin;

    public Placement(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Replaces all control points with {@code count} new ones around {@code center}: one point goes right there,
     * several go evenly around a circle of radius {@code spread}. Completes with how many were placed.
     */
    public CompletableFuture<Integer> autoPoints(Location center, int count, double spread) {
        plugin.game().clearPoints();
        World world = center.getWorld();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (int i = 0; i < count; i++) {
            int x = center.getBlockX();
            int z = center.getBlockZ();
            if (count > 1) {
                double angle = start + i * Math.PI * 2 / count;
                x += (int) Math.round(Math.cos(angle) * spread);
                z += (int) Math.round(Math.sin(angle) * spread);
            }
            int px = x;
            int pz = z;
            chain = chain.thenCompose(placed -> findGroundNear(world, px, pz, center.getBlockY())
                    .thenApply(ground -> placed + (placePoint(ground) ? 1 : 0)));
        }
        return chain;
    }

    /** Turns {@code ground} into a control point base, clearing grass, flowers, snow etc. out of the slot. */
    public boolean placePoint(Block ground) {
        if (ground == null) {
            return false;
        }
        ground.getRelative(0, 1, 0).setType(Material.AIR);
        return plugin.game().addPoint(ground) == null;
    }

    /**
     * Places a spawn for each of {@code teams}, evenly spaced on a circle of {@code radius} around the control
     * points (or around {@code fallbackCenter} if there are none), facing inward. Completes with the teams it
     * couldn't place.
     */
    public CompletableFuture<List<TeamColor>> autoSpawns(List<TeamColor> teams, double radius, Location fallbackCenter) {
        Location found = pointsCenter();
        Location center = found != null ? found : fallbackCenter;
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        List<TeamColor> failed = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < teams.size(); i++) {
            TeamColor team = teams.get(i);
            double angle = start + i * Math.PI * 2 / teams.size();
            chain = chain.thenCompose(v -> findSpawnGround(center, angle, radius, Math.PI / teams.size()))
                    .thenAccept(ground -> {
                        if (ground == null) {
                            failed.add(team);
                            return;
                        }
                        Location spawn = ground.getLocation().add(0.5, 1, 0.5);
                        spawn.setDirection(center.toVector().subtract(spawn.toVector()));
                        spawn.setPitch(0);
                        plugin.game().setSpawn(team, spawn);
                    });
        }
        return chain.thenApply(v -> failed);
    }

    /**
     * Picks ground roughly {@code distance} blocks from {@code origin} in a random direction, for a fresh arena
     * that won't overlap the current one. Tries other directions when it lands in an ocean or outside the border.
     */
    public CompletableFuture<Block> pickNewArena(Location origin, double distance) {
        World world = origin.getWorld();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        List<int[]> candidates = new ArrayList<>();
        for (int attempt = 0; attempt < 16; attempt++) {
            // Step around the circle by the golden angle so retries spread out instead of clustering.
            double angle = start + attempt * Math.PI * 2 * 0.381966;
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (world.getWorldBorder().isInside(new Location(world, x, origin.getY(), z))) {
                candidates.add(new int[] {x, z});
            }
        }
        return firstGround(world, candidates, origin.getBlockY(), 0);
    }

    /** How far /ww quicklaunch new moves by default: config value, or "auto" = 3x the spawn radius (at least 1000). */
    public double defaultNewArenaDistance() {
        String raw = plugin.getConfig().getString("auto.new-arena-distance", "auto");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Math.max(1000, 3 * defaultSpawnRadius());
        }
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
    private CompletableFuture<Block> findSpawnGround(Location center, double angle, double radius, double maxSwing) {
        double swingStep = Math.toRadians(10);
        List<int[]> candidates = new ArrayList<>();
        for (double scale : new double[] {1, 0.85, 0.7}) {
            for (double swing = 0; swing <= maxSwing + 1e-9; swing += swingStep) {
                for (double a : swing == 0 ? new double[] {angle} : new double[] {angle + swing, angle - swing}) {
                    candidates.add(new int[] {
                            center.getBlockX() + (int) Math.round(Math.cos(a) * radius * scale),
                            center.getBlockZ() + (int) Math.round(Math.sin(a) * radius * scale)});
                }
            }
        }
        return firstGround(center.getWorld(), candidates, center.getBlockY(), 0);
    }

    /** Tries each (x, z) candidate in order, loading only as much terrain as it needs, until one has safe ground. */
    private CompletableFuture<Block> firstGround(World world, List<int[]> candidates, int refY, int index) {
        if (index >= candidates.size()) {
            return CompletableFuture.completedFuture(null);
        }
        int[] c = candidates.get(index);
        return findGroundNear(world, c[0], c[1], refY).thenCompose(ground -> ground != null
                ? CompletableFuture.completedFuture(ground)
                : firstGround(world, candidates, refY, index + 1));
    }

    /**
     * Loads every chunk within {@code radius} blocks of (x, z) without blocking the server, holds them loaded
     * while {@code work} runs on the main thread, then lets them go.
     */
    public <T> CompletableFuture<T> withArea(World world, int x, int z, int radius, Supplier<T> work) {
        List<CompletableFuture<?>> loads = new ArrayList<>();
        List<int[]> ticketed = new ArrayList<>();
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                int chunkX = cx;
                int chunkZ = cz;
                loads.add(world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> {
                    // Keep it loaded until the whole area is ready. Chunks that already had our ticket
                    // (control points) keep theirs.
                    if (world.addPluginChunkTicket(chunkX, chunkZ, plugin)) {
                        ticketed.add(new int[] {chunkX, chunkZ});
                    }
                }));
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, loadError) ->
                // Paper completes chunk futures on the main thread; hop there explicitly in case one was already done.
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        if (loadError != null) {
                            result.completeExceptionally(loadError);
                        } else {
                            result.complete(work.get());
                        }
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    } finally {
                        ticketed.forEach(c -> world.removePluginChunkTicket(c[0], c[1], plugin));
                    }
                }));
        return result;
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

    /** Searches outward in rings from (x, z) for a safe ground block, loading the terrain first. */
    public CompletableFuture<Block> findGroundNear(World world, int x, int z, int refY) {
        return withArea(world, x, z, SEARCH_RADIUS, () -> findGroundNearLoaded(world, x, z, refY));
    }

    private Block findGroundNearLoaded(World world, int x, int z, int refY) {
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
