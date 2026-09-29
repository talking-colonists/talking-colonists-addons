# Notice Board

Post a notice for the whole colony: the citizens near the board read it first and spread the word,
and a few of them pin their replies, worries or petitions under it.

## How to start

1. Craft a **Notice Board** (planks around paper, on two sticks) and place it in your colony.
2. Right-click it, write a title and your notice, and press **Post**.
3. The citizens near the board read it (a Talking Colonists broadcast from "the notice board") and
   pass it on, so they bring it up when you talk to them. The window shows how far the word has
   spread, and you hear when half the colony knows and when everyone does.
4. Over the next few minutes, up to three citizens near the board pin short replies under it. You
   see the sheets appear on the board; read them in its window.

## Good to know

- **Take down** removes the notice and stops the replies; posting a new one replaces it.
- **Announce** tells every citizen at once without pinning anything.
- A signed book on a **lectern** inside your colony works as a notice too. Its replies are pinned
  into the book as extra pages; taking the book down cancels them.
- Ring a **bell** in your colony while holding a signed book and every citizen hears it at once, like
  a town crier.
- Watch closely and you'll see citizens stop and pass the news on to each other.

## For operators

| Command | What it does |
|---|---|
| `/noticeboard rush` | Makes waiting notices collect their replies now. |

It needs permission level 2. Notices are stored in `<world>/data/tc_noticeboard.json`.

## Settings

The Notice Board has no settings of its own. Replies use background Gemini requests; how far word
spreads follows Talking Colonists' broadcast settings.
