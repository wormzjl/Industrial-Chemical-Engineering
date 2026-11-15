# coolprop-parity

Purpose: generate the CoolProp 8.0.0 parity fixtures for the Java Helmholtz reference oracle (a test-only port of
CoolProp's pure-fluid reference equations, `src/test/java/com/wormzjl/createcheme/science/thermo/reference`).

Batch: `2026-09-24-coolprop-low-temperature` (unified multiphase thermo plan, P1 item 1, gate G1, decision D4).

Output: `src/test/resources/science/thermo/coolprop/parity-<Fluid>.json` for Nitrogen, CarbonDioxide, Methane, Ethane,
Hydrogen and Water, and since batch `2026-11-15-supercritical-fluids` (S0) for n-Propane, Ethylene, n-Hexane, n-Heptane,
Toluene, n-Decane and n-Dodecane, with a supercritical grid for every fluid but water and, since the follow-up M8, a
compressed-liquid grid (Tr 0.5-0.95, 2-30 MPa) for CO2, N2, CH4, C2H6, C3H8, C2H4, n-hexane and toluene; and, from
`generate_supercritical.py`, `viscosity-reference.json` and `mixture-density-reference.json` for
`G3F11SupercriticalRegionTest`. The fluid files beside them (`<Fluid>.json`, `LICENSE`) are fetched from CoolProp revision
`ae81610e7d23efc57f9d051c8e70a4d66e87537f`, not generated; see the README in that directory.

## Environment

The throw-away `uv` environment of the batch (CoolProp 8.0.0, numpy):

    uv venv "$TEMP/coolprop-probe-venv" --python 3.12
    uv pip install --python "$TEMP/coolprop-probe-venv/Scripts/python.exe" CoolProp numpy

Check: `"$TEMP/coolprop-probe-venv/Scripts/python.exe" -c "import CoolProp; print(CoolProp.__version__, CoolProp.__gitrevision__)"`
prints `8.0.0 ae81610e7d23efc57f9d051c8e70a4d66e87537f`.

Since S0 the environment lives in the worktree's `tmp/` (the scratch rule), with its cache beside it:

    UV_CACHE_DIR=tmp/coolprop-venv-cache uv venv tmp/coolprop-venv --python 3.12
    UV_CACHE_DIR=tmp/coolprop-venv-cache uv pip install --python tmp/coolprop-venv/Scripts/python.exe CoolProp==8.0.0 numpy

## Run

From the worktree root (Git Bash), about one second:

    "$TEMP/coolprop-probe-venv/Scripts/python.exe" tools/coolprop-parity/generate_parity.py src/test/resources/science/thermo/coolprop

    tmp/coolprop-venv/Scripts/python.exe -I tools/coolprop-parity/generate_parity.py src/test/resources/science/thermo/coolprop
    tmp/coolprop-venv/Scripts/python.exe -I tools/coolprop-parity/generate_supercritical.py src/test/resources/science/thermo/coolprop

The output is deterministic for a given CoolProp build; it is written with LF line endings. The S0 regeneration left
`parity-Water.json` byte-identical and only appended `supercritical` rows to the other five earlier fixtures. The M8
regeneration only appended `compressed_liquid` rows (and two methane melting-line refusals) to the eight fixtures it
names; the other five are byte-identical.

## What each fixture holds

- `single_phase`: 11 temperatures (five from Tmin + 1 K to 0.95 Tc, six from 1.05 Tc to min(Tmax, 1000 K)) at 0.01,
  0.1, 1, 2, 5, 10 and 20 MPa, plus the dense and supercritical states of
  `research/2026-09-24-coolprop-low-temperature/pr78-vs-coolprop/probe.py`. Per state: CoolProp's phase string and its
  PT-flash Dmolar, Hmolar, Smolar, Cpmolar, Cvmolar and fugacity coefficient
  (`AbstractState.fugacity_coefficient(0)`; PropsSI has no keyed output for it), each value cross-checked against
  `PropsSI`; and `eos_at_Dmolar`, the same properties and the pressure from a `DmolarT_INPUTS` update at the density
  the flash reported. The two differ by up to 3.3e-6 (cp, ethane 0.08 K above Tc): CoolProp's PT flash does not
  return exactly its EOS at its own density.
- `saturation`: seven temperatures from Ttriple to 0.99 Tc plus the probe's saturation temperatures. Psat, rhoL,
  rhoV, hL, hV, sL, sV from the default QT flash (superancillaries on); `pV_eos`, CoolProp's EOS pressure at its
  saturated-vapour density; and, for diagnosis only, the iterative flash (`ENABLE_SUPERANCILLARIES` off). The
  superancillary Psat differs from `pV_eos` by up to 7.3e-8 at ethane's triple point.
- `refused`: grid states CoolProp refuses (below the melting line at Tmin + 1 K), with its message.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Generates the CoolProp 8.0.0 parity fixtures (`src/test/resources/science/thermo/coolprop/parity-*.json`) for the Java Helmholtz reference oracle in `src/test/java/.../science/thermo/reference` (commit `6ded7c7`); throw-away `uv venv` with CoolProp, no system install

Batch: 2026-09-24-coolprop-low-temperature

Status: Concluded 2026-10-19 (batch in 0.9.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `generate_parity.py`, `generate_supercritical.py`

### History

- In progress since 2026-09-24 (P1 item 1 done; fixtures regenerate byte-identically)
- 2026-11-15 (batch 2026-11-15-supercritical-fluids, S0): seven fluids, the supercritical grid, `generate_supercritical.py`
  (viscosity and mixture-density tables)
- 2026-11-15 (same batch, M8): the compressed-liquid grid of `G3F11SupercriticalRegionTest.m8CompressedLiquids` as rows
  of source `compressed_liquid`; every parity tolerance held unchanged

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.
