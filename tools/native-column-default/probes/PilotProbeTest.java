package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Probe (not a gate): does the cold-start gate's nominal PC03/PC10 pilot separate? */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class PilotProbeTest {
    @Test
    void probe() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        for (int c : new int[] {6, 13}) System.out.printf("pilot component %d %s: Tc %.1f K, Pc %.0f kPa, omega %.3f, M %.1f g/mol%n", c,
                thermo.componentBasis().componentId(c), thermo.componentCriticalTemperatureKelvin(c),
                thermo.componentCriticalPressurePascal(c) / 1000.0, thermo.componentAcentricFactor(c),
                thermo.componentMolecularWeightKgPerMol(c) * 1000.0);
        double[][] settings = {{550.0, 0.0}, {550.0, 0.05e6}, {450.0, 2.0e6}};
        for (double[] setting : settings) {
            double[] feed = new double[thermo.componentBasis().componentCount()];
            feed[6] = 50.0;
            feed[13] = 50.0;
            V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                    thermo.componentBasis(), feed, setting[0], 2, 1, 250_000.0, 750.0, List.of(
                            new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                            new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                            new V3ColumnSpecification.ReboilerDuty(setting[1])));
            V3ColumnOutcome outcome = V3TestInputs.calculate(input);
            StringBuilder line = new StringBuilder(String.format("pilot 2 trays feed %.0f K reboiler %.2f MW: ", setting[0], setting[1] / 1e6));
            if (outcome instanceof V3ColumnOutcome.Success s) {
                for (V3ColumnStreamProperties st : s.result().streams()) {
                    line.append(String.format(" %s %.3f mol/s %.1f K x=", st.streamId(), st.molarFlowMolPerSecond(), st.temperatureKelvin()));
                    for (var cf : st.moleFractions()) if (cf.moleFraction() > 1e-9) line.append(String.format("%s:%.4f ", cf.componentId(), cf.moleFraction()));
                }
                line.append(" audit accepted=").append(s.result().acceptanceAudit().accepted());
            } else line.append(((V3ColumnOutcome.Failure) outcome).code());
            System.out.println(line);
        }
    }
}
