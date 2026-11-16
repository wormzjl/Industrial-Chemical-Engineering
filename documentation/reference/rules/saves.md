# Rules: Saves and data formats

The full rules of the `AGENTS.md` section "Saves and data formats", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- Saved fluid and equipment data holds facts and state. Facts are what the player built and put in: device registrations and settings, node contents, captured solids, ledger totals, the world clock, accessories, equipment shapes, paint and nozzle sizes. State is what the engine can compute again: solved intervals, junction seeds, certificates, cadence, caches, pending events.
- Never discard a fact. State formats change freely: a mismatch drops the state, and the engine rebuilds it on its schedule.
- Fact and shape fields are append-only. A new field defaults to the behaviour before it existed. A field never changes meaning or unit. Enums are saved by name; a renamed or removed constant gets an alias.
- An identity is a declared revision: a token of letters, digits, `_`, `.` and `-`, at most 64 characters. Nothing saved or cached is keyed by a hash of its content.
- A value change bumps the `revision` of the record that holds it: the material record, its viscosity or its freezing point. A package's `revision` changes only with the package record. Its identities list the revisions of the records it names (`MaterialCatalog.Package.scientificRevision` for the column, `MaterialCatalog.fluidRevision` for the fluid network) and name the method by its physics fields.
- After a material or model change, contents keep their amounts and temperature. An engine job recomputes energy and pressure. The ledger books the energy difference and every removed component; the island's status line names them.
- After a placed equipment structure loads, rebuild it to the current layout if another layout placed its cells (`EquipmentRefresh`, `EquipmentStructure.refreshLater`): nozzle pipe blocks first, then cells. Keep the origin, paint, nozzle sizes and fluid identity. Never overwrite another block. Remove a structure whose kind no longer exists whole, with all its cells. A layout change updates `EquipmentLayoutGoldenTest`; a moved nozzle pipe block needs the owner's approval.
- Every format validates what it reads and round-trips what it carries. A damaged facts section refuses the world and leaves its files untouched.
- Every facts-schema or shape-field change adds a corpus image under `src/test/resources/compat/`. The gates read every image. Do not test against player worlds.
- Other formats (wire protocols, the column calculator block): no compatibility work and no migrations. Test a breaking change on a fresh world.
