import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;

/**
 * Prints the default column template's design and footprint, its ports' cells, and the same for the least exchanger, pump
 * and a lying drum, so that a review world's plant can be laid out. The argument is the fraction of the template's column
 * area (default 0.0625). Not a gate; see the folder's README.
 */
public class ReviewWorldLayoutProbe {
    public static void main(String[] a) {
        var catalog = MaterialCatalog.bundled();
        var in = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        double k = Double.parseDouble(a.length > 0 ? a[0] : "0.0625");
        System.out.println(in + " D=" + in.columnDiameterMetres() + " specs=" + in.specifications());
        var choices = new ColumnComponents.Choices(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD, ColumnComponents.Pump.STANDARD, ColumnComponents.Pump.STANDARD));
        var design = ColumnDesign.of(in, choices);
        System.out.println("trays=" + design.trays().trays() + " feed=" + design.trays().feed() + " D=" + design.diameter() + " comps=" + design.components());
        double d = Math.sqrt(k) * in.columnDiameterMetres();
        var shape = EquipmentShape.column(d, design.trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(d));
        System.out.println("misfit=" + ColumnDesign.misfit(shape));
        box("column", shape);
        for (var p : ShapeRasterizer.ports(shape)) System.out.println("  " + shape.nozzles().get(p.nozzle()).role() + " cell=" + p.cell() + " d=" + p.dx() + "," + p.dy() + "," + p.dz());
        for (var kind : List.of(EquipmentShape.Kind.EXCHANGER, EquipmentShape.Kind.PUMP)) {
            var ud = UnitDesign.of(kind);
            System.out.println(kind + " size " + ud.minSize() + ".." + ud.maxSize());
            var s = EquipmentShape.designed(kind, ud.minSize(), ud.defaultLength(ud.minSize()), EquipmentShape.Heading.NORTH, ud.defaultSupport(ud.minSize(), ud.defaultLength(ud.minSize())), ProcessUnit.Bank.ONE);
            box(kind.name(), s);
            for (var p : ShapeRasterizer.ports(s)) System.out.println("  " + s.nozzles().get(p.nozzle()).role() + " cell=" + p.cell() + " d=" + p.dx() + "," + p.dy() + "," + p.dz());
        }
        var drum = EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 5, EquipmentShape.defaultConnections(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, 5, true), true);
        box("drum", drum);
        for (var p : ShapeRasterizer.ports(drum)) System.out.println("  " + drum.nozzles().get(p.nozzle()).role() + " cell=" + p.cell() + " d=" + p.dx() + "," + p.dy() + "," + p.dz());
    }
    static void box(String name, EquipmentShape s) {
        var l = ShapeRasterizer.layout(s);
        int[] lo = {9999, 9999, 9999}, hi = {-9999, -9999, -9999};
        for (var c : l.shell()) { lo[0] = Math.min(lo[0], c.x()); lo[1] = Math.min(lo[1], c.y()); lo[2] = Math.min(lo[2], c.z()); hi[0] = Math.max(hi[0], c.x()); hi[1] = Math.max(hi[1], c.y()); hi[2] = Math.max(hi[2], c.z()); }
        System.out.println(name + " x " + lo[0] + ".." + hi[0] + " y " + lo[1] + ".." + hi[1] + " z " + lo[2] + ".." + hi[2]);
    }
}
