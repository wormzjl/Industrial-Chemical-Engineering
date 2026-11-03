# Coolers: air and water (proposal, 2026-11-03)

Batch 2026-11-02-process-simulator, after W10b (`b5eb35bc5`). Status: proposal; the owner chooses T1 (section 3) or T2 (section 4). The owner: "you need to find a method to differentiate water cooler and air coolers, usually AC cannot be used at lower target T. I think it's time to make a better model", and "I recall that AC is used for target T around 40-50C, and water coolers can go 10-20C lower".

## 1. Expected behaviour

- No cooler can cool a stream below its coolant's inlet temperature; the last kelvins cost unbounded surface. Plants keep an approach (process outlet less coolant inlet).
  - Air coolers: 10 to 15 K over the design dry bulb [HALL-R].
  - Water coolers: 5 K over the tower water in the world's own column water side (`APPROACH_KELVIN`). The water returns at 45 °C at most [BLOG].
- Game values:
  - Air is a fixed 25 °C (`HeatSides.AIR_TEMPERATURE`, owner Q11).
  - The world's cooling tower is calibrated at 35 °C water in and 25 °C out (a 10 K range), on 25 °C air at 50 % humidity, whose wet bulb is 17.9 °C.
  - So both coolants enter at 25 °C. An air cooler is practical to 40 °C and a water cooler to 30 °C: the owner's "40-50" and "10-20 lower" at the game's climate.
  - Below 30 °C a stream needs refrigeration. Even a large tower stops at the 17.9 °C wet bulb.
- Cooling water takes 23.9 kg/s (86 m³/h) per MW at a 10 K range. A 12 m tower cell supplies 288 kg/s, which is 12 MW.

## 2. What the code does

- The run: `AirCoolerUnit` cools to any target, 10 °C included, with 25 °C air. It refuses only a target above its inlet.
- An exchanger with a free side leaves its heat to a utility that has no temperature: any target passes, −50 °C included.
- The column's condenser and pumparound coolers do the same where their water side is free.
- Sizing: an air cooler reads its approach (`AIR_COOLER_OUTLET`, yellow under 15 K), and turns red only when its outlet is at or below 25 °C (infinite surface). A free-sided exchanger has no load at all, so a water cooler drawn that way is never sized or checked.
- The column door's condenser type (air or water) is a choice nothing checks.

## 3. Proposal

1. Every cooling utility has a temperature.
   - Air comes in at 25 °C.
   - Tower water comes in at 25 °C and leaves at 35 °C.
   - This holds for the air cooler, for an exchanger's free cooling side, and for the column's condenser and pumparound free sides.
   - A wired cooling-water stream keeps its own temperature (the sample's CWS, 30 °C).
2. The run (physics, red): a cooler's outlet stays above its coolant's inlet.
   - An outlet or duty target below 25 °C stops at 25 °C, with a warning, as a heater's duty limit caps today.
   - A column whose condenser runs at or below its water warns. V3's own spec is not capped.
3. Sizing (practice, yellow): the approach rows are air 15 K (as now) and water 5 K (new, after `APPROACH_KELVIN`).
   - A free water side gets a load: its surface against 25 to 35 °C water, and its water flow as a reading, in kg/s and m³/h.
   - The 45 °C return row is unchanged.
4. Types, the method that tells them apart (section 3.10's rule: one function, one tile, and sizing proposes the type).
   - The air cooler tile becomes the Cooler tile. Sizing proposes Air while the outlet is at least 40 °C (air + 15 K), and Water from 30 to 40 °C. Below 30 °C it stays Water, yellow, down to 25 °C, and turns red at 25 °C ("needs refrigeration").
   - Water is an exchanger with tower water on its tubes. Send to block stores AIR_COOLER or EXCHANGER, so the constructor builds the right one.
   - A key keeps the player's type, rated as a Fixed size is. The sheet's symbol follows the type.
   - The tile's id becomes `cooler`. An old `air_cooler` reads as a Cooler held on Air: an alias, so the corpus images stay valid.
5. The column block's condenser takes the same proposal, from its own temperature (the receiver's). The door's choice becomes the held type.

## 4. Other forms, risks, out of scope

- Two tiles (T2): keep the air cooler and the water exchanger apart, and give only the utilities their temperatures and limits. It is smaller, but the player must know which to draw, and the sheet offers no proposal.
- The water approach is 5 K or 10 K: 10 K is `EXCHANGER_APPROACH`'s figure for process exchangers, and puts the water floor at 35 °C.
- Risk: sizing a free water side adds surfaces and lamps to every sheet with free sides (the sample's three pumparound coolers). Their numbers move only where the exchanger stood unsized.
- Out of scope: chilled water, refrigeration and a chiller tile (below 25 °C), and heating utilities (steam at a pressure). They would use the same rule, a utility with a temperature, later.
