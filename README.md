# Wool Wars

A Paper plugin for the Minecraft minigame Wool Wars: teams fight to keep their wool on a control point, and whoever holds it when the timer hits zero scores. It replaces the old [wool_wars datapack](https://github.com/Eccentr1city/wool_wars) and borrows the good parts of [Beacon Wars](https://github.com/VH-abc/beacon-war-plugin).

Built for **Paper 26.3** (Java 25).

## Rules

- A control point is a bedrock base. The block on top of it is the wool slot.
- Put your team's wool in the slot to hold the point. Break theirs, place yours.
- When the timer hits zero, the team holding the point scores 1 point. If the slot is empty, no one scores.
- With several points, they take turns being scored, in order. A full rotation takes `round-minutes` (default 10), so with 3 points one is scored every 3:20.
- Any number of teams, one per wool color (up to 16).
- No win condition by default; optionally first to N points or most points after M minutes (`/ww win`).

## What players get

- Their team's wool, torches, a Fortune II iron pickaxe, an Infinity bow (+1 arrow), and a tracker compass. Each is refilled the tick it runs out and kept to one inventory slot.
- The compass and action bar point at the next control point to be scored (with distance, or which dimension it's in).
- Boss bar with the countdown and who's holding the point, sidebar with scores, titles and sounds for captures and scoring.
- Each control point has a glowing outline (visible through walls) and a colored beam in the holder's color. The beam of the next point to be scored glows too. No real beacon involved.
- Full keep-inventory on death; team spawns override beds.
- Nether gear when a point is in the Nether: Efficiency IV on the pickaxe, obsidian + flint and steel at the start, fire resistance on respawn.

## Quick start

You need a **Paper 26.3** server (from [papermc.io](https://papermc.io/downloads/paper)). Realms and vanilla servers can't run plugins.

### 1. Get the plugin jar

Build it from this repo (needs JDK 25 and Maven):

```bash
git clone https://github.com/Eccentr1city/wool-wars-plugin.git
cd wool-wars-plugin
mvn package
```

The jar is now at **`wool-wars-plugin/target/WoolWars-2.0.0.jar`**.

### 2. Install it

Copy that jar into the server's `plugins/` folder (next to `paper.jar`), then restart the server:

```
your-server/
├── paper.jar
└── plugins/
    └── WoolWars-2.0.0.jar   <- here
```

The server log should say `Wool Wars ready`. Settings are created at `plugins/WoolWars/config.yml`.

### 3. Play

1. Make yourself an operator: type `op <your name>` in the server console.
2. Everyone picks a team in chat: `/ww join red`, `/ww join blue`, and so on.
3. The op stands where the fight should be and runs **`/ww quicklaunch`**. It places a control point there, puts team spawns around it, counts down from 10, teleports everyone to their spawn and starts the timer.

That's it. `/ww help` lists everything else.

### Setting things up by hand

For more control over where things go:

```
/ww point add          # turn the block you're looking at (or standing on) into a control point
/ww point auto 3 50    # or: replace all points with 3 placed 50 blocks around you
/ww spawn set red      # red's spawn = where you're standing
/ww spawn auto         # or: spawns for every team with players, spread around the points
/ww start
```

### Picking interesting spots

`/ww point find` adds a point that matches some filters, searching from where you stand:

```
/ww point find high                          # highest ground nearby
/ww point find low                           # deepest cave floor nearby
/ww point find structure:village             # inside the nearest village (any variant)
/ww point find structure:ancient_city
/ww point find biome:cherry_grove
/ww point find biome:jagged_peaks high       # top of the nearest jagged peaks
/ww point find biome:deep_dark               # cave biomes work too
/ww point find low range:200                 # search a wider area
```

Tab completion lists every biome and structure. Repeated searches skip spots near existing points. Use `/ww point tp <n>` to go look, and `/ww point remove <n>` if you don't like it. Big searches can freeze the server for a few seconds.

### Spawn distance

By default `/ww spawn auto` (and quicklaunch) puts spawns far enough out that running to the point takes about a quarter of the timer, assuming ~4 blocks/second over real terrain. That's 600 blocks with a 10 minute timer, 60 with a 1 minute timer. Override it with `/ww spawn auto <blocks>`, or `/ww config auto.spawn-radius <blocks>` (set it back to `auto` to restore), and tune `auto.spawn-travel-fraction` / `auto.run-speed`.

## Commands

Players:

| Command | |
|---|---|
| `/ww join <color>` · `/ww leave` | Pick or leave a team |
| `/ww teams` | Who's on which team |
| `/ww status` | State, timer, points, scores |

Admins (op):

| Command | |
|---|---|
| `/ww quicklaunch` | Auto-place anything missing, count down, teleport, start |
| `/ww point add \| remove <n> \| list \| clear \| tp <n> \| auto [count] [spread]` | Control points (any dimension) |
| `/ww point find [high\|low] [biome:<id>] [structure:<id>] [range:<blocks>]` | Add a point somewhere specific |
| `/ww spawn set [color] \| clear [color] \| list \| auto [radius]` | Team spawns |
| `/ww start` · `stop` · `end` · `pause` · `resume` | `start` resets scores; `end` declares the leader the winner |
| `/ww timer <minutes> \| reset` | Length of a full rotation |
| `/ww win points <n> \| time <minutes> \| off` | Optional win condition |
| `/ww team <player> <color\|none>` | Put someone on a team |
| `/ww scores reset` | Zero the scores |
| `/ww config [key] [value]` · `/ww reload` | View or change any setting in `config.yml` |

Toggles you might want, e.g. `/ww config mining-fatigue.enabled true`, `/ww config death-drop-fraction 0.25`, `/ww config nether-gear on`. See [config.yml](src/main/resources/config.yml) for all of them.

Points, spawns and scores are saved to `plugins/WoolWars/data.yml`, so a restart doesn't lose the game (a running game comes back paused). Teams live in the vanilla scoreboard.
