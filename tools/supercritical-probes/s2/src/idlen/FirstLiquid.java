package idlen;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
public class FirstLiquid {
    public static void main(String[] a) {
        var c = MaterialCatalog.bundled(); var m = FluidThermodynamics.forNetwork(c, "icheme:refinery"); var pack = c.requirePackage("icheme:refinery");
        String[] names = {"Nitrogen", "Methane", "CarbonDioxide"}; double[] trs = {2.40, 1.55, 1.05};
        for (int k = 0; k < 3; k++) {
            var pr = pack.properties().get(pack.components().indexOf(names[k])).pr(); double t = trs[k] * pr.criticalTemperature();
            double[] n = new double[m.hydrocarbon.componentCount() + 1]; n[m.hydrocarbon.components().indexOf(names[k])] = 1;
            double pip = Double.NaN, dens = Double.NaN, dPip = 0, iPip = 0;
            for (double p = 1e5; p <= 6e7; p += 1e4) {
                var tr = m.hydrocarbon.phase(t, p, n, PengRobinsonKernel.Root.VAPOR).traits();
                if (Double.isNaN(pip) && PhaseIdentification.liquidLike(tr.identificationParameter(), tr.compressibility())) { pip = p; dPip = tr.reducedDensity(); iPip = tr.identificationParameter(); }
                if (Double.isNaN(dens) && tr.reducedDensity() >= 1) dens = p;
                if (!Double.isNaN(pip) && !Double.isNaN(dens)) break;
            }
            System.out.printf(java.util.Locale.ROOT, "%s Tr %.2f (%.1f K): today's rule first liquid at %.2f MPa (delta %.3f, PIP %.3f); O2 A at %.2f MPa (delta 1)%n", names[k], trs[k], t, pip / 1e6, dPip, iPip, dens / 1e6);
        }
    }
}
