# Colony Gazette

Every morning the colony writes a newspaper about the day before, in a citizen's own words: raids,
births and deaths, new hires and job changes, buildings built or upgraded, and anything other addons
record as colony events (a campfire night, an election).

## How to start

Nothing to build or craft. The colony's **teacher** writes the paper; without a teacher, a
**student** in the library does, and without either, "the colony's notes" do.

1. Play as usual. Things that happen in your colony become the next issue's news.
2. Each morning (in-game time 1000) colony members get a chat message that a new issue is out.
3. While you are in the colony, a citizen (the courier, if there is one) walks up to you, hands you
   your copy and says a line about it. If you are away, it is brought when you come back.

## Good to know

- One copy per player and issue. Quiet days with no events get no issue.
- Issues use a background Gemini request. While the key's quota is used up or every background slot
  is busy, the issue waits; the gazette retries a few times per day.
- Citizens know what happened, so the headlines come up when you talk to them.

## For operators

| Command | What it does |
|---|---|
| `/gazette publish` | Writes an issue for your colony right away. |
| `/gazette` | Hands you a copy of the latest issue. |

Both need permission level 2. Issues are stored in `<world>/data/tc_gazette.json`.

## Settings

The gazette has no settings of its own. It uses Talking Colonists' Gemini key and its limits for
background requests.
