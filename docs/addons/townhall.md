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

## The mayor

The winner wears the **Mayor's Hat**, a black top hat with a gold band, so everyone can tell who the
mayor is. A citizen mayor wears it on their head. A player mayor is brought one by a citizen.

- **Promises.** What the mayor promised in the campaign (homes, work, food, health care, safety or
  supplies) is measured against the colony: how many citizens lacked it when the term began, and
  how many do now. Citizens know how the promises are going, and so does the Ballot Box.
- **The mayor speaks for the colony.** Once a Minecraft day, by daylight, a citizen mayor walks up
  to a colony member with a written report: what the colony lacks, since when, and how often they
  have reported it already. Then they talk it through. The longer a problem goes unfixed, the more
  frustrated they sound.
- **Proposals.** The mayor proposes one thing at a time: an upgrade for a hut that would help, or a
  new hut the colony has none of (for example a hospital while citizens are sick). Say yes or no in
  the conversation, or answer at the **Mayor's desk** in the Ballot Box window.
  - Agreeing to an upgrade places the builder's work order right away.
  - Agreeing to a new hut is a promise to place its hut block. Once you place it, the mayor orders the
    build in your name.
  - Any building work for the same need keeps your word, not only the hut the mayor named. The
    mayor follows MineColonies' rules for what helps. Citizens feel safe when there are enough
    guards, and a guard tower always holds exactly one guard. So for safety a new guard tower
    counts, and so does a barracks or barracks tower upgrade; a guard tower upgrade doesn't count.
    Homes count fully from level 3. The mayor only raises safety once raids are possible: after the
    colony's first raid, or once it is strong enough for MineColonies to raid it.
  - Building takes days, so the mayor doesn't wait for it to finish. The proposal is kept once a
    builder is at work on it. If no work was ordered within four days, the colony remembers that
    you didn't keep your word. If work was ordered but no builder was free to start within eight
    days, that is remembered too, but it isn't held against you.
  - The mayor doesn't propose anything for a need a builder already has work for.
  - A proposal nobody answers in two days counts as ignored.
- **The mayor's word carries weight.** The report, your answer, and whether an agreed proposal got
  done reach every citizen at once, not by gossip. Other citizens take the mayor's view seriously.
- **Re-election.** A sitting citizen mayor stands again when you call an election. Voters judge
  them on their promises and proposals, and judge you on how you answered them.
- If a citizen mayor dies, the office is vacant and a new election can be called right away.

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
| `/townhall report` | Has every citizen mayor walk to the nearest colony member with a report now. |
| `/townhall appoint` | Makes the grown citizen nearest to you the mayor, without an election (for testing). |

All of them need permission level 2. Elections are stored in `<world>/data/tc_townhall.json`.

## Settings

The Town Hall has no settings of its own. Speeches need Simple Voice Chat. Votes, notes and reading
the mayor's promises use background Gemini requests. The mayor's report is a normal conversation.
