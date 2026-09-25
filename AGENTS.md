# AGENTS.md — talking-colonists-addons

The official addons for Talking Colonists (Gazette, Campfire, Postal, Tavern, Notice Board, Town
Hall), one Gradle project each, built with Stonecutter for `1.21.1-neoforge` and `1.20.1-forge`.
The README covers layout, commands, playtests and self-tests; this file lists the rules and traps.

## Rules

- **License: all rights reserved.** Never add an open-source license or header to the addons.
- **Immersive only.** Players get everything in the world, from citizens, blocks and items, never
  from a command. Commands are for operators and tests (`/<addon> rush`, `/playtest …`).
- **Build only on the public API** of Talking Colonists (`me.sshcrack:mc_talking-api`). Check
  optional features with `TalkingColonistsApi.supports(ApiFeature.…)` so older 2.x runtimes degrade
  gracefully. When something is missing, it belongs in the main repo's API (additive only).
- **Shared code:** edit `shared/` only, then `./gradlew syncShared`. CI's `verifyShared` fails if
  an addon's synced copy (`<addon>/src/main/java/.../shared/`) differs.
- **Java:** import classes; never write fully qualified names inline.
- Never commit `.sc_active_version` (run `./gradlew "Reset active project"` first).
- Never push tags or publish: releases happen only on the maintainer's word.

## Design against the game's real rules

Before designing gameplay that depends on MineColonies (buildings, upgrades, builders, happiness,
guards, raids, growth, day and night), read `docs/minecolonies-mechanics.md` in the main repo
(talking-colonists/talking-colonists). For example:
- A placed hut is level 0 until a builder builds it.
- Building takes days, so ask for work to start, not to finish.
- A guard tower holds one guard at any level.
- MineColonies never raids a young colony.

Playtests have caught every such mismatch so far; the doc exists so the design catches them first.

## Verify before asking for a playtest

- Unit tests: `./gradlew :<addon>:1.21.1-neoforge:test :<addon>:1.20.1-forge:test`. Tests must not
  load Minecraft or MineColonies classes, not even through a lambda or a static initializer: CI's
  Forge run on JDK 25 fails on signed classes, which does not reproduce locally.
- In-game: `TC_ADDONS_KEY_FILE=… bash scripts/selftest.sh <addon> 1.21.1-neoforge 1.20.1-forge`
  runs the addon's `dev/DevSelfTest` on a headless server.
- Scripted client: `bash scripts/scenario.sh <scenario> neoforge|forge [--fresh]` runs the timed
  steps in `playtest/.../PlaytestScenario.java` as a real player, then prints the speech report.
  Steps can be commands, `chat:<text>` (the player chats) or `client:…`: `camera`, `hud`,
  `use`, `close`, and `screenshot <name>`, which saves to
  `playtest/versions/<ver>/run/scenario/screenshots/`.
- Anything visual (models, textures, windows) gets an in-game screenshot on its PR. Push the image
  to the `pr-images` branch and link it with `?raw=true`.
- Keep the in-game `/playtest` checklist (`playtest/.../Checklist.java`) and the addon's
  handbook guide in step with what the player can do.

## Local Talking Colonists

Addons build against Talking Colonists from Maven Local (`./gradlew publishToMavenLocal` in the
main repo). Don't republish while a playtest client runs (check `pgrep -af runClient`): the running
game then fails with `NoClassDefFoundError`.
