package idlen;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
public class RevisionLength {
    public static void main(String[] a) {
        var c = MaterialCatalog.bundled(); var m = FluidThermodynamics.forNetwork(c, "icheme:refinery");
        System.out.println("fluidRevision " + c.fluidRevision("icheme:refinery").length() + " hydrocarbon.revision " + m.hydrocarbon.revision().length()
            + " viscosity.revision " + m.viscosity.revision().length() + " thermodynamicRevision " + m.thermodynamicRevision().length());
    }
}
