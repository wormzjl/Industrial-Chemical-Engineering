"""Reference tables of G3F11SupercriticalRegionTest (batch 2026-11-15-supercritical-fluids, S0, measurements M4 and M7).

Writes, with CoolProp 8.0.0 (HEOS backend, default configuration), next to the parity fixtures:
  viscosity-reference.json:        carbon dioxide, nitrogen, methane, ethane and propane at Tr 1.05, 1.2, 1.5 (reduced by
                                   the fluid file's STATES.critical) and 5, 10, 20, 30 MPa: CoolProp's viscosity, molar
                                   density and phase from a PT update, and the dilute viscosity at the same temperature
                                   and 1 kPa (diagnosis: the dilute part of the reference). Per fluid the critical molar
                                   density and the molar mass. Every state is above Tc, so single phase.
  mixture-density-reference.json:  methane-carbon dioxide, methane-ethane, nitrogen-methane (CoolProp's mixture model, the
                                   Kunz-Wagner GERG-2008 departure functions it carries for these binaries) at z of the
                                   first 0.25, 0.5, 0.75, T 250, 300, 350 K, P 5, 10, 20, 30 MPa: the PT update's phase,
                                   molar density and quality. CoolProp's mixture PT flash tests stability; states it
                                   reports two-phase are listed with phase "twophase" and no density, and the test does
                                   not score them.

Run from the worktree root (the venv of tools/coolprop-parity/README.md):
  tmp/coolprop-venv/Scripts/python.exe -I tools/coolprop-parity/generate_supercritical.py src/test/resources/science/thermo/coolprop
The output is deterministic for a given CoolProp build; it is written with LF line endings.
"""
import json
import sys
from pathlib import Path

# The fixtures' layout (one record per line); found beside this script, also under python -I.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import compact_json  # noqa: E402

import CoolProp
import CoolProp.CoolProp as CP

VISCOSITY_FLUIDS = ["CarbonDioxide", "Nitrogen", "Methane", "Ethane", "n-Propane"]
VISCOSITY_TR = [1.05, 1.2, 1.5]
VISCOSITY_P = [5e6, 10e6, 20e6, 30e6]
DILUTE_P = 1000.0

MIXTURES = [("Methane", "CarbonDioxide"), ("Methane", "Ethane"), ("Nitrogen", "Methane")]
MIXTURE_Z = [0.25, 0.5, 0.75]
MIXTURE_T = [250.0, 300.0, 350.0]
MIXTURE_P = [5e6, 10e6, 20e6, 30e6]


def header(generator_purpose):
    return {"generator": "tools/coolprop-parity/generate_supercritical.py (batch 2026-11-15-supercritical-fluids, S0)",
            "purpose": generator_purpose,
            "coolprop_version": CoolProp.__version__,
            "coolprop_gitrevision": CoolProp.__gitrevision__,
            "backend": "HEOS, default configuration; PT_INPUTS updates"}


def viscosity(out):
    fluids = []
    for fluid in VISCOSITY_FLUIDS:
        critical = json.loads((out / f"{fluid}.json").read_text(encoding="utf-8"))["STATES"]["critical"]
        tc = float(critical["T"])
        st = CP.AbstractState("HEOS", fluid)
        states = []
        for tr in VISCOSITY_TR:
            t = tr * tc
            dilute = CP.AbstractState("HEOS", fluid)
            dilute.update(CP.PT_INPUTS, DILUTE_P, t)
            for p in VISCOSITY_P:
                st.update(CP.PT_INPUTS, p, t)
                states.append({"Tr": tr, "T": t, "P": p, "phase": CP.PhaseSI("T", t, "P", p, fluid),
                               "viscosity": st.viscosity(), "Dmolar": st.rhomolar(),
                               "viscosity_dilute": dilute.viscosity()})
        fluids.append({"fluid": fluid, "Tcrit_file": tc, "rhomolar_critical": st.rhomolar_critical(),
                       "rhomolar_critical_file": float(critical["rhomolar"]), "molar_mass": st.molar_mass(),
                       "states": states})
    doc = header("M4: dense-fluid viscosity reference (Pa s) for the dilute and Jossi-Stiel-Thodos comparison")
    doc["dilute_pressure"] = DILUTE_P
    doc["fluids"] = fluids
    return doc


def mixtures(out):
    rows = []
    for first, second in MIXTURES:
        for z in MIXTURE_Z:
            for t in MIXTURE_T:
                for p in MIXTURE_P:
                    st = CP.AbstractState("HEOS", f"{first}&{second}")
                    st.set_mole_fractions([z, 1 - z])
                    row = {"first": first, "second": second, "z_first": z, "T": t, "P": p}
                    try:
                        st.update(CP.PT_INPUTS, p, t)
                        phase = st.phase()
                        if phase == CP.iphase_twophase:
                            row.update({"phase": "twophase", "quality": st.Q()})
                        else:
                            # The AbstractState's phase enum name without its prefix: "gas", "liquid", "supercritical...".
                            row.update({"phase": str(phase).split("iphase_")[-1], "Dmolar": st.rhomolar()})
                    except ValueError as e:
                        row.update({"phase": "refused", "message": str(e)})
                    rows.append(row)
    doc = header("M7: mixture molar density reference (mol/m3) of three binaries at pressure")
    doc["states"] = rows
    return doc


def write(path, doc):
    compact_json.write(path, doc)


def main(out_dir):
    out = Path(out_dir)
    v = viscosity(out)
    write(out / "viscosity-reference.json", v)
    print(f"viscosity-reference.json: {sum(len(f['states']) for f in v['fluids'])} states")
    m = mixtures(out)
    write(out / "mixture-density-reference.json", m)
    counts = {}
    for row in m["states"]:
        counts[row["phase"]] = counts.get(row["phase"], 0) + 1
    print(f"mixture-density-reference.json: {len(m['states'])} states {counts}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/science/thermo/coolprop")
