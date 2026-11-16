"""S2c GameTest 3 read-out: the carbon dioxide tank charged from a 310 K, 12 MPa line, and what it holds once it
stands at the voids' 11.5 MPa (its bottom outlet's head 6118 Pa over a one-metre level)."""
from CoolProp.CoolProp import PropsSI as P

h = P('H', 'T', 310, 'P', 1.2e7, 'CO2')
t = P('T', 'U', h, 'P', 1.2e7, 'CO2')
d = P('D', 'U', h, 'P', 1.2e7, 'CO2')
dc = P('rhocrit', 'CO2')
print(f"rigid empty tank filled to 12 MPa from 310 K: {t:.1f} K, {d:.1f} kg/m3, delta {d/dc:.3f}")
print(f"a charge at 310 K, 12 MPa would hold {P('D','T',310,'P',1.2e7,'CO2'):.1f} kg/m3")
# Isentropic letdown of what stays in the tank to the voids' pressure.
s = P('S', 'U', h, 'P', 1.2e7, 'CO2')
t2 = P('T', 'S', s, 'P', 1.15e7, 'CO2'); d2 = P('D', 'S', s, 'P', 1.15e7, 'CO2')
print(f"after the drop to 11.5 MPa: {t2:.1f} K, {d2:.1f} kg/m3, delta {d2/dc:.3f}; share drawn {(1-d2/d)*100:.1f} %")
rho = 6118.495217365018 / 9.80665
print(f"density from the bottom head over 1 m: {rho:.1f} kg/m3, delta {rho/dc:.3f}, T at 11.5 MPa {P('T','D',rho,'P',1.15e7,'CO2'):.1f} K")
