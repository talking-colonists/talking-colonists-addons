# Town Hall

Stand for mayor with a speech, and every citizen votes on what actually reached them. A Suggestion
Box collects your citizens' worries and wishes.

## Elections

### How to start

1. Craft a **Ballot Box** (spruce planks around paper and an iron ingot) and place it in your colony.
2. Right-click it. The window shows the candidates with their slogans and plans. Type a slogan and
   what you will do, and press **Stand**.
3. With Simple Voice Chat you then have 30 seconds for a speech. Citizens nearby hear it and pass it
   on. You can give another speech during the campaign.
4. The campaign lasts one Minecraft day. A boss bar shows when voting starts while you are in the
   colony, and a chat reminder comes a minute before.
5. Then every grown citizen votes in character, on what reached them and on how they feel about each
   candidate after your conversations. The Ballot Box window shows a live tally with each voter's
   reason.
6. The whole colony hears the result, and a citizen brings you the results book with a few voters'
   reasons. The mayor is part of what citizens know from then on.

The box shows the phase: a campaign poster while candidates campaign, a flag while voting is open.

### Good to know

- Right-clicking the colony's **Town Hall block** with a signed book also lets you stand: the title is
  your slogan, the text your platform.
- If you are the only candidate, the unhappiest citizen stands against you, with a platform built
  from the colony's real problems, so you can lose.
- A new election can be called three days after the last one.

## Suggestion Box

1. Craft a **Suggestion Box** (paper over a chest over a log) and place it anywhere in the colony.
2. Every morning up to three unhappy citizens drop a short note with a real concern or wish. A paper
   sticks out of the slot while notes wait.
3. Right-click it to read them. Take a note along as a one-page book, or throw it away.

At most six notes wait in the box.

## For operators

| Command | What it does |
|---|---|
| `/townhall rush` | Moves every running campaign on to voting now. |
| `/townhall notes` | Has the citizens of the colony you stand in write notes now. |

Both need permission level 2. Elections are stored in `<world>/data/tc_townhall.json`.

## Settings

The Town Hall has no settings of its own. Speeches need Simple Voice Chat; votes and notes use
background Gemini requests.
