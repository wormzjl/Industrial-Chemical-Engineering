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

    /** One mole of water in the network model. */
    private static double[] water(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model) {
        int water = model.components().indexOf("Water");
        assertTrue(water >= 0);
        var n = new double[model.components().size()];
        n[water] = 1;
        return n;
    }

    /** The condensed mass share of {@code state} as the solve reads it. */
    private static double condensed(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model, com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State state) {
        return com.wormzjl.icheme.science.fluid.network.InletPhase.bulk(model, state).condensedMassShare();
    }

    /** Saturated steam at {@code pressure} (Pa): the network model's dew point by bisection, 0.01 K over it. */
    private static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State saturated(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model, double pressure, double hot) {
        var n = water(model);
        double cold = 300;
        for (int i = 0; i < 60; i++) {
            double t = (cold + hot) / 2;
            if (condensed(model, model.flashTP(t, pressure, n, () -> {})) > 0) cold = t;
            else hot = t;
        }
        return model.flashTP(hot + .01, pressure, n, () -> {});
    }

    /**
     * One row: {@code inlet} expanded at 10 MW (η 0.76) to {@code kPa} on the network's law, printed with its drop, steam,
     * exhaust temperature, condensed share, and the wet-exhaust intensity and life both on the onset of 1 % and on the
     * onset at its exhaust pressure (W5, {@link Wear#wetExhaust(double, double)}). Returns the condensed share.
     */
    private static double row(com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model, SteamTurbine turbine, String label,
                              com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State inlet, double kPa) {
        var n = water(model);
        double k = model.heatCapacityRatio(inlet), density = inlet.mass() / inlet.volume();
        double drop = SteamTurbine.isentropicDrop(inlet.pressure(), density, kPa * 1e3, k);
        double flow = 10e6 / (turbine.efficiency() * drop), work = turbine.efficiency() * drop;
        var out = model.fluidPH(inlet.temperature(), kPa * 1e3, inlet.enthalpy() - work * inlet.mass(), n, () -> {});
        assertNotNull(out, "the exhaust flash answers at " + kPa + " kPa");
        double share = condensed(model, out), flat = Wear.wetExhaust(share), onset = Wear.wetExhaust(share, kPa * 1e3);
        System.out.printf(java.util.Locale.ROOT, "TURBINE_CLASS %s %.1f C -> %.0f kPa: drop %.0f kJ/kg, steam %.2f kg/s (%.2f kg/kWh), exhaust %.1f C, condensed %.2f %%, "
                        + "wear at 1 %% onset %.2f (life %s h), at the %.2f %% onset %.3f (life %s h)%n",
                label, inlet.temperature() - 273.15, kPa, drop / 1e3, flow, flow * 3600 / 1e4, out.temperature() - 273.15, 100 * share,
                flat, life(flat), 100 * Wear.wetOnset(kPa * 1e3), onset, life(onset));
        return share;
    }

    private static String life(double intensity) {
        return intensity > 0 ? String.format(java.util.Locale.ROOT, "%.0f", Wear.CAVITATION_PROCESS_HOURS / intensity) : "none";
    }

    /**
     * The straight-through turbines (batch 2026-11-15-equipment-visual-design, plan W1 and W5; owner points T1 and T3): each
     * inlet class, LP 4.5 bar 200 °C first, at the exhausts 10, 20, 150 and 350 kPa it may take (at most half its inlet), at
     * 10 MW (η 0.76) on the network's own law: the drop on the inlet's real P/ρ and k, the steam {@code P / (η Δh_is)}, and
     * the exhaust at {@code h_in − η Δh_is} by the network's fluid PH flash at the exhaust pressure, its condensed mass share
     * read as the solve reads it ({@code InletPhase.bulk}), and the wet exhaust intensity and life ({@link Wear}); then the
     * same class from a saturated inlet at its pressure. Printed as the table the owner decides on. The physics forces a
     * lower exhaust to take more drop and leave no less moisture; W5's onset leaves design steam unworn at every exhaust,
     * and wears a saturated inlet at 10 and 20 kPa, where the onset must tell the two apart.
     */
    @Test void theClassesExpandStraightThroughToTheirExhausts() {
        var model = com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        var turbine = SteamTurbine.rated(10e6);
        assertEquals(.76, turbine.efficiency(), 1e-12);
        var unseparated = new java.util.ArrayList<String>();
        for (var steam : com.wormzjl.icheme.science.equipment.MachineDuty.Steam.values()) {
            var design = model.flashTP(steam.temperature, steam.pressure * 1e3, water(model), () -> {});
            var dry = saturated(model, steam.pressure * 1e3, steam.temperature);
            assertEquals(0, condensed(model, dry), 0, "saturated steam is dry at its dew point");
            double lastDrop = 0, lastShare = 0;
            for (double kPa : new double[]{350, 150, 20, 10}) {
                if (kPa > steam.pressure / 2) {
                    System.out.printf(java.util.Locale.ROOT, "TURBINE_CLASS %s -> %.0f kPa: above half the inlet, not offered%n", steam, kPa);
                    continue;
                }
                double share = row(model, turbine, steam + " design", design, kPa);
                double drop = SteamTurbine.isentropicDrop(design.pressure(), design.mass() / design.volume(), kPa * 1e3, model.heatCapacityRatio(design));
                assertTrue(drop > lastDrop, "a lower exhaust takes more drop");
                assertTrue(share >= lastShare - 1e-12 && share < .5, "no less moisture at a lower exhaust: " + share);
                assertEquals(0, Wear.wetExhaust(share, kPa * 1e3), 0, steam + " design steam to " + kPa + " kPa wears none (W5)");
                lastDrop = drop;
                lastShare = share;
                double wet = row(model, turbine, steam + " saturated", dry, kPa);
                if (kPa <= 20 && !(wet > Wear.wetOnset(kPa * 1e3))) unseparated.add(steam + " at " + kPa + " kPa: saturated " + wet + " under the onset " + Wear.wetOnset(kPa * 1e3));
            }
        }
        assertTrue(unseparated.isEmpty(), "a pressure-only onset tells lost superheat from design steam (W5): " + unseparated);
    }
}
