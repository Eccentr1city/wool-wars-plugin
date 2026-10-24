package com.woolwars.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.model.ControlPoint;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BiomeSearchResult;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.util.Vector;

/**
 * Finds a spot for a control point matching filters:
 * <ul>
 *   <li>{@code high}: the highest ground in the search area</li>
 *   <li>{@code low}: the deepest cave floor in the search area</li>
 *   <li>{@code biome:<id>}: in the nearest such biome (also works for cave biomes like deep_dark)</li>
 *   <li>{@code structure:<id>}: inside the nearest such structure; a prefix like "village" matches all variants</li>
 *   <li>{@code range:<blocks>}: how far around the center to look (default from config)</li>
 * </ul>
 * Filters combine: {@code biome:jagged_peaks high} is the highest ground in the nearest jagged peaks.
 */
public class PointFinder {
    /** Candidates this close to an existing point are skipped, so repeated searches find new spots. */
    private static final double MIN_POINT_GAP = 32;

    private final WoolWarsPlugin plugin;

    public enum Height { ANY, HIGH, LOW }

    public record Query(Height height, Biome biome, List<Structure> structures, String structureName, int range) {
    }

    /** A found floor block, or an explanation of why nothing was found. */
    public record Result(Block ground, String description, String error) {
        static Result fail(String error) {
            return new Result(null, null, error);
        }
    }

    public PointFinder(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Parses filter words. Returns null and reports through {@code errors} on a bad filter. */
    public Query parse(String[] words, List<String> errors) {
        Height height = Height.ANY;
        Biome biome = null;
        List<Structure> structures = List.of();
        String structureName = null;
        int range = plugin.getConfig().getInt("find.search-radius", 96);
        for (String word : words) {
            String w = word.toLowerCase(Locale.ROOT);
            if (w.equals("high")) {
                height = Height.HIGH;
            } else if (w.equals("low") || w.equals("cave") || w.equals("deep")) {
                height = Height.LOW;
            } else if (w.startsWith("biome:")) {
                biome = biomeRegistry().get(key(w.substring(6)));
                if (biome == null) {
                    errors.add("Unknown biome " + w.substring(6) + ".");
                    return null;
                }
            } else if (w.startsWith("structure:")) {
                structureName = w.substring(10);
                structures = matchingStructures(structureName);
                if (structures.isEmpty()) {
                    errors.add("Unknown structure " + structureName + ".");
                    return null;
                }
            } else if (w.startsWith("range:")) {
                try {
                    range = Math.max(8, Math.min(512, Integer.parseInt(w.substring(6))));
                } catch (NumberFormatException e) {
                    errors.add("range needs a number of blocks.");
                    return null;
                }
            } else {
                errors.add("Unknown filter '" + word + "'. Use high, low, biome:<id>, structure:<id>, range:<blocks>.");
                return null;
            }
        }
        return new Query(height, biome, structures, structureName, range);
    }

    /**
     * Finds a spot for the query around {@code origin}. Locating a biome or structure happens right away; the
     * terrain to search is then loaded in the background, and the future completes on the main thread.
     */
    public CompletableFuture<Result> find(Location origin, Query query) {
        World world = origin.getWorld();
        int locateBlocks = plugin.getConfig().getInt("find.locate-radius", 3000);

        if (!query.structures().isEmpty()) {
            return findInStructure(origin, query, locateBlocks);
        }

        Location center = origin;
        Predicate<Block> biomeMatch = b -> true;
        String where = "nearby";
        int range = query.range();
        if (query.biome() != null) {
            BiomeSearchResult found = world.locateNearestBiome(origin, locateBlocks, 32, 64, query.biome());
            if (found == null) {
                return CompletableFuture.completedFuture(
                        Result.fail("No " + name(biomeRegistry().getKey(query.biome())) + " within " + locateBlocks + " blocks."));
            }
            center = found.getLocation();
            Biome target = query.biome();
            biomeMatch = b -> world.getBiome(b.getX(), b.getY() + 1, b.getZ()).equals(target);
            where = "in " + name(biomeRegistry().getKey(target));
            range = Math.min(range, 48);
        }

        Location c = center;
        Predicate<Block> match = biomeMatch;
        String place = where;
        int r = range;
        return plugin.placement().withArea(world, c.getBlockX(), c.getBlockZ(), r,
                () -> searchArea(world, c, query, r, match, place));
    }

    private Result searchArea(World world, Location center, Query query, int range, Predicate<Block> biomeMatch, String where) {
        Scan mode = query.height() == Height.LOW ? Scan.CAVE : Scan.SURFACE;
        List<Block> floors = scan(world, center.getBlockX(), center.getBlockZ(), range, mode,
                world.getMinHeight(), world.getMaxHeight() - 1, biomeMatch);
        if (floors.isEmpty() && mode == Scan.SURFACE && query.biome() != null) {
            // Cave biomes (deep dark, lush caves, ...) have no surface; take their highest cave floor instead.
            floors = scan(world, center.getBlockX(), center.getBlockZ(), range, Scan.CAVE,
                    world.getMinHeight(), world.getMaxHeight() - 1, biomeMatch);
            if (!floors.isEmpty()) {
                return new Result(floors.stream().max(Comparator.comparingInt(Block::getY)).get(), "cave floor " + where, null);
            }
        }
        if (floors.isEmpty()) {
            return Result.fail("Couldn't find safe ground " + where + (query.height() == Height.LOW ? " in a cave" : "") + ".");
        }
        Block pick = switch (query.height()) {
            case HIGH -> floors.stream().max(Comparator.comparingInt(Block::getY)).get();
            case LOW -> floors.stream().min(Comparator.comparingInt(Block::getY)).get();
            // Surface closest to the center.
            case ANY -> closest(floors, center.toVector().setY(world.getHighestBlockYAt(center.getBlockX(), center.getBlockZ())));
        };
        String what = switch (query.height()) {
            case HIGH -> "highest ground " + where;
            case LOW -> "deepest cave floor " + where;
            case ANY -> "ground " + where;
        };
        return new Result(pick, what, null);
    }

    private CompletableFuture<Result> findInStructure(Location origin, Query query, int locateBlocks) {
        World world = origin.getWorld();
        StructureSearchResult nearest = null;
        double best = Double.MAX_VALUE;
        for (Structure structure : query.structures()) {
            StructureSearchResult r = world.locateNearestStructure(origin, structure, Math.max(1, locateBlocks / 16), false);
            if (r != null && r.getLocation().distanceSquared(origin) < best) {
                best = r.getLocation().distanceSquared(origin);
                nearest = r;
            }
        }
        if (nearest == null) {
            return CompletableFuture.completedFuture(Result.fail("No " + query.structureName() + " within " + locateBlocks + " blocks"
                    + (world.getEnvironment() == World.Environment.NORMAL ? "" : " in the " + world.getEnvironment().name().toLowerCase(Locale.ROOT)) + "."));
        }
        Location at = nearest.getLocation();
        BoundingBox box = null;
        for (GeneratedStructure generated : world.getStructures(at.getBlockX() >> 4, at.getBlockZ() >> 4, nearest.getStructure())) {
            box = generated.getBoundingBox();
        }
        String label = name(structureRegistry().getKey(nearest.getStructure()));
        if (box == null) {
            return CompletableFuture.completedFuture(Result.fail("Found " + article(label) + " " + label + " at "
                    + at.getBlockX() + ", " + at.getBlockZ() + " but couldn't read its layout."));
        }
        for (ControlPoint point : plugin.game().points()) {
            if (world.equals(point.world()) && box.clone().expand(MIN_POINT_GAP).contains(point.x(), point.y(), point.z())) {
                return CompletableFuture.completedFuture(
                        Result.fail("The nearest " + label + " already has a control point. Move further away and search again."));
            }
        }
        BoundingBox inside = box;
        int radius = (int) Math.ceil(Math.max(box.getWidthX(), box.getWidthZ()) / 2);
        return plugin.placement().withArea(world, (int) box.getCenterX(), (int) box.getCenterZ(), radius,
                () -> searchStructure(world, inside, radius, query, label));
    }

    private Result searchStructure(World world, BoundingBox box, int radius, Query query, String label) {
        BoundingBox inside = box;
        List<Block> floors = scan(world, (int) box.getCenterX(), (int) box.getCenterZ(), radius, Scan.ALL,
                (int) box.getMinY(), (int) box.getMaxY(), b -> inside.contains(b.getX() + 0.5, b.getY() + 1.5, b.getZ() + 0.5));
        if (floors.isEmpty()) {
            return Result.fail("Found " + article(label) + " " + label + " but no safe floor inside it.");
        }
        Block pick = switch (query.height()) {
            case HIGH -> floors.stream().max(Comparator.comparingInt(Block::getY)).get();
            case LOW -> floors.stream().min(Comparator.comparingInt(Block::getY)).get();
            case ANY -> closest(floors, box.getCenter());
        };
        return new Result(pick, "inside " + article(label) + " " + label, null);
    }

    /** Which floors of each column to collect. */
    private enum Scan {
        /** The ground under open sky (topmost floor under a ceiling, like in the Nether). */
        SURFACE,
        /** Every floor. */
        ALL,
        /** Floors in enclosed caves. */
        CAVE
    }

    /** Collects safe floor blocks in a square of the given radius around (cx, cz), between minY and maxY. */
    private List<Block> scan(World world, int cx, int cz, int radius, Scan mode, int minY, int maxY, Predicate<Block> accept) {
        int step = Math.max(2, radius / 24);
        List<Block> floors = new ArrayList<>();
        int bottom = Math.max(minY, world.getMinHeight() + 5);
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                int top = Math.min(maxY, world.hasCeiling()
                        ? world.getMinHeight() + world.getLogicalHeight() - 3
                        : world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
                // Under open sky the surface is just the top block; don't fall through water into caves.
                int lowest = mode == Scan.SURFACE && !world.hasCeiling() ? top : bottom;
                for (int y = top; y >= lowest; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!Placement.isSafeGround(block) || !accept.test(block) || nearExistingPoint(block)) {
                        continue;
                    }
                    if (mode == Scan.CAVE && !isCaveFloor(block)) {
                        continue;
                    }
                    floors.add(block);
                    if (mode == Scan.SURFACE) {
                        break;
                    }
                }
            }
        }
        return floors;
    }

    private static boolean isCaveFloor(Block floor) {
        Block above = floor.getRelative(BlockFace.UP);
        boolean enclosed = above.getType() == Material.CAVE_AIR || floor.getWorld().hasCeiling();
        if (!enclosed || above.getLightFromSky() > 0) {
            return false;
        }
        // Keep away from lava right next to where players will stand.
        for (Block open : List.of(above, above.getRelative(BlockFace.UP))) {
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP)) {
                if (open.getRelative(face).getType() == Material.LAVA) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean nearExistingPoint(Block block) {
        for (ControlPoint point : plugin.game().points()) {
            if (block.getWorld().equals(point.world())
                    && block.getLocation().distanceSquared(new Location(point.world(), point.x(), point.y(), point.z()))
                    < MIN_POINT_GAP * MIN_POINT_GAP) {
                return true;
            }
        }
        return false;
    }

    private static Block closest(List<Block> floors, Vector target) {
        return floors.stream().min(Comparator.comparingDouble(b -> b.getLocation().toVector().distanceSquared(target))).get();
    }

    /** Structures whose id equals {@code name} or starts with it ("village" matches village_plains, village_desert, ...). */
    private List<Structure> matchingStructures(String name) {
        List<Structure> matches = new ArrayList<>();
        Registry<Structure> registry = structureRegistry();
        registry.keyStream().forEach(key -> {
            String id = key.getKey();
            if (id.equals(name) || id.startsWith(name + "_")) {
                matches.add(registry.get(key));
            }
        });
        return matches;
    }

    /** Suggestions for tab completion: biome ids and structure ids (plus prefixes shared by several variants). */
    public List<String> suggestions() {
        List<String> out = new ArrayList<>(List.of("high", "low", "range:"));
        biomeRegistry().keyStream().forEach(k -> out.add("biome:" + k.getKey()));
        List<String> structureIds = structureRegistry().keyStream().map(NamespacedKey::getKey).sorted().toList();
        List<String> prefixes = new ArrayList<>();
        for (String id : structureIds) {
            int cut = id.indexOf('_');
            if (cut > 0 && structureIds.stream().filter(s -> s.startsWith(id.substring(0, cut) + "_")).count() > 1
                    && !prefixes.contains(id.substring(0, cut))) {
                prefixes.add(id.substring(0, cut));
            }
        }
        // Two-word groups like ruined_portal_* and ocean_ruin_*.
        for (String group : List.of("ruined_portal", "ocean_ruin", "trail_ruins")) {
            if (structureIds.stream().anyMatch(s -> s.startsWith(group))) {
                prefixes.add(group);
            }
        }
        prefixes.forEach(p -> out.add("structure:" + p));
        structureIds.forEach(id -> out.add("structure:" + id));
        return out;
    }

    private static Registry<Biome> biomeRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
    }

    private static Registry<Structure> structureRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
    }

    private static NamespacedKey key(String id) {
        return id.contains(":") ? NamespacedKey.fromString(id) : NamespacedKey.minecraft(id);
    }

    private static String article(String noun) {
        return "aeiou".indexOf(noun.isEmpty() ? 'x' : noun.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String name(NamespacedKey key) {
        return key == null ? "?" : key.getKey().replace('_', ' ');
    }
}
