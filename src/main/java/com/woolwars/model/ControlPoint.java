package com.woolwars.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A control point: an unbreakable bedrock base, with the wool slot directly above it.
 * Whoever's wool sits in the slot holds the point.
 */
public class ControlPoint {
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    /** Block data of whatever the base replaced, restored when the point is removed. */
    private final String originalBase;
    private TeamColor owner;
    private boolean ownerKnown;

    public ControlPoint(String worldName, int x, int y, int z, String originalBase) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.originalBase = originalBase;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public String originalBase() {
        return originalBase;
    }

    public boolean isLoaded() {
        World world = world();
        return world != null && world.isChunkLoaded(x >> 4, z >> 4);
    }

    public Block base() {
        return world().getBlockAt(x, y, z);
    }

    public Block slot() {
        return world().getBlockAt(x, y + 1, z);
    }

    /** Center of the slot block; what compasses point at. */
    public Location slotCenter() {
        return new Location(world(), x + 0.5, y + 1.5, z + 0.5);
    }

    public boolean isBase(Block block) {
        return block.getX() == x && block.getY() == y && block.getZ() == z
                && block.getWorld().getName().equals(worldName);
    }

    public TeamColor owner() {
        return owner;
    }

    public void setOwner(TeamColor owner) {
        this.owner = owner;
        this.ownerKnown = true;
    }

    public void forgetOwner() {
        ownerKnown = false;
    }

    /** False until the slot has been read once, so the first read isn't announced as a capture. */
    public boolean ownerKnown() {
        return ownerKnown;
    }

    public String coords() {
        return x + ", " + (y + 1) + ", " + z;
    }
}
