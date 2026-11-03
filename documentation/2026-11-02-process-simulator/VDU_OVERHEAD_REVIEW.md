# Where the column block's ejector stands: real practice

Batch 2026-11-02-process-simulator, after W10, 2026-11-03. The owner asked to "Find actual VDU flowsheet and see how people normally do it", after saying "The ejector does not generate vacuum alone, it works also by condensing the steam". Research only; no code changed.

## 1. Expected behaviour: how plants run

- A refinery VDU has no reflux condenser. The top pumparound condenses the oil inside the tower, so the overhead line carries the stripping and lift steam, cracked gas, the air that leaks in and a little light oil [P1].
- Every ejector discharges into a condenser. An intercondenser condenses the motive steam and the steam that its stage lifted, so the next stage lifts only gas. An aftercondenser follows the last stage. Three stages serve a 25 mmHg (3.3 kPa) flash zone [P1].
- There are two layouts. The top pressure and the steam decide which one [P1]:
  - Precondenser, on wet towers at 40 to 60 mmHg (5 to 8 kPa): a condenser at tower pressure comes first, then the ejectors. The floor is water's vapour pressure at the cooling water's temperature, about 50 mmHg on warm water (6.7 kPa; water saturates at 38 °C there). More ejectors cannot go lower.
  - Booster first, on dry or deep-cut towers at 5 to 20 mmHg (0.7 to 2.7 kPa): the first ejector takes the overhead directly. A large first intercondenser then condenses the stripping, lift and motive steam.
- The condensate drains through barometric legs (about 10 m) to a hotwell or seal drum. The oil is skimmed off and the sour water goes to the stripper. The gas goes to the heater's burners or to the flare [R, CP, SK].
- A vacuum column with a reflux condenser condenses at column pressure. Its vacuum is taken at the condenser's cold end or at the receiver's vapour outlet. The ejector then lifts the inerts and the vapour that saturates them [ET, P2].

## 2. What the block does

- Every column has a condenser, a receiver and a reflux line (owner answer Q2).
- World, `ColumnBlueprint`: the ejector stands on the overhead line, ahead of the condenser. This is the booster-first layout.
- Simulator, `ColumnUnit.ejector`: the ejector lifts the top tray's whole vapour, reflux included. V3 still solves the condenser at column pressure, and the motive steam condenses there into the free water. A condenser at column pressure cannot take the discharge of an ejector at 120 kPa, so the world layout and the simulator contradict each other.
- On W10's gate column (80 kPa top), the ejector lifts 94.6 kg/s with 47.2 kg/s of steam. That adds 121.5 MW to a 43.6 MW condenser. No plant sends its reflux vapour through an ejector.

## 3. Proposal, for the owner to choose

- **B+ (recommended): one layout for every column.** The condenser works at column pressure and drains to the receiver. The ejector takes the receiver's vapour outlet, and an aftercondenser at its discharge condenses the motive steam. The gas and that condensate leave through the offgas valve (the block's Overhead port).
  - B+ is the layout of every column with reflux, and of the precondenser VDU. At deep vacuum it also gives the booster's mass flow. Where the condenser's outlet is warmer than water's saturation temperature at the top (38 °C at 6.7 kPa), the stripping steam passes through the condenser and the ejector lifts it. No switch is needed.
  - Gate column: at 80 kPa, water condenses at 93.5 °C. The ejector then lifts only the receiver's gas, not 94.6 kg/s. W10 estimated this gas at about 1 % of the overhead. The steam falls in proportion.
  - Simulator: the load becomes V3's vapour product at the condenser. The Overhead port carries the ejector set's discharge, taken at the aftercondenser. The water product keeps only the receiver's free water. The aftercondenser's heat stays on the condenser's cooling side, as W10's motive heat does now (sub-choice: give it a cooling side of its own).
  - World: the ejector slot moves from the overhead link to the offgas link, with an aftercondenser slot after it. `ColumnStarts` refuses a column whose assembly is not complete. A column built with the ejector ahead of the condenser therefore stops until it is re-piped, though no fact is lost. Sub-choice: the matcher also accepts the old placement, which is a real deep-cut layout. This is recommended.
  - A column with no gas gives the ejector no load. Real ejector sets are also sized for the air that leaks in, which the game does not model.
- **A: keep booster first.** This is right for a deep-cut top with zero reflux. To be consistent, the condenser must work at the ejector's discharge pressure; V3 solves it at the top tray's pressure today. The reflux then returns through its valve and flashes on the top tray. With reflux, the ejector still lifts the reflux vapour.
- **C: a switch per column** between the two layouts. It matches both real layouts, but it adds a mode.

## Sources

- [P1] US 7,267,747, background: FIG. 1 precondenser systems; FIG. 2 deep-cut systems, with the primary ejector ahead of the condensers. https://patents.google.com/patent/US7267747
- [P2] US 8,524,046, "Distillation column pressure control": the steam ejector on the overhead receiver's vapour outlet. https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8524046
- [R] "VDU vacuum system revamp", PTQ: seal legs into seal drums, sour water to the stripper, waste gas burned in the vacuum heater. https://digitalrefining.com/article/1000713/vdu-vacuum-system-revamp
- [CP] "Prevent backflow into vacuum towers during upsets", Chemical Processing: the off-gas goes to the heater's firebox. https://www.chemicalprocessing.com/voices/plant-insites/article/55172967/prevent-backflow-into-vacuum-towers-during-upsets
- [SK] Schutte & Koerting, "Barometric leg defined". https://www.s-k.com/barometric-leg-defined/
- [ET] Eng-Tips, "Non-condensible in reflux condenser": vent at the cold end of the condenser shell and at the reflux drum. https://www.eng-tips.com/viewthread.cfm?qid=488047
- Further reading: Graham Corp., "Vacuum distillation unit ejector-condenser interplay", Hydrocarbon Processing, September 2025, https://graham-mfg.com/wp-content/uploads/2025/11/HP0925-Vacuum-distillation-unit-ejector-condenser-interplay-_-Sept-2025.pdf; "Fouling in VDU ejector systems", PTQ, https://digitalrefining.com/article/1002863/fouling-in-vdu-ejector-systems
