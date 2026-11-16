"""S2c GameTest 2 read-out: what the source's state before the letdown must have been, from mass conservation
between the final states the run printed (two equal one-block tanks), and the isentropic letdown from it."""
from CoolProp.CoolProp import PropsSI as P

fs = P('D', 'T', 325.22666908839483, 'P', 9429875.433958286, 'Nitrogen')
fr = P('D', 'T', 501.4464072010562, 'P', 9429875.433352945, 'Nitrogen')
r0 = P('D', 'T', 298.15, 'P', 5e5, 'Nitrogen')
s0 = fs + fr - r0
print(f"final source {fs:.2f} receiver {fr:.2f} kg/m3; receiver start {r0:.3f}; source start {s0:.2f} kg/m3")
t0 = P('T', 'D', s0, 'P', 2e7, 'Nitrogen')
print(f"source start at 20 MPa: {t0:.1f} K (a charge at 298.15 K would hold {P('D','T',298.15,'P',2e7,'Nitrogen'):.2f} kg/m3)")
# Adiabatic filling of a rigid empty tank from a 298.15 K line: u_tank = h_line.
h = P('H', 'T', 298.15, 'P', 2e7, 'Nitrogen')
print(f"rigid empty tank filled to 20 MPa from a 298.15 K line: {P('T','U',h,'P',2e7,'Nitrogen'):.1f} K")
# Isentropic blowdown of the gas left in the source, from the inferred start to the final pressure.
s = P('S', 'T', t0, 'P', 2e7, 'Nitrogen')
print(f"isentropic source end at 9.43 MPa from {t0:.1f} K: {P('T','S',s,'P',9429875.4,'Nitrogen'):.1f} K")
s = P('S', 'T', 298.15, 'P', 2e7, 'Nitrogen')
print(f"isentropic source end at 9.43 MPa from 298.15 K: {P('T','S',s,'P',9429875.4,'Nitrogen'):.1f} K")
