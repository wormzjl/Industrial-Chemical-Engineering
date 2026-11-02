package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Which way a process stream passes each nozzle (batch 2026-10-26-multi-machine-framework, plan section 9): in, out, or
 * either way, and the arrows that show it. An assembly's validation reads a unit's inlets and outlets from it; the arrows
 * draw it on every kind's nozzle necks, and on valve and in-line mover castings along the line. A marker is a drawing,
 * static and independent of process state and collision: the fluid network does not enforce a nozzle's direction, as a
 * real nozzle does not.
 */
public final class PortMarkings {
    /** A band's room before the flange plate and its bolts, m. */
    private static final double FLANGE_CLEARANCE = .055;
    /** What a band leaves of the neck before the vessel, a support or a neighbour that hides the rest, m. */
    private static final double MARGIN = .02;
    /** The shortest band drawn, m. */
    static final double SHORTEST = .1;
    /** The step of the walk down a neck to where it is hidden, m. */
    private static final double STEP = .01;

    private PortMarkings() {
    }

    /** A nozzle's stream direction. */
    public enum Flow {
        IN, OUT, NONE
    }

    /**
     * The stream direction at {@code role} on a {@code kind}: a role named an inlet or an outlet by its name, the feeds,
     * returns and suctions in, the products, draws and discharges out. A drum's side connections take its feed (owner
     * answer Q3); a storage tank's or sphere's ports serve either way.
     */
    public static Flow flow(Kind kind, Role role) {
        if (kind.storage()) return Flow.NONE;
        return switch (role) {
            case SIDE -> kind == Kind.DRUM ? Flow.IN : Flow.NONE;
            case HOT_WATER_INLET, FEED, REFLUX, RETURN, STEAM, VAPOUR_RETURN, TUBE_INLET, SHELL_INLET, PROCESS_INLET, SUCTION, MOTIVE, FUEL, CONVECTION_INLET, RADIANT_INLET,
                 NITROGEN, COOLANT_INLET, REGENERATION_INLET, HYDROGEN, QUENCH, CATALYST_INLET, AIR, OXIDANT, WATER, CUTTING_WATER, SOLIDS_INLET, FEEDWATER -> Flow.IN;
            case COLD_WATER_OUTLET, VAPOUR_OUTLET, LIQUID_OUTLET, BOOT_DRAIN, DRAW, PUMPAROUND_DRAW, BOTTOMS, TUBE_OUTLET, SHELL_OUTLET, PROCESS_OUTLET, DISCHARGE, CONVECTION_OUTLET,
                 RADIANT_OUTLET, PRODUCT, COOLANT_OUTLET, REGENERATION_OUTLET, EFFLUENT, VENT, EXHAUST, FLUE_GAS, RELIEF, SYNGAS, DRAIN, CONDENSATE,
                 CATALYST_OUTLET, SOLIDS_OUTLET, BLOWDOWN -> Flow.OUT;
            case BOTTOM, OVERFLOW -> Flow.NONE;
        };
    }

    /**
     * The nozzle a stream entering a pass-through unit at {@code role} leaves by, or empty: a mover's or an ejector's
     * suction and discharge, an exchanger's or a kettle's tube side, an exchanger's shell side, an air cooler's process
     * line, and back the same way for a unit met from its outlet.
     */
    public static Optional<Role> partner(Kind kind, Role role) {
        boolean pump = kind.mover() || kind == Kind.EJECTOR;
        boolean tubes = kind == Kind.EXCHANGER || kind == Kind.KETTLE;
        return Optional.ofNullable(switch (role) {
            case SUCTION -> pump ? Role.DISCHARGE : null;
            case DISCHARGE -> pump ? Role.SUCTION : null;
            case TUBE_INLET -> tubes ? Role.TUBE_OUTLET : null;
            case TUBE_OUTLET -> tubes ? Role.TUBE_INLET : null;
            case SHELL_INLET -> kind == Kind.EXCHANGER ? Role.SHELL_OUTLET : null;
            case SHELL_OUTLET -> kind == Kind.EXCHANGER ? Role.SHELL_INLET : null;
            case PROCESS_INLET -> kind == Kind.AIR_COOLER ? Role.PROCESS_OUTLET : null;
            case PROCESS_OUTLET -> kind == Kind.AIR_COOLER ? Role.PROCESS_INLET : null;
            default -> null;
        });
    }

    /** One arrow band on each nozzle with a flow sign, clipped into the equipment cells that own its neck. */
    public static List<Mesh.Polygon> surface(ShapeRasterizer.CellKey key) {
        var shape = key.shape();
        var cell = new ShapeRasterizer.Cell(key.x(), key.y(), key.z());
        var nozzles = shape.nozzles();
        var out = new ArrayList<Mesh.Polygon>();
        int reaching = 0;
        for (int k = 0; k < nozzles.size(); k++) {
            if (!ShapeRasterizer.nozzleCells(shape, k).contains(cell)) continue;
            int code = (int) (key.nozzles() >>> (7 * reaching++)) & 127;
            var flow = flow(shape.kind(), nozzles.get(k).role());
            if (flow == Flow.NONE) continue;
            var mesh = new Mesh();
            mesh.transform(shape.transform());
            band(mesh, shape, k, code, flow);
            out.addAll(mesh.cell(cell.x(), cell.y(), cell.z()));
        }
        return List.copyOf(out);
    }

    /**
     * Nozzle {@code k}'s band at size {@code code} (0: its design size), in the shape's frame: an arrow on four facets round
     * the neck, pointing into the vessel for an inlet and out of it for an outlet. Each arrow ends clear of the flange plate
     * and its bolts, and is as long as the neck shows on its side, at most the pump's length: the vessel, a support or a
     * neighbour hides all but a few centimetres of some necks, and only some sides of others (a neck into a header tee,
     * through a skirt; plan 14.3). A side that shows less than {@link #SHORTEST} carries no arrow.
     */
    static void band(Mesh mesh, EquipmentShape shape, int k, int code, Flow flow) {
        var nozzle = shape.nozzles().get(k);
        double od = code == 0 ? nozzle.defaultOd() : PipeSizes.outsideOf(code);
        int sides = PipeSizes.sides(od);
        double end = nozzle.length() - PipeSizes.flangeThickness(od) - FLANGE_CLEARANCE;
        double longest = Math.min(.65, Math.max(.28, 1.6 * od)), facet = .82 * od * DeterministicMath.sin(Math.PI / sides);
        double radius = od / 2 * DeterministicMath.cos(Math.PI / sides);
        int sign = flow == Flow.IN ? -1 : 1;
        var frame = Mesh.Frame.of(nozzle.base(), nozzle.axis(), nozzle.sign());
        var tangent = frame.side();
        for (int side = 0; side < 4; side++) {
            // Across the whole facet: a curved wall frees a neck's edges where it does not free its middle, and a part's corner
            // can reach in between.
            double length = longest + MARGIN;
            for (double offset = -.5; offset <= .5; offset += .25)
                length = Math.min(length, shown(shape, k, code, frame, normal(frame, tangent, side * sides / 4 + .5 + offset, sides), od / 2 + .01, end, length));
            length -= MARGIN;
            if (length < SHORTEST) continue;
            // A short arrow on a wide line keeps an arrow's proportions.
            var normal = normal(frame, tangent, side * sides / 4 + .5, sides);
            badge(new Label(mesh, new Mesh.Frame(frame.origin(), frame.axis(), normal), radius, end - length / 2, sign), length, Math.min(facet, .6 * length));
        }
    }

    /** The direction out of the neck at {@code facets} facet widths round from the frame's reference. */
    private static double[] normal(Mesh.Frame frame, double[] tangent, double facets, int sides) {
        double angle = 2 * Math.PI * facets / sides, c = DeterministicMath.cos(angle), s = DeterministicMath.sin(angle);
        var normal = new double[3];
        for (int i = 0; i < 3; i++) normal[i] = c * frame.ref()[i] + s * tangent[i];
        return normal;
    }

    /**
     * How far back from {@code end} the neck's side along {@code normal} shows, at most {@code most}: up to the first point
     * at {@code radius} from its axis that the shape's solid hides, or the whole neck down to its base.
     */
    private static double shown(EquipmentShape shape, int k, int code, Mesh.Frame frame, double[] normal, double radius, double end, double most) {
        for (int i = 0; ; i++) {
            double back = i * STEP;
            if (back >= most) return most;
            if (back > end) return end;
            var p = frame.along(end - back);
            for (int a = 0; a < 3; a++) p[a] += radius * normal[a];
            if (ShapeRasterizer.hides(shape, k, code, p)) return Math.max(0, back - STEP);
        }
    }

    private record Plane(long nx, long ny, long nz, long radius) {
    }

    private static final class Patch {
        final double[] normal;
        final double radius;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, left = low, right = high;

        Patch(double[] normal, double radius) {
            this.normal = normal;
            this.radius = radius;
        }
    }

    private static void badge(Label label, double length, double width) {
        label.rect(-length / 2, -width / 2, length / 2, width / 2, .001, Mesh.Material.OPENING);
        label.rect(-.48 * length, -.45 * width, .48 * length, .45 * width, .0015, Mesh.Material.GUARD);
        // Two convex pieces keep the arrowhead sharp through the mesh's normal cell clipping.
        label.rect(-.4 * length, -.13 * width, .16 * length, .13 * width, .002, Mesh.Material.OPENING);
        label.polygon(List.of(new double[]{.12 * length, -.35 * width}, new double[]{.42 * length, 0}, new double[]{.12 * length, .35 * width}), .002, Mesh.Material.OPENING);
    }

    /** Paint on actual axial casting facets, within their available station/width bounds. */
    public static List<Mesh.Polygon> fitting(List<Mesh.Polygon> surface, int facing, double from, double to) {
        return fitting(surface, facing, from, to, Math.PI / 8);
    }

    /** A bonnet can leave diagonal facets clearer than the cardinal panels of a plain casting. */
    static List<Mesh.Polygon> fitting(List<Mesh.Polygon> surface, int facing, double from, double to, double angleOffset) {
        return fitting(surface, facing, from, to, angleOffset, false);
    }

    /** Two lateral arrow panels remain clear of an upright motor/gearbox, including overhanging castings. */
    static List<Mesh.Polygon> fittingOverhanging(List<Mesh.Polygon> surface, int facing, double from, double to) {
        return fitting(surface, facing, from, to, 0, true);
    }

    private static List<Mesh.Polygon> fitting(List<Mesh.Polygon> surface, int facing, double from, double to, double angleOffset, boolean overhang) {
        var mesh = new Mesh();
        var axis = LineGeometry.step(facing);
        var patches = new LinkedHashMap<Plane, Patch>();
        for (var polygon : surface) {
            if (polygon.material() != Mesh.Material.FLANGE || polygon.size() < 3) continue;
            double[] p = polygon.positions(), a = {p[3] - p[0], p[4] - p[1], p[5] - p[2]}, b = {p[6] - p[0], p[7] - p[1], p[8] - p[2]};
            var normal = Mesh.cross(a, b);
            double norm = Math.sqrt(Mesh.dot(normal, normal));
            if (norm < 1e-9) continue;
            for (int i = 0; i < 3; i++) normal[i] /= norm;
            if (Math.abs(Mesh.dot(normal, axis)) > 1e-6) continue;
            var side = Mesh.cross(axis, normal);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, left = low, right = high, radius = 0;
            for (int i = 0; i < p.length; i += 3) {
                double[] delta = {p[i] - .5, p[i + 1] - .5, p[i + 2] - .5};
                double along = Mesh.dot(delta, axis), across = Mesh.dot(delta, side);
                low = Math.min(low, along);
                high = Math.max(high, along);
                left = Math.min(left, across);
                right = Math.max(right, across);
                radius = Mesh.dot(delta, normal);
            }
            low = Math.max(from, low);
            high = Math.min(to, high);
            if (high <= low || radius <= 0) continue;
            var plane = new Plane(Math.round(normal[0] * 1e6), Math.round(normal[1] * 1e6), Math.round(normal[2] * 1e6), Math.round(radius * 1e6));
            var patch = patches.computeIfAbsent(plane, key -> new Patch(normal, Mesh.dot(new double[]{p[0] - .5, p[1] - .5, p[2] - .5}, normal)));
            patch.low = Math.min(patch.low, low);
            patch.high = Math.max(patch.high, high);
            patch.left = Math.min(patch.left, left);
            patch.right = Math.max(patch.right, right);
        }
        int bodyFace = facing | 1;
        var reference = LineGeometry.ref(bodyFace);
        var tangent = Mesh.cross(LineGeometry.step(bodyFace), reference);
        var selected = new LinkedHashSet<Patch>();
        for (int q = 0; q < (overhang ? 2 : 4); q++) {
            double angle = overhang ? (reference[1] == 0 ? 0 : Math.PI / 2) + q * Math.PI : angleOffset + q * Math.PI / 2;
            double c = DeterministicMath.cos(angle), s = DeterministicMath.sin(angle), best = -2;
            Patch choice = null;
            for (var patch : patches.values()) {
                double score = c * Mesh.dot(patch.normal, reference) + s * Mesh.dot(patch.normal, tangent);
                if (score > best) {
                    best = score;
                    choice = patch;
                }
            }
            if (choice != null) selected.add(choice);
        }
        for (var patch : selected) {
            var side = Mesh.cross(axis, patch.normal);
            double[] origin = {.5, .5, .5};
            for (int i = 0; i < 3; i++) origin[i] += .5 * (patch.left + patch.right) * side[i];
            badge(new Label(mesh, new Mesh.Frame(origin, axis, patch.normal), patch.radius, (patch.low + patch.high) / 2, 1), .92 * (patch.high - patch.low),
                    .80 * (patch.right - patch.left));
        }
        return overhang ? LineGeometry.overhanging(mesh) : List.copyOf(mesh.cell(0, 0, 0));
    }

    /** Local axes keep arrows aligned with flow for either nozzle role and every equipment heading. */
    private record Label(Mesh mesh, Mesh.Frame frame, double radius, double station, int flow) {
        void rect(double x0, double y0, double x1, double y1, double lift, Mesh.Material material) {
            polygon(List.of(new double[]{x0, y0}, new double[]{x1, y0}, new double[]{x1, y1}, new double[]{x0, y1}), lift, material);
        }

        void polygon(List<double[]> points, double lift, Mesh.Material material) {
            double[] normal = frame.ref(), side = frame.side(), axis = frame.axis();
            var centre = frame.along(station);
            var polygon = new ArrayList<double[]>(points.size());
            for (var point : points) {
                var p = new double[3];
                for (int a = 0; a < 3; a++) p[a] = centre[a] + (radius + lift) * normal[a] + flow * point[0] * axis[a] - flow * point[1] * side[a];
                polygon.add(p);
            }
            // Solid paint samples avoid borrowing the guard's perforation pattern for a small, legible stencil.
            var u = new Mesh.Uv(new double[3], new double[3], material == Mesh.Material.GUARD ? 1.5 / 16 : 8.5 / 16, 1);
            var v = new Mesh.Uv(new double[3], new double[3], .5 / 16, 1);
            var inside = centre.clone();
            for (int a = 0; a < 3; a++) inside[a] += (radius + lift - 1) * normal[a];
            mesh.add(polygon, inside, Mesh.FLAT, material, u, v);
        }
    }
}
