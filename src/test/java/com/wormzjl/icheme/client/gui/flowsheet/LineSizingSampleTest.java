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
 */
class LineSizingSampleTest {
    /**
     * A line of {@code sizing-sample.tsv}.
     *
     * @param drop bar/100 m
     */
    private record Probe(String line, Service service, double bore, Limit decides, double drop) {
    }

    private static final List<Probe> PROBE = List.of(new Probe("CRUDE", Service.SUCTION_COLD, .4, Limit.SUCTION_COLD_SPEED, .058), new Probe("S-1", Service.LIQUID, .3, Limit.LIQUID_SPEED, .233), new Probe("S-2", Service.LIQUID, .3, Limit.LIQUID_SPEED, .196),
            new Probe("S-3", Service.LIQUID, .35, Limit.LIQUID_SPEED, .085), new Probe("S-4", Service.LIQUID, .35, Limit.LIQUID_SPEED, .084), new Probe("S-5", Service.LIQUID, .35, Limit.LIQUID_SPEED, .086),
            // S-7: the probe's 0.056 at V-101's 2.72 bar; the sample sets 2.80 since S4c, and the denser, less flashed feed loses 0.054.
            new Probe("S-6", Service.TWO_PHASE, .7, Limit.TWO_PHASE_MOMENTUM, .055), new Probe("S-7", Service.TWO_PHASE, .8, Limit.TWO_PHASE_MOMENTUM, .054), new Probe("STEAM", Service.STEAM_SUPERHEATED, .35, Limit.STEAM_SUPERHEATED_SPEED, .064),
            new Probe("CWS", Service.WATER, .7, Limit.WATER_SPEED, .062), new Probe("CWR", Service.WATER, .7, Limit.WATER_SPEED, .062), new Probe("OFFGAS", Service.GAS, .2, Limit.GAS_SPEED, .028),
            new Probe("NAPHTHA", Service.LIQUID, .1, Limit.LIQUID_DROP, .427), new Probe("WATER", Service.WATER, .075, Limit.WATER_DROP, .287), new Probe("DIESEL", Service.LIQUID, .15, Limit.LIQUID_SPEED, .165),
            new Probe("S-10", Service.LIQUID, .25, Limit.LIQUID_SPEED, .179), new Probe("RESIDUE", Service.LIQUID, .25, Limit.LIQUID_SPEED, .345), new Probe("KERO", Service.DRAW, .2, Limit.DRAW_SPEED, .019),
            new Probe("AGO", Service.DRAW, .15, Limit.DRAW_SPEED, .029), new Probe("S-8", Service.DRAW, .25, Limit.DRAW_SPEED, .013), new Probe("S-9", Service.DRAW, .45, Limit.DRAW_SPEED, .009));

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
