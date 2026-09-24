# Talking Colonists Addons

Addons for [Talking Colonists](https://github.com/talking-colonists/talking-colonists), the
MineColonies mod that lets you talk to citizens. Each addon is its own mod with its own download;
they only share this repository and its build.

| Addon | Directory | What it does |
|---|---|---|
| Colony Gazette | [`gazette/`](gazette) | The colony writes a daily newspaper about yesterday's events, in a citizen's own words. |

Every addon builds for **1.21.1 NeoForge** and **1.20.1 Forge** from one source tree, using
[Stonecutter](https://stonecutter.kikugie.dev/), and needs Talking Colonists 2.1 or newer.

## Colony Gazette

Every morning (in-game time 1000) the colony's teacher writes a newspaper about the previous day:
raids, births and deaths, new hires and job changes, buildings built or upgraded, and anything other
addons record as colony events. Without a teacher, a student in the library writes it, and without
one of those, "the colony's notes" do.
Colony members get a chat message when a new issue is out.

- `/gazette` gives you a copy of the latest issue of the colony you stand in (or own). One copy per
  player and issue.
- `/gazette publish` (operators) writes an issue right away.

Quiet days with no events get no issue. Issues use a background Gemini request and are skipped while
the key's quota is used up or all background slots are busy; the gazette retries a few times per day.

## Layout

```
build.neoforge.gradle.kts, build.forge.gradle.kts   shared loader build scripts
build-logic/                                         shared Gradle plugin (mods.toml, publishing, ...)
stonecutter.properties.toml                          shared properties, plus one table per addon
<addon>/src/                                         the addon's code and resources
<addon>/CHANGELOG.md                                 the addon's changelog
```

Each addon is a Stonecutter *branch*. Gradle projects are named `:<addon>:<version>`, e.g.
`:gazette:1.21.1-neoforge`.

### Adding an addon

1. Add the directory name to the `branch` list in `settings.gradle.kts`.
2. Add a `[<addon>]` table to `stonecutter.properties.toml` with its `mod.id`, `mod.name`,
   `mod.group`, `mod.version` and `mod.description`.
3. Create `<addon>/src/main/...` with a `<mod id>.mixins.json`, the icon, and the code, and a
   `<addon>/CHANGELOG.md`.

## Build and run

```sh
./gradlew buildAndCollect                        # jars in build/libs/<mod id>/<version>/
./gradlew :gazette:1.21.1-neoforge:runClient
./gradlew :gazette:1.20.1-forge:test
```

Run `./gradlew "Reset active project"` before committing, so the sources stay in their 1.21.1
NeoForge state. The pre-commit hook blocks commits that change `.sc_active_version`.

### End-to-end self-test

An addon can have a `dev.DevSelfTest` (left out of the release jar) that a headless dev server runs
and that logs `TC_<ADDON>_SELFTEST_SUCCESS` or `..._FAIL`. It needs a Gemini API key, which is copied
into a throwaway config and never printed:

```sh
TC_ADDONS_KEY_FILE=/path/to/key bash scripts/selftest.sh gazette [1.21.1-neoforge 1.20.1-forge]
```

## Depending on Talking Colonists

`gradle.properties` sets `deps.talking_colonists_version`, which is also the minimum Talking
Colonists version in the generated `mods.toml`. Addons compile against the API artifact
(`me.sshcrack:mc_talking-api`) and use the full mod at runtime, both from
`https://maven.sshcrack.me/releases`.

To build against an unreleased Talking Colonists, run `./gradlew publishToMavenLocal` in the Talking
Colonists repository; `mavenLocal()` is checked first. CI does the same automatically when the
version is not on the Maven repository yet.

## License

All rights reserved; see [LICENSE](LICENSE).
