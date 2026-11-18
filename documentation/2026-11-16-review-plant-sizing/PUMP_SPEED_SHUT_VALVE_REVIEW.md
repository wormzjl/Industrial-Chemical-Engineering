# Pump speed and the shut valve (review)

Branch `claude/pump-speed-shut-valve-6020f0` from `main` 0.35.0 (`a7667dd43`). The owner's order was "fix it" for two defects
in the crude unit test plant. The main thread's design calls (2026-11-18) were: the creative motor's default speed, option
A; the trim on every driver, option (ii).

## 1. A sized pump is built at its design speed

**Expected.** NPSH3 at the rated point is `(n_r √Q_r / 200)^(4/3)` m. Owner decision D21: a designer turns a large pump
slower, so that its rated NPSH3 is at most 3.3 m (`Npsh.dutySpeed`). The test plant's charge pump has a 0.35 m bore,
rated 1039 m³/h (0.2886 m³/s). Its duty speed is 911 rpm, which gives NPSH3_r 3.24 m at 900 rpm. At 2950 rpm it needs
15.8 m.

**What the code did.** The sizer rated suction against the in-line pump's duty design (`FlowControl.Pump.npsh` =
`Npsh.forDuty`, at most 3.3 m). It then built the pump with no base speed, so the world's law turned it at 2950 rpm and
needed 15.8 m. The sheet and the world read two different pumps.

**Built** (`687dfd5fa`):
- `PumpSizing.builtSpeed(Q_r)`: the duty speed on the 2-pole speed, rounded down to the constructor's 100 rpm grid,
  at least 300 rpm (the grid a motor's 3000 rpm default steps on; a pump stepped from 2950 reaches it through 3000).
  Up to 0.0275 m³/s it is 2950 rpm with no base speed, as before. Values: 0.203 m³/s gives 1086.8 rpm, built at
  1000 rpm (NPSH3_r 2.95 m); 0.01 m³/s gives 2950 rpm.
- `built(Size, Duty)` adds `withBaseSpeed`. `PumpDesign.checkDuty` accepts any base speed. The rated head does not
  change: `CentrifugalPump.head` is in shares of the rated flow, head and speed, so the design point at speed share 1 is
  the same at any rated speed.
- `requiredNpsh(size, flow)` is the built law's `Npsh.rated(Q_r, builtSpeed, 1).required(Q)`. The `PUMP_NPSH_WORLD` and
  `PUMP_NPSH` rows and `Duty.npsh` read it. `FlowControl.Pump` is no longer read in the sizer. An in-line world pump is
  unchanged.
- The Build list line `sizing.size.pump_duty` ends with the pump's speed: "…, discharge %s m, %s rpm". It is the last
  argument, so the other languages keep their order until the translation commit.
- `PlantBuilder.motor(pump)` builds the motor at the pump's base speed (`MotorDesign.checkDuty`: 300 to 12000 rpm).
- A creative motor with no set speed turns at its train's first machine's design speed over the ratio
  (`ShaftTrains.creativeSpeed`, option A; 2950 rpm with no machine). A creative motor with a set speed is unchanged.
  A second driven machine of another design speed turns at its own share of that speed (no rule added).
- Saved data: the base speed is the machine duty's existing saved field (`EquipmentShellBlockEntity`). A pump already
  built keeps its saved speed. No row moves in `FORMATS.md`.

**Places that build or propose a motor for a sized pump:**
- `PlantBuilder.motor`: fixed as above.
- The Build list: it proposes only a power (`Duty.MOTOR`). It now shows the speed the motor must match.
- Send to block: it stores the bore-only shape (`UnitSize.built(size)`), with no duty. A sent pump is built at the
  default 100 m and 2950 rpm, and no motor is sent. This gap was there before this change; it is an owner item below.

**Point 5, a slow pump on a real motor in REGULATE_FLOW.** The data-link path asks `Duty.flowSpeed`, which is bounded by
`SpeedControl.MAXIMUM_CURVE_SPEED` = 1.5 of the pump's design, and then clamps to 3000 rpm.
- On a 2950 rpm pump, 1.5 × 2950 clamps to 3000 rpm, so the bound is 1.017 of design.
- On a 1000 rpm pump with a 1000 rpm motor, the bound is 1500 rpm, 1.5 of design. Shaft power at that speed is
  about 3.4 × rated. Above its base speed the motor gives its rated power at most (`availablePower`), so in practice it
  goes CURRENT LIMITED first. `PlantBuilder`'s motor is rated for 2 × the design shaft power, which stops it at about
  1.26 × design.
- A creative motor regulates at most 1.0 of its set speed (`ShaftTrain.regulated`), which is now the pump's design
  speed. This is an owner item below.

## 2. A drive idles while a flow valve in its pump's series is shut

**Expected.** Against a shut valve a centrifugal pump passes nothing at any speed. Speed only raises the dead-head
pressure (1.21 H_r s²) and the power (0.35 P_r s³). The drive should turn at its lowest speed.

**What the code did.** `seriesValveDrop` read only a regulating valve. A shut one gave NaN, so the secant saw zero flow
below the target and raised the speed by 0.2 each decision, to the top. The trim existed only on the real motor's
data-link branch. The creative charge drive the owner watched never reached that branch.

**Built** (`4902d1c5a`):
- `seriesValveDrop` returns `SHUT` for a `ControlValve` that meets all of these:
  - mass or volume flow, any sensor, not bare, not pressure-controlled;
  - in the pump's series;
  - endpoint mode `CLOSED` on its own target of no flow.
- Kinds covered:
  - a player's flow valve set to 0;
  - an automated shutoff valve shut (`ON_OFF`, mass target 0);
  - an engine-planned valve given 0 (burner, still, blanket and similar).
- Kinds not covered, and why:
  - A valve closed by a reverse drive (positive target, flow < 0): a faster pump opens it.
  - A conflict's held opening of 0: the solver's choice, not the valve's setting.
  - A one-way pipe that does not flow: it is not shut.
  - Pressure valves and turbines: `REGULATE_PRESSURE` is not changed.
- `Duty.trimSpeed` is `Motor.LOWEST_SPEED` (0.1) when a valve is SHUT. The step code is the trim's own (`ShaftTrains.trimmed`): at most
  0.2 down per decision, never up, the deadband held. The measure stays the flow.
- One law: the trim and the shut target apply to every `REGULATE_FLOW` driver's first machine. A motor still needs its data
  link (NO DATA LINK stays). A creative motor or a turbine needs none. Two fixes came with this:
  - `Driver.atSpeed` keeps a creative motor's set point in rpm.
  - The 3000 rpm clamp applies to motors only.
- (a) Why the trim was MOTOR-only: no reason in the code or in `LOAD_HOLD_REVIEW.md`. It sat where the duty sample was
  already read.
- (b) A creative motor's share is its speed over its own set speed. After option A that set speed is the pump's design
  speed, so the creative's share is the pump's share.
- Not changed: `REGULATE_PRESSURE` against a shut valve still winds up.
- Status: the drive reads `RUNNING` while it idles at 0.1. No existing word fits. The proposal is below.

## Tests

- `UnitSizingTest` (56 pass): `aSizedPumpIsBuiltAtItsDesignSpeed`.
  - 0.203 m³/s gives 1086.8 rpm and is built at 1000 rpm; 0.01 m³/s stays at 2950 rpm.
  - On every bore: built at `builtSpeed`, on the 100 rpm grid, the law turns at it, rated NPSH3 ≤ 3.3 m, the design
    head at the design flow, and `Duty.npsh`, `PUMP_NPSH` and `PUMP_NPSH_WORLD` read the law's NPSH3.
  - The old in-line 3.3 m expectations are now the built law's: 2.889 m at 731 m³/h on the 0.3 m pump, and 3.668 m
    for the 0.1 m pump at 118 % of its rated flow.
  - At 52 × the rated flow, NPSH is the closest row; the flow row stays red.
- `ShaftCouplingTest` (17 pass):
  - A default creative motor on a 1000 rpm pump: share 1.0 in SPEED, at most 1.0 in REGULATE_FLOW.
  - On a motor and on a creative motor, at set flow 15 L/s: regulating, the trim gives 0.830; shut, the speed falls
    0.2 per decision to 0.1 with no flow, never up; regulating again, 0.830 and 5 % on the valve; open, the set flow.
  - No valve and a 31.92 m static head: the drive spins up from rest to 6 L/s.
  - A shut valve on a parallel branch gives the same speed and flow as no valve.
  - The existing trim tests are unchanged and pass.
- Selected by `affected.mjs select --since main`, through the lane's init script, all passing: `test` 1975, `holdGates` 59,
  `equipmentGates` 87. Left out: `LanguageFilesTest` and `UnitTextTest` (the translation commit), and the two classes
  above, which had already passed on the final code.
- `runReviewWorldServer -PreviewBatch=review-operation` on a fresh world: 5 of 5 required tests passed.

## Moved numbers (review-operation, reference `f31db3a4b`)

| Quantity | Before | After | Why |
|---|---|---|---|
| Charge pump speed | 2950 rpm | 900 rpm (0.35 m, 1039 m³/h, 156.3 m) | design speed |
| Charge pump NPSH3_r | 15.8 m | 3.24 m | design speed |
| Other sized pumps | 2950 rpm | 1500, 1000, 1200, 900 rpm (0.2, 0.3, 0.25, 0.35 m bores) | design speed |
| Charge drive share, reports 1, 2, 3 | 0.800, 0.583, 0.588 | 0.500, 0.581, 0.581 | the creative's base is now 900 rpm; the start's path differs |
| Report 4 (feed valve CLOSED) | climbed | 0.100 (90 rpm), no flow | the shut-valve idle |
| Reports 5 to 10 | (valve open, steady) | 0.569, then 0.578 to 0.591, flow 0.1938 m³/s against 0.19357 set | the secant (the feed valve stands open: no trim) |
| `chargeCheck` at report 3 | passed | flow 0.19226 m³/s (−0.7 %), 523 of 900 rpm: passed | |
| Column | RUNNING, about 2557 kmol/h | RUNNING, 2557.2 kmol/h, solved table from report 8 | |
| Heaters (radiant, report 10) | about 30 MW at 619.6 / 623.7 K | 26.0 / 25.3 MW at 634.4 / 634.6 K | not traced |

- Round-deadline holds at ticks 1923, 4927 and 6802 to 6839 (the end). The batch passed, so none was replayed. This run
  did not compare its holds with the reference run's.
- Conservation: no mass, energy or component law was changed. These changes move only the drive's speed decision and
  the sizer's built shape.

## Open items for the owner

1. **A real motor's REGULATE_FLOW bound on a slow pump** is 1.5 × the pump's design speed (about 1.26 × in practice,
   CURRENT LIMITED), where it was 1.017 × before. Options:
   - (A) bound `flowSpeed` and `pressureSpeed` to 1.0 of the pump's design, as a creative motor is bound;
   - (B) keep it: a real VFD runs a pump above its rating up to the motor's limit.
2. **Drive status while idling against a shut valve.** Proposal: `RUNNING / VALVE SHUT` (one en_us key), or keep `RUNNING`.
3. **Send to block sends no pump duty.** A sent pump is built at the default 100 m and 2950 rpm. Option: send
   `PumpSizing.built(SizedUnit)`, which carries the duty and the speed.
4. **The machine page's set speed for a default creative motor** (`MachineColumn` 268) still shows 2950 rpm. Option:
   show the view's design speed.
5. **A creative motor on a geared 2950 rpm pump** now turns at 2950 / ratio, not at 2950. No test covered it.
6. `REGULATE_PRESSURE` against a shut valve still winds up. Not changed.
