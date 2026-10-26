# 117 HD developer tools

Tools for tuning 117 HD's lights and textures:

- **Spawner**: search, preview and spawn objects, spotanims and projectiles, with their textures and lights
- **Spotanim keys**: pause and step a playing spotanim to time its lights
- **Scene captures**: save the objects in a scene, then recreate them somewhere else

## Spawner sidebar

Open it from the orange cube in RuneLite's sidebar, below the configuration spanner. The object list loads once you've
logged in.

- **Objects / Spotanims / Projectiles** switch lists, which are in ID order. **Scenes** opens scene captures.
- **Cube & lightbulb buttons** filter to entries with a model override or lights. Only one can be on at a time.
- A green cube on a row means it's in `model_overrides.json`, a yellow lightbulb that it's in `lights.json`. Both update
  when you save either file.
- **Hover** to preview on your tile, **click** to spawn, play or fire, and **right-click** to copy the gameval.
- **Search** matches gameval, in-game name or ID, with words in any order: `xarth fire` finds
  `TFA_XARTH01_CORPSE_PILE_FIRE01`. Quotes, commas and spaces are ignored, so `"DEATH_GRASS_2",` pasted from a config
  works.
- **Start from** jumps through the list. Gamevals work anywhere IDs do, and a partial gameval uses the lowest match:

  | Input | Shows |
  |---|---|
  | `1000` or `TFA_XARTH` | up to 500 entries from that ID |
  | `1000+100` or `TFA_XARTH+100` | 100 entries from that ID |
  | `500-679` or `NAME_A-NAME_B` | everything between the two IDs |

### Objects

- **Spawned objects** lists what you've spawned. The arrows rotate an object 90 degrees, lights included, and the cross
  removes it. **Clear spawned objects** removes them all.
- Spawned objects are removed when a new scene loads.

### Spotanims & projectiles

- **Looping** makes clicked spotanims and projectiles repeat. Previews always loop.
- **Frame timings** posts a spotanim's frame timings in the chat the first time it plays. Off by default.
- **Tiles ahead** sets how far projectiles fly in the direction you face, and **Start height** how high they start.
- The projectile list is a best guess: projectiles 117 HD already configures, plus spotanims whose names look like
  projectiles.

### Scenes

**Capturing**

- **Capture scene** saves objects, walls, wall decorations and ground objects to `.runelite/117hd/scene_captures`,
  positioned relative to the corner of the 64x64 tile region you're in, along with NPCs. Players and pets aren't
  included.
- With no name, a capture is named after your 117 HD environment and the time, like `TFA_LOBBY_2026-10-25_22-13-05`.
- The area is the whole scene by default, your region, or 16 tiles around you. In instances, the region is a 64x64
  square centred on you, since instance coordinates don't line up with rooms.
- Only what's being drawn is captured: the extended scene and the ground under bridges are included, while hidden planes
  (which instances often fill with other states of the area), area hiding and hidden model overrides are left out.
- Captures also save the terrain, so objects that bend to the ground (like cave walls) bend the same way when recreated,
  each tile's shape and colours for the floor, and the `environments.json` environment in use.

**Recreating**

- **Recreate in my region**, or double-clicking a capture, recreates it in your region, lined up with the region grid
  as it was captured, with its textures and lights. Objects keep their height relative to you.
- The capture's environment is used while it's recreated. Edits to it in `environments.json` show up live.
- Recreated objects are uploaded like real scenery, so whole scenes run at normal frame rates. They appear once the
  capture has finished spawning. Animated objects are redrawn every frame, like spawned objects.
- NPCs are recreated as frozen copies of their models, facing the way they faced, with their model overrides and lights.
  They're never animated and do nothing. Without an animation, some show their models' base pose rather than how they
  stand in game.
- The ground is recreated as a floor in place of the real tiles, with the tiles' shapes and colours. Its
  `tile_overrides.json` overrides are picked from where the tiles really are, and update live when you save the file.
  Ground blending, water and override replacements aren't applied. Tiles only get a floor where there's a real tile to
  replace.
- Anything outside the loaded scene is skipped, and the chat says how many objects were skipped and why.
- **Hide everything else** hides the ground, objects, NPCs and other players, leaving test objects and your character,
  like the Blindfold plugin. Lights of hidden objects go out too. Recreating turns it on and removing turns it off, but
  it can be toggled any time.
- **Remove recreated scene** removes it, as does recreating another capture or loading a new scene.
- To rename or delete captures, use the folder directly.

## Spotanim keys

| Keys | Does |
|---|---|
| Ctrl+Space | pause or resume |
| Ctrl+Left / Ctrl+Right | step one client tick (20 ms) |
| Ctrl+Shift+Left / Ctrl+Shift+Right | step 10 client ticks |

On by default with `./gradlew run`, otherwise turn them on with `::117hd keybinds`. Lights stay in sync while paused, so
you can line them up frame by frame.

## Tips

- `lights.json` and `model_overrides.json` reload on save, so you can tweak while an object or spotanim is on screen.
- Light offsets face the same way on spawned and real objects, so lights tuned on one match the other.
- If an object can't be spawned after a game update, its definition may use something the spawner doesn't know yet.
