# 117 HD developer tools

Developer tools for tuning 117 HD's lights and textures:

- **Spawner sidebar**: search objects, spotanims and projectiles, preview them by hovering, and spawn them next to you
- **`::117hd object`**: spawn an object, with its `model_overrides.json` textures and `lights.json` lights
- **`::117hd spotanim`**: play a spotanim on your player, then pause, step or slow it down to time its lights
- **Scene captures**: save every object in the loaded scene to a file, then recreate it around you somewhere else

Everything is purely visual and client-side. Spawned objects, spotanims and projectiles have no clickboxes or menu
entries, nothing is sent to the game server, and the tools only exist when RuneLite runs in developer mode.
They must never move behind a normal config option or command, since RuneLite's plugin rules reject user-entered IDs
and spawned content for normal users.

## Setup

1. **Install a Java 11 JDK**, such as [Amazon Corretto 11](https://docs.aws.amazon.com/corretto/latest/corretto-11-ug/downloads-list.html).

2. **Get the branch:**
   ```bash
   git clone https://github.com/Jin-Jiyunsun/RLHD-jin.git
   cd RLHD-jin
   git checkout jin-dev-tools
   ```

3. **Launch the development client**, which builds the plugin and starts RuneLite in developer mode:
   ```bash
   ./gradlew run
   ```
   In IntelliJ, you can instead run `rs117.hd.HdPluginTest` with the program arguments `--developer-mode --debug`.

4. **Log in.** Jagex accounts need some extra setup for development clients, described in RuneLite's
   [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide.

## Spawner sidebar

Open it from the orange cube icon in RuneLite's sidebar, just below the configuration spanner. The object list loads
once you've logged in.

- **Objects / Spotanims / Projectiles** switch between the three lists, which are always in ID order. **Scenes**
  opens the scene captures.
- **Cube & lightbulb buttons** only show entries with a model override or with lights. Only one can be on at a time.
- In each row, a green cube means the entry is in `model_overrides.json`, and a yellow lightbulb means it's in
  `lights.json`. These update whenever you save either file.
- **Hover** an entry to preview it on your tile. Long names scroll while hovered.
- **Click** to spawn the object, play the spotanim or fire the projectile. **Right-click** to copy its gameval.
- **Search** filters by gameval, in-game name or ID. Words can match in any order, so `xarth fire` finds
  `TFA_XARTH01_CORPSE_PILE_FIRE01`. Quotes, commas and spaces are ignored, so you can paste a gameval straight from
  a JSON config, like `"DEATH_GRASS_2",`.
- **Start from** jumps through the list. A gameval works anywhere an ID does, and a partial gameval uses the lowest
  ID starting with it:

  | Input | Shows |
  |---|---|
  | `1000` or `TFA_XARTH` | from that ID onwards, up to 500 entries |
  | `1000+100` or `TFA_XARTH+100` | 100 entries from that ID |
  | `500-679` or `NAME_A-NAME_B` | everything between the two IDs |

### Objects

- **Spawned objects** lists what you've spawned. Use the arrows to rotate an object in 90 degree steps, which its
  lights follow, or the cross to remove it. **Clear spawned objects** removes them all.
- Spawned objects are removed when a new scene loads, such as after teleporting.

### Spotanims & projectiles

- **Looping** decides whether clicked spotanims and projectiles repeat. Hover previews always loop.
- **Tiles ahead** sets how far projectiles fly in the direction you're facing, and **Start height** how high above
  the ground they start. They land on the ground of the target tile.
- The projectile list is an approximation, since the game doesn't mark which spotanims are projectiles. It contains
  the projectiles 117 HD already configures, plus spotanims whose names look like projectiles.

### Scenes

- **Capture scene** saves every object, wall, wall decoration and ground object in the chosen area to
  `.runelite/117hd/scene_captures`, positioned relative to where you stand. NPCs and players aren't included, and
  objects which change with varbits are saved as they currently look. Leave the name empty to name it by the date.
- Only your plane and the planes below it are captured. The game hides the planes above you like roofs, and instances
  often fill them with copies of the map, which would otherwise stack up when recreated.
- The area is the 64x64 tile region you're in, everything within 16 tiles of you in each direction, or the whole loaded
  scene. Unlike the real objects they stand in for, recreated objects are redrawn every frame, so a whole scene's worth
  can drop the frame rate a lot.
- **Saved captures** lists the files in that folder. **Recreate around me** spawns the selected capture's objects as
  test objects around you, keeping their height relative to you and their planes relative to yours, with their
  `model_overrides.json` textures and `lights.json` lights. Objects which land outside the loaded scene are skipped.
- Big captures spawn over a few seconds. Recreating another capture replaces the previous one, and **Remove recreated
  objects** removes it. Recreated objects aren't listed under spawned objects, but are also removed when a new scene
  loads.
- To rename or delete captures, open `.runelite/117hd/scene_captures` yourself.

## Chat commands

| Command | Does |
|---|---|
| `::117hd object <name or ID>` | spawn an object on your tile |
| `::117hd object clear` | remove all spawned objects |
| `::117hd spotanim <name or ID> [height] [loop]` | play a spotanim on your player |
| `::117hd spotanim pause` | pause or resume it |
| `::117hd spotanim speed <multiplier>` | play it slower or faster, e.g. `0.25` |
| `::117hd spotanim time <ms>` | jump to a point in it, paused |
| `::117hd spotanim stop` | stop it |

When a spotanim first finishes playing, a summary of its frame timings is posted in the chat.

### Spotanim keys

| Keys | Does |
|---|---|
| Ctrl+Space | pause or resume |
| Ctrl+Left / Ctrl+Right | step back or forward one client tick (20 ms) |
| Ctrl+Shift+Left / Ctrl+Shift+Right | step back or forward 10 client ticks |

These are on by default when launched with `./gradlew run`, or can be turned on with `::117hd keybinds`. While a
spotanim is paused, its lights are held at the same moment, so you can line them up frame by frame.

## Tips

- `lights.json` and `model_overrides.json` reload as soon as you save them, so you can keep tweaking while a spawned
  object or looping spotanim is on screen.
- Light offsets on a spawned object face the same way as on the real object, so lights tuned on one match the other.
- If an object can't be spawned after a game update, its definition may use something the spawner doesn't
  understand yet.
