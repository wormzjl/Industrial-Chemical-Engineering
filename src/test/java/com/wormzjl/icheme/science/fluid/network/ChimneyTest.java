package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A chimney's sizing and reading (batch 2026-11-10-chimney, CHIMNEY_PLAN.md sections 1 and 3, owner C1 to C7). */
class ChimneyTest {
    /** Owner C1 B: the atmosphere falls 11.6 Pa per block from 101,325 Pa at sea level (y 63). */
    @Test void theAtmosphereFallsWithHeight() {
        assertEquals(PhaseLayout.AMBIENT_PRESSURE, PhaseLayout.atmosphere(PhaseLayout.SEA_LEVEL), 0);
        assertEquals(11.62, PhaseLayout.atmosphere(PhaseLayout.SEA_LEVEL) - PhaseLayout.atmosphere(PhaseLayout.SEA_LEVEL + 1), .01);
        assertEquals(PhaseLayout.AMBIENT_PRESSURE - 100 * 11.621, PhaseLayout.atmosphere(163), 1, "100 blocks up: about 1.16 kPa less");
        assertTrue(PhaseLayout.atmosphere(-64) > PhaseLayout.AMBIENT_PRESSURE, "below sea level, more");
    }

    /** C1 B: an open vessel's surface reads the atmosphere at its fill height; a closed bag keeps 101,325 Pa (open item); a rigid wall none. */
    @Test void anOpenVesselsSurfaceReadsItsHeight() {
        var model = com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        var open = PassiveNetwork.emptyVessel(1, 120, 50, 4, true, model);
        assertEquals(PhaseLayout.atmosphere(124), open.inventory().ambient(), 0);
        assertEquals(open.inventory().ambient(), open.inventory().wallWorkPressure(), 0);
        var closed = PassiveNetwork.emptyVessel(2, 120, 50, 4, false, model);
        assertEquals(PhaseLayout.AMBIENT_PRESSURE, closed.inventory().ambient(), 0);
        var moved = new PassiveNetwork.Reservoir(1, 63 - 4, open.state(), open.kind(), open.inventory());
        assertEquals(PhaseLayout.AMBIENT_PRESSURE, moved.inventory().ambient(), 1e-9, "the node restates its own height");
        assertNotEquals(open.inventory(), moved.inventory());
    }

    /** Owner C2 A: H + 1.5 L with L = H, never below 10 m. */
    @Test void goodEngineeringPracticeHeight() {
        assertEquals(38, Chimney.gepHeight(15));
        assertEquals(10, Chimney.gepHeight(0));
        assertEquals(10, Chimney.gepHeight(4));
        assertEquals(50, Chimney.gepHeight(20));
    }

    /** Owner C7: the design area passes 1.2 times the flow at the design velocity; the window and the rating. */
    @Test void theDesignDiameterCarriesTheSpare() {
        double q = 10;
        double d = Chimney.designDiameter(Chimney.Form.STEEL, q);
        assertEquals(20 / Chimney.SPARE, Chimney.exitVelocity(q, d), 1e-9, "the design flow at 20 m/s over the spare");
        assertEquals(12 / Chimney.SPARE, Chimney.exitVelocity(q, Chimney.designDiameter(Chimney.Form.BRICK, q)), 1e-9);
        assertEquals(30 * Math.PI / 4, Chimney.ratedVolumeFlow(1), 1e-12);
        assertEquals(0, Chimney.exitVelocity(5, 0));
        assertEquals(30, Chimney.Form.STEEL.maxHeight(1), "a steel stack at most 30 diameters");
        assertEquals(10, Chimney.Form.STEEL.maxHeight(.1), "never below its least height");
        assertEquals(60, Chimney.Form.STEEL.maxHeight(3));
        assertEquals(150, Chimney.Form.BRICK.maxHeight(1));
    }

    /** Owner C4 A: Beer-Lambert with 1 m²/g; no solids, clear. */
    @Test void opacityFollowsBeerLambert() {
        assertEquals(0, Chimney.opacity(0, 10, 1));
        assertEquals(0, Chimney.opacity(1, 0, 1));
        // 0.1 g/m³ across 2 m: tau 0.2.
        assertEquals(1 - Math.exp(-.2), Chimney.opacity(1e-3, 10, 2), 1e-9);
        assertTrue(Chimney.opacity(1, 10, 2) > .999, "a dense smoke is black");
    }

    /** A hot gas column draws; a gas as dense as air does not. */
    @Test void draftOfTheHotColumn() {
        double hot = Chimney.density(.029, 473.15, PhaseLayout.AMBIENT_PRESSURE);
        assertEquals(.747, hot, .002);
        assertEquals((PhaseLayout.AIR_DENSITY - hot) * 9.80665 * 50, Chimney.draft(hot, 50), 1e-9);
        assertEquals(0, Chimney.draft(PhaseLayout.AIR_DENSITY, 50), 1e-12);
        assertTrue(Chimney.draft(2, 50) < 0, "a dense gas sinks");
    }

    /** The reading's molar mass and water share, water last; the status in its order (C5 A, C6 A, C7). */
    @Test void readingAndStatus() {
        double[] masses = {.028, .044, .018};
        var r = Chimney.reading(new double[] {7, 1, 2}, masses, 450, 2, 0, false, 1, PhaseLayout.AMBIENT_PRESSURE);
        assertEquals((7 * .028 + .044 + 2 * .018) / 10, r.molarMass(), 1e-12);
        assertEquals(2 * .018 / (7 * .028 + .044), r.waterShare(), 1e-12);
        assertEquals(2 / r.density(), r.volumeFlow(), 1e-12);
        assertEquals(Chimney.Status.VENTING, Chimney.status(Chimney.Form.STEEL, r, true));
        var none = Chimney.reading(new double[] {1, 0, 0}, masses, 450, 0, 0, false, 1, PhaseLayout.AMBIENT_PRESSURE);
        assertEquals(Chimney.Status.NO_DRAFT, Chimney.status(Chimney.Form.STEEL, none, true));
        assertEquals(Chimney.Status.NO_FLOW, Chimney.status(Chimney.Form.STEEL, none, false));
        var wet = Chimney.reading(new double[] {7, 1, 2}, masses, 450, 2, 0, true, 1, PhaseLayout.AMBIENT_PRESSURE);
        assertEquals(Chimney.Status.WET_STACK, Chimney.status(Chimney.Form.STEEL, wet, true));
        var fast = Chimney.reading(new double[] {7, 1, 2}, masses, 450, 30, 0, false, .5, PhaseLayout.AMBIENT_PRESSURE);
        assertTrue(fast.exitVelocity() > Chimney.HIGH_VELOCITY);
        assertEquals(Chimney.Status.ABOVE_RATING, Chimney.status(Chimney.Form.STEEL, fast, true));
        var hot = Chimney.reading(new double[] {7, 1, 2}, masses, 750, 2, 0, false, 1, PhaseLayout.AMBIENT_PRESSURE);
        assertEquals(Chimney.Status.HOT_STACK, Chimney.status(Chimney.Form.STEEL, hot, true));
        assertEquals(Chimney.Status.VENTING, Chimney.status(Chimney.Form.BRICK, hot, true), "a brick chimney takes 600 °C");
    }
}
