package com.wormzjl.icheme.science.flowsheet.sizing;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.ExchangerType;
import com.wormzjl.icheme.science.flowsheet.unit.ExchangerRating;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;

import org.junit.jupiter.api.Test;

class CrossflowSizingTest {
    private static HeatCurve liquid(double cp, double film) {
        return new HeatCurve(
                new double[] {0, 2e6},
                new double[] {250, 250 + 2e6 / cp},
                new double[] {film, film});
    }

    private static final ExchangerRating.Side TUBE =
            new ExchangerRating.Side(liquid(4000, 1000), 100, 200000);
    private static final ExchangerRating.Side SHELL =
            new ExchangerRating.Side(liquid(2000, 100), 60, 380000);

    @Test
    void requiredAreaComesFromTheSelectedRoutingAndReproducesItsDuty() {
        double duty = 8e6;
        var standard = ExchangerRating.required(ExchangerType.STANDARD, TUBE, SHELL, duty);
        var cross = ExchangerRating.required(ExchangerType.CROSSFLOW, TUBE, SHELL, duty);
        assertTrue(Double.isFinite(cross.area()));
        assertTrue(cross.area() > standard.area());
        var actual = ExchangerRating.crossflow(TUBE, SHELL, cross.area(), null);
        assertTrue(actual.converged());
        assertEquals(duty, Math.abs(actual.intoTube()), duty * 1e-4);
        assertTrue(
                Math.abs(
                                ExchangerRating.crossflow(TUBE, SHELL, cross.area() * .99, null)
                                        .intoTube())
                        < duty);
    }

    @Test
    void unreachableDutyDoesNotBorrowCounterflowArea() {
        var cold = new ExchangerRating.Side(liquid(2000, 1000), 1, 100000);
        var hot = new ExchangerRating.Side(liquid(2000, 1000), 1, 300000);
        double duty = 195000;
        assertTrue(
                Double.isFinite(
                        ExchangerRating.required(ExchangerType.STANDARD, cold, hot, duty).area()));
        assertEquals(
                Double.POSITIVE_INFINITY,
                ExchangerRating.required(ExchangerType.CROSSFLOW, cold, hot, duty).area());
    }

    @Test
    void lowShellDropCannotBypassTubeDropOrThermalSpare() {
        double duty = 8e6;
        var required = ExchangerRating.required(ExchangerType.STANDARD, TUBE, SHELL, duty);
        var tube = new LineSizing.Carried(100, 100 / 998., 998, .001, 100, null);
        var shell = new LineSizing.Carried(60, 60 / 12., 12, .000015, 100, null);
        var load =
                new ExchangerSizing.Load(
                        false,
                        tube,
                        shell,
                        Limit.EXCHANGER_DROP_LIQUID,
                        Limit.EXCHANGER_DROP_GAS,
                        required.area(),
                        duty,
                        100,
                        100,
                        required.approach(),
                        null,
                        null,
                        new ExchangerSizing.Curves(TUBE, SHELL),
                        ExchangerType.STANDARD);
        var choice = ExchangerSizing.auto(load);
        assertNotNull(choice.size());
        assertEquals(ExchangerType.CROSSFLOW, choice.size().type());
        var needed = load.forType(choice.size().type()).needed();
        assertTrue(choice.size().surface() >= Selection.spared(needed));
        assertTrue(choice.rating().reading(Limit.EXCHANGER_DROP_LIQUID).met());
        assertTrue(choice.rating().reading(Limit.EXCHANGER_DROP_GAS).met());
        var tooSmall =
                ExchangerSizing.rate(
                        load, new ExchangerSizing.Size(false, 1, .5, 2, ExchangerType.CROSSFLOW));
        assertFalse(tooSmall.reading(Limit.EXCHANGER_SURFACE).met());
        assertFalse(tooSmall.reading(Limit.EXCHANGER_DROP_LIQUID).met());
    }
}
