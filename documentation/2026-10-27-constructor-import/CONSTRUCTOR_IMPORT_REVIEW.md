# Constructor Import: review and fix

Batch `2026-10-27-constructor-import`, branch `claude/constructor-import-fix` from main 0.15.2 (`eb0af66a`). Owner report 2026-10-27: "the loading template function in the builder does not work properly". Read as the Equipment Constructor's **Import** (a column calculator's saved design into the constructor). Diagnosis by reading the code; not reproduced in game.

## 1. Expected behaviour

Import loads the nearest calculator's saved column design into the open draft: trays, feed, draws, pumparounds, steam, diameter and components. What the player set in the open screen stays: placement, skirt, paint, nozzle turns. Every press ends: the draft shows the design, or the status line says why not.

## 2. What the code did

- The screen waited for the constructor's synced design to change (`tick`, compared with the design at the press). Two cases never changed it:
  - a refusal (no calculator within 16 blocks, no saved design): the reason went only to chat, drawn under the 196 px panel;
  - an import that changed nothing: `apply` returns early on an equal design, so no block update was sent.
  In both, "Importing the column design…" stayed until the screen closed.
- The server imported onto the design it had stored (from the screen's last close, `design::imported`), and the screen took the result whole: edits made since the screen opened were lost.
- Loading a Column template in the calculator changes only its draft; Import reads what Save design stored. A template loaded and not saved imports the older design or the hidden `no_design` refusal.
- Smaller: an import keeps the earlier column's turns, matched by role and number.

## 3. Fix (owner: "Yes, fix P1 and P2 on a new branch. Leave the turns and the calculator hint as options for later.")

- **P1** (`dbefe7f4`): protocol `equipment-13`. `ConstructorPayload` carries an `Action` (`KEEP`, `BUILD`, `IMPORT`) in place of the build flag; `ImportPayload` is deleted. The server applies the draft (`ConstructorPayload.design`, keeping its own column), then imports onto it.
- **P2** (`dbefe7f4`): every import is answered with `ImportAnswer(position, message, optional design tags)`, also when the constructor cannot be edited or the draft is invalid. The tags are the constructor's `writeDesign`; the client reads them with `readDesign`. The screen stops waiting: it loads the design whole (message to chat), or shows the refusal on its status line in the warning colour until the next edit (to chat when no screen for that constructor is open). The screen no longer watches the synced design.
- New lang key `message.icheme.equipment.not_editable`.

## 4. Options left for the owner

- Turns on a new column: (a) keep as now, (b) drop all when the imported side nozzles differ, (c) keep only turns whose nozzle still exists.
- Calculator: (a) as now, (b) the Components page says when the draft differs from the saved design.

## 5. Formats

Wire only: `equipment-12` → `equipment-13` (no compatibility work, AGENTS.md). No facts, shape or layout change; constructor NBT unchanged.

## 6. Verification

- `EquipmentNetworkCodecTest` (3, new): the draft crosses with each action and the server keeps its column; an import lands on the draft sent (placement, paint, turns stay; on the stored design they would not); the answered design tags read back with no repair. The answer's message is Minecraft's component codec: a unit test cannot encode it (NeoForge's `Bootstrap` needs the game), so it is not covered here.
- `test` 1647/1647 at `dbefe7f4` (0.15.2: 1644 + 3). `slowGates` not run: no gate code touched. Merge as 0.15.3 with no test run on the release tree (owner: "no test").
- In game: not checked (at most one check; the status line and the answered load are client-only judges if the owner wants one).

## 7. Code guide

Chapters equipment, client-gui and column-v3, and the README, at `dbefe7f4`. Tools: none created.
