package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

/** E30 form H scratch probe: the medium grade's lost grid points, with the DIAG instrumentation. Not for the tree. */
class O10FixDiagProbe {
    @Test void lost() {
        var c = MaterialCatalog.bundled();
        var base = c.presets().column("medium_crude").input(c);
        for (double t : new double[] {636.0, 644.0}) {
            System.out.println("DIAG ===== med " + t);
            System.out.println("DIAG result " + t + " " + O10FixGridProbe.run(c, O3FeedTemperatureProbe.at(base, t, 1.0, 1.0, base.feedStageNumber()), new long[1]));
        }
    }
}
