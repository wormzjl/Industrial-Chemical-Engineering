package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The turbine law of plan section 3.4 on the example of section 1.3: 40 bar and 400 °C (0.07341 m³/kg) to 4 bar, where the
 * steam tables give a drop of 528 kJ/kg. */
class SteamTurbineTest {
    private static final double INLET = 40e5, DENSITY = 1 / .07341, EXHAUST = 4e5, K = 1.3;

    /** The steam flow, kg/s, that gives shaft power {@code power} (W) across {@code drop}: {@code P / (η_is Δh_is)}, infinite
     * with no drop (moved here from {@code SteamTurbine} at the batch's tooling cleanup: the governor computes its target). */
    static double steamFor(SteamTurbine turbine, double power, double drop) {
        return drop > 0 ? Math.max(0, power) / (turbine.efficiency() * drop) : Double.POSITIVE_INFINITY;
    }

    /** The steam rate, kg/kWh, across {@code drop}. */
    static double steamRate(SteamTurbine turbine, double drop) {
        return steamFor(turbine, 3.6e6, drop);
    }

    @Test void isentropicDropFromFortyBar() {
        double drop = SteamTurbine.isentropicDrop(INLET, DENSITY, EXHAUST, K);
        assertEquals(524.5e3, drop, 500, "524 kJ/kg");
        assertTrue(Math.abs(drop / 528e3 - 1) < .01, "within 1 % of the steam tables");
        assertEquals(0, SteamTurbine.isentropicDrop(INLET, DENSITY, INLET, K), 0);
        assertEquals(0, SteamTurbine.isentropicDrop(INLET, DENSITY, 2 * INLET, K), 0);
    }

    @Test void steamForAShaftPower() {
        double drop = SteamTurbine.isentropicDrop(INLET, DENSITY, EXHAUST, K);
        var turbine = new SteamTurbine(30e3, .65);
        assertEquals(10.56, steamRate(turbine, drop), .01, "10.5 kg/kWh at 65 %");
        double steam = steamFor(turbine, 24.2e3, drop);
        assertEquals(.071, steam, 5e-4, "0.07 kg/s for the 24 kW pump");
        assertEquals(24.2e3, turbine.work(steam, drop), 1e-9);
        assertEquals(0, turbine.work(-1, drop), 0);
        assertEquals(Double.POSITIVE_INFINITY, steamFor(turbine, 1e3, 0));
    }

    @Test void efficiencyBySize() {
        assertEquals(.45, SteamTurbine.typicalEfficiency(1e5), 0, "single stage");
        assertEquals(.76, SteamTurbine.typicalEfficiency(1e7), 1e-15, "multistage");
        assertEquals(.612, SteamTurbine.typicalEfficiency(1e6), 5e-4);
        double last = 0;
        for (double p = 1e4; p < 1e8; p *= 1.3) {
            double eta = SteamTurbine.typicalEfficiency(p);
            assertTrue(eta >= last, "rises with size at " + p);
            last = eta;
        }
        // On 528 kJ/kg (6.8 kg/kWh ideal): about 15 kg/kWh in a single stage and 9 in a multistage machine.
        assertEquals(15.2, steamRate(SteamTurbine.rated(1e5), 528e3), .1);
        assertEquals(8.97, steamRate(SteamTurbine.rated(1e7), 528e3), .01);
    }

    /**
     * The straight-through turbines (batch 2026-11-15-equipment-visual-design, plan W1; owner points T1 and T3): each inlet
     * class, LP 4.5 bar 200 °C first, at the exhausts 10, 20, 150 and 350 kPa it may take (at most half its inlet), at
     * 10 MW (η 0.76) on the network's own law: the drop on the inlet's real P/ρ and k, the steam {@code P / (η Δh_is)}, and
     * the exhaust at {@code h_in − η Δh_is} by the network's fluid PH flash at the exhaust pressure, its condensed mass share
     * read as the solve reads it ({@code InletPhase.bulk}), and the wet exhaust intensity and life ({@link Wear}). Printed
     * as the table the owner decides T1 and T3 on; the assertions hold only what the physics forces: a lower exhaust takes
     * more drop and, from one class, leaves no less moisture.
     */
    @Test void theClassesExpandStraightThroughToTheirExhausts() {
        var model = com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        int water = model.components().indexOf("Water");
        assertTrue(water >= 0);
        var turbine = SteamTurbine.rated(10e6);
        assertEquals(.76, turbine.efficiency(), 1e-12);
        for (var steam : com.wormzjl.icheme.science.equipment.MachineDuty.Steam.values()) {
            var n = new double[model.components().size()];
            n[water] = 1;
            var inlet = model.flashTP(steam.temperature, steam.pressure * 1e3, n, () -> {});
            double k = model.heatCapacityRatio(inlet), density = inlet.mass() / inlet.volume();
            double lastDrop = 0, lastShare = 0;
            for (double kPa : new double[]{350, 150, 20, 10}) {
                if (kPa > steam.pressure / 2) {
                    System.out.printf(java.util.Locale.ROOT, "TURBINE_CLASS %s %.0f kPa %.0f C -> %.0f kPa: above half the inlet, not offered%n", steam, steam.pressure, steam.temperature - 273.15, kPa);
                    continue;
                }
                double drop = SteamTurbine.isentropicDrop(inlet.pressure(), density, kPa * 1e3, k);
                double flow = 10e6 / (turbine.efficiency() * drop), work = turbine.efficiency() * drop;
                var out = model.fluidPH(inlet.temperature(), kPa * 1e3, inlet.enthalpy() - work * inlet.mass(), n, () -> {});
                assertNotNull(out, "the exhaust flash answers at " + kPa + " kPa");
                double share = com.wormzjl.icheme.science.fluid.network.InletPhase.bulk(model, out).condensedMassShare();
                double intensity = Wear.wetExhaust(share);
                System.out.printf(java.util.Locale.ROOT, "TURBINE_CLASS %s %.0f kPa %.0f C -> %.0f kPa: drop %.0f kJ/kg, steam %.2f kg/s (%.2f kg/kWh), exhaust %.1f C, condensed %.2f %%, wear %.2f (life %s h)%n",
                        steam, steam.pressure, steam.temperature - 273.15, kPa, drop / 1e3, flow, flow * 3600 / 1e4, out.temperature() - 273.15, 100 * share, intensity,
                        intensity > 0 ? String.format(java.util.Locale.ROOT, "%.0f", Wear.CAVITATION_PROCESS_HOURS / intensity) : "none");
                assertTrue(drop > lastDrop, "a lower exhaust takes more drop");
                assertTrue(share >= lastShare - 1e-12 && share < .5, "no less moisture at a lower exhaust: " + share);
                lastDrop = drop;
                lastShare = share;
            }
        }
    }
}
