package com.woolwars.game;

import java.util.HashMap;
import java.util.Map;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.model.ControlPoint;
import com.woolwars.model.TeamColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Beacon-style markers made of block display entities, so no real beacon is needed:
 * a glowing outline on the wool slot (visible through walls, in the holder's color) and a colored beam
 * rising from it. The beam of the point that's scored next also glows, so it stands out from far away.
 */
public class Visuals {
    public static final String TAG = "woolwars_visual";
    private static final float BEAM_WIDTH = 0.3f;
    private static final Color NEUTRAL_GLOW = Color.WHITE;

    private final WoolWarsPlugin plugin;
    private final Map<ControlPoint, Markers> markers = new HashMap<>();

    private static final class Markers {
        BlockDisplay slot;
        BlockDisplay beam;
        boolean beamGlowing;
    }

    public Visuals(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called every second: respawns missing markers and moves the beam highlight to the active point. */
    public void maintain() {
        ControlPoint active = plugin.game().activePoint();
        for (ControlPoint point : plugin.game().points()) {
            if (!point.isLoaded()) {
                continue;
            }
            Markers m = markers.computeIfAbsent(point, p -> new Markers());
            boolean missing = m.slot == null || !m.slot.isValid() || (beamsEnabled() && (m.beam == null || !m.beam.isValid()));
            if (missing) {
                despawn(m);
                spawn(point, m);
            }
            boolean shouldGlow = point == active && plugin.getConfig().getBoolean("visuals.highlight-active-beam", true);
            if (m.beam != null && m.beamGlowing != shouldGlow) {
                m.beam.setGlowing(shouldGlow);
                m.beamGlowing = shouldGlow;
            }
        }
    }

    /** Recolors a point's markers after its owner changed. */
    public void refresh(ControlPoint point) {
        Markers m = markers.get(point);
        if (m == null) {
            return;
        }
        if (m.slot != null && m.slot.isValid()) {
            styleSlot(m.slot, point.owner());
        }
        if (m.beam != null && m.beam.isValid()) {
            styleBeam(m.beam, point.owner());
        }
    }

    public void remove(ControlPoint point) {
        Markers m = markers.remove(point);
        if (m != null) {
            despawn(m);
        }
    }

    public void removeAll() {
        markers.values().forEach(this::despawn);
        markers.clear();
    }

    /** Clears markers left behind by a previous load of the plugin (e.g. after /reload). */
    public void sweepStrays(Iterable<World> worlds) {
        for (World world : worlds) {
            for (Entity entity : world.getEntitiesByClass(BlockDisplay.class)) {
                if (entity.getScoreboardTags().contains(TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private void spawn(ControlPoint point, Markers m) {
        World world = point.world();
        Location slotCorner = new Location(world, point.x(), point.y() + 1, point.z());
        m.slot = world.spawn(slotCorner, BlockDisplay.class, d -> {
            prepare(d);
            float grow = 0.004f;
            d.setTransformation(new Transformation(new Vector3f(-grow / 2), new AxisAngle4f(),
                    new Vector3f(1 + grow), new AxisAngle4f()));
            d.setGlowing(plugin.getConfig().getBoolean("visuals.glow", true));
            d.setViewRange(4f);
            styleSlot(d, point.owner());
        });

        if (beamsEnabled()) {
            Location beamBase = new Location(world, point.x(), point.y() + 2, point.z());
            float height = Math.max(1, world.getMaxHeight() - (point.y() + 2));
            float offset = (1 - BEAM_WIDTH) / 2;
            m.beam = world.spawn(beamBase, BlockDisplay.class, d -> {
                prepare(d);
                d.setTransformation(new Transformation(new Vector3f(offset, 0, offset), new AxisAngle4f(),
                        new Vector3f(BEAM_WIDTH, height, BEAM_WIDTH), new AxisAngle4f()));
                d.setViewRange(16f);
                styleBeam(d, point.owner());
            });
            m.beamGlowing = false;
        }
    }

    private void prepare(BlockDisplay d) {
        d.setPersistent(false);
        d.addScoreboardTag(TAG);
        d.setBrightness(new Display.Brightness(15, 15));
    }

    private void styleSlot(BlockDisplay d, TeamColor owner) {
        if (owner == null) {
            d.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
            d.setGlowColorOverride(NEUTRAL_GLOW);
        } else {
            d.setBlock(owner.wool().createBlockData());
            d.setGlowColorOverride(owner.rgb());
        }
    }

    private void styleBeam(BlockDisplay d, TeamColor owner) {
        d.setBlock((owner == null ? Material.WHITE_STAINED_GLASS : owner.glass()).createBlockData());
        d.setGlowColorOverride(owner == null ? NEUTRAL_GLOW : owner.rgb());
    }

    private void despawn(Markers m) {
        if (m.slot != null) {
            m.slot.remove();
            m.slot = null;
        }
        if (m.beam != null) {
            m.beam.remove();
            m.beam = null;
        }
        m.beamGlowing = false;
    }

    private boolean beamsEnabled() {
        return plugin.getConfig().getBoolean("visuals.beam", true);
    }
}
