import com.wormzjl.icheme.science.equipment.*;
import java.util.*;
var keys = new LinkedHashMap<String, List<String>>();
double[] bores = {0.025, 0.05, 0.1, 0.15, 0.2, 0.3, 0.4};
for (int facing = 2; facing < 6; facing++) for (int corner = 0; corner < 6; corner++) {
  var rack = new RackGeometry.Rack(facing, corner);
  for (double bore : bores) {
    int code = PipeSizes.code(PipeSizes.outside(bore));
    int width = RackGeometry.width(PipeSizes.drawn(bore));
    for (int option = 0; option < RackGeometry.OPTIONS; option++)
      for (int a = 0; a < 6; a++) for (int b = a + 1; b < 6; b++) {
        int arms = 1 << a | 1 << b;
        if (!RackGeometry.valid(rack, option, width, arms)) continue;
        var lane = new RackGeometry.Lane(option, width, arms, 0, code);
        String key = option + "/" + width + "/" + arms + "/" + code + "/" + RackGeometry.points(rack, lane);
        keys.computeIfAbsent(key, k -> new ArrayList<>()).add(facing + ":" + corner);
      }
  }
}
int total = keys.size(), ambiguous = 0, cornerDiffers = 0;
var examples = new ArrayList<String>();
for (var e : keys.entrySet()) if (e.getValue().size() > 1) {
  ambiguous++;
  var corners = new HashSet<String>(); for (var r : e.getValue()) corners.add(r.split(":")[1]);
  if (corners.size() > 1) { cornerDiffers++; if (examples.size() < 8) examples.add(e.getKey() + " -> " + e.getValue()); }
}
System.out.println("lane keys=" + total + " ambiguous=" + ambiguous + " ambiguousAcrossShapes=" + cornerDiffers);
examples.forEach(System.out::println);
/exit
