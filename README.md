# ScpMobs — moving SCPs for facility RP servers

[![Reviewed by PatchPilots](https://img.shields.io/badge/Reviewed%20by-PatchPilots-8A2BE2)](https://github.com/alavesa/patchpilots)

SCPs that *move*: **SCP-173** and **SCP-106** as living entities with custom 3D models
and walk animations, plus the **blink meter** — because on a facility server, the scary
part is the half second when your eyes are closed. Companion to
[lab-datapack](https://github.com/alavesa/lab-datapack) and
[labra-plugin](https://github.com/alavesa/labra-plugin); SCP-096 is next on the list.

## Install

1. `ScpMobs-x.y.z.jar` → server `plugins/`, restart. Paper 1.21.4+, Java 21.
2. Resource pack: use the combined **scp_and_chemistry.zip** as the server pack (it
   includes these models and the blink glyph). Without it, mobs show as floating base
   blocks and the blink is invisible — the meter still runs.

## The blink

Every survival/adventure player blinks on a 20-second cycle:

- the **XP bar is the blink meter** — it drains from full to empty over 20 s
  (the plugin owns the XP display; don't use XP for anything else)
- at zero the screen goes **completely black for half a second** (a full-screen font
  glyph in a title — no mods needed), then the meter refills
- `/scpmob blink on|off` toggles it server-wide

SCP-173 knows when you blink.

## SCP-173

`/scpmob spawn 173` — the concrete statue. An invisible pathfinding mob carries the
model, so it navigates corridors and stairs like anything alive:

- **frozen while anyone looks at it** (line of sight + facing, spectators don't count)
- the instant every watcher blinks or turns away, it **moves — fast**, stone scraping
  on concrete
- reach a player and their neck snaps. Death message says who did it.
- unkillable, doesn't burn in daylight, persists across restarts

## SCP-106

`/scpmob spawn 106` — the Old Man. No pathfinding because he doesn't need any:

- **walks slowly through walls, floors and ceilings**, straight toward the nearest
  player, trailing black corrosion
- two-frame shuffle animation; a low groan every few seconds
- touching him: wither + blindness, and if staff has set the **pocket dimension**
  (`/scpmob pd set` — stand where victims should wake up, e.g. your PD build), the
  victim is teleported there. `/scpmob pd clear` reverts to pure corrosion damage.
- unkillable. Attacks pass through him.

## The 173 Cage

`/scpmob give cage` — a hand-held containment cage with its own item texture. To
contain the statue: **hold right-click on SCP-173 with the cage for 15 seconds**.
Let go and the attempt resets. Succeed and a **3D cage model locks around the
statue** — a caged 173 is docile and **follows its captor anywhere** like on a lead,
rattling softly. Sneak + empty hand takes the cage off (and then it is very much not
docile).

Fine print your D-class will discover: the blink meter keeps running while you hold
the cage. Fifteen seconds is always long enough to blink at least once. Bring a
friend who watches the statue while you work.

## Containment status & breaches

Every SCP has a **contained / breached** status (`/scpmob status`):

- `/scpmob breach 106` — facility-wide CONTAINMENT BREACH announcement, and SCP-106
  starts **emerging near random players on his own** (every ~30 s there is a small
  chance, capped at `scp106.breach-max` instances). While breached he also gains his
  signature move: **rarely, he teleports out of the floor directly beneath a random
  unsuspecting player** (`scp106.ambush-chance`, default 8% per half-minute — rare,
  as it should be).
- `/scpmob contain 106` — recontainment: announcement, and every 106 corrodes away.
- `/scpmob breach|contain 173|all` — same statuses for the statue (no auto-spawns;
  it breaches the old-fashioned way: someone stops looking).

## Custom models & animations

The models are item_displays with `custom_model_data` strings, same override pattern
as the lab pack — replace one file to reskin:

| Hook | Model file |
|---|---|
| `scp173` | `assets/scp/models/entity/scp173.json` |
| `scp106_walk1` / `scp106_walk2` | `assets/scp/models/entity/scp106_walk1.json` / `walk2.json` |

Animations are **frame swaps**: the plugin alternates the 106 model between `walk1`
and `walk2` every half second. Add more dramatic Blockbench models with as many frames
as you like — keep the hook names, or extend the frame list in `ScpTask`. The shipped
models are original pixel-art interpretations built from generated textures.

## Commands (`scpmobs.admin`, default op)

```
/scpmob spawn <173|106>      /scpmob give cage [player]
/scpmob breach <173|106|all> /scpmob contain <173|106|all>   /scpmob status
/scpmob remove (16 blocks)   /scpmob pd set|clear            /scpmob blink on|off
```

## Notes

- 173's "being watched" check is generous (wide FOV + line of sight) — corners and
  blinks are the intended counterplay, exactly like the game that started it all.
- Mobs tick statelessly by scoreboard tag, so they survive restarts and chunk
  unloads without any bookkeeping.
- SCP-096 needs a face-detection rage state — the watching machinery is already here,
  so it's the natural next addition.
