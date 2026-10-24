package com.woolwars.game;

import java.util.ArrayList;
import java.util.List;

import com.woolwars.model.TeamColor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Team membership lives in the vanilla main scoreboard (teams "ww_red", "ww_blue", ...), same as the old datapack.
 * That means it survives restarts and disconnects for free, and "/team join ww_red" still works.
 */
public class Teams {

    public void ensureTeams() {
        Scoreboard board = board();
        for (TeamColor color : TeamColor.values()) {
            Team team = board.getTeam(color.teamName());
            if (team == null) {
                team = board.registerNewTeam(color.teamName());
            }
            team.displayName(Component.text(color.displayName() + " Team"));
            team.color(color.textColor());
        }
    }

    public TeamColor teamOf(OfflinePlayer player) {
        if (player.getName() == null) {
            return null;
        }
        Team team = board().getEntryTeam(player.getName());
        return team == null ? null : TeamColor.fromTeamName(team.getName());
    }

    public void join(OfflinePlayer player, TeamColor color) {
        Team team = board().getTeam(color.teamName());
        if (team != null && player.getName() != null) {
            team.addEntry(player.getName());
        }
    }

    public void leave(OfflinePlayer player) {
        if (player.getName() == null) {
            return;
        }
        Team team = board().getEntryTeam(player.getName());
        if (team != null && TeamColor.fromTeamName(team.getName()) != null) {
            team.removeEntry(player.getName());
        }
    }

    /** Takes everyone off every Wool Wars team. */
    public void clearAll() {
        for (TeamColor color : TeamColor.values()) {
            Team team = board().getTeam(color.teamName());
            if (team != null) {
                team.getEntries().forEach(team::removeEntry);
            }
        }
    }

    /** Teams with at least one member (online or not). */
    public List<TeamColor> activeTeams() {
        List<TeamColor> active = new ArrayList<>();
        for (TeamColor color : TeamColor.values()) {
            Team team = board().getTeam(color.teamName());
            if (team != null && !team.getEntries().isEmpty()) {
                active.add(color);
            }
        }
        return active;
    }

    public List<String> members(TeamColor color) {
        Team team = board().getTeam(color.teamName());
        return team == null ? List.of() : new ArrayList<>(team.getEntries());
    }

    public List<Player> onlineMembers(TeamColor color) {
        List<Player> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (teamOf(player) == color) {
                online.add(player);
            }
        }
        return online;
    }

    private Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }
}
