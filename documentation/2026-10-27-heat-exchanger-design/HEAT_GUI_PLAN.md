# Heat units' GUI: Control tab, stream switch, line temperatures (plan, 2026-10-28)

The owner approved `HEAT_GUI_MOCKUP.svg` ("GUI seems ok but it needs its own thread"). This plan builds it on branch `claude/heat-gui-redesign-abb1db` (base `8f78119b`); it merges back into `claude/heat-exchanger-design-cf05d1`. Findings and the mockup: `OWNER_TEST_REVIEW.md` sections 6 and 7.

## 1. Data on the wire (fluid-15 → fluid-16, no compatibility work)

All of it is built where the view is built today (`FluidWorldAuthority.view`, at a presentation bucket); a new `HeatPresentation` class holds the reads so the heat session's edits to the authority stay apart. Opening a screen replays the cached snapshot; nothing here runs a solver.

- `FluidView.Heat` gains:
  - `actuator`: what the side's control moves, as a key the screen names (`bypass`, `bypass_valve`, `fans`, `burners`, `feed_valve`, `none`). The server picks it, so louvers (owner test section 2) are one server line and one language line.
  - `effort`: that actuator's share, 0 to 1 (the bypassed share of the flow, the fans' speed, the firing share), or null.
  - `limit`: the controller's planned limit by name (`HeatControl.Limit`: NONE, LOWEST, HIGHEST, LOW_LIMIT).
  - `ports`: every inlet and outlet of the unit, both sides: role, T, P, mass flow, and the stream's phase moles and volumes per second.
  - `lines`: an exchanger side's lines (W8): status, parts not in place, and each slot's state, detail, place and stream (T at a tee, flow at a valve or meter).
- `FluidView.PipeInfo` gains `reading`: the temperature, pressure and whether it flows, of the stream a plain pipe carries.
- `FluidWorldAuthority.Lines` keeps the report's slots (state, not saved), so `ExchangerLines.publish` passes them on.

How the numbers are read:

- A side's inlet is its inlet node (the nozzle's cell), its outlet the outlet junction; flow is the side's own pipe. The phase split and composition are the node's holdup scaled to that flow: at steady state a junction holds what passes it.
- A kettle's shell ports are its connections, told apart by port class (FEED bulk, VAPOUR_OUTLET vapour, LIQUID_OUTLET liquid), from the interval's pipe transfers; T and P are the shell's for an outflow and the far node's for the feed.
- A pipe reads the observation the temperature gauge reads (`instrumentPipeReading`: the donor's T, the tap's P); a junction pipe its own node. A still pipe shows the last flowing value the authority saw, marked "no flow", or, if none since the server started, the observation at rest, marked the same.
- Fluid phases only: a port carries no solids (the heat curve is fluid-only, an open W1 item).

## 2. The screens (`FluidDeviceScreen`, new `client/gui/common/HeatDiagram`)

- **Control tab** (page 6) on every heat side and on the kettle's shell. The rail's heat control rows (power, set point, mode, target, bypass, low limit, Check lines and the control's reading rows) move here.
  - Left: the unit as a flowsheet on a design grid, drawn as the column's (`AssemblyScreen`): nozzle squares, pills for the streams outside, every line labelled with T and flow; the W8 parts teal, yellow or red from the report, with the column's hover texts. Check lines and the legend sit under the drawing.
  - Right, a scrolled column: the side selector (tube/shell, convection/radiant, tubes/pool; the other side opens its own menu on its Control tab), the modes as a list with one line each naming the actuator, the set point, "Never cool below", "Acting now" with its effort bar, and the lines' note.
  - The status in words, in the player's temperature unit, above Close and Apply.
- **Overview**: a stream switch above the phase table: "This side" (today's table), then each port. A port fills the phase table and a line of T, P and flow.
- **Pipe screen**: Temperature and Pressure rows from `reading`, "(no flow)" when still.
- **Fixes**: the exchanger's ratio field gets its own key (`heat_ratio_input`; `ratio_input` stays the compressor's); statuses are read clause by clause, so "/ FULL" becomes words, and the LOW LIMIT and DUTY INSUFFICIENT clauses are shown in the player's unit.

## 3. Choices for the owner (built as the first option; each is reversible)

- **Q-G1, the drawn bypass while the internal bypass acts.** (a) The drawn lines show only what flows in the real pipes; the internal bypass shows its share inside the unit and under "Acting now". (b) As the mockup: the internal bypass's flow written on the drawn bypass line. (a) keeps every line a measurement.
- **Q-G2, a fired heater's modes.** `HeatUnit` lets a coil only fire to its set point. (a) Two rows: "Burners out" and "Fire to the set point", the burners switch as a mode. (b) One row and the burners as a button.
- **Q-G3, the kettle's pool.** It has no control of its own. (a) Its Control tab shows the drawing and says the tubes hold the duty, with the selector to the tubes. (b) No Control tab on the pool.
- **Q-G4, a still pipe.** (a) The last flowing value, marked "no flow" (as the gauge). (b) The observation at rest, which is the first end's node.

## 4. Verification

- Gates: `HeatDiagramTest` (every unit and line design: parts inside the grid and apart, a square at every nozzle a line meets, every text has its English line, no key defined twice); `FluidPacketCodecTest` (fluid-16; ports, lines and the pipe reading round-trip and are bounded); `FluidGuiConsistencyTest` (the Control page's columns at 300 to 790 px); `HeatPresentationTest` (an air cooler's ports and fans from a solved island; an exchanger's lines part by part and the bypass valve or internal bypass acting).
- Software renders of the drawing to PNG (`tools/heat-gui-renders/HeatDiagramRender.java`, Java2D, the drawing's own `HeatDiagram` records), judged by eye. No dev-client check (owner: enough client testing). `./gradlew test` green before the commit.

## 5. As built (2026-10-28)

- The status line sits where every page has it, under the tabs (not above Close and Apply); the raw status shows on hover.
- The Control tab has its own mouse handlers: a scroll area of another page never takes its clicks. One walker lays out and draws the column, so a widget and its text cannot drift apart when it scrolls.
- The statuses stay as the server writes them (the tests read them); the screen re-reads the LOW LIMIT and DUTY INSUFFICIENT clauses by their formats (`%.1f K`). A change to those formats needs the screen's patterns changed with it.
- Not gated: the authority's memory of a pipe's last flowing reading (`lineReading`), and the kettle's shell ports (`poolPorts`); both are read only by the screen.
