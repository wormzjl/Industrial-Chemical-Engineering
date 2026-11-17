# Dynamic settling: plan (2026-11-17)

Class: PHYSICS (owner's options, with numbers). Lanes: fluid laws (`Separation`), runtime (the slice plan, the facts),
client (the drum page, the tank display), flowsheet (the sizer's rows, only with decision D2 B), fluid step (only with
decision D3 B). Nothing is coded. It follows batch `2026-11-17-three-phase-separator` and replaces its law's time form.

## 1. The request

Owner, 2026-11-17: "is it possible to implement dynamic settling in drums? for example we pump a well mixed oil/water in
a vertical drum and it settles after a certain period. This will be required when working on the stirred tank";
"The plan is basically longer residence time = better separation", "and simulated dynamically"; "write the plan, also
add some sort of level of separation to drum GUI. This needs to applicable to 3 phases"; "with sound scientific roots
of course".

## 2. What exists

- A vessel is one inventory at phase equilibrium. Which phases exist and how much of each is the flash's answer. How
  well they stand apart is not a state: it is read again every slice.
- Batch `2026-11-17-three-phase-separator`: at dispatch `Separation.plan` reads the last slice's flows and sets what
  each outlet carries (`Inventory.carried`): water in oil and oil in water where the settling number
  `N = v_s A / Q` is under 1, liquid in gas above Souders and Brown's speed. The vessel has no memory: a drum whose
  feed stops is clean on the next slice, and a drum that starts to be overloaded carries at once.
- The page shows the three carry-over meters (`DeviceColumn`, group Carry-over). The tank display draws an oil level
  and a water level (`TankDisplay`).
- A bottom port (`LIQUID`) of a vessel holding both liquids draws them at equal volume rates, whatever their layers
  (`PhaseDraw`, decision D11).

## 3. Physics

What happens in a real vessel, in the order the model takes it. Each law names its source; section 10 lists them with
what was checked.

1. **One drop.** A drop of the other liquid falls or rises at `v_s = g d² Δρ / (18 μ (1 + 0.15 Re^0.687))`: Stokes'
   law with Schiller and Naumann's drag correction. Built (`Separation.settling`). Drops: 0.5 mm of water in oil,
   0.2 mm of oil in water (owner, 2026-11-17).
2. **A layer in flow.** Hazen's ideal basin, as Camp wrote it: a drop is removed if it crosses the layer before the
   layer's liquid leaves. Removal depends on the plan area and the flow, not on the depth: `N = v_s A / Q`, all
   removed at `N` of 1 or more, the share `1 − N` left under 1. Built. In residence time: `N = τ / (h / v_s)`, the
   layer's residence time over the time a drop needs to cross it. Longer residence at the same depth is better
   separation.
3. **A layer in time.** Kynch's theory of sedimentation: in a uniform dispersion every drop moves at the same speed,
   so the dispersion keeps its concentration and loses height. A sharp front moves through it at `v_s` and leaves
   clear liquid behind. A layer of height `h`, filled turbid and left alone, is clear after `h / v_s`, linearly.
4. **2 and 3 are one model.** A layer holds a turbid band. Feed adds to it; the front removes `v_s A` of it per
   second; the outlet draws the layer's oldest liquid, which is clear until the band fills the layer.
   - `N ≥ 1`: the band never grows past one slice's feed. Clean, as today, exactly.
   - `N < 1`: the band grows by `Q − v_s A`, fills the layer after `V / (Q − v_s A)`, and from then the outlet draws
     `Q − v_s A` of turbid liquid: the carried share is `1 − N`. Today's law, as the steady state.
   - Feed stopped: the band shrinks by `v_s A` and is gone after at most `h / v_s`.
5. **A swarm of drops.** Drops in a concentrated dispersion hinder each other: `v = v_s (1 − φ)^n`, Richardson and
   Zaki, `φ` the dispersed volume fraction, `n` 4.65 at small Reynolds number falling to 2.39 at large. Henschke,
   Schlieper and Pfennig's batch-settling model uses the same swarm law for its sedimentation zone. Not built
   (decision D2).
6. **Which liquid is in drops.** In a mixture of two liquids the one with the larger volume share is continuous and
   the other is dispersed in it (phase inversion; between about 30 % and 70 % either can be, depending on history,
   which the model does not keep). Today's law takes every feed as mixed both ways instead (decision D1).
7. **Gas and liquid.** A mist drop at Souders and Brown's speed falls 0.61 m/s in the review plant's receiver: it
   crosses a vapour space of 1 m in under 2 s, less than one slice. The gas law therefore has no time form worth a
   state: it stays as built. Gas bubbles in a liquid are slow enough to matter (0.2 mm: 26 mm/s in naphtha,
   1.9 mm/s in a heavy crude, 17 min through 2 m), but no design bubble size has a source (decision D4).
8. **What the model leaves out.** In a real settler the drops that reach the interface must still coalesce with their
   own layer: a dense-packed zone forms there, and with small drops or surface-active compounds it, not the
   sedimentation, sets the time (Henschke et al.; Jeelani and Hartland). The band model is the sedimentation limit: its
   times are the shortest a real vessel can reach. No coalescence law has parameters the mod could read from its
   materials, so none is proposed. One drop size per dispersion; no drop growth.

### Numbers

Run with `tools/dynamic-settling/band.mjs` on the review plant's receiver (oil 666 kg/m3, 0.30 mPa s; water 984 kg/m3,
0.47 mPa s; water drops fall 43.7 mm/s in the oil, oil drops rise 10.4 mm/s in the water).

Steady flow, the band law against today's law, slices of 5 s:

| Layer | `N` | Today's law | Band law | Clean period after start, `V / (Q − v_s A)` | Band law |
|---|---|---|---|---|---|
| 1 x 6 m drum at 4 times the flow, boot | 0.874 | 0.1265 | 0.1265 | 332 s | 330 s |
| 2 x 5 m drum at 16 times, boot | 0.605 | 0.3951 | 0.3951 | 103 s | 103 s |
| 2 x 5 m drum at 128 times, oil layer | 0.800 | 0.0178 | 0.0178 | 71 s | 69 s |
| 2 x 5 m drum at design flow, oil layer | 102 | 0 | 0 | never carries | never |

The boot of the first row, after 20 min at that flow, is turbid throughout; with the feed stopped it is clear after
50 s (`h / v_s` 48 s).

A layer of 2 m filled turbid and left alone:

| Dispersion | `v_s` | Clear after `h / v_s` | With the swarm law (D2 B) |
|---|---|---|---|
| 9 % water in naphtha, 0.5 mm | 43.7 mm/s | 46 s | 59 s (`n` 3.0, factor 0.77) |
| 9 % oil in water, 0.2 mm | 10.4 mm/s | 193 s | 268 s (factor 0.72) |
| 10 % water in a heavy crude (900 kg/m3, 10 mPa s), 0.5 mm | 1.20 mm/s | 28 min | 45 min (factor 0.61) |
| 30 % water in the same crude, 0.5 mm | 1.20 mm/s | 28 min | 2.4 h (factor 0.19) |
| The same, stirred to 0.1 mm | 0.049 mm/s | 11 h | 60 h |

The last two rows are why the swarm law matters for a stirred tank: without it a 30 % emulsion settles as fast as a
1 % one.

## 4. Laws

L1. **State.** Each liquid layer of a vessel the liquids' law acts on holds a band: `turbid`, the volume of the layer's
own liquid that still holds drops (0 to the layer's volume), and `held`, the volume of the other liquid in those drops.
Two bands a vessel, four numbers. A vessel with no band is settled: today's vessels.

L2. **One slice.** Pure arithmetic at dispatch, on the last slice's flows; the step is not coupled and keeps its rows.
For a layer of own volume `V`, plan area `A`, drop speed `v`, own inflow `F`, own outflow `Q`, feed ratio `r`
(dispersed volume per own volume entering), over `dt` in 16 equal parts, each part in this order:
1. the front clears `s = min(turbid, v A dt)`: `held −= held s / turbid`, `turbid −= s`;
2. the feed arrives turbid: `turbid += F dt`, `held += F r dt`;
3. the outlet draws the oldest liquid: the clear volume is `V + F dt − turbid`; the turbid volume drawn is
   `X = min(turbid, max(0, Q dt − clear))`; it carries `held X / turbid`; both leave the band; `V += (F − Q) dt`.

The slice's carried ratio is the dispersed volume carried over the own volume drawn. 16 parts: with one part the clean
period reads 11 % short at a turnover of 0.1 layer volumes a slice, and the steady value is 47 % high above one
turnover a slice; with 16 parts the steady value is exact to four digits up to 5.7 turnovers a slice and the clean
period is within 3 % at slices of 5 s (9 % at 20 s).

L3. **What the outlet carries.** At dispatch for the next slice: (a) the band at the slice's start is the last slice's
start band advanced by L2 on the last slice's actual flows; (b) the outlet's carried ratio for the coming slice is the
ratio L2 gives from that band on the same flows, as today's law estimates the coming slice by the last. It goes to the
step as `Inventory.carried`, unchanged in form: `StepEquations` is not touched.

L4. **The flash rules.** A band never exceeds what the vessel holds: `turbid` at most the layer's own liquid, `held`
at most the vessel's volume of the other liquid. A layer the flash no longer holds loses its band.

L5. **The feed ratio `r`** (decision D1). A: both layers take every feed as mixed, `r = min(1, other in / own in)`
(today). B: the liquid with the smaller volume inflow enters as drops in the other; its own layer receives nothing
from the feed and grows by what settles.

L6. **The gas** stays the quasi-steady law of the separator batch (section 3, point 7).

L7. **The level of separation**, one definition for the three phases: the share of a phase's volume in the vessel that
stands in its own clear layer. Oil: `1 − turbid_oil / V_oil`, less the oil held as drops in the water. Water the same
way. Gas: `1 − liquid in gas`, the gas outlet's law. 100 % is a settled vessel; a vessel stirred through reads 0 % for
both liquids. A phase the vessel does not hold has no reading.

L8. **Saved** (decision D5): the bands are process state the engine cannot compute again. Default of a missing band:
settled.

L9. **The stirrer's hook**, for the stirred tank's batch, not built here: a running stirrer sets both layers turbid
throughout each slice and sets the drop size from its power (Hinze's balance of turbulence against interfacial
tension; a stirred tank's Sauter mean diameter falls with the Weber number to the power −0.6). The drop size then
becomes a band's third number. Interfacial tension is not a property the materials carry today: that batch's question.

## 5. Owner decisions

D1. **Which liquid is dispersed.**
- A (today): every feed is mixed both ways, up to volume for volume. An undersized boot on an oil-rich feed gives
  water with 13 % to 40 % of oil in it (section 3's table). Keeps every number of the separator batch.
- B (recommended): the minority liquid is in drops, the majority is continuous (section 3, point 6). On an oil-rich
  feed the oil carries water where its layer is too small, exactly as today, and the water leaves clean; on a
  water-rich feed the reverse. Moves: `SeparationDrawTest`'s undersized-boot case (oil in water becomes water in oil
  or nothing), the Carry-over meters of such drums. The sizer keeps both settling rows: practice sizes the water leg
  for 0.2 mm oil drops whatever the feed.
- Why B: a real undersized separator gives oil with a few percent of water and water with oil at the ppm level, not
  40 %; and D2 B is only possible with it.

D2. **Drops in a swarm.**
- A: one drop's speed (today). A 30 % emulsion settles as fast as a 1 % one: 28 min where the swarm law gives 2.4 h.
- B (recommended, with D1 B): Richardson and Zaki's factor on the band's own dispersed fraction. The review plant's
  receiver, 8 % water in its oil: factor 0.77, its `N` 102 becomes 79; every sized drum stays clean. Moves: the
  settling rows' readings on the sheet by that factor (never under 0.6 for a feed of at most 10 % water).
- B is not possible with D1 A: a boot fed "volume for volume" is a 50 % dispersion, factor 0.07, and the sized
  2 x 5 m drum's boot falls from `N` 9.7 to 0.67 and carries.

D3. **A standing drum's bottom outlet.**
- A: as today, both liquids at equal volume rates. The owner's example then settles on the page and in the tank
  display, and not at the outlet.
- B (recommended, as the batch's last package): the port draws the heavier liquid first where the vessel is settled
  and the band's dispersion while it is turbid. A standing drum holding 2.4 m3 of oil over 0.25 m3 of water then gives
  0.25 m3 of water and after it oil; today it gives 0.25 m3 of water with 0.25 m3 of oil, then oil. It changes
  decision D11 in the step; every world with a bottom port on a vessel of two liquids moves. One hold campaign of at
  most 200 seeds.

D4. **Gas bubbles in the liquids.** A: not modelled (recommended): no design bubble size has a source, and the liquid
lines and pumps would see gas. B: a third and fourth band, bubbles of a size the owner sets. The bands are a list, so
B can follow.

D5. **Saved.** A (recommended): with the vessel's facts, four numbers appended, default settled: a reload then does
not settle an emulsion that needs 11 h. B: not saved, as `Inventory.carried` today: a loaded world starts settled.

D6. **The page** (section 6): the owner picks from rendered sheets.

## 6. The drum page and the tank display

- A group Separation with one row a phase the vessel holds: Gas, Oil, Water, each its level of separation (L7) in
  percent. It answers "how far has this vessel settled" for the three phases at a glance; the Carry-over group stays
  and says what each outlet draws now.
- Sheets to render before any build (`GuiPrototypes`, both doors, scrolled to the end; cases: a settled lying drum,
  a standing drum half settled, a drum over its gas limit, a vessel with one liquid):
  - S-A: three bars, one a phase, in the phase colours of the Phases group, the percentage after each.
  - S-B: three meters (LED), as the Carry-over group's, no bars.
  - S-C: S-A's group, and on the glass a section of the vessel: gas, clear oil, the turbid band, clear water.
- The tank display (sight glass, tank render): the turbid band between the oil and the water as a blend of their two
  colours, from the band's volume through the vessel's geometry.
- Texts: `gui.icheme.fluid.device_group.separation`, `separation.hint`, `separation_gas`, `separation_oil`,
  `separation_water` and their hints, in `en_us.json` only.

## 7. Work packages

Order: 1, 2, 3, then 4 and 5 by the owner's decisions. Each package is one Opus thread with its own brief (owner,
2026-11-17: "delegate all implementation to opus"); this plan is the design. Before each, read `rules/physics.md`;
package 2 `rules/saves.md` and `rules/engine-schedule.md`; package 3 the `gui-creation` skill.

Blocked until batch `2026-11-16-solute-reaction-system` releases them: `PassiveNetwork.java`, `IslandSets.java`,
`PhysicalRegistry.java`, `FluidFacts.java` (packages 1b and 2). Package 1a touches none of them. Numbers to reserve on
`main` before the first code commit: the next facts schema (D5 A), the next protocol version (package 3).

1a. **The band law**, `science/fluid/network/Separation.java`, no vessel reads it yet.
   - `record Band(double turbid, double held)` with `NONE`; `record Slice(Band next, double carried)`;
     `static Slice advance(Band band, double volume, double area, double speed, double inflow, double ratio,
     double outflow, double seconds)`: L2, 16 parts, plain arithmetic, no `Math.pow`.
   - D2 B: `static double swarm(double speed, double drop, double viscosity, double density, double fraction)`,
     the exponent by Richardson and Zaki's steps on the single drop's Reynolds number, through `DeterministicMath.pow`.
   - `SettlingBandTest` on section 3's two tables: the steady ratios to 1e-4; the clean periods within 5 %; a batch
     clear within one part of `h / v_s`; a sized layer exactly 0 on every slice; a band never negative and never over
     its layer; L4's clamps; the swarm factors 0.77, 0.72, 0.61, 0.19.
   - Run: `./gradlew test --tests com.wormzjl.icheme.science.fluid.network.SettlingBandTest --offline --max-workers=4`.
   - Done: the test passes; nothing else changes; no other test is selected by `affected.mjs`.
1b. **The state.** `PassiveNetwork.Inventory` gains `Separation.Settling settling` (`record Settling(Band oil, Band
   water)`, `NONE`), as `carried` was added: the canonical constructor one longer, the older shapes delegating, every
   `with*` passing it, `withSettling`, in `equals` and `hashCode`.
2. **The slice plan.** `Separation.plan`: for each vessel `acts` gives the liquids' law, L3 (a) then (b), writing
   `settling` and `carried`; the gas as today. A null or empty last slice leaves the bands and plans from zero flows.
   - Keep the bands where an inventory is built anew: `IslandSets` (split and merge, two places),
     `PhysicalRegistry` (a re-specified vessel: kept while its geometry is the same, else settled);
     `ConservativeTransport` keeps them through `inventory().with(...)`. `PassiveStepSolver` line 1554 rebuilds
     generator nodes only: no change.
   - D5 A: `FluidFacts`, the vessel's inventory record, four doubles appended, schema bumped, a file of the older
     schema reads settled; `FORMATS.md` row; `FluidFactsTest` round trip and the older schema's read.
   - Tests in `SeparationDrawTest` (its interval fixtures): an undersized boot is clean for the first slices, then
     carries, and its ratio reaches today's law within 1 %; the feed stopped, the page's value falls to 0 within
     `h / v_s` plus one slice; a sized drum's graph is the same instance on every slice (`assertSame`), so every
     existing reference is untouched.
   - Run the classes of `node tools/merge-lane/affected.mjs select` (about 400, as the separator's package 2) once,
     and its 27 watch seeds with `refused=0`. No campaign.
   - Done: no reference moved but those D1 names; the report lists each with old and new value.
3. **The page and the display.** `FluidView.Tank` gains the three levels of separation (boxed, null where the vessel
   does not hold the phase) and the band's heights; `TankOutlets.tank` fills them from the inventory; `TankDisplay`
   draws the band; `DeviceMeters` and `DeviceColumn` the group of the sheet the owner picked; the protocol version;
   gates `DeviceColumnTest` (every case at every width, `assertInLine`), `DeviceMetersTest`, `FluidPacketCodecTest`;
   the sheets rendered from the shipped builder.
4. **The sizer** (D2 B only): `Separation.numbers` and `DrumSizing.rate` take the design feed's dispersed fraction;
   the settling rows read the swarm speed. Moved results listed with old and new values.
5. **The bottom outlet** (D3 B only): `PhaseDraw.order` for `LIQUID` on a vessel of two liquids, the mixed segment
   replaced by the settled order and the band's shares; one campaign of at most 200 seeds.

## 8. Ruled out

- A well-mixed layer (`dH/dt = feed − v_s A H / V − Q H / V`): its steady state is `1 / (1 + N)`, never clean: a
  drum sized for `N` of 10 would carry 9 %, against decision S1 A.
- A band that mixes with the clear liquid, a drop-size distribution, coalescence kinetics, the dense-packed zone:
  no parameter the materials carry (section 3, point 8).
- The settling as unknowns of the step: the step stays as it is; the bands are slice inputs, as `carried`.
- A time form of the gas law (section 3, point 7).
- Any reading of the salt: salt has nothing to do with phase carry-over (owner, 2026-11-17).

## 9. Pitfalls

- A slice that turns the layer over more than once: one part a slice is wrong by 47 %; keep the 16 parts (L2).
- The bands are labels over the flash's phases, not inventories: mass, components and energy are the step's, exact
  as today. Never move amounts between phases from a band.
- A vessel that takes no law (open, a kettle, a pot, a tower basin) has no band and must stay bit for bit.
- `IslandSets` and `PhysicalRegistry` build inventories with the short constructor: a band dropped there settles
  every drum when a player places a pipe.
- The carried ratio of the coming slice is an estimate from the last slice's flows, as today; the band itself is
  advanced on actual flows one dispatch later (L3). Do not advance it twice.
- `Separation.plan` returns the same graph instance when nothing changes: keep that for vessels whose band is `NONE`
  and whose flows give `NONE`.

## 10. Sources

| Law | Source | Checked |
|---|---|---|
| Drop speed | Stokes; Schiller and Naumann, VDI-Z. 77 (1935) 318 | Built and tested in the separator batch |
| Ideal basin, `N = v_s A / Q` | Hazen, Trans. ASCE 53 (1904) 45; Camp, Trans. ASCE 111 (1946) 895 | Secondary sources: removal by area and flow, not depth; the `v_s / v_0` share from memory |
| Sharp front in a uniform dispersion | Kynch, Trans. Faraday Soc. 48 (1952) 166, doi:10.1039/TF9524800166 | From memory; not read |
| Swarm settling, `(1 − φ)^n` | Richardson and Zaki, Trans. Inst. Chem. Eng. 32 (1954) 35 | 4.65 at small Reynolds number confirmed; the steps 4.35 Re^-0.03, 4.45 Re^-0.1 and 2.39 from memory: read before package 1a |
| Batch settling: sedimentation and dense-packed zones | Henschke, Schlieper, Pfennig, Chem. Eng. J. 85 (2002) 369, doi:10.1016/S1385-8947(01)00251-0; Jeelani and Hartland, AIChE J. 31 (1985) 711 | Citation and structure confirmed from secondary sources (it uses the Richardson and Zaki swarm law); the paper not read |
| Liquid-liquid swarm alternative | Kumar and Hartland, Can. J. Chem. Eng. 63 (1985) 368 | From memory; an alternative to Richardson and Zaki if the owner wants the liquid-liquid form |
| Design drops, 0.5 mm and 0.2 mm | Arnold and Stewart, Surface Production Operations, vol. 1 | 0.5 mm confirmed from secondary sources; 0.2 mm as the upper end of practice's 0.15 to 0.2 mm |
| Gas speed | Souders and Brown, Ind. Eng. Chem. 26 (1934) 98; GPSA | Built in the separator batch |
| Phase inversion by volume share | Selker and Sleicher, Can. J. Chem. Eng. 43 (1965) 298 | From memory; not read |
| Stirred drop size (L9, later batch) | Hinze, AIChE J. 1 (1955) 289; Chen and Middleman, AIChE J. 13 (1967) 989 | The Weber number form (power −0.6) confirmed; the constants not confirmed |

Papers worth the owner's fetch, in order of need: Richardson and Zaki (the exponent's steps, before package 1a with
D2 B); Henschke et al. (to state the left-out coalescence in its own terms); Chen and Middleman (the stirred tank).
