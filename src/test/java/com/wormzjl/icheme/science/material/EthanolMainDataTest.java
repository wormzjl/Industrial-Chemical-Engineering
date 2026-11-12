package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.fluid.network.StillColumn;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Ethanol in the main data (batch 2026-11-11-aqueous-solvents, W6, gates G2, G3, G4 and G6 of {@code W6_ETHANOL_DATA_PLAN.md}
 * section 6): the ChemSep record {@code icheme:chemsep_ethanol} on the network package {@code icheme:refinery}, the same numbers
 * as the pilot record W3 to W5 ran on, its heat of combustion and its creative-source preset.
 */
final class EthanolMainDataTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final FluidThermodynamics REFINERY = FluidThermodynamics.forNetwork(CATALOG, NETWORK);
    private static final double ATM = 101_325;
    /** A wash of 10 % ethanol by volume: 0.0332 by mole (W4). */
    private static final double WASH = .0332;

    private static StillColumn column(FluidThermodynamics thermo) {
        var column = new StillColumn(thermo.on(List.of("Ethanol")), ATM);
        assertEquals(List.of("Ethanol"), column.model().components(), "the cubic's compounds");
        assertEquals(1, column.water(), "water in the cubic, last");
        return column;
    }

    private static double[] liquid(double ethanol) {
        return new double[] {ethanol, 1 - ethanol};
    }

    @Test void theNetworkPackageHoldsEthanolOnItsChemSepRecord() {
        assertEquals("icheme:refinery", NETWORK);
        var pack = CATALOG.requirePackage(NETWORK);
        int e = pack.components().indexOf("Ethanol");
        assertEquals(pack.components().size() - 1, e, "ethanol is the package's last compound (D3)");
        assertEquals("icheme:chemsep_ethanol", pack.properties().get(e).id());
    }

    /** G2: pure ethanol boils at 351.4 K at 1 atm; its standard liquid density is 795.5 kg/m3 at 60 F. */
    @Test void pureEthanolBoilsAndWeighsAsItsRecord() {
        double boiling = column(REFINERY).bubble(liquid(1), 350).temperature();
        var dry = REFINERY.on(List.of("Ethanol"), false);
        double[] n = new double[dry.componentCount()];
        n[dry.components().indexOf("Ethanol")] = 1;
        var state = dry.flashTP(288.7056, ATM, n, () -> {
        });
        assertTrue(FluidThermodynamics.holdsPhase(state, FluidThermodynamics.OIL) || FluidThermodynamics.holdsPhase(state, FluidThermodynamics.AQUEOUS), "a liquid at 60 F");
        assertFalse(FluidThermodynamics.holdsPhase(state, FluidThermodynamics.GAS), "no vapour at 60 F");
        double density = dry.liquidDensity(state);
        System.out.printf(Locale.ROOT, "G2 ethanol: boils %.3f K at 1 atm, %.3f kg/m3 at 288.7056 K%n", boiling, density);
        assertEquals(351.4, boiling, .5, "the normal boiling point");
        assertEquals(795.5, density, .001 * 795.5, "the standard liquid density");
    }

    /** G3: the 10 % wash on the network package boils where it did on the pilot package (W4: 92.574 C, a first vapour of 0.2567). */
    @Test void theWashBoilsOnTheNetworkPackageAsOnThePilot() {
        var pilot = FluidThermodynamics.forNetwork(PilotAqueousTestCatalog.catalog(), PilotAqueousTestCatalog.PACKAGE_ID);
        var on = column(REFINERY).bubble(liquid(WASH), 365);
        var was = column(pilot).bubble(liquid(WASH), 365);
        System.out.printf(Locale.ROOT, "G3 wash: %.6f C, vapour %.6f ethanol (pilot %.6f C, %.6f)%n", on.temperature() - 273.15, on.vapour()[0],
                was.temperature() - 273.15, was.vapour()[0]);
        assertEquals(was.temperature(), on.temperature(), 1e-9 * was.temperature(), "the bubble point");
        assertEquals(was.vapour()[0], on.vapour()[0], 1e-9 * was.vapour()[0], "the first vapour");
        assertEquals(92.574, on.temperature() - 273.15, .001, "W4's bubble point");
        assertEquals(.2567, on.vapour()[0], 1e-4, "W4's first vapour");
    }

    /** G4: the heat of combustion is Hess's 1277.5 kJ/mol; C2H6O gives the record's molar mass. */
    @Test void ethanolBurnsAtItsHeatOfCombustion() {
        var components = CATALOG.conservedComponents(NETWORK);
        double lhv = CATALOG.heatingValues(NETWORK)[components.indexOf("Ethanol")];
        assertEquals(1_277_500, lhv, .005 * 1_277_500, "the lower heating value, J/mol");
        var formula = SubRecords.thermochemistry(CATALOG, NETWORK, "Ethanol").orElseThrow();
        assertEquals(Map.of("C", 2., "H", 6., "O", 1.), formula.elements());
        var pack = CATALOG.requirePackage(NETWORK);
        double weight = pack.properties().get(pack.components().indexOf("Ethanol")).molecularWeight();
        assertEquals(weight * 1000, SubRecords.molarMass(formula) * 1000, .02, "C2H6O, g/mol");
        var limits = SubRecords.flammability(CATALOG, NETWORK, "Ethanol").orElseThrow();
        assertEquals(.033, limits.lowerLimit(), 1e-12, "NIOSH: 3.3 %");
        assertEquals(.19, limits.upperLimit().doubleValue(), 1e-12, "NIOSH: 19 %");
    }

    /** G6: the creative source offers ethanol, one compound at 1.0 on the world list. */
    @Test void theCreativeSourceOffersEthanol() {
        var preset = FluidPresetCatalog.resolve(CATALOG).stream().filter(p -> p.id().equals("ethanol")).findFirst().orElseThrow();
        var axis = CATALOG.conservedComponents(NETWORK);
        double[] x = preset.moleFractions();
        assertEquals(axis.size(), x.length);
        for (int i = 0; i < x.length; i++) assertEquals(axis.get(i).equals("Ethanol") ? 1 : 0, x[i], 0, axis.get(i));
        assertEquals("Ethanol", preset.name());
    }
}
