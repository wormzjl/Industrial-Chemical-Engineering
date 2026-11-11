# Pressure safety valve: design plan

Batch `2026-11-11-gas-blanketing`. Owner, 2026-11-11: "Can be installed on drums, and pipes (is it possible & not so expensive to do so?)"; "Keeps reservoir pressure under certain value"; "Outlet can be connected to pipes, release to atmosphere otherwise"; "like an auto-switch to connect fluid networks?"; "these 2 should be placed on top of drums, there are space intentionally left for them". Nothing here is built. Decisions D1 to D5 are the owner's.

## 1. Physics

- A safety valve stays shut below its set pressure. Above it, it passes what the protected volume holds in excess to a lower pressure, and the volume stays near the set pressure while the valve's bore can carry the load. Past that capacity the pressure rises further (accumulation).
- Numbers: a 20 m3 drum of nitrogen at 300 K, set at 4 bar abs, fed 1 kg/s with its outlet shut, must pass 1 kg/s. A 0.1 m bore at 4 bar passes about 3.5 kg/s at the network's 100 m/s limit, so the drum stands at its set pressure. Liquid-full on a 200 kg/s feed, the same bore passes about 190 kg/s of water at 3 bar of drop, so the drum rises to about 4.3 bar.

## 2. What the engine already has

- An upstream-regulating valve: `FlowControl.PressureValve(targetPressure)`, and the control valve's `PRESSURE` source. It passes only while its first end stands above its set point, and holds that end at the set point inside the solver (implicit, no slice lag). The rest test already reads it (`NetworkReduction.atRest`).
- A sink at ambient pressure: the `VOID` node. What enters it is booked as a boundary transfer.
- Role ports on a vessel whose run carries a built-in valve: the still's outlet, the firebox, and now the drum's `BLANKET` port (`PhysicalFluidTopology`, the valved-sink run).
- Reserved top flanges on drums: F1 (gas blanketing valve, built) and F2 (PSV).
- So a player can already assemble a relief: a branch pipe, a pressure valve fitting, a void block. The safety valve packages that.

## 3. Proposal: one mechanism for the four cases

**A safety valve is the existing pressure valve law on a relief run from the protected node to a sink. The sink is the pipe at its outlet, or a built-in atmosphere void when nothing is connected there.**

| Case | Protected node | Relief run | Sink |
|---|---|---|---|
| Drum, outlet open | the drum | a compiled stub out of its `RELIEF` port (F2) | built-in `VOID` at the drum's ambient |
| Drum, outlet piped | the drum | the run out of its `RELIEF` port | whatever the pipes reach (flare header, knock-out drum) |
| Pipe, outlet open | the pipe's junction | a compiled stub out of the fitting's outlet face | built-in `VOID` |
| Pipe, outlet piped | the pipe's junction | the run out of that face | whatever the pipes reach |

- No new solver law, no new unknown, no explicit release term. The valve is implicit, so it holds the set pressure inside a step where the blanketing valve's vent lags by its response time.
- It deletes nothing existing; it adds no second way to relieve. The blanketing valve's vent stays (it is the pad's partner, 5 % above the pad's set pressure, far below a relief set pressure).
- **"An auto-switch between networks": yes in function.** Below the set pressure nothing passes and the two sides do not see each other; above it they are connected. In the engine both sides are one island joined by a closed valve, as with every valve. The valve does not edit the topology when it lifts: an edit recompiles the island, and a valve that chatters would recompile it every lift.

## 4. On drums

- Accessory item "Pressure safety valve", fitted on F2 with the same request, ghost, wrench and refund path as the blanketing valve. Its outlet faces away from F1, at a cell face, so a pipe placed there connects and the two valves' pipes never meet.
- Facts: `FluidDeviceSpec.relief` (set pressure, 0 for none; append-only, default 0), port role `RELIEF` (appended, by name). Fluid facts schema 23, accessory facts schema 6 (type `SAFETY_VALVE`, mount `safety_valve`), a corpus image for each.
- `InstrumentHosts.vesselFitting` returns one fitting per host today. It becomes one per mount, so a drum holds both valves. The fitting key already is (host, mount).
- The set pressure must stand above the blanketing valve's vent pressure (set × 1.05) when both are fitted; the page refuses a lower one and says why.
- Page: the drum's Accessory page gets a second group: set pressure (entry), "Relieving" (kg/s, 0 when shut), "Discharge" (atmosphere or piped). The status line names a relieving drum.

## 5. On pipes: possible, and cheap

- A pipe takes one fitting on its `pipe_accessory` mount (shut-off, check, pressure valve, filter, pump, compressor). The safety valve is one more, with a facing: the face its outlet points at. The pipe's other faces stay joined as a plain pipe's.
- Engine cost per valve: one pipe and, with the outlet open, one void node in the island. No inventory on the pipe is needed: the valve reads the junction's pressure, as the pressure valve does.
- Build cost: a fitting type, its geometry on the pipe (a body with an outlet stub), the compile of a branch at the facing, the pipe page's set pressure. About a third of the drum side.
- Limit: a pipe that carries a safety valve carries no other fitting.

## 6. Risks

- **Joined islands.** A flare header that collects ten drums' safety valves makes one island of the ten drums and the header, although nothing flows. It solves as one job and holds as one. This is already so for vents piped to a flare stack. Measure it on a ten-drum header rig (solve time and substeps by counters) before deciding D5.
- **Voids at the end of a run.** The open gas-vent hold (drum batch, defect 1) and the direct-start hold (GAS_BLANKET_REVIEW.md, item 1) both involve a run into a void. The relief stub is short and level, but liquid relief through it must be in the tests.
- **Chatter.** A valve that opens and shuts in alternate steps costs active-set passes. The pressure valve regulates, it does not snap, so it should not. If a rig shows it, the owner's lag applies: relax the valve's set point toward the vessel's pressure over a response time.

## 7. Decisions for the owner

- **D1 Valve action.** (a) Modulating: the existing law, the pressure stands at the set point (recommended; no new state, no chatter). (b) Pop action: full lift at the set pressure, reseat 7 % lower. Real for gas service, but it adds a two-state device with hysteresis, the kind of state that cycled in the holds above.
- **D2 Liquid to atmosphere.** A liquid-full drum relieves liquid. With the outlet open that liquid leaves the world, booked at the void. Accept, or require a piped outlet for it to pass liquid (then a liquid-full unpiped drum rises to its feed pressure).
- **D3 Set pressure.** Proposed range ambient + 10 kPa to ambient + 10 MPa, default ambient + 300 kPa. The game has no vessel design pressure to derive it from.
- **D4 Pipes: own fitting or a mode.** (a) Its own item and fitting (recommended: its own look, its outlet may stay open). (b) A switch on the existing pressure valve, "relieves to atmosphere when its outlet is open".
- **D5 Header islands.** (a) Accept joined islands, after the ten-drum measurement (recommended). (b) Decouple: the valve releases explicitly and an account delivers the release to the header's island a slice later, as a column's ports do. Islands stay apart, but the material in transit is a new saved fact, the header's back pressure no longer acts on the valve, and it is the largest work package here.

## 8. Work packages

1. Engine and topology: `RELIEF` role, the relief run with its valve, the built-in void for an open outlet, `FluidDeviceSpec.relief`, facts schema 23. Tests: a blocked-in drum on a gas feed stands at its set pressure; liquid-full relief; piped to a second drum; rest below the set pressure.
2. Drum accessory: geometry on F2, item, renderer, both valves on one drum, accessory facts schema 6, corpus images.
3. Pipe fitting: type, facing, branch compile, geometry.
4. Pages and status: the drum's Accessory group, the pipe's entry, the relieving line; `GuiPrototypes` renders.
5. Rigs: a GameTest of a blocked-in drum relieving to a flare stack; the ten-drum header measurement.
6. Documents, drawings for the owner, translation and code guide at the merge.
