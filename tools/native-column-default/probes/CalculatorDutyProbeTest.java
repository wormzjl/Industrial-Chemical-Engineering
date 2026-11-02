package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Probe (not a gate): what the calculator publishes with the C++ and with the Java outer pass. */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class CalculatorDutyProbeTest {
    @Test
    void probe() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        for (int stages : new int[] {6, 10, 20, 30}) {
            for (double duty : new double[] {2.0e6, 4.0e6, 6.0e6}) { double feedTemperature = 450.0; {
                double[] feed = new double[thermo.componentBasis().componentCount()];
                feed[6] = 50.0;
                feed[13] = 50.0;
                V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:probe",
                        thermo.componentBasis(), feed, feedTemperature, stages, stages / 2, 250_000.0, 750.0, List.of(
                                new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                                new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                                new V3ColumnSpecification.ReboilerDuty(duty)));
                String[] published = new String[2]; String[] kindNote = {"", ""};
                StringBuilder line = new StringBuilder(String.format("stages %2d duty %.0f MW:", stages, duty / 1.0e6));
                for (int m = 0; m < 2; m++) {
                    System.setProperty("icheme.native.thermo.batch", m == 0 ? "true" : "false");
                    long t0 = System.nanoTime();
                    V3ColumnOutcome outcome = V3TestInputs.calculate(input);
                    double ms = (System.nanoTime() - t0) / 1.0e6;
                    String kind;
                    if (outcome instanceof V3ColumnOutcome.Success s) {
                        published[m] = String.valueOf(s.result().streams()) + s.result().inspection().map(String::valueOf).orElse("");
                        for (V3ColumnStreamProperties st : s.result().streams()) if (st.streamId().startsWith("distillate")) for (var cf : st.moleFractions()) if (cf.componentId().endsWith("pc03")) kindNote[m] = String.format(" xD(pc03)=%.4f D=%.2f", cf.moleFraction(), st.molarFlowMolPerSecond());
                        kind = "Success" + (String.valueOf(s.diagnostics()).contains("Boston-Sullivan") ? "+BS" : "");
                    } else {
                        V3ColumnOutcome.Failure f = (V3ColumnOutcome.Failure) outcome;
                        published[m] = f.code() + " " + f.summary();
                        kind = "Failure " + f.code() + (String.valueOf(f.diagnostics()).contains("Boston-Sullivan") ? "+BS" : "");
                    }
                    line.append(String.format(" [%s: %s%s, %.0f ms]", m == 0 ? "C++" : "Java", kind, kindNote[m], ms));
                }
                System.clearProperty("icheme.native.thermo.batch");
                line.append(published[0].equals(published[1]) ? " published: identical" : " published: DIFFERENT");
                System.out.println(line);
                if (!published[0].equals(published[1])) {
                    System.out.println("    C++ : " + published[0].substring(0, Math.min(400, published[0].length())));
                    System.out.println("    Java: " + published[1].substring(0, Math.min(400, published[1].length())));
                }
            }
            }
        }
    }
}
