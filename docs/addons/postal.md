# Postal Service

Write letters to your citizens and get replies in their own words. The courier walks your letter to
its recipient and brings you the answer.

## How to start

1. Write your letter in a **book and quill**.
2. Sign it with the recipient's name as the title: "Anna", "Dear Anna Smith" and "To: Anna" all work.
3. Right-click the colony's **courier** with it, or the recipient in person. Without a courier, any
   citizen passes it on.
4. The citizen who took it walks it over and hands it over (you get a line in chat). If the recipient
   is too far away or not around, it arrives a few minutes later instead.
5. The recipient reads it and writes back in character. The courier (or the recipient) walks up to
   you and hands you the reply the next time you are in the colony.

## Good to know

- The citizen remembers your letter (a confirmed Talking Colonists memory) and may bring it up when
  you talk.
- The recipient has to be loaded to write back; letters wait for them.
- A letter comes back undelivered after three in-game days, or if the citizen left the colony.
- Replies use a background Gemini request and wait while the quota is used up.

## For operators

| Command | What it does |
|---|---|
| `/mail rush` | Makes your letters reach their recipients now. |
| `/mail` | Hands you all your waiting replies. |

Both need permission level 2. Letters are stored in `<world>/data/tc_postal.json`.

## Settings

The Postal Service has no settings of its own.
