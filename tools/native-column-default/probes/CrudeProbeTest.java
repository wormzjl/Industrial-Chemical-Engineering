package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Probe (not a gate): a separating dry crude column, published with the C++ and with the Java outer pass. */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class CrudeProbeTest {
    @Test
    void probe() {
        V3CrudeFeed crude = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018").crudeFeed("icheme:tia_juana_light");
        int[][] cases = {{4, 3}, {8, 6}, {15, 12}, {30, 24}};
        for (double reflux : new double[] {4.17, 2.0}) {
            for (int[] sample : cases) {
                double[] flows = crude.moleFractions();
                double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
                for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
                V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                        crude.componentBasis(), flows, 365.0 + 273.15, sample[0], sample[1], 250_000.0, 750.0, List.of(
                                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                                new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                                new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
                StringBuilder line = new StringBuilder(String.format("crude stages %2d R %.2f:", sample[0], reflux));
                List<V3ColumnStreamProperties>[] streams = new List[2];
                String[] published = new String[2];
                for (int m = 0; m < 2; m++) {
                    System.setProperty("icheme.native.thermo.batch", m == 0 ? "true" : "false");
                    long t0 = System.nanoTime();
                    V3ColumnOutcome outcome = V3TestInputs.calculate(input);
                    double ms = (System.nanoTime() - t0) / 1.0e6;
                    String kind;
                    if (outcome instanceof V3ColumnOutcome.Success s) {
                        streams[m] = s.result().streams();
                        published[m] = String.valueOf(s.result().streams()) + s.result().inspection().map(String::valueOf).orElse("");
                        double d = 0;
                        for (V3ColumnStreamProperties st : s.result().streams()) if (st.streamId().startsWith("distillate")) d = st.molarFlowMolPerSecond();
                        kind = String.format("Success%s D=%.3f", String.valueOf(s.diagnostics()).contains("Boston-Sullivan") ? "+BS" : "", d);
                    } else {
                        V3ColumnOutcome.Failure f = (V3ColumnOutcome.Failure) outcome;
                        published[m] = f.code() + " " + f.summary();
                        kind = "Failure " + f.code();
                    }
                    line.append(String.format(" [%s: %s, %.0f ms]", m == 0 ? "C++" : "Java", kind, ms));
                }
                System.clearProperty("icheme.native.thermo.batch");
                if (published[0].equals(published[1])) line.append(" published: identical");
                else if (streams[0] != null && streams[1] != null && streams[0].size() == streams[1].size()) {
                    double worst = 0;
                    for (int i = 0; i < streams[0].size(); i++) {
                        V3ColumnStreamProperties a = streams[0].get(i), b = streams[1].get(i);
                        worst = Math.max(worst, Math.abs(a.molarFlowMolPerSecond() - b.molarFlowMolPerSecond()) / Math.max(a.molarFlowMolPerSecond(), b.molarFlowMolPerSecond()));
                        worst = Math.max(worst, Math.abs(a.temperatureKelvin() - b.temperatureKelvin()) / a.temperatureKelvin());
                        for (int c = 0; c < a.moleFractions().size(); c++) {
                            double x = a.moleFractions().get(c).moleFraction(), y = b.moleFractions().get(c).moleFraction();
                            if (Math.max(x, y) > 1e-6) worst = Math.max(worst, Math.abs(x - y) / Math.max(x, y));
                        }
                    }
                    line.append(String.format(" published: differ, largest %.2e relative (flows, T, fractions > 1e-6)", worst));
                } else line.append(" published: DIFFERENT outcome");
                System.out.println(line);
            }
        }
    }
}
