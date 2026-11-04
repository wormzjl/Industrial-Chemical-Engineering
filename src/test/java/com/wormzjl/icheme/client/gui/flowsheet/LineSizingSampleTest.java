package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 5), a slow gate:
 * the 21 lines on the sheet take the probe's bore, count and deciding limit, on the service the plan's rule gives. The probe's
 * {@code sizing-sample.tsv} holds them. Its ten member lines (Overhead, Receiver out, Pump out, Reflux, the PA draws and returns)
 * stay for S3, which owns the circulation they need. The probe labelled WATER as Liquid by hand; the rule makes it Water, whose
 * row holds the same limits, so its size stands.
 *
 * <p>Since O4 of batch 2026-11-04-open-thermo-data (crude cuts on Twu 1985 viscosity, the one-term mixture term) the drops are
 * the run's, not the probe's table: eight crude lines moved at the third place, by -0.8 to +2.2 % (CRUDE 0.058 to 0.059, S-1
 * 0.233 to 0.236, S-2 0.196 to 0.199, S-3 0.085 to 0.086, S-4 0.084 to 0.085, DIESEL 0.165 to 0.166, S-10 0.179 to 0.183,
 * RESIDUE 0.345 to 0.342); every bore, count and deciding limit stands.
 *
 * <p>Since O3 of the same batch the sample runs the medium crude grade, and every value is the run's. Four bores moved (CWS and
 * CWR 0.7 to 0.6 m on the condenser's smaller duty; KERO 0.2 to 0.25 m and AGO 0.15 to 0.2 m on the larger draws) and two
 * deciding limits (OFFGAS speed to drop, NAPHTHA drop to speed); every service, count and lamp stands.
 */
class LineSizingSampleTest {
    /**
     * A line of {@code sizing-sample.tsv}.
     *
     * @param drop bar/100 m
     */
    private record Probe(String line, Service service, double bore, Limit decides, double drop) {
    }

    private static final List<Probe> PROBE = List.of(new Probe("CRUDE", Service.SUCTION_COLD, .4, Limit.SUCTION_COLD_SPEED, .061), new Probe("S-1", Service.LIQUID, .3, Limit.LIQUID_SPEED, .243), new Probe("S-2", Service.LIQUID, .3, Limit.LIQUID_SPEED, .202),
            new Probe("S-3", Service.LIQUID, .35, Limit.LIQUID_SPEED, .086), new Probe("S-4", Service.LIQUID, .35, Limit.LIQUID_SPEED, .085), new Probe("S-5", Service.LIQUID, .35, Limit.LIQUID_SPEED, .087),
            // S-7: the probe's 0.056 at V-101's 2.72 bar; the sample sets 2.80 since S4c (0.054 on Tia Juana, 0.050 on the medium grade).
            new Probe("S-6", Service.TWO_PHASE, .7, Limit.TWO_PHASE_MOMENTUM, .050), new Probe("S-7", Service.TWO_PHASE, .8, Limit.TWO_PHASE_MOMENTUM, .050), new Probe("STEAM", Service.STEAM_SUPERHEATED, .35, Limit.STEAM_SUPERHEATED_SPEED, .063),
            new Probe("CWS", Service.WATER, .6, Limit.WATER_SPEED, .086), new Probe("CWR", Service.WATER, .6, Limit.WATER_SPEED, .085), new Probe("OFFGAS", Service.GAS, .2, Limit.GAS_DROP, .075),
            new Probe("NAPHTHA", Service.LIQUID, .1, Limit.LIQUID_SPEED, .174), new Probe("WATER", Service.WATER, .075, Limit.WATER_DROP, .279), new Probe("DIESEL", Service.LIQUID, .15, Limit.LIQUID_SPEED, .155),
            new Probe("S-10", Service.LIQUID, .25, Limit.LIQUID_SPEED, .188), new Probe("RESIDUE", Service.LIQUID, .25, Limit.LIQUID_SPEED, .326), new Probe("KERO", Service.DRAW, .25, Limit.DRAW_SPEED, .008),
            new Probe("AGO", Service.DRAW, .2, Limit.DRAW_SPEED, .008), new Probe("S-8", Service.DRAW, .25, Limit.DRAW_SPEED, .012), new Probe("S-9", Service.DRAW, .45, Limit.DRAW_SPEED, .010));

    @Test
    void theSheetsLinesEqualTheProbes() {
        var sheet = SheetFixture.sample();
        var run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        assertEquals(sheet.streams().size(), PROBE.size(), "every line on the sheet");
        var thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        var scratch = new MixtureViscosity.Workspace();
        MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            for (var probe : PROBE) {
                var stream = stream(sheet, probe.line());
                var flow = run.streams().get(stream.id());
                var service = Service.of(flow, Service.Ends.of(sheet, stream), thermo, null);
                var line = LineSizing.Line.of(flow, service, DesignBasis.DEFAULT, thermo, PipeSizes.MAX_BORE, null, scratch);
                var choice = LineSizing.auto(line);
                var size = choice.size();
                var closest = choice.rating().closest();
                assertEquals(probe.service(), service, probe.line());
                assertEquals(probe.bore(), size.bore(), probe.line());
                assertEquals(1, size.runs(), probe.line());
                assertEquals(probe.decides(), closest.limit(), probe.line());
                assertEquals(Lamp.GREEN, choice.rating().lamp(), probe.line());
                assertEquals(probe.drop(), LineSizing.drop(line, size, 100), .0006, probe.line() + ": the probe's table holds three places");
            }
            return null;
        });
    }

    private static Flowsheet.Stream stream(Flowsheet sheet, String name) {
        for (var stream : sheet.streams()) if (stream.name().equals(name)) return stream;
        throw new AssertionError(name);
    }
}
