# Coolers: air and water (proposal, 2026-11-03)

Batch 2026-11-02-process-simulator, after W10b (`b5eb35bc5`). Status: proposal; the owner chooses T1 (section 3, revised after the practice check of section 5) or T2 (section 4). The owner: "you need to find a method to differentiate water cooler and air coolers, usually AC cannot be used at lower target T. I think it's time to make a better model", and "I recall that AC is used for target T around 40-50C, and water coolers can go 10-20C lower".

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

Sources: [HALL-R], [THERMO] and [BLOG] are in `SIZING_SOURCES.md`. [PCONV]: Minimum Approach Temperature Reference for Heat Exchangers, https://processconvert.pages.dev/engineering-references/heat-exchanger-minimum-approach-temperature-reference (cooling water 5 to 10 °C, air on the dry bulb's seasonal value).
