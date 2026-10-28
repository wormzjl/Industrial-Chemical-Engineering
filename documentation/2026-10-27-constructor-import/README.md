# 2026-10-27-constructor-import

Purpose: make the Equipment Constructor's Import (a column calculator's saved design into the constructor) always finish and keep the open draft's edits (owner report 2026-10-27: "the loading template function in the builder does not work properly").

Status: Implemented 2026-10-27 (0.15.3; branch `claude/constructor-import-fix` from main 0.15.2 `eb0af66a` fast-forwarded into `main`); not pushed. Index: [../INDEX.md](../INDEX.md).

## Documents

- [CONSTRUCTOR_IMPORT_REVIEW.md](CONSTRUCTOR_IMPORT_REVIEW.md): expected behaviour, what the code did, the fix (P1, P2), options left, formats, verification.

## Code areas

`network/EquipmentNetwork` (`Action`, `ConstructorPayload.design`, `ImportAnswer`, `importColumn`, `answer`; protocol `equipment-13`); `client/gui/screens/EquipmentConstructorScreen` (`importColumn`, `answered`, `refused` on the status line); lang `message.icheme.equipment.not_editable`. Test: `network/EquipmentNetworkCodecTest`. Code guide: equipment, client-gui, column-v3, README.

## History

- 2026-10-27: diagnosis (review sections 1 and 2): a refused or unchanged import left "Importing…" on the screen for good, the refusal only in chat under the panel; an import replaced the open draft's edits. Owner: "Yes, fix P1 and P2 on a new branch. Leave the turns and the calculator hint as options for later."
- 2026-10-27: P1 and P2 in `dbefe7f4`: one `ConstructorPayload` with an `Action`, `ImportPayload` deleted, every import answered with `ImportAnswer`. `test` 1647/1647. Code guide at `dbefe7f4`.
- 2026-10-27: merged as 0.15.3 (owner: "Merge the constructor import fix to main as 0.15.3 (patch). Run slowGates in the merge lane. Do not push.", then "no test"). Release prep: version, CHANGELOG `[0.15.3]`, INDEX row, code guide snapshots at `dbefe7f4` (main, mod 0.15.3). No `test` or `slowGates` run on the release tree (owner); the last run is `test` 1647/1647 at `dbefe7f4`. Main fast-forwarded from `eb0af66a`; not pushed.

## Open items

- Push (owner).
- `slowGates` not run for this merge.
- Options (review section 4): the turns on a new column; the calculator's "draft differs from the saved design" hint.
- Not checked in game.
