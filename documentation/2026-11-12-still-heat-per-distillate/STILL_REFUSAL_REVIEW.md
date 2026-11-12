# Batch still refused part way through a crude charge: review

Set-up: 500 L solid-fired pot still, built-in condenser, 0 stages, 60 % full of medium crude at 25 °C (259.5 kg, 1,156 mol), firing 1 (32.0 kW), run by `StillBatch` (`BatchStillTest.aMediumCrudePotStillRunsToItsLimit`).

## 1. Expected behaviour

- Per step the pot's balance is Q dt = d(N hL) + hV dD = (hV(y) − Σ yᵢ h̄ᵢ(T, z)) dD + N cp dT. The first term is the distillate's partial molar latent heat out of the pot liquid: always positive, 14 kJ/mol at 45 °C (light ends) rising to 47 kJ/mol at 339 °C. The warming term adds to it.
- About half of medium crude boils below 360 °C: the run goes on to OVERHEAT (350 °C) with 30 to 50 % of the mass distilled, every component and the energy ledger closed, the pot never cooling.

## 2. What the code did

`StillColumn.single` took hV(T, y) − hPot(T, z) as the latent heat and refused unless positive. That is the enthalpy of the light vapour less that of the heavy pot liquid, two mixtures. Per mole the heavy liquid's sensible heat outgrows the light vapour's, so the difference falls through zero while the pot still distils. Baseline (on `main`, one run):

| Law step | Pot °C | Moles distilled | hV(y) J/mol | hPot(z) J/mol | Difference | M_y kg/mol | M_z kg/mol |
|---|---|---|---|---|---|---|---|
| 78 | 208.27 | 0.2887 | 41,956 | 28,626 | 13,330 | 0.1296 | 0.2852 |
| 90 | 233.86 | 0.3685 | 55,781 | 51,077 | 4,704 | 0.1455 | 0.3049 |
| 94 | 243.59 | 0.3973 | 61,790 | 60,556 | 1,235 | 0.1519 | 0.3128 |
| 95 | 245.51 | 0.4030 | 63,032 | 62,503 | 529 | 0.1532 | 0.3144 |
| 96 | 246.75 | 0.4066 | 63,836 | 63,765 | 71 | 0.1540 | 0.3154 |
| 97 | 248.03 | 0.4104 | 64,680 | 65,087 | −407 | 0.1549 | 0.3165 |

At step 97 the split refused ("no positive latent heat"); the fallback, step 96's split, gave the bracket's seed budget / (e − hStart) with e − hStart = 63,836 − 65,087 < 0; the doubling loop doubled a negative bracket to −∞ and `fractions` threw "Invalid still amount". 41.0 % of the moles had distilled and 2.9 % vented (the parent's 20.2 % and 0.7 % are the same by mass). The rates, normalised per watt of that difference and multiplied back, were right while it was positive: it entered only the seed, the guards and the normalisation.

Found while building: the single stage's H1 − h0 is no latent heat either. The condensate's bubble point of y lands on the trivial K = 1 root, because y carries light ends (M_y 0.028 to 0.043 in steps 1 to 8) with no liquid at 1 atm near the pot's temperature: T_cond = T_pot − 1 K (the search's start) with H1 − h0 = 51 to 75 J/mol, then at step 8 T_cond = T_pot + 38 K and H1 − h0 = −3,069 J/mol.

The running world at this refusal (read, not run): `StillHead.run` returns `Failed`; `Stills.completed` (lines 414 to 419) keeps the last answer and appends "COLUMN: no positive latent heat" to the status line (461). A pot still's head is plain anyway (208): the pot keeps boiling on the network, its condensate and head temperatures frozen at the last answer (336, 454). No hold, no exception. With a column the flood cap (201) used F per watt of e − hPot: near the crossing it cut the firing to almost nothing, past it the cap was off.

## 3. The fix built and the options

Built (A): `StillColumn.Split`'s rates are per mole of distillate (a closed valve's per mole of top vapour, whose heat is H1 − h0); no duty argument, no "no positive ..." guards; `energyPerDistillate` stored. `BatchStill.boiled`: rates times the distillate's moles a second (closed: the heat over H1 − h0) for the flood and the condenser's need; the bracket starts at the budget over the balance's own slope at a 0.1 % trial (one more balance evaluation a pass: a warm bubble solve and an enthalpy), not at H1 − h0 as briefed (finding above). `StillHead`: F per watt over the condenser's duty.

After: OVERHEAT at 350.00 °C after 143 law steps, 41.8 % of the mass (66.4 % of the moles) distilled, 2.9 % of the moles vented, no column failure. Components closed within 1e-6 of the charge, heat = pot's change + what left within 1e-6, pot temperature never falling, no FLOODING. The pot's warming took 6.2 times the vapour's heat over the run (4.0 to 4.3 a step above 200 °C; the documents' 2.1 is a stabilised light crude's first step).

Pinned numbers: every law number agrees with `main` within 3.1e-8 relative (largest: a pot's warming, 1.2e-4 J of 3.85 kJ, inside the bubble search's own 1e-7 K), batches within 5.5e-10; the closed valve's F-factor moved 8.4e-6 (the old basis carried the R = 1e4 profile's (H1 − hPot)/R); the head's F per watt +2.1 % on naphtha at R 3 (now over (R + 1)(H1 − h0), was over e − hPot). No still test needed re-pinning.

Heat a mole of distillate (J/mol), per option, along pot-still runs at full firing (probe, `tmp/still-refusal/`):

| Charge, pot °C | old hV − hPot | A: balance | B: hV − Σ y h̄ | C: hV − hL(y) | H1 − h0 |
|---|---|---|---|---|---|
| crude 45 | 60,940 | 14,135 | 14,134 | 0 | 71 |
| crude 247 | 25 | 37,503 | 37,501 | 37,052 | 68,860 |
| crude 339 | −41,841 | 46,949 | 46,946 | 46,294 | 95,583 |
| naphtha 45 / 150 | 28,929 / 35,774 | 25,162 / 35,599 | 25,160 / 35,599 | 24,994 / 35,597 | 27,287 / 38,831 |
| hexane-toluene 82 / 110 | 31,879 / 33,346 | 29,516 / 33,155 | 29,517 / 33,155 | 29,569 / 33,155 | 31,226 / 33,469 |

- A: no extra enthalpy call a split; one balance evaluation a pass. The balance already holds B's quantity (A = B within 0.01 %).
- B: one more liquid enthalpy a split (Σ y h̄ is the derivative of N h along y − z: one-sided, step 1e-3), or two centred; a step to choose. It would keep a per-watt basis; it adds nothing the balance does not give.
- C: one more call a split; 1 to 2 % under B on crude, and 0 where y has no liquid root at the pot's temperature (crude at 45 °C): the division fails again.

For the owner: (1) the runtime's flood cap multiplies the pot's whole heat by F per watt of the condenser's duty. On crude the duty is 1.8 to 2.0 times the vapour's heat and the warming 3.9 to 4.3 times it (pot-still numbers above), so F is overstated 2.4 to 2.9 times and the cap trips at about 40 % of the firing that floods (naphtha: within 20 %). Option: take F from the pot's measured vapour flow. (2) The single stage's condensate of a vapour with light ends (trivial root) still sets the law's distillate enthalpy, the condenser's temperature and, in the world, which components condense. Option: give non-condensables their own share at the condenser, as `StillHead.divide` does by critical temperature.
