package com.woolwars.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.game.Game;
import com.woolwars.game.PointFinder;
import com.woolwars.model.ControlPoint;
import com.woolwars.model.TeamColor;
import com.woolwars.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public class WoolWarsCommand implements TabExecutor {
    private static final String ADMIN = "woolwars.admin";
    private static final List<String> PLAYER_SUBS = List.of("help", "join", "leave", "teams", "status");
    private static final List<String> ADMIN_SUBS = List.of("point", "spawn", "start", "stop", "end", "pause", "resume",
            "quicklaunch", "reset", "resistance", "timer", "scores", "win", "team", "config", "reload");

    private final WoolWarsPlugin plugin;
    /** True while a background setup (point/spawn placement, quicklaunch) is running. */
    private boolean busy;

    public WoolWarsCommand(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs a setup step that loads terrain in the background. Only one runs at a time, so two admins (or one
     * impatient one) can't interleave placements.
     */
    private void setupTask(CommandSender sender, Supplier<CompletableFuture<?>> task) {
        if (busy) {
            Msg.error(sender, "Still working on the last setup command; give it a moment.");
            return;
        }
        busy = true;
        CompletableFuture<?> running;
        try {
            running = task.get();
        } catch (RuntimeException e) {
            busy = false;
            throw e;
        }
        running.whenComplete((v, error) -> {
            busy = false;
            if (error != null) {
                Msg.error(sender, "Setup failed: " + error.getMessage());
                plugin.getLogger().log(Level.WARNING, "Setup command failed", error);
            }
        });
    }

    private Game game() {
        return plugin.game();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        if (ADMIN_SUBS.contains(sub) || sub.equals("unpause") || sub.equals("ql")) {
            if (!sender.hasPermission(ADMIN)) {
                Msg.error(sender, "You need to be an admin (op) for that.");
                return true;
            }
        }
        switch (sub) {
            case "join" -> join(sender, rest);
            case "leave" -> leave(sender);
            case "teams" -> teams(sender);
            case "status", "scores" -> {
                if (sub.equals("scores") && rest.length > 0 && rest[0].equalsIgnoreCase("reset")) {
                    game().resetScores();
                    Msg.broadcast(Component.text("Scores reset.", NamedTextColor.RED));
                } else {
                    status(sender);
                }
            }
            case "point" -> point(sender, rest);
            case "spawn" -> spawn(sender, rest);
            case "start" -> {
                if (!game().start()) {
                    Msg.error(sender, "Add a control point first (/ww point add or /ww point auto).");
                }
            }
            case "stop" -> game().stop();
            case "end" -> game().end(game().leader());
            case "pause" -> game().pause();
            case "resume", "unpause" -> game().resume();
            case "quicklaunch", "ql" -> quicklaunch(sender, rest);
            case "reset" -> reset(sender, rest);
            case "resistance" -> resistance(sender, rest);
            case "timer" -> timer(sender, rest);
            case "win" -> win(sender, rest);
            case "team" -> team(sender, rest);
            case "config" -> config(sender, rest);
            case "reload" -> {
                plugin.reloadConfig();
                plugin.visuals().removeAll();
                Msg.ok(sender, "Config reloaded.");
            }
            default -> help(sender);
        }
        return true;
    }

    // ---------------------------------------------------------------- players

    private void join(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.error(sender, "Only players can join. Use /ww team <player> <color>.");
            return;
        }
        TeamColor color = args.length == 0 ? null : TeamColor.fromId(String.join("_", args));
        if (color == null) {
            Msg.error(sender, "Pick a color: " + String.join(", ", colorIds()));
            return;
        }
        plugin.teams().join(player, color);
        Msg.broadcast(Component.text(player.getName() + " joined ").append(color.label()));
    }

    private void leave(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.teams().leave(player);
            Msg.ok(sender, "You left your team.");
        }
    }

    private void teams(CommandSender sender) {
        List<TeamColor> active = plugin.teams().activeTeams();
        if (active.isEmpty()) {
            Msg.info(sender, "No one is on a team yet. /ww join <color>");
            return;
        }
        for (TeamColor color : active) {
            Location spawn = game().spawnLocation(color);
            sender.sendMessage(Component.text("  ").append(color.label())
                    .append(Component.text(": " + String.join(", ", plugin.teams().members(color)), NamedTextColor.WHITE))
                    .append(Component.text(spawn == null ? "  (no spawn)" : "  (spawn set)", NamedTextColor.DARK_GRAY))
                    .append(Component.text(game().resistance(color) > 0 ? "  Resistance " + roman(game().resistance(color)) : "",
                            NamedTextColor.DARK_GRAY)));
        }
    }

    private void status(CommandSender sender) {
        Game g = game();
        Msg.info(sender, Component.text("State: " + g.state().name().toLowerCase(Locale.ROOT)
                + (g.state() == Game.State.IDLE ? "" : " · " + Msg.clock(g.ticksLeft() / 20) + " until "
                + g.pointName(g.activeIndex()) + " scores"), NamedTextColor.YELLOW));
        listPoints(sender);
        for (Map.Entry<TeamColor, Integer> entry : g.standings()) {
            sender.sendMessage(Component.text("  ").append(entry.getKey().label())
                    .append(Component.text(": " + entry.getValue(), NamedTextColor.WHITE)));
        }
        int target = plugin.getConfig().getInt("win.points", 0);
        int minutes = plugin.getConfig().getInt("win.time-minutes", 0);
        if (target > 0 || minutes > 0) {
            sender.sendMessage(Component.text("  Win: " + (target > 0 ? "first to " + target + " points" : "")
                    + (target > 0 && minutes > 0 ? ", or " : "")
                    + (minutes > 0 ? "most points after " + minutes + " min" : ""), NamedTextColor.GRAY));
        }
    }

    private void help(CommandSender sender) {
        Msg.info(sender, "Commands:");
        line(sender, "/ww join <color> · leave · teams · status", "pick a team, see the game");
        if (sender.hasPermission(ADMIN)) {
            line(sender, "/ww quicklaunch", "auto-place anything missing, count down, teleport, start");
            line(sender, "/ww quicklaunch new [blocks] [filters]", "wipe this arena, set up a fresh one far away, start");
            line(sender, "/ww reset [all]", "clear points, spawns and scores (all: also teams and resistance)");
            line(sender, "/ww resistance <color> <0-4>", "handicap: resistance for a team, reapplied on respawn");
            line(sender, "/ww point add|remove <n>|list|clear|tp <n>|auto [count] [spread]", "control points");
            line(sender, "/ww point find [high|low] [biome:<id>] [structure:<id>] [range:<n>]", "add a point somewhere specific");
            line(sender, "/ww spawn set [color]|clear [color]|list|auto [radius]", "team spawns");
            line(sender, "/ww start · stop · end · pause · resume", "game flow (end declares a winner)");
            line(sender, "/ww timer <minutes>|reset", "minutes for a full rotation through all points");
            line(sender, "/ww win points <n>|time <minutes>|off", "optional win condition");
            line(sender, "/ww team <player> <color|none> · scores reset", "admin team/score control");
            line(sender, "/ww config [key] [value] · reload", "view or change settings");
        }
    }

    private static void line(CommandSender sender, String cmd, String what) {
        sender.sendMessage(Component.text("  " + cmd, NamedTextColor.AQUA).append(Component.text(" - " + what, NamedTextColor.GRAY)));
    }

    // ---------------------------------------------------------------- admin

    private void point(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> {
                Player player = requirePlayer(sender);
                if (player == null) {
                    return;
                }
                Block target = player.getTargetBlockExact(5);
                Block base = target != null && !target.isPassable() ? target : player.getLocation().getBlock().getRelative(0, -1, 0);
                if (base.isPassable()) {
                    Msg.error(sender, "Look at a block (or stand on one) to make it a control point base.");
                    return;
                }
                String error = game().addPoint(base);
                if (error != null) {
                    Msg.error(sender, error);
                } else {
                    Msg.ok(sender, "Added " + game().pointName(game().points().size() - 1) + " at " + base.getX() + ", "
                            + base.getY() + ", " + base.getZ() + ". Wool goes on top of the bedrock.");
                }
            }
            case "remove" -> {
                int n = args.length > 1 ? parseInt(args[1], -1) : (game().points().size() == 1 ? 1 : -1);
                if (n < 1 || n > game().points().size()) {
                    Msg.error(sender, "Which point? /ww point remove <1-" + game().points().size() + ">");
                    return;
                }
                game().removePoint(n - 1);
                Msg.ok(sender, "Removed point #" + n + ". Remaining points renumbered.");
            }
            case "find" -> {
                // Searches from where you stand; from the console, from the world spawn.
                Location origin = sender instanceof Player player ? player.getLocation()
                        : Bukkit.getWorlds().get(0).getSpawnLocation();
                List<String> errors = new ArrayList<>();
                PointFinder.Query query = plugin.pointFinder().parse(Arrays.copyOfRange(args, 1, args.length), errors);
                if (query == null) {
                    errors.forEach(e -> Msg.error(sender, e));
                    return;
                }
                Msg.info(sender, "Searching...");
                setupTask(sender, () -> plugin.pointFinder().find(origin, query).thenAccept(result -> {
                    if (result.error() != null) {
                        Msg.error(sender, result.error());
                        return;
                    }
                    Block ground = result.ground();
                    if (!plugin.placement().placePoint(ground)) {
                        Msg.error(sender, "Couldn't place a point there.");
                        return;
                    }
                    int n = game().points().size();
                    int away = (int) Math.hypot(ground.getX() - origin.getX(), ground.getZ() - origin.getZ());
                    Msg.ok(sender, "Added point #" + n + " at " + ground.getX() + ", " + (ground.getY() + 1) + ", " + ground.getZ()
                            + " (" + result.description() + ", " + away + " blocks away). /ww point tp " + n + " to go look.");
                }));
            }
            case "tp" -> {
                Player player = requirePlayer(sender);
                if (player == null) {
                    return;
                }
                int n = args.length > 1 ? parseInt(args[1], -1) : game().activeIndex() + 1;
                if (n < 1 || n > game().points().size() || game().points().get(n - 1).world() == null) {
                    Msg.error(sender, "Which point? /ww point tp <1-" + game().points().size() + ">");
                    return;
                }
                ControlPoint p = game().points().get(n - 1);
                Location to = p.slotCenter().add(0, 1, 2.5);
                to.setDirection(p.slotCenter().toVector().subtract(to.toVector()));
                player.teleport(to);
            }
            case "clear" -> {
                game().clearPoints();
                Msg.ok(sender, "All control points removed.");
            }
            case "auto" -> {
                Player player = requirePlayer(sender);
                if (player == null) {
                    return;
                }
                int count = args.length > 1 ? parseInt(args[1], 1) : 1;
                double spread = args.length > 2 ? parseInt(args[2], 40) : plugin.getConfig().getDouble("auto.point-spread", 40);
                if (count < 1 || count > 16) {
                    Msg.error(sender, "Count must be between 1 and 16.");
                    return;
                }
                setupTask(sender, () -> plugin.placement().autoPoints(player.getLocation(), count, spread).thenAccept(placed -> {
                    Msg.ok(sender, "Placed " + placed + "/" + count + " control point" + (count == 1 ? "" : "s") + ".");
                    listPoints(sender);
                }));
            }
            default -> listPoints(sender);
        }
    }

    private void listPoints(CommandSender sender) {
        List<ControlPoint> points = game().points();
        if (points.isEmpty()) {
            sender.sendMessage(Component.text("  No control points. /ww point add or /ww point auto", NamedTextColor.GRAY));
            return;
        }
        for (int i = 0; i < points.size(); i++) {
            ControlPoint p = points.get(i);
            Component holder = p.owner() == null ? Component.text("empty", NamedTextColor.GRAY) : p.owner().label();
            sender.sendMessage(Component.text((i == game().activeIndex() ? "  ➤ " : "    ") + "#" + (i + 1) + "  "
                    + p.coords() + " (" + Msg.dimension(p.world()) + ")  ", NamedTextColor.WHITE).append(holder));
        }
    }

    private void spawn(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "set" -> {
                Player player = requirePlayer(sender);
                if (player == null) {
                    return;
                }
                TeamColor team = args.length > 1 ? TeamColor.fromId(args[1]) : plugin.teams().teamOf(player);
                if (team == null) {
                    Msg.error(sender, "Which team? /ww spawn set <color>");
                    return;
                }
                game().setSpawn(team, player.getLocation());
                Msg.broadcast(Component.empty().append(team.label()).append(Component.text(" spawn set by " + player.getName())));
            }
            case "clear" -> {
                if (args.length > 1) {
                    TeamColor team = TeamColor.fromId(args[1]);
                    if (team == null) {
                        Msg.error(sender, "Unknown color.");
                        return;
                    }
                    game().clearSpawn(team);
                } else {
                    for (TeamColor team : TeamColor.values()) {
                        game().clearSpawn(team);
                    }
                }
                Msg.ok(sender, "Spawn(s) cleared.");
            }
            case "auto" -> {
                List<TeamColor> teams = plugin.teams().activeTeams();
                if (teams.isEmpty()) {
                    Msg.error(sender, "No one is on a team yet, so there's nothing to place spawns for.");
                    return;
                }
                Location fallback = sender instanceof Player p ? p.getLocation() : null;
                if (game().points().isEmpty() && fallback == null) {
                    Msg.error(sender, "Add a control point first, or run this in-game.");
                    return;
                }
                double radius = args.length > 1 ? parseInt(args[1], 100) : plugin.placement().defaultSpawnRadius();
                setupTask(sender, () -> autoSpawns(sender, teams, radius, fallback));
            }
            default -> {
                for (Map.Entry<TeamColor, Location> entry : game().spawns().entrySet()) {
                    Location l = entry.getValue();
                    sender.sendMessage(Component.text("  ").append(entry.getKey().label()).append(Component.text(
                            ": " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + " (" + Msg.dimension(l.getWorld()) + ")",
                            NamedTextColor.WHITE)));
                }
                if (game().spawns().isEmpty()) {
                    sender.sendMessage(Component.text("  No team spawns. /ww spawn set or /ww spawn auto", NamedTextColor.GRAY));
                }
            }
        }
    }

    private CompletableFuture<Void> autoSpawns(CommandSender sender, List<TeamColor> teams, double radius, Location fallback) {
        Msg.info(sender, "Placing spawns " + (int) radius + " blocks out...");
        return plugin.placement().autoSpawns(teams, radius, fallback).thenAccept(failed -> {
            Msg.ok(sender, "Placed spawns for " + (teams.size() - failed.size()) + " team(s), " + (int) radius + " blocks out.");
            for (TeamColor team : failed) {
                Msg.error(sender, "Couldn't find safe ground for " + team.displayName() + "; set it with /ww spawn set " + team.id());
            }
        });
    }

    private void quicklaunch(CommandSender sender, String[] args) {
        Player player = sender instanceof Player p ? p : null;
        boolean fresh = args.length > 0 && args[0].equalsIgnoreCase("new");
        if (!fresh && game().points().isEmpty() && player == null) {
            Msg.error(sender, "No control points; run this in-game or add one first.");
            return;
        }
        if (plugin.teams().activeTeams().isEmpty()) {
            Msg.error(sender, "No one is on a team yet. /ww join <color>");
            return;
        }
        setupTask(sender, () -> {
            CompletableFuture<Boolean> arena;
            if (fresh && game().state() != Game.State.IDLE) {
                // A new round replaces whatever is going on now.
                game().stop();
            }
            if (fresh) {
                arena = newArena(sender, Arrays.copyOfRange(args, 1, args.length));
            } else if (game().points().isEmpty()) {
                arena = plugin.placement().autoPoints(player.getLocation(), 1, 0).thenApply(placed -> {
                    if (placed == 0) {
                        Msg.error(sender, "Couldn't find ground for a control point here.");
                        return false;
                    }
                    Msg.ok(sender, "Placed a control point at your location.");
                    return true;
                });
            } else {
                arena = CompletableFuture.completedFuture(true);
            }
            return arena.thenCompose(ok -> {
                if (!ok) {
                    return CompletableFuture.completedFuture(null);
                }
                List<TeamColor> teams = plugin.teams().activeTeams();
                boolean missingSpawn = teams.stream().anyMatch(t -> game().spawnLocation(t) == null);
                CompletableFuture<Void> spawns = missingSpawn
                        ? autoSpawns(sender, teams, plugin.placement().defaultSpawnRadius(), player == null ? null : player.getLocation())
                        : CompletableFuture.completedFuture(null);
                return spawns.thenRun(() -> game().beginCountdown());
            });
        });
    }

    /**
     * /ww quicklaunch new [blocks] [find filters]: wipes the current arena and sets up a fresh one about
     * {@code blocks} away in a random direction, so the new round can't overlap the old one.
     * Completes with whether a new control point was placed.
     */
    private CompletableFuture<Boolean> newArena(CommandSender sender, String[] args) {
        Location origin = sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        double parsed = args.length > 0 ? parseDouble(args[0], -1) : -1;
        double distance = parsed > 0 ? parsed : plugin.placement().defaultNewArenaDistance();
        String[] filters = parsed > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        List<String> errors = new ArrayList<>();
        PointFinder.Query query = filters.length == 0 ? null : plugin.pointFinder().parse(filters, errors);
        if (filters.length > 0 && query == null) {
            errors.forEach(e -> Msg.error(sender, e));
            return CompletableFuture.completedFuture(false);
        }

        Msg.info(sender, "Finding a new arena about " + (int) distance + " blocks away...");
        return plugin.placement().pickNewArena(origin, distance).thenCompose(center -> {
            if (center == null) {
                Msg.error(sender, "Couldn't find dry land " + (int) distance + " blocks away in any direction. Try another distance.");
                return CompletableFuture.completedFuture(null);
            }
            if (query == null) {
                return CompletableFuture.completedFuture(new PointFinder.Result(center, "", null));
            }
            return plugin.pointFinder().find(center.getLocation(), query);
        }).thenApply(result -> {
            if (result == null) {
                return false;
            }
            if (result.error() != null) {
                Msg.error(sender, result.error());
                return false;
            }
            game().reset(false);
            Block ground = result.ground();
            if (!plugin.placement().placePoint(ground)) {
                Msg.error(sender, "Couldn't place the control point.");
                return false;
            }
            int away = (int) Math.hypot(ground.getX() - origin.getX(), ground.getZ() - origin.getZ());
            String what = result.description().isEmpty() ? "" : " (" + result.description() + ")";
            Msg.broadcast(Component.text("New arena: control point at " + ground.getX() + ", " + (ground.getY() + 1) + ", "
                    + ground.getZ() + what + ", " + away + " blocks away.", NamedTextColor.AQUA));
            return true;
        });
    }

    private void reset(CommandSender sender, String[] args) {
        boolean everything = args.length > 0 && args[0].equalsIgnoreCase("all");
        game().reset(everything);
        if (everything) {
            plugin.teams().clearAll();
        }
        Msg.broadcast(Component.text(everything
                ? "Everything reset: control points, spawns, scores, teams and team resistance."
                : "Arena reset: control points, spawns and scores cleared. Teams kept.", NamedTextColor.RED));
    }

    private void resistance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            boolean any = false;
            for (TeamColor team : TeamColor.values()) {
                if (game().resistance(team) > 0) {
                    any = true;
                    sender.sendMessage(Component.text("  ").append(team.label())
                            .append(Component.text(": Resistance " + roman(game().resistance(team)), NamedTextColor.WHITE)));
                }
            }
            if (!any) {
                Msg.info(sender, "No team has resistance. /ww resistance <color> <0-4>");
            }
            return;
        }
        TeamColor team = TeamColor.fromId(args[0]);
        int level = args.length > 1 ? parseInt(args[1], -1) : -1;
        if (team == null || level < 0 || level > 4) {
            Msg.error(sender, "/ww resistance <color> <0-4>  (0 removes it; each level is 20% less damage)");
            return;
        }
        game().setResistance(team, level);
        if (level == 0) {
            for (Player p : plugin.teams().onlineMembers(team)) {
                p.removePotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE);
            }
            Msg.broadcast(Component.empty().append(team.label()).append(Component.text(" no longer has resistance.")));
        } else {
            Msg.broadcast(Component.empty().append(team.label()).append(Component.text(" now has Resistance " + roman(level)
                    + " (" + level * 20 + "% less damage).")));
        }
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(n);
        };
    }

    private void timer(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Msg.info(sender, "A full rotation takes " + plugin.getConfig().getDouble("round-minutes") + " min; each point is scored every "
                    + Msg.clock(game().intervalTicks() / 20) + ". /ww timer <minutes> or /ww timer reset");
            return;
        }
        if (args[0].equalsIgnoreCase("reset")) {
            game().resetTimer();
            Msg.broadcast(Component.text("Timer reset to " + Msg.clock(game().intervalTicks() / 20) + "."));
            return;
        }
        double minutes = parseDouble(args[0], -1);
        if (minutes <= 0) {
            Msg.error(sender, "Give a positive number of minutes.");
            return;
        }
        plugin.getConfig().set("round-minutes", minutes);
        plugin.saveConfig();
        game().resetTimer();
        Msg.broadcast(Component.text("Round length set to " + minutes + " min (a point is scored every "
                + Msg.clock(game().intervalTicks() / 20) + ")."));
    }

    private void win(CommandSender sender, String[] args) {
        if (args.length == 0) {
            status(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "points" -> plugin.getConfig().set("win.points", args.length > 1 ? Math.max(0, parseInt(args[1], 0)) : 0);
            case "time" -> plugin.getConfig().set("win.time-minutes", args.length > 1 ? Math.max(0, parseInt(args[1], 0)) : 0);
            case "off" -> {
                plugin.getConfig().set("win.points", 0);
                plugin.getConfig().set("win.time-minutes", 0);
            }
            default -> {
                Msg.error(sender, "/ww win points <n> | time <minutes> | off");
                return;
            }
        }
        plugin.saveConfig();
        status(sender);
    }

    private void team(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.error(sender, "/ww team <player> <color|none>");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[0]);
        }
        if (target == null) {
            Msg.error(sender, "Unknown player " + args[0] + ".");
            return;
        }
        if (args[1].equalsIgnoreCase("none")) {
            plugin.teams().leave(target);
            Msg.ok(sender, target.getName() + " removed from their team.");
            return;
        }
        TeamColor color = TeamColor.fromId(args[1]);
        if (color == null) {
            Msg.error(sender, "Unknown color.");
            return;
        }
        plugin.teams().join(target, color);
        Msg.broadcast(Component.text(target.getName() + " was put on ").append(color.label()));
    }

    private void config(CommandSender sender, String[] args) {
        if (args.length == 0) {
            for (String key : configKeys()) {
                sender.sendMessage(Component.text("  " + key + ": ", NamedTextColor.AQUA)
                        .append(Component.text(String.valueOf(plugin.getConfig().get(key)), NamedTextColor.WHITE)));
            }
            return;
        }
        String key = args[0];
        if (!configKeys().contains(key)) {
            Msg.error(sender, "Unknown setting " + key + ". /ww config to list them.");
            return;
        }
        if (args.length == 1) {
            Msg.info(sender, key + " = " + plugin.getConfig().get(key));
            return;
        }
        // Type-check against the shipped default, so settings like auto.spawn-radius accept both "auto" and numbers.
        var defaults = plugin.getConfig().getDefaults();
        Object current = defaults != null && defaults.contains(key) ? defaults.get(key) : plugin.getConfig().get(key);
        String raw = args[1];
        Object value;
        if (current instanceof Boolean) {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
                Msg.error(sender, key + " is true/false.");
                return;
            }
            value = Boolean.parseBoolean(raw);
        } else if (current instanceof Integer) {
            value = parseInt(raw, Integer.MIN_VALUE);
            if ((int) value == Integer.MIN_VALUE) {
                Msg.error(sender, key + " is a whole number.");
                return;
            }
        } else if (current instanceof Number) {
            value = parseDouble(raw, Double.NaN);
            if (Double.isNaN((double) value)) {
                Msg.error(sender, key + " is a number.");
                return;
            }
        } else {
            Double number = parseDouble(raw, Double.NaN);
            value = number.isNaN() ? raw : (number == Math.rint(number) ? (Object) number.intValue() : number);
        }
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        if (key.startsWith("visuals.")) {
            plugin.visuals().removeAll();
        }
        if (key.equals("round-minutes")) {
            game().resetTimer();
        }
        Msg.ok(sender, key + " set to " + value + ".");
    }

    private List<String> configKeys() {
        List<String> keys = new ArrayList<>();
        for (String key : plugin.getConfig().getKeys(true)) {
            if (!plugin.getConfig().isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    // ---------------------------------------------------------------- helpers

    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Msg.error(sender, "Run this in-game; it uses your position.");
        return null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> colorIds() {
        return Arrays.stream(TeamColor.values()).map(TeamColor::id).toList();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (admin) {
                options.addAll(ADMIN_SUBS);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "join" -> options.addAll(colorIds());
                case "point" -> options.addAll(admin ? List.of("add", "find", "remove", "list", "clear", "tp", "auto") : List.of());
                case "spawn" -> options.addAll(admin ? List.of("set", "clear", "list", "auto") : List.of());
                case "win" -> options.addAll(List.of("points", "time", "off"));
                case "quicklaunch", "ql" -> options.add("new");
                case "reset" -> options.add("all");
                case "resistance" -> options.addAll(colorIds());
                case "timer" -> options.addAll(List.of("reset", "10", "5"));
                case "scores" -> options.add("reset");
                case "team" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case "config" -> options.addAll(admin ? configKeys() : List.of());
                default -> {
                }
            }
        } else if (args.length >= 3 && args[0].equalsIgnoreCase("point") && args[1].equalsIgnoreCase("find") && admin) {
            options.addAll(plugin.pointFinder().suggestions());
        } else if (args.length >= 3 && (args[0].equalsIgnoreCase("quicklaunch") || args[0].equalsIgnoreCase("ql"))
                && args[1].equalsIgnoreCase("new") && admin) {
            if (args.length == 3) {
                options.addAll(List.of("1000", "2000", "3000"));
            }
            options.addAll(plugin.pointFinder().suggestions());
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("resistance")) {
                options.addAll(List.of("0", "1", "2", "3", "4"));
            } else if (sub.equals("team")) {
                options.addAll(colorIds());
                options.add("none");
            } else if (sub.equals("spawn") && (action.equals("set") || action.equals("clear"))) {
                options.addAll(colorIds());
            } else if (sub.equals("point") && (action.equals("remove") || action.equals("tp"))) {
                for (int i = 1; i <= game().points().size(); i++) {
                    options.add(String.valueOf(i));
                }
            } else if (sub.equals("config")) {
                Object current = plugin.getConfig().get(args[1]);
                if (current instanceof Boolean) {
                    options.addAll(List.of("true", "false"));
                } else if (args[1].equals("nether-gear")) {
                    options.addAll(List.of("auto", "on", "off"));
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
