# Device tabs, furnace coils and engineering labels

Client and presentation lanes. In progress since 2026-11-18 on `codex/constructor-ghost-rendering`.

## History

- 2026-11-18 — Fixed the selected device tab, combined the furnace coil views, clarified 56 English GUI labels and updated the Field Guide and device tours. The protocol reservation and implementation are local to the task branch at the owner's request.

## Behavior

Overview, Control and Datasheet highlight the current page. The furnace title identifies the whole unit when opened from either coil. Overview and Control show both sections in process-flow order. Control has one burner switch and a radiant outlet temperature target. Convection recovers heat from the flue gas. Each coil has its own inlet and outlet temperatures and pressures, mass flow, pressure drop, absorbed heat and deposit readings. The radiant coil also shows its duty limit and percentage used. Overview identifies each coil's corrosion readings.

Each duty is the booked heat divided by the duration of that coil's own committed interval. The display reads both islands from the existing publication bucket. Missing partner data appears as a dash. Exchangers and kettles retain independent sides. The pressure drop uses the published loss for throttling devices and the inlet-minus-outlet difference for each furnace coil. A pump's developed head is reported as a pressure rise.

[English label inventory](DEVICE_GUI_LABELS_REVIEW.md) lists every changed entry. The corrosion allowance is the fraction remaining. The rate is total modeled wall loss in mm per corrosion year, including erosion, and the time to wall failure uses minutes of play.

## Verification

Compiled production, test and GameTest sources. 179 distinct focused tests passed across 21 test classes. Checks covered paired-coil readings from either nozzle, missing ports, Celsius conversion, signed pressure differences, the network round trip, GUI layout, equipment screens, simulator labels and tutorial anchors. The furnace and corrosion panels were inspected in both software-rendered themes.

Before merging, coordinate the branch-local protocol `icheme-9` against main. Live-client appearance has not been verified.
