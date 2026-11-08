# Control cabinets and sensor finish

- 2026-11-07, code `4682156e`, branch `codex/simulator-sizing`; prepared for 0.22.0, not merged.
- Controller, DCS and five digital sensors share the constructor/simulator palette: neutral coated metal, dark seals/bezel, blue display and restrained amber identification.
- Local controller shows a loop and selector; DCS shows several loops/trends. Sealed sensor cases retain distinct P/T/F/L/N marks and their existing item silhouettes.
- `tools/pump-sprites/ControlAssets.java` writes eleven PNGs and the two existing block models through the workstation model writer.
- Controller keeps its two boxes; DCS keeps its three. Bounds, UVs, four headings, item transforms, collision and connection roles are unchanged at this commit.
- Installed sensors use the matching colour literals in `InstrumentGeometry` and high-contrast type text in `InstrumentRenderer`; the source audit confirms colour-only changes at `4682156e`.
- All texture references resolve; legacy `control_case.png` stays byte-identical. Asset audit includes sprite dimensions and SHA-256 values.
- Standalone JDK 21 compilation passed while the owner's client was open; no suite or client launch was performed then.
- After the client closed, the control rework's filtered checks (including GaugeAccessoriesTest), normal compilation and final package passed. The final jar contains all eleven sprites byte-identical to source; see CONTROL_REWORK_REVIEW.
- Cabinet front/rear/top comparisons against both workstations and the five-sensor sheet were inspected as software renders.
- Evidence: main checkout `research/2026-11-06-simulator-sizing/evidence/control-finish/`; original `tmp/control-finish/` remains retained while unmerged.
- Sources: `tools/pump-sprites/`; shared station renderer/model exporter: `tools/simulator-sizing/`.
- No saved schema, model identity, language or version changes in this finish task. Drum terminal geometry changed separately in `76ad7889`.
