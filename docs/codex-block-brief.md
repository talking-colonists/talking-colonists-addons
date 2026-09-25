# Codex brief template: block models and textures

Block models and textures are made by Codex in Blockbench web (browser / computer use). Run:

```sh
git worktree add -b feat/<name>-models ../<name>-models-wt main
cd ../<name>-models-wt
codex exec --skip-git-repo-check -m gpt-6-astra -c model_reasoning_effort=\"low\" \
  -s workspace-write -c sandbox_workspace_write.network_access=true - < geometry-brief.md
# then the texture pass, in the same session:
codex exec resume --last --skip-git-repo-check -m gpt-6-astra -c model_reasoning_effort=\"low\" \
  -c sandbox_mode=\"workspace-write\" -c sandbox_workspace_write.network_access=true - < texture-brief.md
```

Adapt the block list, bounding boxes (must match the hitbox in code) and paths below.

## Geometry brief (first pass)

### Task: model three Minecraft blocks in Blockbench (web) for the Town Hall addon

You are working in a git worktree of the talking-colonists-addons repo (Minecraft 1.20.1 Forge / 1.21.1 NeoForge mods).
Mod id / namespace: `tc_townhall`. Only create or edit files in these places:

- `townhall/src/main/resources/assets/tc_townhall/models/block/<name>.json` (Java block model JSON, exported from Blockbench)
- `townhall/src/main/resources/assets/tc_townhall/textures/block/<name>.png` (textures)
- `townhall/models-src/<name>.bbmodel` (the Blockbench project, so the author can edit it later)
- `townhall/models-src/previews/<name>.png` (a screenshot of each finished model, 3/4 view, for the pull request)

Do NOT touch Java code, blockstates, item models, lang files, build files, or anything outside the worktree.
Do not read files in /tmp or in any Minecraft config folder. Do not commit, push, or open PRs; just leave the files.

## Tooling

**Use your web browser / browser-use tools** to open https://web.blockbench.net/ and build the models there by hand, like a person would. Use **Blockbench web (https://web.blockbench.net/)** in the browser, with the "Java Block/Item" format,
so the models are real Blockbench models. Build each model there, paint or import its texture, then export it with
File > Export > Export Block/Item Model, and save the project (.bbmodel). If the browser really cannot save files
into the worktree, copy the exported JSON/PNG content into the files by other means. Only if Blockbench web is
unusable, write the model JSON by hand in the same format and say so clearly in your final message.

## Style

Vanilla Minecraft / MineColonies look: 16×16 pixel-art per texture area, oak and spruce wood, iron/brass fittings,
muted colours, no gradients, no anti-aliasing. The author will later repaint the textures with an image generator,
so keep a **clean, non-overlapping UV layout** on one texture per block (32×32 or 64×64 png is fine) and name the
texture reference `tc_townhall:block/<name>`. Always set `"particle"` in `textures`.

## Hard technical rules (Minecraft Java block models)

- The model's front faces **north** (−Z). The game rotates it with the blockstate; don't pre-rotate.
- All element coordinates between −16 and 32; element rotations only on one axis, angles −45, −22.5, 0, 22.5, 45.
- Stay inside the given bounding box (it is the block's hitbox in code), so collision and looks match.
- Keep it light: at most ~30 elements per model. Cull faces that touch the block edge (`cullface`) where correct.
- Include `"display"` transforms so the item looks right in hand, in the GUI (like vanilla blocks: gui rotation 30/225/0, scale 0.625), on the ground and in item frames.
- Validate every JSON file parses (`python3 -m json.tool`).

## The models

1. `suggestion_box` — a small wooden box where citizens drop notes of complaints and wishes. Think a sturdy oak
   mailbox/collection box: a slanted or flat lid with a clear slot on the top, a small brass hinge or lock plate on the
   front, maybe a little carved/painted "note" sign on the front. Bounding box in pixels: x 1–15, y 0–12, z 2–14.
2. `suggestion_box_notes` — the same model with one or two folded paper notes sticking out of the slot (so a player
   sees from a distance that notes are waiting). Easiest: build `suggestion_box` first, duplicate, add the paper.
   Same bounding box (the paper may rise to y 14).
3. `ballot_box` — the election ballot box: a sealed box, more official than the suggestion box — darker wood
   (spruce/dark oak) with iron corner bands, a padlock on the front, a narrow slot on top, and a small red-white
   cockade/rosette or ribbon on the front. Bounding box: x 2–14, y 0–13, z 2–14.
4. `ballot_box_voting` — same, plus a small pennant flag on a thin pole standing on the lid, meaning "voting is open".
   The pole/flag may rise to y 22.
5. `notice_board` — a freestanding community notice board ("schwarzes Brett"): two wooden posts with little feet,
   a framed cork board between them, and a small roof/cap board on top. The cork face points north. Leave the cork
   face **empty and flat** (no painted notes): the game draws the pinned notes on it. The cork's front plane must be a
   single element face; tell me in your final message its exact rectangle (x1,y1,x2,y2 and the z of its front plane)
   so I can render notes onto it. Bounding box: x 0–16, y 0–16, z 6–10 (thin, like a sign).

## Finish

At the end, print a short report: every file created, for each model its element count and bounding box, the
notice board's cork rectangle, and anything you could not do. Screenshot previews go into `townhall/models-src/previews/`.

## Texture brief (second pass)

Second pass, same worktree: **textures**.

**The goal is the best-looking end result in Minecraft.** The blocks should look like they belong in vanilla
Minecraft / MineColonies and look good next to vanilla blocks. The guidance below is a default, not a rule: vanilla
textures usually fit best and follow resource packs, so start there. Where a custom texture (image-generated, or a
recoloured/edited vanilla one) clearly looks better, use it. Judge by what it looks like in Blockbench, and change the
geometry too if that makes the block look better (stay inside the bounding boxes).

Start with **vanilla Minecraft textures** where they fit, referenced directly in the model JSON, e.g.
`minecraft:block/oak_planks`, `minecraft:block/spruce_planks`, `minecraft:block/dark_oak_planks`,
`minecraft:block/stripped_oak_log`, `minecraft:block/iron_block`, `minecraft:block/gold_block`,
`minecraft:block/white_wool`, `minecraft:block/red_wool`, `minecraft:block/black_concrete`, `minecraft:block/chain`,
`minecraft:block/lantern`, `minecraft:item/paper`, `minecraft:block/barrel_side`, `minecraft:block/composter_side`, …
(names valid for both 1.20.1 and 1.21.1). Use UV sub-regions (0–16) of those textures for small parts such as fittings.
Only where no vanilla texture works (for example the notice board's cork face, or a painted sign plate) create a small
custom texture: generate it with your image generation tool, downscale with nearest-neighbour to 16×16 (or 32×32), reduce
the palette (~16 colours), hard pixel edges, no gradients, no text. Keep the notice board's cork face flat and empty.

Apply the textures to the models in Blockbench web in the browser and check how each looks from several angles; fix what
looks off. Then re-export the model JSONs (texture references must be `minecraft:...` for vanilla ones and
`tc_townhall:block/<name>` for custom ones in `townhall/src/main/resources/assets/tc_townhall/textures/block/`), delete
custom texture files no model uses anymore, update the .bbmodel files, and re-take the previews in
`townhall/models-src/previews/`. Every model keeps a `particle` texture.

Same rules as before: no code, no commits, don't read /tmp outside this worktree. Finish with a short report: per model,
which textures it uses, and any custom textures you made.
