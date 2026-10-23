package com.woolwars;

import com.woolwars.commands.WoolWarsCommand;
import com.woolwars.game.Game;
import com.woolwars.game.Hud;
import com.woolwars.game.Placement;
import com.woolwars.game.Supplies;
import com.woolwars.game.Teams;
import com.woolwars.game.Visuals;
import com.woolwars.listeners.GameListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class WoolWarsPlugin extends JavaPlugin {
    private Teams teams;
    private Game game;
    private Visuals visuals;
    private Supplies supplies;
    private Hud hud;
    private Placement placement;
    private long tickCount;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        teams = new Teams();
        teams.ensureTeams();
        visuals = new Visuals(this);
        supplies = new Supplies(this);
        hud = new Hud(this);
        placement = new Placement(this);
        game = new Game(this);
        game.load();
        visuals.sweepStrays(getServer().getWorlds());

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        WoolWarsCommand command = new WoolWarsCommand(this);
        PluginCommand ww = getCommand("woolwars");
        ww.setExecutor(command);
        ww.setTabCompleter(command);

        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("Wool Wars ready: " + game.points().size() + " control point(s), state " + game.state());
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.save();
        }
        if (visuals != null) {
            visuals.removeAll();
        }
        if (hud != null) {
            hud.hideAll();
        }
    }

    private void tick() {
        tickCount++;
        game.tick();
        supplies.supplyAll();
        if (tickCount % 10 == 0) {
            supplies.dedupeAll();
            supplies.applyMiningFatigue();
            hud.update();
        }
        if (tickCount % 20 == 0) {
            visuals.maintain();
        }
        if (tickCount % 600 == 0) {
            game.saveIfDirty();
        }
    }

    public Teams teams() {
        return teams;
    }

    public Game game() {
        return game;
    }

    public Visuals visuals() {
        return visuals;
    }

    public Supplies supplies() {
        return supplies;
    }

    public Hud hud() {
        return hud;
    }

    public Placement placement() {
        return placement;
    }
}
