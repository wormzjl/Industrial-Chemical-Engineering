# Fluid conversion plan review

Date: 2026-11-12. Reviewed: the owner's plan for a converter between standard Forge (NeoForge) fluids and IChemE fluids (quoted in [README.md](README.md)). Basis: the code guide at `d647d331d` and source greps on `ccde84295` (0.27.1). No code changed; no test run.

Result: the plan describes the block and its GUI. It leaves out the physics of the boundary, how the converter fits the engine schedule, the rule semantics, and the save rules. The gaps follow, most important first. Each is a question the plan must answer before a build.

## 1. Physics and conservation

| # | Gap | What the plan must state |
|---|---|---|
| P1 | "1 mB = 1 L" has no reference state. Volume to moles needs a density. | The state at which the litre is measured: the rule's T and P forward, the tank's own state in reverse, with the package's liquid density. |
| P2 | A rule cannot impose a pressure on a vessel. A vessel's pressure is a result of its contents. | The rule sets the enthalpy of what enters (at the rule's T and P). The IChemE tank is open to the atmosphere, closed with a blanket or vent, or rigid. |
| P3 | No ledger booking. A conversion is a mass and energy source or sink, like the Generator and the Void. | Reverse conversion removes the components the Forge fluid does not carry and the T and P difference; the ledger books both, and the status line names them. |
| P4 | A round trip launders composition and heat. A fluid with 4 % C converts to the Forge fluid and returns as the rule's pure composition at ambient T: a free separator and cooler. | An owner decision: accept it, narrow the ranges, or charge for it (power, a loss). |
| P5 | Phase is not specified. | Whether only the liquid converts; what happens with gas, two liquids or solids in the tank; how a gaseous Forge fluid converts (1 L of gas is very few moles). |

## 2. Engine schedule

| # | Gap | What the plan must state |
|---|---|---|
| E1 | The two sides run on different clocks. A Forge fluid handler acts per tick on the server thread; an island advances in intervals on workers. | The hand-off: a planned rate per interval with the Forge tank as the buffer, queued as engine events. No process calculation on the tick. |
| E2 | The time factor. Process time runs `fluid.timeScale` (default 4) times play time. | Which clock "max conversion rate" uses, and how mB per tick and L per process second reconcile. |
| E3 | A held or fenced island. | Conversion stops, and the status says so. |
| E4 | Forge amounts are whole mB. | The fractional remainder is carried as a fact; otherwise mass leaks each interval. |

## 3. Rule semantics

| # | Gap | What the plan must state |
|---|---|---|
| R1 | Composition basis. | Mole, mass or volume fractions; a limit for the components a rule does not name. |
| R2 | Several rules can match one content. | A priority order, and hysteresis for a composition that sits on a bound. |
| R3 | A rule can name a compound outside the network package (`icheme:refinery`). | The rule is refused at load with a message. A forward rule names an existing preset (`medium_crude`) and not an inline composition. |
| R4 | The Forge side of a match. | Fluid id or tag; whether `FluidStack` data components are ignored. |
| R5 | A datapack reload removes or changes a rule while a tank holds its fluid. | Contents are kept and conversion stops (facts are never discarded). A rule carries a declared revision, not a hash. |
| R6 | The client needs the rules. | Sync for the GUI and for JEI or EMI. |

## 4. Balance mode

| # | Gap | What the plan must state |
|---|---|---|
| B1 | Balance needs a forward and a reverse rule for the same fluid, and contents inside the reverse rule's ranges. | What the converter does when one is missing. |
| B2 | "Balance" is not defined. | Equal fill fraction or equal volume, with a deadband. Without one it oscillates and launders heat on each swing (P4). |
| B3 | A direction or fluid change while the Forge tank holds another fluid. | One fluid at a time, and the rule for a change. |

## 5. Equipment and integration

| # | Gap | What the plan must state |
|---|---|---|
| Q1 | How the converter is built and scaled. | An equipment constructor kind or a plain block; the size tiers; nozzle sizes; which cells expose the Forge fluid capability; push, pull or passive; whether it draws FE. |
| Q2 | A new `EquipmentShape.Kind` has standing duties. | Its `FlowsheetCatalog` case (MODELLED or DRAWING_ONLY), the layout golden test, the drawings workflow. |
| Q3 | The control system's channels. | The list (both levels, rate, direction, mode) and which are writable. Alarms besides level: off specification, no rule, source empty, destination full. Level alarm thresholds are new facts; today's alarms are fixed `EquipmentAlarm` channels. |
| Q4 | KubeJS is an optional dependency with no Java integration in `src/main/java`. | Plain datapack JSON that a KubeJS script generates (no code), or a KubeJS plugin with its own event. |
| Q5 | The mod registers no Forge fluid, so every rule depends on another mod. The fluid survey is not done. | The mods and fluids checked for 1.21.1, by `c:` tag where one exists. Candidates: TFMG, PneumaticCraft, Immersive Petroleum, Immersive Engineering, Thermal, Mekanism, Ad Astra; vanilla water (an infinite free source) and lava (the package cannot represent it). |

## 6. Saves and verification

| # | Gap | What the plan must state |
|---|---|---|
| S1 | New facts: registration, settings, Forge tank contents, alarm thresholds, the remainder (E4). | A facts schema step with a corpus image. A Forge fluid whose mod was removed is kept, not dropped. |
| S2 | No test plan. | A conservation gate across a conversion, rule validation tests, a GameTest with a Forge fluid handler, GUI renders, the translation commit. |

## Decisions for the owner

P2 (tank type), P4 (laundering), P5 (phases), E2 (rate clock), B2 (what balance means), Q1 (build form and power), Q4 (KubeJS form).
