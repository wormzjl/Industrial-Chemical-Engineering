"""S2c GameTest 1 estimate: the closed CO2 loop's pressure once its hot leg (heater tube outlet half, the 13-pipe middle
line, the cooler tube inlet half) runs hot and the rest cold, for a charge of the whole loop from a line at T0, P0.
Volumes from LoopLayoutProbe: tank 1 m3, each tube side 0.109 m3, 45 pipe blocks (bore assumed 0.05 to 0.1 m)."""
from CoolProp.CoolProp import PropsSI as P
def brentq(f,a,b):
    fa=f(a)
    for _ in range(200):
        m=(a*b)**.5;fm=f(m)
        if (fm>0)==(fa>0):a,fa=m,fm
        else:b=m
    return (a*b)**.5
import math

def run(t0, p0, bore, th, tc):
    pipe = math.pi * bore**2 / 4
    tube = 0.1087
    hot = tube / 2 + 13 * pipe + tube / 2
    cold = 1.0 + tube / 2 + tube / 2 + 32 * pipe
    h = P('H', 'T', t0, 'P', p0, 'CO2')
    tf = P('T', 'U', h, 'P', p0, 'CO2')      # adiabatic fill of a rigid empty loop from the line
    m = (hot + cold) * P('D', 'U', h, 'P', p0, 'CO2')
    f = lambda p: hot * P('D', 'T', th, 'P', p, 'CO2') + cold * P('D', 'T', tc, 'P', p, 'CO2') - m
    p = brentq(f, 2e6, 80e6)
    dc = P('rhocrit', 'CO2')
    print(f"charge {t0:.0f} K {p0/1e6:.0f} MPa (filled at {tf:.1f} K), bore {bore} m, hot {th:.0f} K cold {tc:.0f} K: "
          f"P {p/1e6:.2f} MPa; hot delta {P('D','T',th,'P',p,'CO2')/dc:.2f}, cold delta {P('D','T',tc,'P',p,'CO2')/dc:.2f}")

for t0, p0 in [(300, 9e6), (295, 10e6), (295, 9e6), (300, 10e6)]:
    for bore in [0.05, 0.1]:
        for th, tc in [(345, 295), (355, 292), (330, 300)]:
            run(t0, p0, bore, th, tc)
