import com.wormzjl.icheme.science.equipment.*;
public class Cells {
    public static void main(String[] a) {
        for (int m = 1; m <= 4; m++) {
            var s = EquipmentShape.unit(EquipmentShape.Kind.PLUNGER_PUMP, m, 1, EquipmentShape.Heading.NORTH, 0);
            var sb = new StringBuilder("model " + m + ":");
            for (var p : ShapeRasterizer.ports(s)) sb.append(" port ").append(p.port()).append(" #").append(p.nozzle()).append(" cell ").append(p.cell()).append(" d ").append(p.dx()).append(',').append(p.dy()).append(',').append(p.dz());
            for (var h : ShapeRasterizer.shafts(s)) sb.append(" | shaft cell ").append(h.cell()).append(" block ").append(h.block()).append(" d ").append(h.dx()).append(',').append(h.dy()).append(',').append(h.dz());
            int[] lo = {99,99,99}, hi = {-99,-99,-99};
            for (var c : ShapeRasterizer.layout(s).shell()) { lo[0]=Math.min(lo[0],c.x());lo[1]=Math.min(lo[1],c.y());lo[2]=Math.min(lo[2],c.z());hi[0]=Math.max(hi[0],c.x());hi[1]=Math.max(hi[1],c.y());hi[2]=Math.max(hi[2],c.z()); }
            sb.append(" | shell x ").append(lo[0]).append("..").append(hi[0]).append(" y ").append(lo[1]).append("..").append(hi[1]).append(" z ").append(lo[2]).append("..").append(hi[2]);
            System.out.println(sb);
        }
    }
}
