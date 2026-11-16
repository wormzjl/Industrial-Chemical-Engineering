"""Numbers for PD_PUMP_RESEARCH_REVIEW.md: a reciprocating pump's volumetric efficiency on a compressible fluid.

Run from the worktree root: tmp/coolprop-venv/Scripts/python.exe -I tmp/dense-pump-research/pd_numbers.py
eta_v = 1 - c (rho_d / rho_s - 1): the fluid left in the clearance c (a share of the displacement) at the discharge
re-expands along its isentrope to the suction pressure before the suction valve opens. CoolProp HEOS, isentropic step.
Also the work: the isentropic head h2s - h1 against the liquid form (P2 - P1) / rho_s.
"""
import CoolProp.CoolProp as CP

CASES = [
    ("Water", 300.0, 1e6, 21e6),
    ("CarbonDioxide", 268.0, 5e6, 25e6),
    ("CarbonDioxide", 290.0, 10e6, 30e6),
    ("CarbonDioxide", 310.0, 10e6, 30e6),
    ("CarbonDioxide", 310.0, 8.5e6, 25e6),
    ("CarbonDioxide", 320.0, 10e6, 30e6),
    ("Ethylene", 300.0, 8e6, 25e6),
    ("Nitrogen", 300.0, 15e6, 30e6),
    ("CarbonDioxide", 300.0, 1e6, 3e6),
    ("Nitrogen", 300.0, 0.1e6, 0.4e6),
]
print("fluid T1 P1 -> P2 | delta1 rho_s -> rho_d ratio | eta_v at c 0.1, 0.3, 1.0 | lock ratio needs c >= | gH / (dP/rho_s)")
for fluid, t1, p1, p2 in CASES:
    rho_c = CP.PropsSI("rhocrit", fluid)
    rho1 = CP.PropsSI("D", "T", t1, "P", p1, fluid)
    h1 = CP.PropsSI("H", "T", t1, "P", p1, fluid)
    s1 = CP.PropsSI("S", "T", t1, "P", p1, fluid)
    rho2 = CP.PropsSI("D", "P", p2, "S", s1, fluid)
    h2 = CP.PropsSI("H", "P", p2, "S", s1, fluid)
    t2 = CP.PropsSI("T", "P", p2, "S", s1, fluid)
    ratio = rho2 / rho1
    eta = [max(0.0, 1 - c * (ratio - 1)) for c in (0.1, 0.3, 1.0)]
    print(f"{fluid} {t1:.0f} K {p1/1e6:.1f} -> {p2/1e6:.1f} MPa | delta {rho1/rho_c:.3f} rho {rho1:.1f} -> {rho2:.1f} ({ratio:.3f}) T2s {t2:.1f} K | "
          f"eta_v {eta[0]:.3f} {eta[1]:.3f} {eta[2]:.3f} | c_lock {1/(ratio-1):.2f} | work share {(h2-h1)/((p2-p1)/rho1):.3f}")
