"""Numbers for DENSE_PUMP_RESEARCH_REVIEW.md: an isentropic pump step on a dense fluid against the mod's rho_s g H.

Run from the worktree root: tmp/coolprop-venv/Scripts/python.exe -I tmp/dense-pump-research/pump_numbers.py
For each case: suction density, discharge density and temperature on the isentrope (CoolProp HEOS), the isentropic
head gH = h2s - h1, the rise the mod's law gives for that head (rho_s g H), and the density of the fluid in units of
the critical density.
"""
import CoolProp.CoolProp as CP

CASES = [
    ("CarbonDioxide", 310.0, 8.5e6, 15e6),
    ("CarbonDioxide", 310.0, 10e6, 20e6),
    ("CarbonDioxide", 320.0, 10e6, 20e6),
    ("CarbonDioxide", 320.0, 15e6, 25e6),
    ("CarbonDioxide", 290.0, 10e6, 20e6),
    ("Nitrogen", 300.0, 15e6, 25e6),
    ("Ethylene", 300.0, 8e6, 16e6),
    ("Water", 300.0, 1e6, 11e6),
]
print("fluid T1 P1 P2 | delta1 rho1 rho2 | T2s dT | gH J/kg | mod rise MPa / real rise MPa = share")
for fluid, t1, p1, p2 in CASES:
    rho_c = CP.PropsSI("rhocrit", fluid)
    rho1 = CP.PropsSI("D", "T", t1, "P", p1, fluid)
    h1 = CP.PropsSI("H", "T", t1, "P", p1, fluid)
    s1 = CP.PropsSI("S", "T", t1, "P", p1, fluid)
    h2 = CP.PropsSI("H", "P", p2, "S", s1, fluid)
    t2 = CP.PropsSI("T", "P", p2, "S", s1, fluid)
    rho2 = CP.PropsSI("D", "P", p2, "S", s1, fluid)
    head = h2 - h1
    mod = rho1 * head
    print(f"{fluid} {t1:.0f} K {p1/1e6:.1f} -> {p2/1e6:.1f} MPa | delta {rho1/rho_c:.3f} rho {rho1:.1f} -> {rho2:.1f} kg/m3 | "
          f"T2s {t2:.2f} K (+{t2-t1:.2f}) | gH {head:.0f} | {mod/1e6:.3f} / {(p2-p1)/1e6:.3f} = {mod/(p2-p1):.3f}")
