import com.wormzjl.icheme.science.equipment.*;
public class Bores {
    public static void main(String[] a) {
        for (int m = 1; m <= 4; m++) {
            var s = EquipmentShape.unit(EquipmentShape.Kind.PLUNGER_PUMP, m, 1, EquipmentShape.Heading.NORTH, 0);
            for (int k = 0; k < s.nozzles().size(); k++) {
                var nz = s.nozzles().get(k);
                double b = NozzleBore.largest(s, k);
                System.out.printf(java.util.Locale.ROOT, "model %d %s design %.4f m, largest %.4f m (od %.4f), next clear %s%n", m, nz.role(), nz.defaultOd() / 1.1, b, PipeSizes.outside(b),
                        NozzleBore.clear(s, k, PipeSizes.outside(b + NozzleBore.STEP)));
            }
        }
    }
}
