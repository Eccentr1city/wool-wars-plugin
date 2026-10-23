package com.woolwars.game;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Game state and rules: control points, team spawns, scores, and the scoring timer.
 *
 * Rules: every interval the timer hits zero and the team whose wool sits on the active control point scores
 * a point. With several points they take turns being the active one, in order. A full rotation through all
 * points takes round-minutes, so each point is scored once per round.
 */
public class Game {
    public enum State { IDLE, COUNTDOWN, RUNNING, PAUSED }

    private static final int[] ANNOUNCE_SECONDS = {300, 120, 60, 30};
    private static final int QUICKLAUNCH_COUNTDOWN_SECONDS = 10;

    private final WoolWarsPlugin plugin;
    private final File dataFile;

    private final List<ControlPoint> points = new ArrayList<>();
    private final Map<TeamColor, Location> spawns = new EnumMap<>(TeamColor.class);
    private final Map<TeamColor, Integer> scores = new EnumMap<>(TeamColor.class);

    private State state = State.IDLE;
    private int activeIndex;
    private long ticksLeft;
    private long elapsedTicks;
    private int countdownTicks;
    private boolean dirty;

    public Game(WoolWarsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ---------------------------------------------------------------- tick

    public void tick() {
        updateOwnership();

        switch (state) {
            case COUNTDOWN -> tickCountdown();
            case RUNNING -> tickRunning();
            default -> {
            }
        }
    }

    private void updateOwnership() {
        for (int i = 0; i < points.size(); i++) {
            ControlPoint point = points.get(i);
            if (!point.isLoaded()) {
                continue;
            }
            TeamColor now = TeamColor.fromWool(point.slot().getType());
            TeamColor before = point.owner();
            boolean firstLook = !point.ownerKnown();
            if (now == before && !firstLook) {
                continue;
            }
            point.setOwner(now);
            plugin.visuals().refresh(point);
            if (!firstLook && plugin.getConfig().getBoolean("announce-captures", true)) {
                announceCapture(i, before, now);
            }
        }
    }

    private void announceCapture(int index, TeamColor before, TeamColor now) {
        Component where = Component.text(pointName(index), NamedTextColor.WHITE);
        if (now != null) {
            Msg.broadcast(Component.empty().append(now.label()).append(Component.text(" took ")).append(where)
                    .append(Component.text("!")));
            Msg.soundAll(Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 1f);
        } else {
            Msg.broadcast(Component.empty().append(before.label()).append(Component.text(" lost ")).append(where));
            Msg.soundAll(Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.5f);
        }
    }

    private void tickRunning() {
        ticksLeft--;
        elapsedTicks++;

        if (ticksLeft % 20 == 0) {
            long seconds = ticksLeft / 20;
            for (int announce : ANNOUNCE_SECONDS) {
                if (seconds == announce && intervalTicks() > announce * 20L) {
                    Msg.broadcast(Component.text(Msg.clock(seconds) + " until " + pointName(activeIndex) + " is scored",
                            NamedTextColor.AQUA));
                }
            }
            if (seconds > 0 && seconds <= 5) {
                Msg.soundAll(Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1f);
            }
        }

        if (ticksLeft <= 0) {
            scoreActivePoint();
            if (state != State.RUNNING) {
                return;
            }
        }

        int limitMinutes = plugin.getConfig().getInt("win.time-minutes", 0);
        if (limitMinutes > 0 && elapsedTicks >= limitMinutes * 1200L) {
            end(leader());
        }
    }

    private void tickCountdown() {
        if (countdownTicks % 20 == 0) {
            int seconds = countdownTicks / 20;
            if (seconds > 0) {
                Msg.titleAll(Component.text(seconds, NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Wool Wars starting", NamedTextColor.YELLOW), 1);
                Msg.soundAll(Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, seconds <= 3 ? 1.5f : 1f);
            }
        }
        countdownTicks--;
        if (countdownTicks <= 0) {
            teleportTeamsToSpawns();
            start();
        }
    }

    // ---------------------------------------------------------------- scoring

    private void scoreActivePoint() {
        ControlPoint point = points.get(activeIndex);
        TeamColor owner = point.isLoaded() ? TeamColor.fromWool(point.slot().getType()) : null;

        if (owner != null) {
            // Rising major arpeggio on bells, then a firework twinkle.
            Msg.jingle(plugin, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 3, 1.0f, 1.26f, 1.5f, 2.0f);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> Msg.soundAll(Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.7f, 1f), 12);
            int score = scores.merge(owner, 1, Integer::sum);
            Msg.titleAll(Component.empty().append(owner.label()).append(Component.text(" scores!", NamedTextColor.YELLOW)),
                    Component.text(pointName(activeIndex) + " · " + score + (score == 1 ? " point" : " points"),
                            NamedTextColor.GRAY), 3);
            Msg.broadcast(Component.empty().append(owner.label())
                    .append(Component.text(" held " + pointName(activeIndex) + " and scores! ", NamedTextColor.GREEN))
                    .append(Component.text("(" + score + ")", NamedTextColor.WHITE)));
        } else {
            Msg.jingle(plugin, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 4, 0.8f, 0.6f);
            Msg.titleAll(Component.text("No one scores", NamedTextColor.GRAY),
                    Component.text(Msg.capitalize(pointName(activeIndex)) + " was empty", NamedTextColor.DARK_GRAY), 3);
            Msg.broadcast(Component.text(Msg.capitalize(pointName(activeIndex)) + " was empty. No one scores.", NamedTextColor.GRAY));
        }
        dirty = true;

        int target = plugin.getConfig().getInt("win.points", 0);
        if (owner != null && target > 0 && scores.get(owner) >= target) {
            end(owner);
            return;
        }

        activeIndex = (activeIndex + 1) % points.size();
        ticksLeft = intervalTicks();
        if (points.size() > 1) {
            Msg.broadcast(Component.text("Next up: " + pointName(activeIndex) + " (" + points.get(activeIndex).coords()
                    + ", " + Msg.dimension(points.get(activeIndex).world()) + ")", NamedTextColor.AQUA));
        }
    }

    /** Team with the strictly highest score, or null on a tie / no scores. */
    public TeamColor leader() {
        TeamColor best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Map.Entry<TeamColor, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
                tie = false;
            } else if (entry.getValue() == bestScore && bestScore > 0) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    public List<Map.Entry<TeamColor, Integer>> standings() {
        List<Map.Entry<TeamColor, Integer>> list = new ArrayList<>();
        for (TeamColor color : TeamColor.values()) {
            int score = scores.getOrDefault(color, 0);
            if (score > 0 || !plugin.teams().members(color).isEmpty()) {
                list.add(Map.entry(color, score));
            }
        }
        list.sort(Comparator.comparing((Map.Entry<TeamColor, Integer> e) -> e.getValue()).reversed());
        return list;
    }

    public void broadcastStandings() {
        for (Map.Entry<TeamColor, Integer> entry : standings()) {
            Bukkit.broadcast(Component.text("  ").append(entry.getKey().label())
                    .append(Component.text(": " + entry.getValue(), NamedTextColor.WHITE)));
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts a fresh game: scores reset, first point active, full timer. */
    public boolean start() {
        if (points.isEmpty()) {
            return false;
        }
        scores.clear();
        activeIndex = 0;
        ticksLeft = intervalTicks();
        elapsedTicks = 0;
        state = State.RUNNING;
        dirty = true;
        plugin.supplies().giveStartGear();

        Msg.titleAll(Component.text("Wool Wars", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Hold " + pointName(0) + " when the timer hits zero", NamedTextColor.YELLOW), 3);
        Msg.soundAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.2f);
        Msg.broadcast(Component.text("Game started! " + Msg.capitalize(pointName(0)) + " is scored in "
                + Msg.clock(ticksLeft / 20) + ".", NamedTextColor.GREEN));
        return true;
    }

    public boolean beginCountdown() {
        if (points.isEmpty()) {
            return false;
        }
        state = State.COUNTDOWN;
        countdownTicks = QUICKLAUNCH_COUNTDOWN_SECONDS * 20;
        return true;
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            dirty = true;
            Msg.broadcast(Component.text("Game paused.", NamedTextColor.RED));
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING;
            dirty = true;
            Msg.broadcast(Component.text("Game resumed! " + Msg.clock(ticksLeft / 20) + " until "
                    + pointName(activeIndex) + " is scored.", NamedTextColor.GREEN));
        }
    }

    /** Stops without declaring a winner. Scores stay visible until the next start. */
    public void stop() {
        state = State.IDLE;
        dirty = true;
        Msg.broadcast(Component.text("Game stopped.", NamedTextColor.RED));
    }

    public void end(TeamColor winner) {
        state = State.IDLE;
        dirty = true;
        if (winner != null) {
            Msg.titleAll(Component.empty().append(winner.label()).append(Component.text(" wins!", NamedTextColor.GOLD)),
                    Component.text("GG", NamedTextColor.YELLOW), 6);
            Msg.broadcast(Component.empty().append(winner.label()).append(Component.text(" wins the game!", NamedTextColor.GOLD)));
        } else {
            Msg.titleAll(Component.text("Game over", NamedTextColor.GOLD), Component.text("It's a tie", NamedTextColor.YELLOW), 6);
            Msg.broadcast(Component.text("Game over: it's a tie!", NamedTextColor.GOLD));
        }
        Msg.soundAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        broadcastStandings();
    }

    public void resetScores() {
        scores.clear();
        dirty = true;
    }

    public void resetTimer() {
        ticksLeft = intervalTicks();
        dirty = true;
    }

    public void teleportTeamsToSpawns() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            TeamColor team = plugin.teams().teamOf(player);
            Location spawn = team == null ? null : spawnLocation(team);
            if (spawn == null) {
                continue;
            }
            player.teleport(spawn);
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
            player.setFoodLevel(20);
            player.setSaturation(5);
        }
    }

    // ---------------------------------------------------------------- points

    public long intervalTicks() {
        double minutes = plugin.getConfig().getDouble("round-minutes", 10);
        return Math.max(20, Math.round(minutes * 1200 / Math.max(1, points.size())));
    }

    /** Turns {@code base} into a control point base. Returns an error message, or null on success. */
    public String addPoint(Block base) {
        Block slot = base.getRelative(0, 1, 0);
        if (!slot.getType().isAir() && TeamColor.fromWool(slot.getType()) == null) {
            return "The block above the base must be empty (or wool).";
        }
        if (findPoint(base) != null) {
            return "That's already a control point.";
        }
        String original = base.getType() == Material.BEDROCK ? Material.STONE.createBlockData().getAsString()
                : base.getBlockData().getAsString();
        ControlPoint point = new ControlPoint(base.getWorld().getName(), base.getX(), base.getY(), base.getZ(), original);
        base.setType(Material.BEDROCK);
        base.getChunk().addPluginChunkTicket(plugin);
        points.add(point);
        afterPointsChanged();
        return null;
    }

    public void removePoint(int index) {
        ControlPoint point = points.remove(index);
        plugin.visuals().remove(point);
        if (point.world() != null) {
            if (point.base().getType() == Material.BEDROCK) {
                point.base().setBlockData(Bukkit.createBlockData(point.originalBase()));
            }
            point.world().removePluginChunkTicket(point.x() >> 4, point.z() >> 4, plugin);
        }
        if (points.isEmpty() && state != State.IDLE) {
            state = State.IDLE;
            Msg.broadcast(Component.text("Last control point removed; game stopped.", NamedTextColor.RED));
        }
        afterPointsChanged();
    }

    public void clearPoints() {
        while (!points.isEmpty()) {
            removePoint(points.size() - 1);
        }
    }

    private void afterPointsChanged() {
        if (activeIndex >= points.size()) {
            activeIndex = 0;
        }
        // Rescale the time left so the countdown matches the new interval.
        if (state == State.IDLE || ticksLeft > intervalTicks()) {
            ticksLeft = intervalTicks();
        }
        dirty = true;
    }

    public ControlPoint findPoint(Block base) {
        for (ControlPoint point : points) {
            if (point.isBase(base)) {
                return point;
            }
        }
        return null;
    }

    public List<ControlPoint> points() {
        return points;
    }

    public ControlPoint activePoint() {
        return points.isEmpty() ? null : points.get(activeIndex);
    }

    public int activeIndex() {
        return activeIndex;
    }

    public String pointName(int index) {
        return points.size() == 1 ? "the control point" : "Point #" + (index + 1);
    }

    // ---------------------------------------------------------------- spawns

    public void setSpawn(TeamColor team, Location location) {
        spawns.put(team, location.clone());
        dirty = true;
    }

    public void clearSpawn(TeamColor team) {
        spawns.remove(team);
        dirty = true;
    }

    public Location spawnLocation(TeamColor team) {
        Location spawn = spawns.get(team);
        return spawn == null || spawn.getWorld() == null ? null : spawn.clone();
    }

    public Map<TeamColor, Location> spawns() {
        return spawns;
    }

    // ---------------------------------------------------------------- accessors

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public long ticksLeft() {
        return ticksLeft;
    }

    public long elapsedTicks() {
        return elapsedTicks;
    }

    public int score(TeamColor team) {
        return scores.getOrDefault(team, 0);
    }

    public Map<TeamColor, Integer> scores() {
        return scores;
    }

    public boolean netherGearEnabled() {
        String mode = plugin.getConfig().getString("nether-gear", "auto");
        if ("on".equalsIgnoreCase(mode) || "true".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)) {
            return false;
        }
        for (ControlPoint point : points) {
            World world = point.world();
            if (world != null && world.getEnvironment() == World.Environment.NETHER) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- persistence

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        List<String> pointStrings = new ArrayList<>();
        for (ControlPoint p : points) {
            pointStrings.add(p.worldName() + ";" + p.x() + ";" + p.y() + ";" + p.z() + ";" + p.originalBase());
        }
        data.set("points", pointStrings);
        for (Map.Entry<TeamColor, Location> entry : spawns.entrySet()) {
            Location l = entry.getValue();
            if (l.getWorld() == null) {
                continue;
            }
            String path = "spawns." + entry.getKey().id();
            data.set(path + ".world", l.getWorld().getName());
            data.set(path + ".x", l.getX());
            data.set(path + ".y", l.getY());
            data.set(path + ".z", l.getZ());
            data.set(path + ".yaw", l.getYaw());
        }
        for (Map.Entry<TeamColor, Integer> entry : scores.entrySet()) {
            data.set("scores." + entry.getKey().id(), entry.getValue());
        }
        // A countdown in progress is saved as idle; a running game comes back paused.
        data.set("state", state == State.COUNTDOWN ? State.IDLE.name() : state.name());
        data.set("active-index", activeIndex);
        data.set("ticks-left", ticksLeft);
        data.set("elapsed-ticks", elapsedTicks);
        try {
            plugin.getDataFolder().mkdirs();
            data.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    public void load() {
        if (!dataFile.exists()) {
            ticksLeft = intervalTicks();
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : data.getStringList("points")) {
            String[] parts = s.split(";", 5);
            if (parts.length < 4) {
                continue;
            }
            String original = parts.length == 5 ? parts[4] : Material.STONE.createBlockData().getAsString();
            points.add(new ControlPoint(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), original));
            if (Bukkit.getWorld(parts[0]) == null) {
                plugin.getLogger().warning("Control point in unknown world '" + parts[0] + "'. Loaded worlds: "
                        + Bukkit.getWorlds().stream().map(World::getName).toList());
            }
        }
        ConfigurationSection spawnSection = data.getConfigurationSection("spawns");
        if (spawnSection != null) {
            for (String key : spawnSection.getKeys(false)) {
                TeamColor color = TeamColor.fromId(key);
                World world = Bukkit.getWorld(spawnSection.getString(key + ".world", ""));
                if (color == null || world == null) {
                    continue;
                }
                spawns.put(color, new Location(world, spawnSection.getDouble(key + ".x"), spawnSection.getDouble(key + ".y"),
                        spawnSection.getDouble(key + ".z"), (float) spawnSection.getDouble(key + ".yaw"), 0));
            }
        }
        ConfigurationSection scoreSection = data.getConfigurationSection("scores");
        if (scoreSection != null) {
            for (String key : scoreSection.getKeys(false)) {
                TeamColor color = TeamColor.fromId(key);
                if (color != null) {
                    scores.put(color, scoreSection.getInt(key));
                }
            }
        }
        State saved = State.valueOf(data.getString("state", "IDLE"));
        state = saved == State.RUNNING ? State.PAUSED : saved;
        activeIndex = points.isEmpty() ? 0 : Math.min(data.getInt("active-index"), points.size() - 1);
        ticksLeft = data.getLong("ticks-left", intervalTicks());
        elapsedTicks = data.getLong("elapsed-ticks");
        // Keep point chunks loaded so ownership is always readable and scoring never sees an unloaded slot.
        for (ControlPoint point : points) {
            if (point.world() != null) {
                point.world().addPluginChunkTicket(point.x() >> 4, point.z() >> 4, plugin);
            }
        }
    }
}
