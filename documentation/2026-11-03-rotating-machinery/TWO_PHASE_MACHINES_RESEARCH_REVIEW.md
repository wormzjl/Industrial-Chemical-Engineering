# Two-phase machines: research review

Batch `2026-11-03-rotating-machinery`, 2026-11-04. Web research and a reading of the plan ([ROTATING_MACHINERY_PLAN.md](ROTATING_MACHINERY_PLAN.md) sections 1, 3, 14) and of `InletPhase`. Nothing was run in Gradle. Worked numbers: `tmp/rotating-machinery/two-phase/laws.js`. Format as [MACHINE_RESEARCH_REVIEW.md](MACHINE_RESEARCH_REVIEW.md).

Owner request 2026-11-04: remove the hard no-gas limit on pumps; pumps lose performance as gas rises; cavitation as a mechanism, with noise that changes with gas; a durability system that cavitation wears; the same for compressors with liquid.

Today: pumps refuse a suction above 2 % vapour by volume (resume below 0.5 %); compressors refuse above 1 % condensed by mass (resume below 0.2 %). The suction floor is the liquid's bubble pressure (D14, D16 C, D17); a supplier with no NPSH at no flow stands the pump vapour-locked (D15, D16 A). No NPSH-required curve (S2), no wear.

## 1. Centrifugal pump head with gas at the inlet

Expected behaviour. Small bubbles ride with the liquid and cost little. Past a critical inlet gas volume fraction (GVF) the bubbles coalesce into a gas pocket on the suction side of the impeller channels; the head falls in a step ("surging") and then on toward gas lock, where the pump makes almost no head and loses its prime [GP][MM].

- Onset depends on impeller type, speed, flow, gas density (suction pressure) and surface tension [GP]. Mixed-flow and semi-open impellers tolerate more than radial closed ones [GP][LB].
- Rules of thumb: 2 % free air costs about 10 % of capacity, 4 % about 43 % (Goulds, secondary) [BK]; most centrifugal pumps lose most of their performance between 6 and 12 % [PS]; ESP field limits 10 % free gas (radial) and 25 % (mixed flow) [SPE].
- Measured: a mixed-flow reactor coolant pump at about 2.5 bar suction falls sharply near 7 % void, to about 30 % of head past 10 % and 16 % at 30 % void; less degradation at the higher speed [KAERI]. The Semiscale MOD-1 radial pump, basis of the RELAP5 and TRAC multipliers, gives almost no head from 20 to 90 % void [L08]. Those multipliers depend on void fraction only; full-scale data showed a pressure effect that cannot be neglected [L08].
- Low pressure, low speed is the worst case: an ESP stage at 2 psig and 600 rpm surged at 0.12 to 1 % GVF; the surge moved 2.3 times higher from 600 to 1000 rpm, about 6 times higher with SF6 (about 5 times air's density), and lower at part load (0.48, 0.73, 1.03 % at 0.2, 0.4, 0.6 of the open-choke flow) [GP].
- Turpin et al. 1986 onset correlation (ESP, from Lea and Bearden's data): stable while `φ = 2000 (q_g/q_l) / (3 p_in[psia]) < 1`; critical gas-liquid ratio `r_T = 3 p_in/2000 = 0.0218 p_in[bar]`, `α_T = r_T/(1 + r_T)` [TU] (secondary, as quoted in [IR]). α_T is 2.1 % at 1 bar, 5.2 % at 2.5 bar, 9.8 % at 5 bar, 18 % at 10 bar, 30 % at 20 bar. It carries the gas-density effect in one line.

Recommendation, one form. A head multiplier on the liquid curve, with the onset from Turpin:

`H_2φ(Q, s) = H(Q, s) × M(α)`, `M(α) = 1 / (1 + (α/α_50)^4)`, `α_50 = 1.6 α_T(p_s)`.

- Q is the suction mixture volume flow, as today; the pressure rise `ρ_s g H_2φ` already takes the mixture density, so the rise carries a second factor (1 − α) on its own.
- Head share M (and rise share M(1 − α)): at 1 bar 0.89 (0.88) at 2 %, 0.34 (0.33) at 4 %, 0.01 at 10 %; at 2.5 bar 0.95 at 4 %, 0.66 at 7 %, 0.32 at 10 %, 0.03 at 20 %; at 10 bar 0.99 at 10 %, 0.81 at 20 %, 0.45 at 30 %. At the Turpin onset M is 0.87. The 1 bar row matches the Goulds 2 % / 4 % rule; the 2.5 bar row matches the KAERI pump to 10 % and falls faster beyond (radial is less tolerant than that mixed-flow pump).
- Options (assumptions the physics does not force): a speed factor `α_T × s` (Gamboa and Prado: 1.25 to 2.3 times for 1.67 times speed); a flow factor below the rated flow; a type factor for mixed-flow or helico-axial casings (25 % field limit, helico-axial to about 7 bar per stage [P2]). Recommended first: pressure only.
- Simplification T1: own vapour counts as gas. Condensable vapour recondenses as the impeller raises the pressure, so a real pump degrades less with its own vapour than with nitrogen; an equilibrium two-phase suction also has NPSHA close to its static lift only, so the cavitation law of section 3 binds first.

## 2. Positive-displacement pumps with gas

- The displaced volume fills with the mixture at suction pressure: liquid delivered ≈ (1 − α_s) × displacement, less slip. Sizing practice adds the gas volume at suction to the liquid flow; most PD designs handle entrained gas in large quantities, and below atmospheric suction small gas amounts matter a lot [WP].
- Twin-screw multiphase pumps run to about 95 % GVF without loss of flow, to 100 % for short periods with recirculation [P1][P2]. Progressive cavity pumps handle high entrained-gas fractions [HI3]. Reciprocating pumps gas-lock when the gas trapped in the clearance only compresses and re-expands, so the valves never open; larger clearance locks at lower GVF [SR].
- Clearance re-expansion has the compressor's form `η_v = 1 − c (r^(1/k) − 1)` applied to the gas share of the clearance (textbook form, not verified for pumps in this search).

Recommendation: the mod's set-flow law already counts suction mixture volume, so it delivers (1 − α_s) of its displacement as liquid with no change. Drop the refusal; no multiplier. Gas lock of reciprocating pumps is a notice only (simplification T2).

## 3. Cavitation: NPSH required and the head drop

- NPSH3: the NPSHA at which the first-stage head falls 3 %; the accepted rating point (HI 9.6.1) [HI][KSB]. NPSH0 (start of head drop) and NPSHi (first bubbles) lie above it. NPSHi/NPSH3 is typically 4 to 6 for normal designs [KN][SV], about 4 measured by acoustic emission [KN2]; it grows off design.
- NPSH3 against flow rises slowly toward the best efficiency flow and steeply beyond it [KSB]. Affinity: NPSH ∝ n² at a similar flow coefficient; suction specific speed `S = n √Q / NPSH3^0.75` stays constant with speed [PS2]. US values 6,000 to 15,000 (metric = US/51.6); many users cap 8,500 or 11,000 [PS2][BU].
- Below NPSH3 the head breaks down steeply: at a fixed NPSHA the head-flow curve turns down near vertical at the flow where NPSH3(Q) reaches NPSHA. The pump then delivers the flow its suction can feed. Today's bubble floor is exactly this cliff for a pump with NPSH3 = 0.
- Hot hydrocarbons and hot water measure a lower NPSH3 than cold water (thermodynamic effect) [KSB]; ignored here (T3).

Recommendation:

- `NPSH3(Q, s) = NPSH3_r × s² × (0.4 + 0.6 (q/s)²)`: 0.40 at shutoff, 0.55 at half flow, 1.26 at 120 %, 1.75 at 150 %. `NPSH3_r = (n_r √Q_r / S)^(4/3)` with S = 200 (metric, US 10,300); per eye for double suction. The pump of plan 1.1 (100 m³/h, 2950 rpm) needs 3.3 m; at 1475 rpm 1.3 m; 500 m³/h at 2950 rpm 9.7 m.
- Option A (recommended): the suction floor becomes `p_v + κ ρ_l g NPSH3(Q, s)` with κ = 0.85, the full-breakdown NPSH (an estimate; the 0 to 3 % head drop above it is ignored, T4). It stays in the D17 row on the trial point (the lesser of floor and supplier minus lift), smooth, no new mode. The floor rises with Q while the suction line's pressure falls with Q, so they cross once. At s → 0 or Q → 0 it returns to today's bubble floor. For the plan 1.1 pump it adds 22 kPa: it moves worlds whose pumps draw from boiling drums with little elevation (a campaign decides).
- Option B: floor unchanged; NPSH3 only feeds noise, wear and a notice. No world moves, but no cavitation mechanism in the flow.

## 4. Cavitation noise

- Bubble collapse makes broadband noise. Inception shows as high-frequency crackle, about 1 to 20 kHz; developed cavitation as 20 to 500 Hz rumble from large pulsating cavities [JA]. In a pump, a broadband rise with tones that track cavitation development (Čudina) [CU].
- Loudness rises from inception, peaks before fully developed cavitation, then falls fast; vibration follows the same curve; the noise maximum coincides with the erosion maximum [JA]. One pump study shows two peaks for two cavitation types [BME].
- Gas: injected air cushions the high-frequency components of noise and pressure [AR][SP]; but air also triggers cavitation at higher cavitation numbers and adds pulsation peaks at 65 to 170 Hz [JA]. Gas surging in the impeller makes irregular low-frequency surges [GP].

Recommendation for a sound designer, with σ = NPSHA/NPSH3 and α the inlet GVF:

- Crackle layer ("gravel in the pump", broadband, sharp transients): volume `I_c(σ) × g(α)`, I_c from section 5 (0 above σ = 4, 0.11 at 3.5, 0.47 at 3, 1.0 at 2, 0.79 at 1.5, 0.39 at 1, 0.08 at 0.5); gas cushion `g(α) = exp(−α/0.02)`: 0.78 at 0.5 %, 0.61 at 1 %, 0.37 at 2 %, 0.08 at 5 %.
- Churn layer (low, irregular surges, 50 to 200 Hz): rises with `α/α_T` from 0.5 to 1.5, fades toward gas lock, where the pump unloads and the motor note rises. Compressors: liquid knock for reciprocating machines (sharp metallic knocks), a droplet hiss for centrifugal ones.

## 5. Erosion, wear and pump life

- Gülich's erosion power (EPRI GS-6398, 1989; Centrifugal Pumps 2008 section 6.6, as quoted in [BW]): `P_ER = C1 (Δp/p_ref)³ (F_cor/F_mat) (L_cav/L_ref)^2.83 (a/a_ref) (α_ref/α_gas)^0.36 (ρ_v/ρ_v,ref)^0.44`, C1 = 5.4e-24 W/m² (suction side), Δp = p1 − p_v (the collapse drive), L_ref = 10 mm, α_ref = 24 ppm gas content; life = blade thickness share lost over the erosion rate. Erosion needs the cavitation intensity to beat the material's resistance [BW].
- Maximum erosion near NPSHA = 2 × NPSH3 (Monticello RHR pump, predicted maximum erosion zone) [BW]; reliability about doubles from margin ratio 1 to 4 [BU2]. Impeller life criterion 40,000 h (Vlaming, as quoted in [SV]); high suction energy pumps can erode their inlet vanes within months [BU2]. More gas content, less erosion (the 0.36 power above); air injection very effective against erosion [AR], best method on the Bratsk runners [SP].
- Gas without cavitation: surging gives vibration and rotor instability that load bearings and seals [FH]. Dry running (gas lock, power on): seal failure in seconds to minutes (vendor and forum figures, unreliable) [FH][NOV]. Low flow: recirculation; minimum continuous flow 25 to 40 % of best efficiency flow (MACHINE_RESEARCH_REVIEW [MF]).
- Plants detect cavitation by noise and vibration monitoring, NPSH margin checks and impeller inspection; underload (current) trips catch dry running [FH]; the repair is a new impeller, or a seal kit after dry running.

Recommendation. Health D, from 1 (new) to 0 (failed), integrated at commit on committed states, per online time:

- Cavitation: with `x = σ`, `I_c = (x/x*)³ ((4 − x)/(4 − x*))^2.83` for 0 < x < 4 and 0 otherwise, x* = 2.06 (σ_i = 4). It is Gülich's Δp³ L_cav^2.83 with Δp ∝ σ and L_cav growing linearly from inception; its peak at 2.06 lands on the measured maximum near 2 × NPSH3. `dD/dt = I_c g(α) / τ_c`, τ_c = 2 h online: life 2 h at the peak (6 Minecraft days), 5 h at NPSH3, 19 h at σ = 3.5, no wear above σ = 4.
- Gas surging: `dD/dt = clamp((α/α_T − 1), 0, 1) / 20 h`. Dry running (power on, M H at shutoff below the static lift, or vapour-locked): `1/(10 min)`. Below 30 % of rated flow: `1/(50 h)`.
- Effect: head and efficiency × (0.9 + 0.1 D); at D = 0 the pump trips and stands closed with a status (`IMPELLER FAILED`) until repaired.
- Options: a monotone I_c (largest at the floor) if the peak at σ = 2 reads wrong in play; a size factor (Δp³: a 10 m NPSH3 pump erodes about 27 times faster than a 3.3 m one); every τ in the config.

## 6. Compressors with liquid

- Centrifugal: wet-gas tests ran 0 to 5 % liquid volume fraction (LVF): adding liquid raises the pressure ratio, lowers polytropic head and raises absorbed power, so efficiency falls [BE12]; dry-gas performance parameters do not carry over [HU08][BR05]. Subsea wet-gas machines (Gullfaks, 2 × 5 MW) take 0 to 5 % LVF; 5 % LVF is the usual upper limit of wet-gas compression [TM]. Droplet erosion begins slowly and then accelerates, on blades, hub and the first impeller's shroud; smaller droplets and lower impact speed reduce it [PT].
- Fractions: at 50 bar natural gas with condensate (40 and 600 kg/m³), 1 % LVF is 13 % liquid by mass, 5 % LVF 44 %. Today's 1 % mass limit is 0.07 % LVF at 50 bar.
- Reciprocating: liquid in the cylinder is incompressible; when its volume exceeds the clearance at top dead centre, valves shatter and rods and pistons break within strokes; one gas station lost three pistons in a slug [OGJ][IIR]. A suction scrubber (knock-out drum) is standard; noise detection comes too late [OGJ].
- Screw: oil-flooded screws take liquid by design (not in the mod).

Recommendation:

- Centrifugal: homogeneous mixture. The head curve reads the suction mixture volume; the ratio formula of plan 3.2 uses the suction mixture density (so the ratio rises with liquid, as measured); power `ṁ_total H_p / η_wet`, `η_wet = η_p (1 − 0.5 w_l)` with w_l the liquid mass share (the factor 0.5 is an assumption, an option); the PH flash gives the discharge temperature and evaporation cools it. Wear `dD/dt = (w_l/0.01) / 100 h`; a wet-gas design option ten times slower. A `WET GAS` notice above 0.5 % LVF.
- Reciprocating (and the set-flow compressor mover): gas delivered = displacement less the liquid's volume. Valve wear `dD/dt = (LVF_s/0.001) / 2 h`. Liquid knock when `LVF_s ≥ 0.5 c` (c clearance, 0.08 to 0.15): D falls 0.5 and the machine trips (one event per slice).

## 7. Minecraft conventions (short)

- GregTech: multiblocks develop maintenance problems while running; each needs its own tool on the maintenance hatch (wrench, screwdriver, hammer, soft mallet, soldering iron, crowbar), tools lose durability, each problem costs 10 % efficiency (GT5 wiki), duct tape fixes all, an auto-maintenance hatch removes the chore [GT][GTCEU].
- Maintenance (SteelWorks, NeoForge 1.21.1): vanilla machines wear from 100 to 0 % per operation, stop at 0 with a metallic sound, a wrench repairs part per use at its own durability; presets and per-block rates in a server config [MT]. Create: Maintenance (1.20.1) adds wear to Create machines, repair drones automate it [CM]; Factory Tech uses consumable parts in quality tiers [FT].
- Create, Mekanism and Immersive Engineering themselves have no machine wear (author's knowledge, not a cited source).

Fit: wear only from a fault the player can see and fix (cavitation, gas, liquid, dry running), never from clean running; a gauge and the sound warn first; repair with a part (impeller, valve set, seal kit) in a few clicks; wear rates in the server config.

## 8. Implementation proposal (no code)

- Read once per solve on the step's start states (the D16 pattern): inlet α (`InletPhase.Supply.vapourVolumeShare`), p_s, w_l, health D. They fix M(α), α_T, η_wet and the health factor as numbers for the solve. The pump curve keeps its smooth row; M only scales H_r. No refusal, no hysteresis, no new mode: the 2 % / 0.5 % and 1 % / 0.2 % refusals go.
- In the row (option A): the floor `p_v + κ ρ g NPSH3(Q, s)` on the trial point, inside D17's lesser-of. Gas lock emerges: when M H at shutoff falls below the lift, the curve meets zero flow and the check valve closes it.
- The bubble floor is the NPSH3 → 0 end of the cavitation floor; vapour lock (D15/D16 A) stays as the NPSHA ≤ 0 at no flow end, closed for the solve.
- At commit: σ, I_c, the surge index and the wear rates from committed states; D integrates over the slice's online time. A trip at D = 0 is applied between slices.
- To the client: two bytes per mover in the presentation snapshot, on the engine's schedule: crackle `I_c g(α)` and churn (pumps) or knock (compressors), plus D for the screen. The client eases the volumes and never computes process state.
- Saves: `health` (double, 0 to 1) is a fact (history the engine cannot recompute, like ledger totals), on the machine's device registration, append-only, default 1.0 = no wear and full performance (the behaviour before the field). A corpus image under `src/test/resources/compat/`. Repair is a queued engine event; its acknowledgement lands on the schedule.
- Status line: `GAS` (α > α_T), `CAVITATION` (σ < 4 and I_c > 0.1), `WET GAS`, `LIQUID KNOCK`, `WORN` (D < 0.5), `FAILED`.

Owner choices: cavitation option A or B (section 3); speed and type factors on α_T (section 1); intensity shape physical or monotone, size factor, the τ values (section 5); the wet efficiency factor (section 6); the repair item (section 7).

## Sources

- [GP] Gamboa, Prado 2010, "Visualization study of performance breakdown in two-phase performance of an ESP", Proc. 26th Int. Pump Users Symposium, Texas A&M: https://www.911metallurgist.com/wp-content/uploads/2016/01/Visualization-Study-Of-The-Performance-Breakdown-In-The-Two-Phase-Performance-Of-An-Electrical-Submersible-Pump.pdf (reviews Murakami and Minemura 1974 [MM], Lea and Bearden 1980 [LB])
- [KAERI] Bae, Kim, Lim, Lee 2025, "Experimental study on head degradation in a reactor coolant pump for an SMR under two-phase flow", KNS Spring Meeting: https://www.kns.org/files/pre_paper/53/25S-338-배병건.pdf
- [L08] Lane et al. 2008, "Performance assessment of the two-phase pump degradation model in RELAP5-3D", Nucl. Technol. 161(3):277–285 (abstract): https://wx1.ans.org/pubs/journals/nt/a_3926 ; INL seminar: https://relap53d.inl.gov/seminars/westyellowstone2006/Shared%20Documents/17aumiller-pump.pdf (not readable here)
- [TU] Turpin, Lea, Bearden 1986, "Gas-liquid flow through centrifugal pumps: correlation of data", Proc. 3rd Int. Pump Symposium; as quoted in [IR] Iranzi et al. 2022, Appl. Sci. 12:2825: https://scholarworks.bwise.kr/hanyang/bitstream/2021.sw.hanyang/139301/1/applsci-12-02825-v2.pdf
- [SPE] ESP field limits 10 % / 25 %, from a search excerpt of SPE 81910-PA or 87627-MS (copies at petroleumengineers.ru, not read in full)
- [BK] https://b-k.com/index.php/properties-of-liquid/pumping-liquids-with-entrained-gas (secondary, Goulds) ; [PS] https://pumpsandsystems.com/how-reduce-or-eliminate-air-entrainment
- [P1] Yamashita et al., twin-screw pre-field trial: https://sites.google.com/site/petrosupu/article/1072-1 ; [P2] US8393876 (background): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8393876 ; [WP] Parker (Warren Pumps), PD pumps: https://www.scribd.com/doc/209831195/Pd-Pumps (secondary) ; [HI3] https://www.pumpsandsystems.com/progressive-cavity-pumps ; [SR] Scientific Reports Oct 2024, multiphase pump clearance and gas locking: https://doaj.org/article/5514f77069a94d2c8d2e6251cfe93dcb
- [HI] ANSI/HI 9.6.1: https://pumps.org/product/ansi-hi-9-6-1-rotodynamic-pumps-guideline-for-npsh-margin ; [KSB] https://www.ksb.com/en-global/centrifugal-pump-lexicon/article/npsh-1116954
- [KN] [KN2] KNS papers on NPSHi/NPSH3 (4 to 6 citing Gülich; about 4 by acoustic emission): https://kns.org/files/pre_paper/10/549이도환.pdf , https://kns.org/files/pre_paper/10/546이도환.pdf ; [SV] Schiavello, Visser, "Pump cavitation: various NPSHR criteria, NPSHA margins, and impeller life expectancy", Texas A&M Pump Symposium (via search excerpt)
- [PS2] Suction specific speed: https://pumpsandsystems.com/suction-specific-speed-part-one ; [BU] Budris: https://waterworld.com/wastewater-treatment/article/16191719/high-suction-energy-pumps-may-require-higher-npsh-margins-than-recommended ; [BU2] https://waterworld.com/water-utility-management/energy-management/article/16215849/sufficient-npsh-margin-important-to-pump-reliability
- [BW] Monticello RHR pump, "Task 4: operation in maximum erosion rate zone" (Gülich's formulae; Gülich, Centrifugal Pumps, Springer 2008, section 6.6; EPRI GS-6398, 1989): https://www.nrc.gov/docs/ML1230/ML12300A221.pdf
- [JA] Jablonská, Mahdal, Kozubková 2017, "Spectral analysis of pressure, noise and vibration velocity measurement in cavitation", Meas. Sci. Rev. 17(6):250–256: https://www.measurement.sk/2017/Jablonska.pdf ; [CU] Čudina 2003, "Detection of cavitation phenomenon in a centrifugal pump using audible sound", Mech. Syst. Signal Process. 17(6) (abstract): https://typeset.io/papers/detection-of-cavitation-phenomenon-in-a-centrifugal-pump-ucki9fu2c3 ; [BME] Periodica Polytechnica (scanned, not readable here): https://pp.bme.hu/me/article/download/6046/5151/9804
- [AR] Arndt, Ellis, Paul, air injection to mitigate cavitation erosion: https://www.sensorprod.com/?p=3703 ; [SP] aeration for erosion protection (Bratsk), Springer chapter: https://link.springer.com/chapter/10.1007/978-981-15-9785-5_3
- [FH] https://fluidhandlingpro.com/news/8-reasons-for-mechanical-seal-failure/ ; [NOV] https://nov.com/media/nov/files/products/caps/intervention-and-stimulation-equipment/mission-magnum-centrifugal-pumps/seal-guard-protection-system-flyer.pdf (vendor)
- [BE12] Bertoneri et al., GT2012-69819, "Measured performance of two-stage centrifugal compressor under wet gas conditions" (abstract): https://www.sciencegate.app/document/10.1115/gt2012-69819 ; [HU08] Hundseid, Bakken, Grüner, Brenne, Bjørge, ASME Turbo Expo 2008 (abstract): https://app.cristin.no/results/show.jsf?id=232984 ; [BR05] Brenne, Bjørge et al., 34th Turbomachinery Symposium 2005: https://oaktrust.library.tamu.edu/bitstream/handle/1969.1/163233/t34-12.pdf (not readable here)
- [TM] https://www.turbomachinerymag.com/view/what-the-subsea-compression-projects-in-norway-tell-us ; [PT] US10920788, liquid tolerant impeller: https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10920788
- [OGJ] Oil & Gas Journal, "Liquids entrainment 1: changing operating conditions lead to compressor damage": https://www.ogj.com/home/article/17221442/special-report-liquids-entrainment1-changing-operating-conditions-lead-to-compressor-damage ; [IIR] https://iifiir.org/en/fridoc/experimental-investigation-of-liquid-slugging-in-reciprocating-150269
- [GT] https://ftbwiki.org/Maintenance_Hatch_(GregTech_5) , https://ftb.gamepedia.com/Maintenance_Hatch ; [GTCEU] https://modrinth.com/mod/gregtechceu-modern/changelog ; [MT] https://www.curseforge.com/minecraft/mc-mods/maintenance ; [CM] https://metamods.net/en/mods/create-maintenance ; [FT] https://ftb.fandom.com/wiki/Factory_Tech_Machine_Maintenance
