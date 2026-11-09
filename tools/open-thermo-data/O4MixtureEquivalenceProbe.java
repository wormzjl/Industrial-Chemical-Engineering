package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O4, decision E12): the one-term {@link LiquidMixtureCorrection}
 * gives the numbers of the four-coefficient form it replaced ({@code symmetric_pair_groups_v1}, copied below verbatim from
 * commit 50d6c506f as {@link Old}) bit for bit, at coefficients [G, 0, 0, 0] with every participating record's descriptor
 * {participation 1, residue retention 0, activation slope 0}. The old participation is read from the parent commit's
 * property records ({@code git show 50d6c506f:<record>} into {@code tmp/open-thermo-o4/head-properties/}). Compared on every
 * bundled package: each assay it holds, single compounds, and 20,000 random mixtures (random zeros, amounts 1e-12 to 10)
 * at random temperatures 150 to 1200 K. Writes {@code tmp/open-thermo-o4/equivalence.txt}. Never committed under
 * {@code src/test}: copy it to {@code src/test/java/com/wormzjl/icheme/science/material/}, run
 * {@code ./gradlew test --offline --tests com.wormzjl.icheme.science.material.O4MixtureEquivalenceProbe}, move it back.
 */
class O4MixtureEquivalenceProbe {
    /** {@code LiquidMixtureCorrection} at 50d6c506f, its arithmetic verbatim (JSON readers dropped). */
    record Old(List<Double> coefficients, List<Descriptor> descriptors, double referenceKelvin, double slopeCapKelvin) {
        record Descriptor(double participation, double residueRetention, double activationSlope) {
            static final Descriptor NONE = new Descriptor(0, 0, 0);
        }
        public double logCorrection(double temperature, double[] amounts) {
            if (!Double.isFinite(temperature) || temperature <= 0 || amounts.length != descriptors.size())
                throw new IllegalArgumentException("Invalid liquid correction temperature or basis");
            double total=0;
            for (double n:amounts) {
                if (!Double.isFinite(n) || n<0) throw new IllegalArgumentException("Invalid liquid correction amount");
                total+=n;
            }
            if (!(total>0) || !Double.isFinite(total)) throw new IllegalArgumentException("Empty liquid correction mixture");
            double x=0,x2=0,xh=0,xh2=0,xe=0,xhe=0,x2e=0,xh2e=0;
            for (int i=0;i<amounts.length;i++) {
                var d=descriptors.get(i);double v=amounts[i]/total*d.participation(),h=v*d.residueRetention(),e=d.activationSlope();
                x+=v;x2+=v*v;xh+=h;xh2+=h*h;xe+=v*e;xhe+=h*e;x2e+=v*v*e;xh2e+=h*h*e;
            }
            double fa=x*x-x2,fh=xh*xh-xh2,ea=x*xe-x2e,eh=xh*xhe-xh2e;
            double q=1000*(1/Math.min(temperature,slopeCapKelvin)-1/referenceKelvin);
            return coefficients.get(0)*(fa-fh)+coefficients.get(1)*fh
                    +coefficients.get(2)*(ea-eh)*q+coefficients.get(3)*eh*q;
        }
    }

    @Test void oneTermEqualsTheOldFormBitForBit() throws Exception {
        var head = Path.of("tmp/open-thermo-o4/head-properties");
        var participation = new HashMap<String, Double>(); // by component id, from the parent commit's records
        try (var files = Files.list(head)) {
            for (var file : files.toList()) {
                JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                double p = o.has("liquid_mixture_descriptor") ? o.getAsJsonObject("liquid_mixture_descriptor").get("participation").getAsDouble() : 0;
                participation.merge(o.get("component").getAsString(), p, (a, b) -> { if (!a.equals(b)) throw new IllegalStateException(); return a; });
            }
        }
        var catalog = MaterialCatalog.bundled();
        var report = new StringBuilder();
        var random = new Random(20261104L);
        long compared = 0, differing = 0, nonzero = 0;
        for (var id : new java.util.TreeSet<>(catalog.packages().keySet())) {
            var pack = catalog.requirePackage(id);
            var now = catalog.liquidMixture(id);
            var axis = pack.components();
            var descriptors = new ArrayList<Old.Descriptor>();
            int participants = 0;
            for (String c : axis) {
                double p = participation.getOrDefault(c, 0.0);
                descriptors.add(p == 0 ? Old.Descriptor.NONE : new Old.Descriptor(p, 0, 0));
                if (p != 0) participants++;
            }
            assertEquals(participants, now.participants().size(), id + ": the method's list names the old participants");
            for (int i : now.participants()) assertEquals(1.0, participation.get(axis.get(i)), id + ": " + axis.get(i));
            var old = new Old(List.of(now.coefficient(), 0., 0., 0.), descriptors, 323.15, 373.15);
            var cases = new ArrayList<double[]>();
            for (var assay : pack.assays().values()) cases.add(assay.amounts().stream().mapToDouble(Double::doubleValue).toArray());
            for (int i = 0; i < axis.size(); i++) { double[] n = new double[axis.size()]; n[i] = 3.7; cases.add(n); }
            for (int s = 0; s < 20000; s++) {
                double[] n = new double[axis.size()];
                boolean any = false;
                for (int i = 0; i < n.length; i++) if (random.nextDouble() < 0.6) { n[i] = Math.pow(10, -12 + 13 * random.nextDouble()); any = true; }
                if (!any) n[random.nextInt(n.length)] = 1;
                cases.add(n);
            }
            long packDiffering = 0;
            for (double[] n : cases) {
                double t = 150 + 1050 * random.nextDouble();
                double a = old.logCorrection(t, n), b = now.logCorrection(n);
                compared++;
                if (a != 0) nonzero++;
                if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) { differing++; packDiffering++; }
            }
            report.append(id).append(": ").append(axis.size()).append(" compounds, ").append(participants).append(" participants, ")
                    .append(cases.size()).append(" mixtures (").append(pack.assays().size()).append(" assays), differing bits ")
                    .append(packDiffering).append('\n');
        }
        report.append("total compared ").append(compared).append(", non-zero terms ").append(nonzero).append(", differing bits ").append(differing).append('\n');
        Files.createDirectories(Path.of("tmp/open-thermo-o4"));
        Files.writeString(Path.of("tmp/open-thermo-o4/equivalence.txt"), report);
        assertEquals(0, differing, report.toString());
        assertTrue(nonzero > compared / 2);
    }
}
