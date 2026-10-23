package com.woolwars.game;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.woolwars.WoolWarsPlugin;
import com.woolwars.model.ControlPoint;
import com.woolwars.model.TeamColor;
import com.woolwars.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Boss bar (timer + who holds the next point), action bar (where the next point is), and the score sidebar.
 */
public class Hud {
    private static final String OBJECTIVE = "ww_scores";

    private final WoolWarsPlugin plugin;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    private final Set<String> sidebarEntries = new HashSet<>();

    public Hud(WoolWarsPlugin plugin) {
        this.plugin = plugin;
    }

    public void update() {
        updateBossBar();
        updateActionBars();
        updateSidebar();
    }

    private void updateBossBar() {
        Game game = plugin.game();
        ControlPoint active = game.activePoint();
        if (active == null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
            return;
        }

        TeamColor holder = active.owner();
        Component holderText = holder == null ? Component.text("empty", NamedTextColor.GRAY)
                : Component.text("held by ", NamedTextColor.WHITE).append(holder.label());
        String what = capitalize(game.pointName(game.activeIndex()));
        Component name;
        float progress = 1f;
        switch (game.state()) {
            case IDLE -> name = Component.text("Wool Wars · not running · ", NamedTextColor.GRAY)
                    .append(Component.text(what + " ", NamedTextColor.WHITE)).append(holderText);
            case COUNTDOWN -> name = Component.text("Wool Wars starting...", NamedTextColor.GOLD);
            default -> {
                progress = Math.clamp((float) game.ticksLeft() / game.intervalTicks(), 0f, 1f);
                Component prefix = game.state() == Game.State.PAUSED
                        ? Component.text("PAUSED · ", NamedTextColor.RED, TextDecoration.BOLD) : Component.empty();
                name = prefix.append(Component.text(what + " scores in ", NamedTextColor.WHITE))
                        .append(Component.text(Msg.clock(game.ticksLeft() / 20), NamedTextColor.YELLOW, TextDecoration.BOLD))
                        .append(Component.text(" · ", NamedTextColor.DARK_GRAY)).append(holderText);
            }
        }
        bar.name(name);
        bar.progress(progress);
        bar.color(holder == null ? BossBar.Color.WHITE : holder.barColor());
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(bar));
    }

    private void updateActionBars() {
        ControlPoint active = plugin.game().activePoint();
        if (active == null || active.world() == null || !plugin.getConfig().getBoolean("hud.action-bar", true)) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.teams().teamOf(player) == null) {
                continue;
            }
            Component text = Component.text("➤ ", NamedTextColor.AQUA)
                    .append(Component.text(capitalize(plugin.game().pointName(plugin.game().activeIndex())) + " ",
                            NamedTextColor.WHITE))
                    .append(Component.text("(" + active.coords() + ")", NamedTextColor.GRAY));
            if (active.world().equals(player.getWorld())) {
                int distance = (int) player.getLocation().distance(active.slotCenter());
                text = text.append(Component.text(" " + distance + "m", NamedTextColor.YELLOW));
            } else {
                text = text.append(Component.text(" in the " + Msg.dimension(active.world()), NamedTextColor.LIGHT_PURPLE));
            }
            player.sendActionBar(text);
        }
    }

    private void updateSidebar() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective objective = board.getObjective(OBJECTIVE);
        if (!plugin.getConfig().getBoolean("hud.sidebar", true)) {
            if (objective != null) {
                objective.unregister();
                sidebarEntries.clear();
            }
            return;
        }
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
            sidebarEntries.clear();
        }
        int target = plugin.getConfig().getInt("win.points", 0);
        Component title = Component.text("Wool Wars", NamedTextColor.GOLD, TextDecoration.BOLD);
        if (target > 0) {
            title = title.append(Component.text(" (to " + target + ")", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));
        }
        objective.displayName(title);
        if (objective.getDisplaySlot() != DisplaySlot.SIDEBAR) {
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        Set<String> current = new HashSet<>();
        for (Map.Entry<TeamColor, Integer> entry : plugin.game().standings()) {
            String key = entry.getKey().teamName();
            current.add(key);
            var score = objective.getScore(key);
            score.setScore(entry.getValue());
            score.customName(entry.getKey().label());
        }
        for (String stale : sidebarEntries) {
            if (!current.contains(stale)) {
                board.resetScores(stale);
            }
        }
        sidebarEntries.clear();
        sidebarEntries.addAll(current);
    }

    public void hideAll() {
        Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
