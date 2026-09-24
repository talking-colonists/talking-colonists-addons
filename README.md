# Talking Colonists Addons

Addons for [Talking Colonists](https://github.com/talking-colonists/talking-colonists), the
MineColonies mod that lets you talk to citizens. Each addon is its own mod with its own download;
they only share this repository and its build.

| Addon | Directory | What it does |
|---|---|---|
| Colony Gazette | [`gazette/`](gazette) | The colony writes a daily newspaper about yesterday's events, in a citizen's own words. |
| Campfire Nights | [`campfire/`](campfire) | At dusk, citizens gather around a campfire and tell each other stories. |
| Postal Service | [`postal/`](postal) | Write letters to your citizens and get in-character replies in your mailbox. |
| Tavern Recruiter | [`tavern/`](tavern) | Talk to tavern visitors and talk them into joining for less. |
| Notice Board | [`noticeboard/`](noticeboard) | Post notices on a lectern for citizens to spread and answer; ring the town bell to tell everyone. |
| Town Hall | [`townhall/`](townhall) | Stand for mayor with a speech; citizens vote on what they heard. A suggestion box for their notes. |

Every addon builds for **1.21.1 NeoForge** and **1.20.1 Forge** from one source tree, using
[Stonecutter](https://stonecutter.kikugie.dev/), and needs Talking Colonists 2.1 or newer.

## Colony Gazette

Every morning (in-game time 1000) the colony's teacher writes a newspaper about the previous day:
raids, births and deaths, new hires and job changes, buildings built or upgraded, and anything other
addons record as colony events. Without a teacher, a student in the library writes it, and without
one of those, "the colony's notes" do.
Colony members get a chat message when a new issue is out, and while you are in the colony a citizen
(the courier if there is one) walks up to you and hands you a copy, saying a line about it. One copy
per player and issue; if you are away, it is brought when you come back.

- Operators: `/gazette publish` writes an issue right away, `/gazette` hands you a copy.

Quiet days with no events get no issue. Issues use a background Gemini request and are skipped while
the key's quota is used up or all background slots are busy; the gazette retries a few times per day.

## Campfire Nights

At dusk (in-game time 12000 to 13500), if a player is near a lit campfire inside a colony, three to
five idle citizens walk over, sit around it and take turns telling stories: memories, colony news or
tales from before they came. It is a real Gemini Live group conversation, so you hear them talk. Chat
while you stand near the fire and they hear you and may answer. Each colony has at most one campfire
night per day, and only when Talking Colonists has spare speech capacity. The night becomes colony
news, so the Gazette may report it.

- `/campfire start` (operators) starts one at the nearest campfire right away; `/campfire stop` ends it.

## Postal Service

Write a letter in a book and quill and sign it with the recipient's name as the title ("Anna",
"Dear Anna Smith" and "To: Anna" all work). Right-click the colony's courier with it, or the
recipient in person; without a courier any citizen passes it on. A few minutes later the recipient
writes back in character, and the courier (or the writer) walks up to you and hands you the reply the
next time you are in the colony.

- Operators: `/mail rush` makes your letters reach their recipients now, `/mail` hands you all waiting replies.

The citizen remembers the exchange (a confirmed Talking Colonists memory), so they can bring it up
when you talk. The recipient has to be loaded to write back; letters wait for them, and come back
undelivered after three in-game days or if the citizen left the colony.

## Tavern Recruiter

MineColonies visitors at the tavern can now be talked to like citizens. Each one knows what joining
costs and may haggle: make a good case (a job that suits them, kindness, a fair offer) and they lower
their recruit cost through the `negotiate_recruit_cost` tool, which the server limits to small steps,
never below half the original price and at most three changes a day. The new price shows up in
MineColonies' recruit window, and the visitor remembers the deal after they join.

## Notice Board

Put a signed book on a lectern inside your colony and it becomes a notice: the citizens near the board
read it first (a Talking Colonists broadcast from "the notice board") and spread it to the others, so
they bring it up when you talk to them. A few minutes later up to three citizens near the board write
short replies, worries or petitions, which are pinned into the book as extra pages; read them on the
lectern. Taking the book down cancels the replies. As word spreads you hear how far it got: once half
the colony knows, and once everyone does (with Talking Colonists versions that report it). Watch
closely and you'll see citizens stop and pass the news on to each other.

Ring a bell inside your colony while holding a signed book and every citizen hears it at once, like
a town crier (the bell rings as usual).

- Operators: `/noticeboard rush` makes waiting notices collect their replies now.

## Town Hall

Right-click your colony's Town Hall block while holding a signed book: you stand for mayor, with the
book's title as your slogan and its text as your platform. With Simple Voice Chat you then have 30
seconds to give a speech. Citizens at the town hall hear it and pass it on, so every citizen votes on
what actually reached them, and on how they feel about you after your conversations.

The campaign lasts one Minecraft day. If you're the only candidate, the unhappiest citizen stands
against you, with a platform built from the colony's real problems, so you can lose. Then every grown
citizen votes in character. The whole colony hears the result, a citizen brings you the results book
with a few voters' reasons, and the mayor is part of what citizens know from then on. A new election
can be called three days later.

Place a barrel within 4 blocks of the Town Hall block and it becomes the suggestion box: every morning
up to three unhappy citizens drop a one-page note with a real concern or wish (at most 6 wait in it).

- Operators: `/townhall rush` ends running campaigns now; `/townhall notes` has citizens write notes now.

## Layout

```
build.neoforge.gradle.kts, build.forge.gradle.kts   shared loader build scripts
playtest/                                            dev-only mod for scripts/playtest.sh (never released)
icons/<addon>.svg                                    addon icons; scripts/render-icons.sh renders the PNGs
shared/src/                                          code several addons use (see "Shared code")
build-logic/                                         shared Gradle plugin (mods.toml, publishing, ...)
stonecutter.properties.toml                          shared properties, plus one table per addon
<addon>/src/                                         the addon's code and resources
<addon>/CHANGELOG.md                                 the addon's changelog
```

Each addon is a Stonecutter *branch*. Gradle projects are named `:<addon>:<version>`, e.g.
`:gazette:1.21.1-neoforge`.

### Shared code

Code that several addons need (for example `book`: written books on both loaders) lives once in
`shared/src/{main,test}/java/me/sshcrack/tc_shared/<package>/`. It is copied into each addon that
lists the package in `sharedPackages` (`stonecutter.gradle.kts`), under the addon's own package
`<mod group>.shared.<package>`, because two mods that ship the same Java package cannot be loaded
together. Edit the `shared/` version and run `./gradlew syncShared`; CI runs `verifyShared`, which
fails when a copy is out of date. The copies are committed, so each addon still builds on its own.

### Adding an addon

1. Add the directory name to the `branch` list in `settings.gradle.kts`.
2. Add a `[<addon>]` table to `stonecutter.properties.toml` with its `mod.id`, `mod.name`,
   `mod.group`, `mod.version` and `mod.description`.
3. Create `<addon>/src/main/...` with a `<mod id>.mixins.json`, the icon, and the code, and a
   `<addon>/CHANGELOG.md`.

## Playtest

One command starts a dev client with every addon loaded:

```sh
bash scripts/playtest.sh            # 1.21.1 NeoForge   (forge: 1.20.1 Forge, both: two clients)
bash scripts/playtest.sh --fresh    # start over with a new test world
```

The first launch creates the creative superflat world `TC_Playtest`, later launches open it directly.
When you join, the dev-only `playtest/` mod builds the colony "Playtest Hollow" next to you: town
hall, school with a teacher, library with a student, tavern with a visitor, two houses, six citizens
and a campfire. Chat shows a clickable checklist for every addon (`/playtest` shows it again).

Handing things over: whenever an addon gives you something (a newspaper, a reply letter), a citizen
walks up to you and hands it over, saying a short line through Talking Colonists. When it cannot speak
right now (quota, busy voices, no key), you get a chat line instead. The code is `shared/delivery`.

The Gemini key comes from the main repository's dev config (`../talking-colonists`), or from
`TC_ADDONS_KEY_FILE`. It is never printed.

When you add an addon, add its section to `playtest/.../Checklist.java`.

### Speech report and scripted scenarios

The playtest clients log a speech timeline (Talking Colonists' `-Dmc_talking.speechTimeline`): every
citizen voice with its kind, position, start and end, and every line that was heard. The report turns
it into a timeline and flags overlapping voices, audio played without the speaking animation,
near-repeats between citizens, and how often citizens addressed you unprompted:

```sh
bash scripts/speech-report.sh                  # your last NeoForge playtest (forge, or a log path)
bash scripts/scenario.sh                       # scripted "campfire" run, headless, then the report
bash scripts/scenario.sh ambient forge         # "ambient" scenario on 1.20.1 Forge
```

`scenario.sh` starts a headless client (xvfb; `TC_SCENARIO_VISIBLE=1` shows the window) in its own
copy of the playtest world, plays the timed steps of `playtest/.../PlaytestScenario.java` as the
player, quits and prints the report. Logs and JSON findings are kept in `build/scenario/`. It uses
Gemini quota like a real session. The report script lives in the main repository
(`scripts/speech-timeline-report.py`).

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
