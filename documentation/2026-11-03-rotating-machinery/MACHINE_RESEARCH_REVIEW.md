# Machine research: review

Batch `2026-11-03-rotating-machinery`, 2026-11-03. Two web research threads (engineering models; Minecraft energy and Create) and a reading of the repository. Nothing was run. Worked numbers: `tmp/rotating-machinery/`. The plan that uses this: [ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md).

## 1. Engineering models (process level)

Centrifugal pumps:

- Affinity laws: Q ∝ n, H ∝ n², P ∝ n³. The usual `H = n² f(Q/n)` divides by n and fails at zero speed. The form `H = a n² + b n Q + c Q|Q| − k Q` (a > 0, b ≤ 0, c < 0, k > 0) has `dH/dQ ≤ −k` at every speed [W].
- A head that falls with flow at least by a bound everywhere gives one solution in any network of rising resistances [W]. Modelica Buildings 0.12 had no linear term and stalled at zero speed; 1.3 added `k = Δp_max/V_max × δ²/10` [B3]. EPANET floors the gradient near zero flow and switches a pump's status by rules [E2][E3].
- A quadratic through three falling points can still rise at low flow; Buildings uses a monotone spline extrapolated linearly [W][B1].
- EPANET's one-point default: shutoff 133 % of design head, maximum flow 200 % [E1]. Head rise to shutoff by specific speed: flat to +15–20 % for radial pumps, +35 % and more toward axial [JE]. API 610: a curve rising continuously to shutoff, at least 10 % above rated, needed for parallel service; preferred region 70–120 % of best efficiency flow [A610][DC].
- Use a smooth power or torque polynomial, not ρgQH/η, which is 0/0 at shutoff [M]. Radial pumps take their least power at shutoff (about 40–60 % of best efficiency power, estimate); axial pumps their most [KSB].
- At shutoff all shaft power heats the trapped liquid (45 kW into 50 kg of water: about 13 K a minute). Minimum continuous flow about 25–40 % of best efficiency flow, higher for high suction specific speed [MF].
- Simulators: EPANET (flow ≥ 0, status switching, a closed pump as a 1/CBIG resistance) [E2]; Modelica.Fluid (`(N/N0)² f(V N0/N)`, linear extrapolation, a rotational flange for motors and gears) [M]; DWSIM (sequential, curves read at the inlet flow) [DW]; HYSYS (curves at several speeds, curves extended into surge and stonewall in dynamics, one inertia per train) [HY].

Positive displacement:

- `Q = n V_d − Q_slip`, slip rising with Δp; a relief valve bypasses discharge to suction [VK]. A blocked PD pump with no relief has no finite solution.
- Reciprocating compressors: `ṁ = ρ_1 n V_swept η_v`, `η_v = 1 − c (r^(1/k) − 1)`; at c = 0.10, k = 1.3: 0.93, 0.87, 0.81 at ratios 2, 3, 4; delivery stops near r = 23 [TT]. Ratio per stage 1.2–4; discharge limits about 135 °C (hydrogen-rich) to 150 °C; speeds 300–750 rpm (slow) and 750–1200 rpm (high-speed separable) [GA][618][BH].

Centrifugal compressors:

- Polytropic head (ASME PTC 10 form) `H_p = Z R T_1/M × n/(n−1) × [(P_2/P_1)^((n−1)/n) − 1]`, `(n−1)/n = (k−1)/(k η_p)`; `Δh = H_p/η_p`; η_p 0.70–0.85 [GA].
- Map: H_p and η_p against inlet volume flow, one line per speed, collapsing roughly by the fan laws. Surge near peak head; the surge control line 10–15 % in flow to its right; the recycle valve strokes in under 2 s [BK][PGJ]. Choke: head falls steeply; mainly an efficiency loss [KZ].
- About 30 kJ/kg per impeller, tip speed about 310 m/s [TS]. At flow coefficient 0.1 and 300 m/s: 1 m³/s needs a 0.21 m impeller at about 28,000 rpm, 10 m³/s 0.65 m at 8,800 rpm (estimate).

Drivers:

- Induction motors: synchronous speed 120 f/p (3000, 1500, 1000, 750 rpm at 50 Hz). Rated slip 2–5 % at 1–100 kW, 0.5–2 % above [FB]. Breakdown torque 175–300 %, starting current 6–7 times [NM]. Speed time constant about 5 ms for an 1150 kW motor; motor-pump sets run up in 1–3 s, geared compressor trains in 5–20 s [DS][MS].
- IE3 minimum efficiency, 4-pole 50 Hz: 82.5 % at 0.75 kW, 90.4 % at 7.5 kW, 95.0 % at 75 kW, 96.0 % from 200 to 1000 kW [EU]. Variable-speed drives 95–97 % at full load [DOE1]. Synchronous motors and load-commutated drives for MW compressors [EP].
- Steam turbines: API 611 general purpose up to 48 bar, 400 °C, 6000 rpm, single stage typically to 3000 kW [611]; API 612 trip at 110 % of maximum continuous speed [612]; NEMA SM 23 trip 110–115 % [SM23]. Willans line `ṁ = ṁ_0 + a P` with no-load flow about 10 % [WL]. Steam rate 40 bar, 400 °C to 4 bar: 6.8 kg/kWh theoretical, about 9 (multistage) to 15 (single stage) actual [DOE2].

Shafts and gears (drawing only):

- `d = (16 T/(π τ))^(1/3)`; allowable shear 27.6 MPa for main power shafts, 41.4 for line shafts, 58.6 for short shafts [MH]. At 27.6 MPa: 100 kW at 3000 rpm 39 mm, 1 MW 84 mm, 1 MW at 250 rpm 192 mm.
- Gears 98–99 % per stage; API 613 for special-purpose parallel-shaft units [613][GE]. Critical speeds: a notice at most [CS].

Speed handling: speed frozen in a step and updated between steps keeps the Newton's size; speed as a Newton unknown risks folds (no root past breakdown) and stiff rows; an implicit inertia state is robust but couples flows and speeds in one larger Jacobian (as MOOSE and HYSYS dynamics) [MO][HY]. The plan takes the first, without inertia.

## 2. Forge Energy (FE) conventions

- FE, RF and IE's "IF" trade 1:1 in every mod checked. Only Mekanism defines FE in joules: `feConversionRate` 2.5 J per FE.
- EU: GregTech CEu 4 FE = 1 EU (1 EU = 10 J at Mekanism's rate); Modern Industrialization 2.3.2 1 EU = 10 FE.
- Create's electricity addons fix one rate between them: Crafts & Additions `fe_at_max_rpm` 480 at `max_stress` 16,384 su; New Age `suToEnergy` 0.0293 FE per su per tick ("matches C&A"). So 16,384 su ↔ 480 FE/t, 34.13 su per FE/t.
- Generators, FE/t: early 20 to 200 (Mekanism solar 20, heat 80; Thermal Stirling 40; IE water wheel about 25); mid 140 to 4,096 (Mekanism bio 140, C&A alternator 360, Powah niotic 2,000, IE diesel 4,096); late 4×10⁴ to 10⁸ (Powah nitro, Mekanism turbines). IE, Thermal and Powah figures come from 1.16 to 1.20 wikis.
- Machines, FE/t (Mekanism): crusher 20, compressor 40, purifier 80, crystallizer 160, digital miner 400, laser 4,000, nucleosynthesizer 40,000.
- Electric input of a motor, FE/t = P / (20 × J per FE):

| P | 2.5 J/FE | 10 J/FE | 17.1 J/FE |
|---|---|---|---|
| 10 kW | 200 | 50 | 29 |
| 100 kW | 2,000 | 500 | 293 |
| 1 MW | 20,000 | 5,000 | 2,930 |
| 10 MW | 200,000 | 50,000 | 29,300 |

- Parity with the Create addons: 20 × r_FE W ↔ 34.13 su, so r_su [W/su] = 0.586 × r_FE [J/FE]. At parity a loop FE → motor → Create → addon generator → FE returns at most the product of its efficiencies. Off parity, one direction gains energy.
- Recommendation: 10 J per FE with 5.86 W per su. 10 to 100 kW devices draw 50 to 500 FE/t, 1 to 10 MW 5,000 to 50,000 FE/t (late game, reachable).

## 3. NeoForge energy capability (1.21.1)

- `Capabilities.EnergyStorage.BLOCK`: `BlockCapability<IEnergyStorage, @Nullable Direction>`, id `neoforge:energy`. `IEnergyStorage` is int-based: `receiveEnergy`, `extractEnergy`, `getEnergyStored`, `getMaxEnergyStored`, `canExtract`, `canReceive`.
- Register in `RegisterCapabilitiesEvent` (`registerBlockEntity` or `registerBlock`); return null on faces without power; `level.invalidateCapabilities(pos)` when the handler changes.
- Producers and cables push into consumers every tick (C&A alternator: simulate, extract, receive on each face). A consumer exposes receive only; C&A's motor buffers about 10 ticks of full draw; C&A wires carry 1,000 or 5,000 FE/t.

## 4. Create 6.0.x kinetics (source, `mc1.21.1/dev`)

- A network's capacity is Σ capacity × |rpm| of its sources, its load Σ impact × |rpm| (`KineticNetwork`). Gearing changes rpm, not power. When the load exceeds the capacity, every member's speed reads 0.
- `CKinetics`: `maxRotationSpeed` 256 rpm (faster propagation destroys the block), `kineticValidationFrequency` 60 ticks.
- Sources (`AllBlocks`, `CStress`):

| Source | su/rpm | rpm | su |
|---|---|---|---|
| Hand crank | 8 | 32 | 256 |
| Water wheel | 32 | 8 | 256 |
| Large water wheel | 128 | 4 | 512 |
| Windmill bearing | 512 | 1 to 16 | 512 to 8,192 |
| Steam engine, per boiler level | 1,024 | 16 to 64 | 16,384 |
| Creative motor | 16,384 | up to 256 | up to 4,194,304 |

- Consumers (su/rpm): mechanical pump 4, press 8, mixer 4, crushing wheel 8, fan 2; shafts and gearboxes 0.
- Addon API: `BlockStressValues.IMPACTS.register(block, DoubleSupplier)` and `CAPACITIES` (the supplier may read our own config); `CStress.setImpact` refuses non-Create blocks. A consumer extends `KineticBlockEntity`, overrides `calculateStressApplied()` and pushes a change with `getOrCreateNetwork().updateStressFor(this, value)`; it receives the network's capacity and load in `updateFromNetwork`. A source extends `GeneratingKineticBlockEntity` (`getGeneratedSpeed`, `calculateAddedStressCapacity`, `updateGeneratedRotation`).

## 5. Water wheels and the stress rate

- Overshot and breastshot wheels: P ≈ 7000 Q H W at about 70 %; best speed 21/√D rpm (12 rpm at 3 m; Create uses 8). Undershot stream wheels: P ≈ 100 A v³ W at about 20 %.
- Built wheels (Cumbria): 4.0 m × 0.8 m at 100 L/s, 3.5 kW; 4.1 m × 1 m at 200 L/s, 6 kW.
- For Create's one-block-wide wheels: 3 m overshot 2.1 to 4.2 kW, 5 m 3.5 to 7 kW, undershot in a 1.5 to 2 m/s stream 0.17 to 0.4 kW.
- One boiler level takes 0.2 kg/s of water: about 543 kW of heat for 10 bar steam from 15 °C.

| Source | su | 5.86 W/su | 10 W/su |
|---|---|---|---|
| Water wheel, hand crank | 256 | 1.5 kW | 2.6 kW |
| Large water wheel | 512 | 3.0 kW | 5.1 kW |
| Windmill, 8 to 128 sails | 512 to 8,192 | 3.0 to 48 kW | 5.1 to 82 kW |
| Steam engine per boiler level | 16,384 | 96 kW (18 % of its heat) | 164 kW (30 %) |
| Level-18 boiler | 294,912 | 1.73 MW | 2.95 MW |
| Creative motor at 256 rpm | 4,194,304 | 24.6 MW | 42 MW |

- The hand crank (256 su) is 20 to 34 times a person's sustained 75 W at these rates: a gameplay exception.

## 6. Create as an optional dependency

- `neoforge.mods.toml`: `type = "optional"` with the version range; if present, Create must be in range.
- Gradle: Create, Ponder, Flywheel and Registrate on `compileOnly`; their runtime jars on the MDK's `localRuntime`, which dependents do not pull.
- Class isolation: no Create type in a field, signature, lambda or generic of a class that always loads. One guarded entry point, `if (ModList.get().isLoaded("create")) CreateCompat.init(modBus)` (Create's own pattern for CC: Tweaked; Supplementaries does the same for Create).
- Registration: a block that must implement Create's `IRotate` lives in the compat package and registers only with Create; a world opened without Create loses it, as it loses every Create block.
- Data: recipes with Create items take `neoforge:mod_loaded` conditions; tag entries for Create items use `"required": false`.
- This repository: Create is required in the toml; Catnip's `Outliner` is imported by `EquipmentGhost` and `PipeLayingClient`; `equipment_constructor.json` and `equipment_interior.json` use Create textures; one GameTest calls `BlockMovementChecks`; `data/create/tags/block/non_movable.json` is inert without Create. KubeJS is required and unused.

## 7. Drawing high speeds

- Create draws the true speed (`KineticBlockEntityRenderer`: angle = time × rpm × 0.3°; Flywheel's `SPEED_MULTIPLIER` 6). Only its 256 rpm cap limits it: 25.6° per frame at 60 fps.
- A part with n-fold symmetry looks right while 6N/f < 180°/n, so N < 30 f/n: at 60 fps, 1,800 rpm for n = 1, 450 for n = 4. 3,000 rpm shows as −600 rpm; 3,600 rpm stands still.
- Strategies: a visual speed cap (100 to 250 rpm), housings round fast parts (as real machines have), speed on gauges and in sound, and an angle integrated on the client with an eased speed, so slow server updates do not make it jump.

## Sources

Engineering (tags as cited in section 1; "secondary" marks forum, blog or Scribd copies):

- [W] Wetter 2013, Building Simulation 2013, p. 3505: https://www.aivc.org/sites/default/files/p_1040.pdf
- [B1] [B2] [B3] Modelica Buildings movers: https://simulationresearch.lbl.gov/modelica/releases/latest/help/Buildings_Fluid_Movers_UsersGuide.html , https://simulationresearch.lbl.gov/modelica/releases/latest/help/Buildings_Fluid_Movers_BaseClasses_Characteristics.html , https://simulationresearch.lbl.gov/modelica/releases/v4.0.0/help/Buildings_Fluid_Movers_BaseClasses.html
- [M] Modelica.Fluid Machines: https://raw.githubusercontent.com/modelica/ModelicaStandardLibrary/master/Modelica/Fluid/Machines.mo
- [E1] [E2] [E3] EPANET: https://tbn2net.com/help/SOLIDOSen/EpanetPumpCurve.html , https://epanet-manual.readthedocs.io/en/latest/analysis_algorithms.html , https://github.com/OpenWaterAnalytics/EPANET/blob/dev/src/hydcoeffs.c
- [DW] DWSIM pump and compressor: https://dwsim.org/api_help/html/T_DWSIM_UnitOperations_UnitOperations_Pump.htm , https://dwsim.org/api_help/html/T_DWSIM_UnitOperations_UnitOperations_Compressor.htm
- [HY] HYSYS dynamics (secondary): https://es.scribd.com/doc/268653783/11-5913-FAQ-Activated-Dynamics-FINAL , https://www.cheresources.com/invision/topic/21288-how-to-input-the-inertia-of-compressor-to-hysys-for-dynamic-simulation
- [MO] MOOSE shaft-connected pump: https://mooseframework.inl.gov/source/components/ShaftConnectedPump1Phase.html
- [JE] https://rcworst.com/pages/centrifugal-pump-efficiency-part-5-curve-shape-breadth-of-efficiency ; [KSB] https://www.ksb.com/en-global/centrifugal-pump-lexicon/article/characteristic-curve-1117926
- [A610] (secondary) https://www.scribd.com/document/772804401/API-610-Basic-Pumps-Design-Requirements , https://www.eng-tips.com/threads/10-head-rise-to-shutoff.380159 ; [DC] https://www.datacor.com/resources/api-and-hi-pump-operating-range
- [MF] https://911metallurgist.com/wp-content/uploads/2016/01/Determining-the-Minimum-Safe-Flow-Line-for-a-5-Stage-350-HP-Variable-Speed-Centrifugal-Pump.pdf ; [VK] https://www.axflow.com/en-lv/catalog/products/pumps/viking-456/
- [GA] Gallick, Phillippi, Williams 2005: https://www.turbomachinerymag.com/view/centrifugal-vs-reciprocating-compressor ; [BK] https://turbomachinerymag.com/view/myth-centrifugal-compressor-maps-surge-and-choke ; [KZ] https://www.turbomachinerymag.com/how-dangerous-is-compressor-operation-at-choke-conditions ; [PGJ] https://pgjonline.com/magazine/2012/march-2012-vol-239-no-3/features/protecting-a-centrifugal-compressor-from-surge ; [TS] (secondary) https://www.eng-tips.com/viewthread.cfm?qid=449993
- [TT] https://help.technicaltoolboxes.com/wiki/pipeline-hub-user-resources/pipeline-toolbox-user-guides/pipeline-compressors/reciprocating-compressor-cylinder-equivalent-capability-horsepower ; [618] (secondary) https://eng-tips.com/threads/max-disch-temp-for-h2-rich-service.98565 ; [BH] https://bakerhughes.com/sites/bakerhughes/files/2021-03/BakerHughes_ReciprocatingCompressors_Overview-030321.pdf
- [FB] https://www.fabrico.io/blog/motor-slip-induction/ ; [NM] https://petrowiki.spe.org/NEMA_motor_characteristics ; [DS] https://bepltd.com/cdn/shop/files/400.043TECCB3-IE3.pdf ; [MS] https://electrical-engineering-portal.com/calculation-of-motor-starting-time-as-first-approximation
- [EU] IEC 60034-30-1 limits (EU 2019/1781 Annex I): https://gesetze.legal/eu/vo_eu_2019_1781/anhang_i ; [DOE1] https://www.energy.gov/sites/prod/files/2014/04/f15/motor_tip_sheet11.pdf ; [EP] https://infoscience.epfl.ch/record/131546
- [611] https://standards.globalspec.com/std/1271154/api-std-611 ; [612] https://cdn.standards.iteh.ai/samples/18499/fae41667f23b4a9a891b3b0d5e456db3/ISO-10437-1993.pdf ; [SM23] https://www.woodward.com/products/publication-asset/04038 ; [WL] https://en.wikipedia.org/wiki/Steam_turbine_governing ; [DOE2] https://www1.eere.energy.gov/manufacturing/tech_assistance/pdfs/steam21_rotating_equip.pdf
- [MH] Machinery's Handbook: https://online.flippingbook.com/view/954192180/325/ ; [613] https://standards.globalspec.com/std/14396632/std-613 ; [GE] https://mdpi-res.com/d_attachment/machines/machines-09-00264/article_deploy/machines-09-00264-v3.pdf ; [CS] https://www.magneticbearings.org/app/uploads/publications/ismb16/ismb16_008.pdf

Minecraft (sections 2 to 7):

- Create `mc1.21.1/dev`: https://github.com/Creators-of-Create/Create/tree/mc1.21.1/dev (`AllBlocks`, `CStress`, `CKinetics`, `BlockStressValues`, `KineticNetwork`, `RotationPropagator`, `KineticBlockEntity`, `GeneratingKineticBlockEntity`, `KineticBlockEntityRenderer`, `PoweredShaftBlockEntity`, `BoilerData`, `compat/Mods`); addon setup: https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1
- Crafts & Additions: https://github.com/mrh0/createaddition/tree/1.21.1 ; New Age: https://gitlab.com/antarcticgardens/create-new-age/-/tree/1.21.1 ; Mekanism: https://github.com/mekanism/Mekanism/tree/1.21.x
- GregTech CEu: https://github.com/GregTechCEu/GregTech-Modern/blob/1.20.1/src/main/java/com/gregtechceu/gtceu/config/ConfigHolder.java ; Modern Industrialization 2.3.2: https://modrinth.com/mod/modern-industrialization/version/2.3.2 ; Industrial Foregoing: https://github.com/InnovativeOnlineIndustries/Industrial-Foregoing/tree/1.21
- Older wikis (1.16 to 1.20): https://github-wiki-see.page/m/EnigmaticaModpacks/Enigmatica6/wiki/Immersive-Engineering , https://m.ftbwiki.org/Stirling_Dynamo , https://www.minecraft-guides.com/mod/powah-rearchitected/
- NeoForge 1.21.1 docs: https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/ , https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/ , https://docs.neoforged.net/docs/1.21.1/resources/server/conditions/ ; source: https://github.com/neoforged/NeoForge/tree/1.21.1
- Supplementaries' Create compat: https://github.com/MehVahdJukaar/Supplementaries/blob/1.21/common/src/main/java/net/mehvahdjukaar/supplementaries/integration/CompatHandler.java
- Water wheels and human power: https://en.wikipedia.org/wiki/Water_wheel , https://jonathan-smith-n15k.squarespace.com/hydro , Quaranta and Revelli, RSER 97:414–427 (2018), https://iris.polito.it/handle/11583/2712517 , https://en.wikipedia.org/wiki/Human_power ; aliasing: https://en.wikipedia.org/wiki/Wagon-wheel_effect
