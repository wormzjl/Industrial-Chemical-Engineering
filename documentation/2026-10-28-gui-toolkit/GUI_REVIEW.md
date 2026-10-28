# GUI review: the other screens under the panel rules

Batch 2026-10-28-gui-toolkit, 2026-10-28, branch `claude/gui-toolkit`. Owner: "Review other GUI based on above rule, prototype first", "these buttons should distinguish themselves from the left ones". Code guide: at the merge (GUI chapter).

## The rules (from the Control tab, plan sections 10 and 11)

1. Function groups in engraved frames under flat plates of one or two words; a plate never looks like a button. Sentences go to hovers.
2. A device that runs has an Off and On switch, then its target; selector positions are latched.
3. No cut text: wrap, stack, keep units whole. No noise: no timestamps, raw engine lines or a status said twice.
4. The door holds switches, set points and indicating meters (7-segment, bar graphs); the HMI glass holds tables and drawings.
5. New, owner 2026-10-28: display keys (a unit, a basis) change what the screens show, not the plant. They are flat black keys in a tray engraved into the door, at the footer's right (owner: "C looks better"); Close, Apply and the other push buttons stay raised (plan section 12).
6. New, owner 2026-10-28: the theme and the units are the client's, one switch for every screen, kept in `icheme-client.toml`; no unit reaches the server. The theme key is a sign, not a word (plan section 12).
7. Every screen draws through the kit and the light or dark theme.

## Findings

| Screen | Breaks | Prototype |
|---|---|---|
| `FluidDeviceScreen` pages (pump, generator, valve, tank) | rail drawn raw in the vanilla look (6); cut labels (3); the status on the display and again on the rail (3); raw controller and status lines (3); hints that repeat the label (1) | `fluid-pump`, `fluid-generator`, `fluid-valve`, `fluid-tank` |
| Column calculator | no kit, no theme (6); cut field labels (3); reflux hidden between 710 and 823 px wide (3); three status readouts (3); Diagnostics lines (3); cycle buttons show only the current value (2) | `column-calculator` |
| Assembly | block coordinates in the heading (3); parts without hovers (1) | `assembly` |
| Vessel datasheet | world compass names where the nozzles use Front and Left (3); no scroll for long nozzle lists | `vessel-datasheet` |
| Pipeline tool, gauge | raw vanilla widgets (6) | `pipeline-tool`, `gauge` |
| Equipment constructor | "▶" markers (1); a Shift hint on every row (3); 42 px bottom buttons cut "Nozzles" (3); the config file path in a hover (3) | `equipment-constructor` |

The prototypes are in `tmp/gui-prototypes/` (both doors on each sheet), drawn through the shipped kit in the game's font by `tools/heat-gui-renders/GuiPrototypes.java`. The prototype check removed meters that repeated the status (generator flow out, tank net flow in), put the vessel's nozzle side in its own column, and moved the constructor's volume display under its groups. Done stays out of the constructor: closing saves the draft.

## Done in this task

- The column calculator's tray dP field is gone (owner: "outdated"). The draft keeps the base value (`V3EditorDraft`, `unchanged("s8")`); its hover text is removed, while the profile button still uses the label key.
- The fluid screen's basis and unit keys are 40 px display keys on their strip (rule 5); flows also per minute; Celsius reads "°C". The units, the basis and the theme are the client's config; the column calculator reads its °C or K from it; the theme key shows a contrast sign (rule 6).
- Units stay whole beside a meter: a wider unit narrows the box (`Column.UNIT`).

## Owner choices

- (a) Column presets still read and write `stage_pressure_drop_kpa` (`V3ClientPresets`). Option 1: keep it as the hidden value that a preset carries. Option 2: drop it from new presets and read old ones without it.
- (b) A diameter of 0 (the prescribed-drop mode) now runs on a hidden tray dP. Option 1: require a diameter above 0. Option 2: keep the mode with the base value.
- (c) "Duty ratio" on the air cooler was read as the fans' speed (plan section 11).
- (d) A fired heater's duty target needs the heat engine (`FiredCoil`, `HeatControl`, heat session).
- (e) A gauge's unit is saved on the gauge (`GaugeSettings.unit`, its dial in the world). Option 1: keep it. Option 2: the dial follows each player's config (plan section 12).

## Implementation order (after the owner approves the prototypes)

1. `FluidDeviceScreen` device pages onto `Column` groups (rules 1 to 4; the rail goes).
2. The small dialogs: gauge, pipeline tool, vessel datasheet (kit buttons, meters, themes).
3. The equipment constructor's panel (steppers, groups, bottom buttons).
4. The assembly screen (heading, hovers).
5. The column calculator on the kit (the largest; its own task).
