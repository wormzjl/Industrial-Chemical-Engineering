package com.wormzjl.icheme.science.column.v3;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Independent phase/root checks on captured states; does not run a column solve. */
public final class VacuumAnalyze {
    private VacuumAnalyze() {}
    public static void main(String[] args) throws Exception {
        MaterialRuntime.publish(MaterialCatalog.bundled());
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane");
        System.out.println("sequence,node,T_K,L_mol_s,V_mol_s,max_xy_difference,Z_liquid,Z_vapor,roots_liquid,roots_vapor,independent_flash_beta");
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            var row = JsonParser.parseString(line).getAsJsonObject();
            if (!row.has("sequence") || !row.has("liquid")) continue;
            int sequence = row.get("sequence").getAsInt();
            if (sequence != 130 && sequence != 131 && sequence != 132 && sequence != 1714 && sequence != 2384) continue;
            var active = row.getAsJsonArray("activeComponents");
            var liquid = row.getAsJsonArray("liquid");
            var vapor = row.getAsJsonArray("vapor");
            if (args.length > 1) {
                var original = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject().getAsJsonObject("input");
                double[] feed = new double[thermo.componentBasis().componentCount()];
                for (int c = 0; c < feed.length; c++) feed[c] = original.getAsJsonArray("feedComponentMolarFlowsMolPerSecond").get(c).getAsDouble();
                var specs = row.getAsJsonArray("specs");
                var input = new V3ColumnInput(1,thermo.packageId(),original.get("assayId").getAsString(),thermo.componentBasis(),feed,
                        row.get("feedT").getAsDouble(),row.get("stages").getAsInt(),row.get("feedStage").getAsInt(),
                        row.get("pressure").getAsDouble(),0,java.util.List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(specs.get(0).getAsJsonObject().get("kelvin").getAsDouble()),
                        new V3ColumnSpecification.OrganicRefluxRatio(specs.get(1).getAsJsonObject().get("ratio").getAsDouble()),
                        new V3ColumnSpecification.ReboilerDuty(specs.get(2).getAsJsonObject().get("watts").getAsDouble())),
                        java.util.List.of(),java.util.List.of(),java.util.List.of(),15);
                double[][] l = new double[liquid.size()][active.size()], v = new double[liquid.size()][active.size()];
                double[] t = new double[liquid.size()];
                for (int n = 0; n < t.length; n++) {
                    t[n] = row.getAsJsonArray("temperature").get(n).getAsDouble();
                    for (int c = 0; c < active.size(); c++) { l[n][c] = liquid.get(n).getAsJsonArray().get(c).getAsDouble(); v[n][c] = vapor.get(n).getAsJsonArray().get(c).getAsDouble(); }
                }
                var branch = Arrays.stream(v[0]).sum() == 0 ? V3CondenserPhaseBranch.LIQUID_ONLY : V3CondenserPhaseBranch.TWO_PHASE;
                var problem = V3ColumnProblemResolver.resolve(input,branch);
                var state = new V3DryMeshState(problem.topology(),active.size(),l,v,t);
                var support = V3TruncationSupport.derive(problem,0,state);
                var prepared = V3ColumnProblemResolver.withTruncation(problem,support,V3WetTraySet.dry(problem.topology()));
                state = support.projectSeed(problem,state);
                double total = Arrays.stream(feed).sum();
                double[] z = Arrays.stream(feed).map(f -> f/total).toArray();
                double h = thermo.flashTP(input.feedTemperatureKelvin(),input.topPressurePascal(),z,thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
                var audit = new V3AcceptanceAuditor(prepared,thermo,h,0.001).audit(state,thermo.newWorkspace(),V3SolveControl.UNBOUNDED);
                var residual = new V3MeshResidualEvaluator(prepared,thermo,h).evaluate(state,thermo.newWorkspace());
                var energy = residual.rows().stream().filter(r -> r.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE)
                        .max(java.util.Comparator.comparingDouble(r -> Math.abs(r.physicalValue()))).orElseThrow();
                System.err.printf(java.util.Locale.ROOT,"sequence %d: fresh audit accepted=%s; max energy=%.9g W; residual=%.9g%n",sequence,audit.accepted(),energy.physicalValue(),residual.maximumAbsoluteScaledResidual());
            }
            for (int n = 1; n < liquid.size(); n++) {
                double[] x = new double[thermo.componentBasis().componentCount()], y = x.clone();
                for (int c = 0; c < active.size(); c++) {
                    int index = thermo.componentBasis().componentIds().indexOf(active.get(c).getAsString());
                    x[index] = liquid.get(n).getAsJsonArray().get(c).getAsDouble();
                    y[index] = vapor.get(n).getAsJsonArray().get(c).getAsDouble();
                }
                double l = Arrays.stream(x).sum(), v = Arrays.stream(y).sum();
                if (l == 0 || v == 0) continue;
                double[] z = new double[x.length];
                double difference = 0;
                for (int c = 0; c < x.length; c++) {
                    z[c] = (x[c] + y[c]) / (l + v); x[c] /= l; y[c] /= v;
                    difference = Math.max(difference, Math.abs(x[c] - y[c]));
                }
                double t = row.getAsJsonArray("temperature").get(n).getAsDouble();
                double p = row.get("pressure").getAsDouble();
                var fl = thermo.fugacity(t,p,x,Root.LIQUID,thermo.newWorkspace());
                var fv = thermo.fugacity(t,p,y,Root.VAPOR,thermo.newWorkspace());
                var flash = thermo.flashTP(t,p,z,thermo.newWorkspace());
                System.out.printf(java.util.Locale.ROOT,"%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%d,%d,%.9g%n",
                        sequence,n,t,l,v,difference,fl.compressibilityFactor(),fv.compressibilityFactor(),
                        fl.physicalRootCount(),fv.physicalRootCount(),flash.vaporFraction());
            }
        }
    }
}
