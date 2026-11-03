# Coolers: air and water (plan, 2026-11-03)

Batch 2026-11-02-process-simulator, after W10b (`b5eb35bc5`). Status: approved by the owner on 2026-11-03 ("Ok for the plan": T1 as revised in section 3), with the column condenser's air-then-water configuration added (section 6). Building in the order of section 7. The owner: "you need to find a method to differentiate water cooler and air coolers, usually AC cannot be used at lower target T. I think it's time to make a better model", and "I recall that AC is used for target T around 40-50C, and water coolers can go 10-20C lower".

## 1. Expected behaviour

- No cooler can cool a stream below its coolant's inlet temperature; the last kelvins cost unbounded surface. Plants keep an approach (process outlet less coolant inlet).
  - Air coolers: 10 to 15 K over the design dry bulb [HALL-R]. The design dry bulb is a summer figure, not exceeded on about 95 % of the year; on hotter days a water trim cooler takes what the air cannot [THERMO].
  - Water coolers: 10 K over the tower water by the classic rule (20 °F) [BLOG], and 5 to 10 K in current designs [PCONV]. The water rises about 10 K and returns at 45 °C (115 °F) at most [BLOG].
  - The world's column water side refuses heat within 5 K (`APPROACH_KELVIN`): a limit of the world, not a design figure.
- Game values:
  - Air is a fixed 25 °C (`HeatSides.AIR_TEMPERATURE`, owner Q11).
  - The world's cooling tower is calibrated at 35 °C water in and 25 °C out (a 10 K range), on 25 °C air at 50 % humidity, whose wet bulb is 17.9 °C.
  - Both coolants therefore enter at 25 °C. An air cooler is practical to 40 °C. A water cooler is practical to 35 °C, and reaches 30 °C at the tight 5 K end.
  - Plants design for a summer dry bulb of 30 to 40 °C, so their air coolers stop at 45 to 55 °C, and their water coolers at 35 to 45 °C, 5 to 15 K lower: the owner's figures. The game's constant 25 °C moves both floors down. The method is the same.
  - Below 25 °C a stream needs refrigeration. Even a large tower stops at the 17.9 °C wet bulb.
- Cooling water takes 23.9 kg/s (86 m³/h) per MW at a 10 K range. A 12 m tower cell supplies 288 kg/s, which is 12 MW.

## 2. What the code does

- The run: `AirCoolerUnit` cools to any target, 10 °C included, with 25 °C air. It refuses only a target above its inlet.
- An exchanger with a free side leaves its heat to a utility that has no temperature: any target passes, −50 °C included.
- The column's condenser and pumparound coolers do the same where their water side is free.
- Sizing: an air cooler reads its approach (`AIR_COOLER_OUTLET`, yellow under 15 K), and turns red only when its outlet is at or below 25 °C (infinite surface). A free-sided exchanger has no load at all, so a water cooler drawn that way is never sized or checked.
- The column door's condenser type (air or water) is a choice nothing checks.

## 3. Proposal T1 (revised)

1. Every cooling utility has a temperature.
   - Air comes in at 25 °C.
   - Tower water comes in at 25 °C and leaves at 35 °C.
   - This holds for the air cooler, for an exchanger's free cooling side, and for the column's condenser and pumparound free sides.
   - A wired cooling-water stream keeps its own temperature (the sample's CWS, 30 °C).
2. The run (physics, red): a cooler's outlet stays above its coolant's inlet.
   - An outlet or duty target below 25 °C stops at 25 °C, with a warning, as a heater's duty limit caps today.
   - A column whose condenser runs at or below its water warns. V3's own spec is not capped.
3. Sizing (practice, yellow): the approach rows are air 15 K (`AIR_COOLER_OUTLET`, as now) and water 10 K (`EXCHANGER_APPROACH`, the row every exchanger already reads). No new row.
   - A free water side gets a load: its surface against 25 to 35 °C water, and its water flow as a reading, in kg/s and m³/h.
   - Its water rises 10 K, less where the process inlet is cool. The return is 25 + min(10, max(T_in − 35, (T_in − 25)/4)) °C. It keeps 10 K at the hot end down to a 40 °C inlet, and never reaches the inlet. Added with section 6: `EXCHANGER_APPROACH` reads the least difference along the exchanger, so a fixed 35 °C return reads yellow on every inlet under 45 °C, and red (`EXCHANGER_HOT_END`) at 35 °C and under.
   - The 45 °C return row is unchanged.
4. Types, the method that tells them apart (section 3.10's rule: one function, one tile, and sizing proposes the type).
   - The air cooler tile becomes the Cooler tile. Sizing proposes Air while the outlet is at least 40 °C (air + 15 K), and Water below. Water is green from 35 °C, yellow below it, and red at 25 °C ("needs refrigeration").
   - A water cooler whose inlet stands above 40 °C reads the duty an air cooler ahead of it would take, down to 40 °C. Practice cools in air first and trims with water. The player draws that pair as two Cooler tiles, and sizing proposes each one by its own outlet.
   - Water is an exchanger with tower water on its tubes. Send to block stores AIR_COOLER or EXCHANGER, so the constructor builds the right one.
   - A key keeps the player's type, rated as a Fixed size is. The sheet's symbol follows the type.
   - The tile's id becomes `cooler`. An old `air_cooler` reads as a Cooler held on Air: an alias, so the corpus images stay valid.
5. The column block's condenser takes the same proposal, from its own temperature (the receiver's). The door's choice becomes the held type.

## 4. Other forms, risks, out of scope

- T2, two tiles: keep the air cooler and the water exchanger apart, and give only the utilities their temperatures and limits. It is smaller, but the player must know which to draw, and the sheet offers no proposal.
- R1, air and trim as one type: the Cooler tile splits its own duty at 40 °C, and Send to block stores two units. It automates the usual pair, but it needs a compound type and a threshold for when the air part is worth a unit of its own.
- Risk: sizing a free water side adds surfaces and lamps to every sheet with free sides (the sample's three pumparound coolers). Their numbers move only where the exchanger stood unsized.
- Out of scope: chilled water, refrigeration and a chiller tile (below 25 °C), and heating utilities (steam at a pressure). They would use the same rule, a utility with a temperature, later.

## 5. Against industrial practice (the owner's question, 2026-11-03)

| Point | Proposal | Practice | Verdict |
|---|---|---|---|
| Outlet above the coolant | Run caps at 25 °C | Physics; simulators flag a crossing with the utility | Matches |
| Air approach | 15 K | 10 to 15 K over the design dry bulb [HALL-R] | Matches, at the cautious end |
| Water approach | 5 K in the first draft | 10 K classic, 5 to 10 K current [BLOG], [PCONV] | Revised to 10 K (the existing row) |
| Water range and return | 25 → 35 °C, Should ≤ 45 °C | About 10 K rise, return ≤ 115 °F (46 °C) [BLOG] | Matches |
| Hot inlet to a target under the air floor | One type by the outlet | Air first, water trim [THERMO] | Revised: an air-ahead reading; R1 as the automated form |
| Design ambient | Constant 25 °C | A summer dry bulb, 30 to 40 °C [THERMO] | The game's climate (owner Q11); floors about 10 K lower than a plant's |
| Choosing the type | Temperature only | Temperature first, then water supply, power, plot and cost | A game-level screen, as section 3.10's heaters and tanks |

## 6. The column's condenser: air then water (owner, 2026-11-03)

The owner, approving the plan: "also allow air-then-water approach in column condenser configuration". Refinery overhead systems often run that way: an air condenser, then a water trim cooler.

- **Physics.**
  - The overhead condenses in air from the top down to the split, 40 °C (air + 15 K), and water trims it from there to the receiver.
  - The split is max(receiver, 40 °C), and never above the top vapour. A receiver at or above 40 °C leaves the trim no duty; a top vapour at or below 40 °C leaves the air none.
- **Facts.**
  - `ColumnComponents.Condenser` gains `AIR_THEN_WATER`, saved by name.
  - `Units` gains `trim`, 1 to 6, default 1.
  - Both are append-only, with a corpus image each.
- **World assembly** (`ColumnBlueprint`): the overhead link runs [ejector] → air cooler (the condenser slot, `units.condenser` banks) → water exchanger (a new slot `trim_condenser`, `units.trim` shells, the water condenser's own form `EXCHANGER_CONDENSER`: process on the shell, water on the tubes) → receiver. The condensate link starts after the trim.
- **World operation.**
  - `ColumnStarts` takes the water sides from the trim slot.
  - A solve records the water share of the condenser duty, the overhead flashed at the split on the worker. It is kept in `SplitTable`, in the topology state section (`FluidFacts.columnState`): state, so no compatibility work.
  - Each cycle owes that share to the water side, under the same `APPROACH_KELVIN` ceiling. The air share books to the totals, as an air-cooled condenser's does now.
- **Simulator.**
  - The column door's condenser takes three values.
  - `ColumnUnit` reads `condenser_air` and `condenser_water`. The water share goes to the condenser's water side (a link, or the tower-water utility), and the air share to the air.
  - Sizing sizes both members: the air cooler on its part, with its outlet at the split, and the exchanger on the trim. From a 40 °C split the trim's water returns at 30 °C (section 3 item 3), so both ends keep 10 K at a 35 °C receiver. The water is 47.8 kg/s per MW, twice the full range's.
  - Send to block stores both. The block draws the pair in series.
- **Proposal** (extends section 3 item 5):
  - Air while the receiver is at least 40 °C.
  - Air then water while the receiver is below 40 °C and the top vapour above it.
  - Water when the top vapour is at or below 40 °C.
  - A key keeps the player's choice.
- **GUI.** The calculator's and the constructor's condenser choice take the third key, and `AssemblyDiagram` draws the pair.
- **Decided.** The owner kept 40 °C (2026-11-03, "Keep 40 °C"). A 45 °C split would have put the air at 20 K and halved the trim's water.

## 7. Build order (one writer)

- C1, world: the enum value, `Units.trim`, the blueprint's trim slot, `ColumnStarts`, the water share in `SplitTable` and `ColumnOperation`, the calculator's and the constructor's choice, `AssemblyDiagram`, corpus images. Built 2026-11-03 (`97df0ff8c`); the constructor has no condenser choice of its own (it takes the imported design's), so nothing changed there.
- C2, simulator science and sizing: sections 3 and 6.
  - Coolant temperatures and the 25 °C cap.
  - Free water sides' loads and their water flow.
  - The Cooler tile and its alias.
  - The type proposals, including the condenser's three.
  - The air-ahead reading.
  - Built 2026-11-03 (C2 in the batch README's history): `CoolerUnit` and the free side's cap, `CoolerSizing` (the return rule, the two proposals, the air-ahead duty), tower-water loads in `ExchangerSizing`, the `cooler` tile with the `air_cooler` alias held on Air, `trim_units` on the door, `ColumnUnit`'s two shares, the `trim` member, and the idle twin member's `TWIN_IDLE` row (the owner's addendum, README C2 decisions).
- C3, screen: the Cooler door and its type keys, the column door's condenser choice, `ColumnBlock`, the palette, both languages, renders.

## 8. Utility regulation (owner, 2026-11-03)

The owner: "the equipment side should auto-regulate the utility flowrate based on what it gets. like if you have a cooling water coming at higher temperature the flowrate should be increased to match duty". Then: "if you have a process upset and the condenser duty changed, it should also auto-regulate to target condenser T". On the proposal for the simulator and the world: "ok", and "consider AC as well, it will use electricity later".

Expected behaviour. A plant's cooling-water side carries a temperature controller on the process outlet, whose valve opens until the water takes the duty: hotter water, or more duty, takes more water. Steam to a reboiler is set by the duty and leaves as condensate at its saturation. An air cooler holds its outlet with its fans (or louvers); the fan power follows the cube of the speed.

The rules (U1, the simulator):

1. Which sides regulate. A column heat side regulates when its inlet is wired straight to a sheet feed of water alone: the condenser's water pair (an exchanger, and air then water's trim) and each pumparound cooler's pair take cooling water (a liquid feed), the kettle's pair takes steam (a vapour feed). That side sets its feed's flow; the feed keeps its temperature, pressure and composition, and its typed amount is only the first guess. The run says the flow is set by the unit (an info note on the stream). Any other inlet keeps its own flow: crude through a pumparound cooler, water from another unit, a feed of the wrong phase. Stripping steam and the ejector's Motive port are no heat sides.
2. Cooling water. T_out = min(T_in + 10 K, ceiling), the ceiling the process side's cold end less 5 K (`HeatSides.WATER_APPROACH`): the receiver for the condenser and the trim, the pumparound's return (its draw less the design basis's fall) for a pumparound cooler. Flow = Q / (h(T_out) − h(T_in)), one function for the simulator and the world (`HeatSides.coolingWaterOutlet`). Water at or above the ceiling cannot hold the cold end: it keeps its typed flow, with a warning. The column's free water sides (tower water at 25 °C) take the same outlet for their load and water reading; other exchangers' and the Cooler's free sides keep section 3's rule.
3. Steam. Flow = Q / (h_in − h of saturated liquid at the side's outlet pressure), so the condensate leaves saturated. Steam that cannot boil the bottoms keeps its typed flow, with `column.reboiler_cold`.
4. Air coolers (the Cooler on Air, the column's air condenser, the air of air then water). On the size held, or the proposed size before the player sizes, the fans run at the least speed whose films (`HeatSides.airFilms`) remove the run's duty against 25 °C air, from natural draught (0: the louvers throttle, the fans stand still, no power) to full speed (1: a face short at full fans is already red on its surface row). The fan power is `HeatSides.fanPower` (1 kW per m² of face at full speed, times the cube of the speed). Sizing shows both beside the unit's rows, and the sheet's total fan power, for the electricity network to come.

The world (U2, the C1 thread). The condenser's water side regulates through a feed valve and flowmeter on its water line (the exchanger's W8 lines): each cycle the column sets the valve's mass flow to carry the duty it owes at the same T_out, its approach `ColumnOperation.APPROACH_KELVIN` pointing at `HeatSides.WATER_APPROACH`. With no valve it works as today, and a valve that cannot give the flow limits the column (LIMITED: condenser). The column's air condenser runs its fans at the speed its built face needs for the air's share, and reports the fan power; a duty beyond full fans limits the column, as a water shortage does, where today an air condenser of any size takes any duty. An outlet's booking difference goes to the trim's water below 40 °C, and to the air at 40 °C and above (the owner's "divide by temperature").

- U1, the simulator: rules 1 to 4. Built 2026-11-03 (U1 in the batch README's history, findings and decisions): `Port.utilityIn` and the compiler's hand-over of a feed of water alone, the column's utility calculations (cooling water and steam), `HeatSides.WATER_APPROACH` and `coolingWaterOutlet`, the column's free water sides on the same outlet, and the air coolers' fans (`AirCoolerSizing.fans`, `Sized.fanPower`).
- U2, the world: the paragraph above, after U1's report.

Sources: [HALL-R], [THERMO] and [BLOG] are in `SIZING_SOURCES.md`. [PCONV]: Minimum Approach Temperature Reference for Heat Exchangers, https://processconvert.pages.dev/engineering-references/heat-exchanger-minimum-approach-temperature-reference (cooling water 5 to 10 °C, air on the dry bulb's seasonal value).
