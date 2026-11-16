package idlen;
import com.wormzjl.icheme.science.material.MaterialCatalog;
public class IdentityLength {
    public static void main(String[] a) {
        var c = MaterialCatalog.bundled();
        for (var id : c.packages().keySet()) {
            String r = c.fluidRevision(id);
            int seg = r.indexOf(";critical-volume="), end = seg < 0 ? -1 : r.indexOf(';', seg + 1);
            System.out.println(id + " compounds " + c.requirePackage(id).components().size() + " fluid identity " + r.length() + " (critical-volume segment " + (seg < 0 ? 0 : end - seg) + ")");
        }
    }
}
