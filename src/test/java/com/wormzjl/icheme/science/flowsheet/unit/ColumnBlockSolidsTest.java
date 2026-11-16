package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column block's solids (section 13 of the 2026-11-16 fouling and corrosion plan, owner D6): a crystal that arrives melts
 * into its compound with its true energy, an inert particle is held for the bottoms, and a product that holds a crystal at its
 * solved state refuses the run with column.frozen. {@code FlowsheetColumnTest} runs the particles through a whole column.
 */
final class ColumnBlockSolidsTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final FluidThermodynamics MODEL = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));
    private static final FlowThermo THERMO = new FlowThermo(MODEL, CATALOG, NETWORK, () -> {
    });
    private static final ParticleSize SIZE = ParticleSize.micrometres("100");

    /** Ice arriving with water at 300 K: its moles join the water, its stored enthalpy and its offset the energy, the offset booked as it enters. */
    @Test void aCrystalMeltsIntoItsCompoundWithItsTrueEnergy() {
        int water = THERMO.water();
        double[] amounts = new double[THERMO.axis().size()];
        amounts[water] = 50;
        var ice = MODEL.solid("icheme:crystal_ice_ih");
        var sand = CATALOG.solids().materials().get("icheme:demo_particle");
        var solids = new SolidInventory(List.of(new SolidInventory.Population(ice, SIZE, .2), new SolidInventory.Population(sand, SIZE, .5)));
        var stream = THERMO.atTemperature(300, 2e5, amounts, solids);
        var held = new ColumnBlockSolve.Solids(THERMO);
        var report = new Report();
        var fluid = held.fluid(stream, report);
        assertTrue(fluid.solids().empty(), "the solve takes the fluid alone");
        assertEquals(50 + .2 / MODEL.molecularWeight(water), fluid.amountsView()[water], 1e-12, "the ice's moles join the water");
        double offset = .2 * ice.crystal().energyOffset(), particles = new SolidInventory(List.of(new SolidInventory.Population(sand, SIZE, .5))).moments().enthalpy(300, 2e5);
        assertEquals(offset, report.enthalpyIn(), 0, "the offset is booked as it enters");
        assertEquals(stream.enthalpy() - particles + offset, fluid.enthalpy(), 1e-6 * Math.abs(fluid.enthalpy()), "the melt holds the crystal's true energy");
        assertTrue(fluid.temperature() < 300, "melting the ice cools the water");
        assertEquals(.5, held.held().massKg(), 0, "the particles are held for the bottoms");
        assertEquals(particles, held.enthalpy(), 0);
        assertEquals(stream.mass(), fluid.mass() + held.held().massKg(), 1e-12 * stream.mass(), "mass is kept");
    }

    /** A product of water at 260 K holds ice: the run refuses with column.frozen, naming water and the product's port; at 300 K it runs. */
    @Test void aProductThatHoldsACrystalRefusesTheRun() {
        String water = THERMO.axis().get(THERMO.water());
        var fraction = List.of(new V3ColumnStreamProperties.ComponentFraction(water, 1, 1));
        var cold = new V3ColumnStreamProperties("free_water", "Free water", "liquid", 10, .18, 260, 101325, 0, fraction);
        var failure = assertThrows(FlowsheetFailure.class, () -> ColumnBlockSolve.frozen(THERMO, List.of(cold), id -> ColumnUnit.WATER));
        assertEquals("column.frozen", failure.key());
        assertEquals(List.of(water, ColumnUnit.WATER), failure.arguments());
        var warm = new V3ColumnStreamProperties("free_water", "Free water", "liquid", 10, .18, 300, 101325, 0, fraction);
        ColumnBlockSolve.frozen(THERMO, List.of(warm), id -> ColumnUnit.WATER);
    }
}
