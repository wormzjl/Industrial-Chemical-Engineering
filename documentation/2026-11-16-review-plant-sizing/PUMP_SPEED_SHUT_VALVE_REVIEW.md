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
- Send to block: it sends `PumpSizing.built` (owner item 3, below): the pump with its duty and design speed. The
  constructor's Import keeps the shape's duty (`Design.landed`). No motor is sent.

**Point 5, a slow pump on a real motor in REGULATE_FLOW.** `Duty.flowSpeed` and `pressureSpeed` were bounded at
`SpeedControl.MAXIMUM_CURVE_SPEED` = 1.5 of the pump's design (1.017 on a 2950 rpm pump, held by the 3000 rpm clamp). On a
1000 rpm pump the motor went CURRENT LIMITED near 1.26. Owner item 1 bounds both at `SpeedControl.REGULATED_TOP` = 1.0.

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
- `REGULATE_PRESSURE` is not changed. See owner item 6: it does not wind up.
- Status: `RUNNING / VALVE SHUT` while it idles (owner item 2).

## 3. The creative motor as a data host (part C)

- The data socket sits on the terminal box: north model (8, 16, 8.5) sixteenths, facing up, turned with FACING. It is the one
  constant `CreativeMotorGeometry.SOCKET`.
- `DataPorts.creativeMotor` and `ControlHosts.bindCreativeMotor` keep an actuator socket fact for the shaft unit. The
  100-tick motor bind covers creative motors, and removing the unit removes the fact.
- `CreativeMotorBlockEntity` (`DataPort.Owner`) holds the copy that the laying tool and the client read.
- `CreativeMotorBlock.java`: one changed line (the class line gains `implements EntityBlock`) and six added lines
  (`newBlockEntity`, before `getStateForPlacement`). `SHAPES`, its static block and the imports are untouched.
- A creative motor regulates only a machine that its data cables reach. It uses the motor's path, so it can say NO DATA LINK,
  MULTIPLE DUTIES or NO DUTY TARGET. With no link it holds at most its last speed: from rest it stays at rest, not at its set
  speed.
  - Existing worlds show NO DATA LINK (in the CHANGELOG).
  - The test plant lays the cable for either drive and waits for the socket.
- Scope: no controller or DCS sets any drive's speed. That is package 2 of the pump plan.

## Owner items (main thread's calls, 2026-11-18)

1. **Bound at 1.0.** `SpeedControl.REGULATED_TOP` = 1 replaces `MAXIMUM_CURVE_SPEED` in `flowSpeed` and `pressureSpeed`.
   At the bound the drive stands at the pump's design speed and says `RUNNING`, with no hold (D3).
   - Test `aRegulatingMotorStandsAtItsPumpsDesignSpeed`: an 11 kW, 1000 rpm motor on the 1000 rpm pump, asked twice the
     rated flow, gives share <= 1.0, ends at 1.0, status `RUNNING`.
   - Turbine frames keep their own 1.5.
2. **Status.** `ShaftTrains.VALVE_SHUT` (" / VALVE SHUT") is appended to the step's status when the trim came from a shut
   valve, the drive runs untripped and its speed is above 0.
   - Drive statuses are raw engine words, so no `en_us.json` line is added.
   - "RUNNING / VALVE SHUT" is 120 px at the test font. The door's status row is at least 280 px (300 less 20). Not a warning.
   - Tests: the shut-valve tests check the suffix while the valve is shut and its absence once it regulates again;
     `MachineColumnTest.theValveShutStatusFitsTheStatusRow`.
3. **Send to block.** `SheetSizing.designs` sends `PumpSizing.built(size, Duty.of(rating))` for a pump.
   - Test `CrudeUnitSampleTest.sendToBlockSendsEachPumpWithItsDutyAndSpeed`: each sized pump is sent with its built duty,
     and at least one differs from the bore-only shape.
4. **Machine page.** `MachineView` sends a creative motor's design speed as `ShaftTrains.creativeSpeed`, the function the
   engine turns it by. `MachineColumn.speedEntry` shows it when no speed is set.
   - Test `aDefaultCreativeMotorShowsItsTrainsDesignSpeed`: 900 rpm, and a set 1500 rpm stays.
5. **Geared.** The behaviour is kept.
   - Test `aDefaultCreativeMotorThroughAGearboxTurnsItsPumpAtItsDesignSpeed`: through the placed gearbox (ratio 4) the pump
     is at share 1.0 and the motor at 1000 / ratio rpm.
6. **`REGULATE_PRESSURE` against a shut valve is a defined state.** This corrects the earlier line, "still winds up".
   - `pressureSpeed` inverts the curve at the booked flow (`SpeedControl.requiredSpeed`, bisection). It is not a secant.
   - At no flow it asks `s = sqrt(dp / (1.21 rho g H_r))`, bounded to [0.1, 1.0]. Example: a 50 m pump with a set rise of
     30 m gives s = 0.704, shaft power 0.35 P_r s^3 = 0.12 P_r.
   - A set rise above 1.21 H_r stands at 1.0 and says RUNNING.

## The heater trace (stored logs, no run)

- The reference's "about 30 MW at 619.6 / 623.7 K" is report 6 (t=4321) of the fouling batch's `review-operation-2.log`,
  which is that run's last report: 30.9 / 30.1 MW at 165.26 kg/s.
  - Its drive had stood at share 1.0 against the shut valve (2950 rpm). At report 5 one heater was at 89 kW, so at
    report 6 its heaters were still recovering.
- This branch's report 6 (t=4421): 30.3 / 29.4 MW at 634.1 / 634.2 K and 168.0 kg/s (`review-operation-fix12.log`). The
  part C run gave 29.9 / 29.2 MW at 631.0 / 627.8 K and 162.9 kg/s. At the same report the duty agrees within 2 to 3 %.
- The table compared this branch's report 10 with the reference's report 6.
  - Between reports 6 and 10, at the same 168 kg/s, the convection outlet (the radiant inlet) rose from 533.3 to 546.0 K,
    because the preheat warmed.
  - So the radiant duty for the same outlet fell to 26.6 / 25.8 MW. That is 168 kg/s x about 3 kJ/(kg K) x 12.7 K
    = about 6.4 MW for both heaters; the drop seen is 7.3 MW.
  - The outlets stand at 634.4 to 634.8 K, below a set point of about 638.2 K.
- No defect is found. The run differs from the reference only in its flow history (the drive's idle and return).

## Tests

- `UnitSizingTest`: `aSizedPumpIsBuiltAtItsDesignSpeed`.
  - 0.203 m3/s is built at 1000 rpm (duty speed 1086.8 rpm); 0.01 m3/s stays at 2950 rpm.
  - The in-line expectations become the built law's: 2.889 m and 3.668 m.
- `ShaftCouplingTest`: the trim and the shut-valve idle on a motor and on a creative motor; the drive spins up against a
  static head with no valve; a shut valve on a parallel branch changes nothing; the creative motor's default speed, its data
  link and the gearbox; the motor's 1.0 bound (items 1, 2, 5).
- `CreativeMotorGeometryTest`, `MachineColumnTest` (items 2, 4), `CrudeUnitSampleTest` (item 3).
- Each run selected by `affected.mjs select`, through the lane's init script, all passing:
  - fixes 1 and 2: test 1975 tests, holdGates 59, equipmentGates 87;
  - part C: 832 / 10 / 2;
  - the block entity: 70 / 2;
  - owner items (`--since 95f718836`): 230 test classes, 10 holdGates, 2 equipmentGates.
  - `LanguageFilesTest` and `UnitTextTest` are left out until the translation commit.
- `runReviewWorldServer -PreviewBatch=review-operation`, 5 of 5 each time:
  - after fixes 1 and 2;
  - after part C, on a fresh world (the old world is kept in `tmp/pump-speed/review-world-fix12`).
  - No run after the owner items: the plant's drive is the creative motor, whose top was already 1.0, and nothing in the
    plant reads a drive's status. Only its log line gains " / VALVE SHUT".

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
| Heaters (radiant) | 30.9 / 30.1 MW at 619.6 / 623.7 K (report 6, the last) | report 6: 30.3 / 29.4 MW at 634.1 / 634.2 K; report 10: 26.6 / 25.8 MW at 634.5 / 634.6 K | the preheat warmed; see the heater trace |

- Part C run: shares 0.487, 0.571, 0.578, then 0.100 (90 rpm, no flow) at t=3223 and t=5020 with the feed valve shut,
  0.570 to 0.587 between. The drive is wired by cable. FEED0 2557.2 kmol/h. Holds at ticks 1891 and 6214.
- Round-deadline holds at ticks 1923, 4927 and 6802 to 6839 (the end). The batch passed, so none was replayed. This run
  did not compare its holds with the reference run's.
- Conservation: no mass, energy or component law was changed. These changes move only the drive's speed decision and
  the sizer's built shape.

## Open items for the owner

None from this branch. The owner items above are built.
