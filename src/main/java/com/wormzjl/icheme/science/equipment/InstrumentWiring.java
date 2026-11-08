package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import java.util.*;

/**
 * Equipment-owned, non-network instrument finish. No blocks, simulation nodes or collision are
 * added.
 */
public final class InstrumentWiring {
    private InstrumentWiring() {}

    public record Run(String source, List<double[]> points, double width) {
        public Run {
            points = points.stream().map(double[]::clone).toList();
        }
    }

    public record Drawing(List<Polygon> polygons, List<Run> runs, List<String> unresolved) {
        public Drawing {
            polygons = List.copyOf(polygons);
            runs = List.copyOf(runs);
            unresolved = List.copyOf(unresolved);
        }
    }

    private static final Set<Material> SKIN =
            Set.of(
                    Material.SHELL,
                    Material.HEAD,
                    Material.PIPE,
                    Material.FLANGE,
                    Material.SUPPORT,
                    Material.STEELWORK,
                    Material.CONCRETE,
                    Material.TOWER_PANEL,
                    Material.TOWER_LOUVER,
                    Material.TOWER_CONCRETE,
                    Material.TOWER_FLOOR,
                    Material.GRATING,
                    Material.SERVICE_CASE);
    private static final Set<Material> PROCESS =
            Set.of(
                    Material.SHELL,
                    Material.HEAD,
                    Material.PIPE,
                    Material.TOWER_PANEL,
                    Material.CONCRETE);

    static Drawing build(
            EquipmentShape shape,
            List<Polygon> nativePolys,
            EquipmentServices.Plan service,
            EquipmentInstruments.Plan instruments,
            Set<String> installed,
            RenderDetail detail) {
        var north = ServiceSurface.north(shape);
        var canonical = ServiceSurface.between(shape.transform(), north.transform());
        var turn = ServiceSurface.between(north.transform(), shape.transform());
        var carriers =
                ServiceSurface.turn(
                        nativePolys.stream().filter(p -> SKIN.contains(p.material())).toList(),
                        canonical);
        var surface = new ServiceSurface(carriers);
        var hardware = new Mesh(detail);
        SurfaceJoints[] joints = {null};
        var runs = new ArrayList<Run>();
        var failures = new ArrayList<String>();
        var sources = new ArrayList<double[]>();
        var probes = new ArrayList<EquipmentInstruments.Probe>();
        for (var physical : instruments.probes())
            if (!physical.manual() || installed.contains(physical.id())) {
                var p = physical.turn(canonical);
                probes.add(p);
                root(hardware, p, detail);
                double[] start = p.entry(), foot;
                try {
                    int axis = p.top() ? 1 : Math.abs(p.normal()[0]) > .5 ? 0 : 2;
                    int sign = p.top() ? 1 : p.normal()[axis] > 0 ? 1 : -1;
                    foot = surface.ray(axis, start, sign, PROCESS);
                    sources.add(foot);
                    for (var polygon : LineGeometry.conduit(List.of(start, foot), .020))
                        hardware.include(material(polygon, Material.INSTRUMENT_STEEL));
                } catch (IllegalArgumentException ex) {
                    // A very small head can overhang its shell. Bring the protected lead in along
                    // horizontal axes,
                    // then descend once onto the actual tap surface; never invent a vertical foot
                    // outside the vessel.
                    if (p.top())
                        try {
                            var footWanted = p.tap();
                            foot = surface.ray(1, footWanted, 1, PROCESS);
                            var a = start.clone();
                            a[0] = foot[0];
                            var b = a.clone();
                            b[2] = foot[2];
                            for (var polygon :
                                    LineGeometry.conduit(List.of(start, a, b, foot), .020))
                                hardware.include(material(polygon, Material.INSTRUMENT_STEEL));
                            sources.add(foot);
                        } catch (IllegalArgumentException missing) {
                            sources.add(p.tap());
                            failures.add(p.id() + ": " + missing.getMessage());
                        }
                    else {
                        sources.add(p.tap());
                        failures.add(p.id() + ": " + ex.getMessage());
                    }
                }
            }
        var entry = service.instrumentEntry();
        if (!probes.isEmpty() && entry != null) {
            var target = canonical.point(entry);
            var centre = new double[3];
            for (var p : sources) for (int a = 0; a < 3; a++) centre[a] += p[a] / sources.size();
            boolean roof =
                    shape.kind() == EquipmentShape.Kind.SPHERE
                            || shape.kind() == EquipmentShape.Kind.DRUM
                                    && !shape.orientation().horizontal();
            int axis =
                    roof || shape.kind() == EquipmentShape.Kind.TANK || shape.kind().coolingTower()
                            ? 0
                            : 2;
            // The baseline has one station transverse to the probe row; all sources join that same
            // line.
            var baseline = centre.clone();
            if (shape.kind() == EquipmentShape.Kind.TANK || shape.kind().coolingTower())
                baseline[1] = target[1];
            if (shape.kind() == EquipmentShape.Kind.EXCHANGER) {
                var skin = surface.bounds(Set.of(Material.SHELL));
                baseline[1] = Math.clamp(baseline[1], skin[1] + .05, skin[4] - .05);
            }
            double exit = target[axis];
            var body = surface.bounds(PROCESS);
            if (roof && shape.kind() != EquipmentShape.Kind.SPHERE)
                exit =
                        Math.clamp(
                                exit,
                                body[axis] + .12 * (body[axis + 3] - body[axis]),
                                body[axis + 3] - .12 * (body[axis + 3] - body[axis]));
            var exitWanted = baseline.clone();
            exitWanted[axis] = exit;
            try {
                var exitPoint = project(surface, exitWanted, roof, shape.kind());
                var joins = new ArrayList<double[]>();
                joins.add(exitPoint);
                for (int i = 0; i < probes.size(); i++) {
                    var src = sources.get(i);
                    var joinWanted = baseline.clone();
                    joinWanted[axis] = src[axis];
                    var join = project(surface, joinWanted, roof, shape.kind());
                    int branchAxis = axis;
                    add(
                            runs,
                            probes.get(i).id(),
                            section(
                                    carriers,
                                    src,
                                    join,
                                    branchAxis,
                                    runs,
                                    probes.get(i).id(),
                                    joints),
                            .020);
                    joins.add(join);
                }
                final int baselineAxis = axis;
                joins.sort(Comparator.comparingDouble(p -> p[baselineAxis]));
                add(
                        runs,
                        "baseline",
                        section(carriers, joins.getFirst(), joins.getLast(), roof ? 2 : 1),
                        .025);
                // First move across the roof in the orthogonal plan direction, then descend in the
                // cabinet section.
                var roofExit = exitPoint;
                if (roof && shape.kind() != EquipmentShape.Kind.SPHERE) {
                    var wanted = exitPoint.clone();
                    wanted[2] = target[2];
                    roofExit = surface.ray(1, wanted, 1, PROCESS);
                    add(runs, "controller_roof", section(carriers, exitPoint, roofExit, 0), .025);
                }
                var sectionTarget = target.clone();
                sectionTarget[roof ? 0 : axis] = roof ? roofExit[0] : exitPoint[axis];
                // A roof exit on a curved body and a leg/skirt entry can be in different X
                // stations.
                // Descend in Z for a roof, so X follows the physical head/skirt section rather than
                // making a diagonal.
                int sectionAxis = shape.kind() == EquipmentShape.Kind.SPHERE ? 0 : roof ? 2 : axis;
                var end = target.clone();
                end[sectionAxis] = roofExit[sectionAxis];
                var nearest = end;
                add(
                        runs,
                        "controller",
                        section(
                                carriers,
                                roofExit,
                                nearest,
                                sectionAxis,
                                runs,
                                "controller",
                                joints),
                        .025);
                if (SurfaceSection.distance(nearest, target) > .001) {
                    int constant = sectionAxis;
                    add(
                            runs,
                            "controller_entry",
                            section(carriers, nearest, target, constant),
                            .025);
                }
            } catch (IllegalArgumentException ex) {
                failures.add("shared baseline: " + ex.getMessage());
            }
        }
        var polygons = new ArrayList<Polygon>(hardware.facets().stream().map(Mesh::whole).toList());
        var seen = new HashSet<String>();
        for (var run : runs)
            if (!run.source.startsWith("concealed:")) {
                double total = 0, offset = 0;
                for (int i = 1; i < run.points.size(); i++)
                    total += SurfaceSection.distance(run.points.get(i - 1), run.points.get(i));
                for (int i = 1; i < run.points.size(); i++) {
                    var a = run.points.get(i - 1);
                    var b = run.points.get(i);
                    String key = key(a, b);
                    if (seen.add(key))
                        polygons.addAll(paint(carriers, a, b, run.width, offset, total));
                    offset += SurfaceSection.distance(a, b);
                }
            }
        return new Drawing(
                ServiceSurface.turn(polygons, turn),
                runs.stream()
                        .map(
                                r ->
                                        new Run(
                                                r.source,
                                                r.points.stream().map(turn::point).toList(),
                                                r.width))
                        .toList(),
                failures);
    }

    private static List<double[]> section(
            List<Polygon> carriers,
            double[] a,
            double[] b,
            int axis,
            List<Run> runs,
            String source,
            SurfaceJoints[] joints) {
        try {
            return section(carriers, a, b, axis);
        } catch (IllegalArgumentException disconnected) {
            if (joints[0] == null) joints[0] = new SurfaceJoints(carriers);
            var path = joints[0].route(a, b);
            for (var edge : path) {
                boolean concealed =
                        Set.of(Material.SUPPORT, Material.STEELWORK, Material.CONCRETE)
                                .contains(edge.material());
                if (concealed) {
                    add(runs, "concealed:" + source, List.of(edge.a(), edge.b()), .025);
                    continue;
                }
                // Flat casing runs are H/V in their native face. A curve changes coordinates only
                // as its facet projects them.
                var delta = sub(edge.b(), edge.a());
                int fixed = 0;
                for (int k = 1; k < 3; k++)
                    if (Math.abs(delta[k]) < Math.abs(delta[fixed])) fixed = k;
                var corner = edge.a().clone();
                int first = (fixed + 1) % 3;
                corner[first] = edge.b()[first];
                if (Math.abs(delta[fixed]) < 1e-7) {
                    add(runs, source, List.of(edge.a(), corner, edge.b()), .020);
                } else add(runs, source, List.of(edge.a(), edge.b()), .020);
            }
            return List.of(a);
        }
    }

    private static List<double[]> section(
            List<Polygon> carriers, double[] a, double[] b, int axis) {
        if (SurfaceSection.distance(a, b) < 1e-7) return List.of(a);
        return SurfaceSection.route(carriers, a, b, axis);
    }

    private static void add(List<Run> runs, String name, List<double[]> points, double width) {
        if (points.size() > 1) runs.add(new Run(name, points, width));
    }

    private static String key(double[] a, double[] b) {
        String x = Arrays.toString(Arrays.stream(a).map(v -> Math.rint(v * 1e7)).toArray()),
                y = Arrays.toString(Arrays.stream(b).map(v -> Math.rint(v * 1e7)).toArray());
        return x.compareTo(y) < 0 ? x + y : y + x;
    }

    private static double[] project(
            ServiceSurface surface, double[] point, boolean roof, EquipmentShape.Kind kind) {
        if (roof) return surface.ray(1, point, 1, PROCESS);
        if (kind == EquipmentShape.Kind.TANK || kind.coolingTower())
            return surface.ray(2, point, -1, PROCESS);
        if (kind == EquipmentShape.Kind.EXCHANGER)
            return surface.ray(0, point, 1, Set.of(Material.SHELL, Material.HEAD));
        return nearest(surface.polygons, point);
    }

    private static double[] nearest(List<Polygon> polys, double[] wanted) {
        double best = Double.POSITIVE_INFINITY;
        double[] answer = null;
        for (var poly : polys) {
            var v = poly.positions();
            for (int i = 1; i + 1 < poly.size(); i++) {
                var a = Arrays.copyOfRange(v, 0, 3);
                var b = Arrays.copyOfRange(v, 3 * i, 3 * i + 3);
                var c = Arrays.copyOfRange(v, 3 * i + 3, 3 * i + 6);
                var n = cross(sub(b, a), sub(c, a));
                double nn = dot(n, n);
                if (nn < 1e-18) continue;
                var q = sub(wanted, scale(n, dot(sub(wanted, a), n) / nn));
                if (!inside(q, a, b, c)) {
                    double shortest = Double.POSITIVE_INFINITY;
                    for (var edge :
                            List.of(
                                    new double[][] {a, b},
                                    new double[][] {b, c},
                                    new double[][] {c, a})) {
                        var p =
                                plus(
                                        edge[0],
                                        scale(
                                                sub(edge[1], edge[0]),
                                                SurfaceSection.amount(wanted, edge[0], edge[1])));
                        double d = SurfaceSection.distance(wanted, p);
                        if (d < shortest) {
                            shortest = d;
                            q = p;
                        }
                    }
                }
                double d = SurfaceSection.distance(wanted, q);
                if (d < best) {
                    best = d;
                    answer = q;
                }
            }
        }
        if (answer == null) throw new IllegalArgumentException("No wiring skin");
        return answer;
    }

    private static boolean inside(double[] p, double[] a, double[] b, double[] c) {
        var u = sub(b, a);
        var v = sub(c, a);
        var w = sub(p, a);
        double aa = dot(u, u),
                bb = dot(u, v),
                cc = dot(v, v),
                wu = dot(w, u),
                wv = dot(w, v),
                det = aa * cc - bb * bb;
        if (det < 1e-20) return false;
        double s = (wu * cc - wv * bb) / det, t = (wv * aa - wu * bb) / det;
        return s >= -1e-8 && t >= -1e-8 && s + t <= 1 + 1e-8;
    }

    private static List<Polygon> paint(
            List<Polygon> carriers,
            double[] a,
            double[] b,
            double width,
            double offset,
            double total) {
        var out = new ArrayList<Polygon>();
        var d = sub(b, a);
        double length = Math.sqrt(dot(d, d));
        if (length < 1e-7) return out;
        var along = scale(d, 1 / length);
        for (var poly : carriers) {
            var v = poly.positions();
            for (int i = 1; i + 1 < poly.size(); i++) {
                var triangle =
                        new ArrayList<double[]>(
                                List.of(
                                        Arrays.copyOfRange(v, 0, 3),
                                        Arrays.copyOfRange(v, 3 * i, 3 * i + 3),
                                        Arrays.copyOfRange(v, 3 * i + 3, 3 * i + 6)));
                var cross =
                        cross(
                                sub(triangle.get(1), triangle.getFirst()),
                                sub(triangle.get(2), triangle.getFirst()));
                double norm = Math.sqrt(dot(cross, cross));
                if (norm < 1e-12) continue;
                var n = scale(cross, 1 / norm);
                if (Math.abs(dot(sub(a, triangle.getFirst()), n)) > .001
                        || Math.abs(dot(sub(b, triangle.getFirst()), n)) > .001) continue;
                var side = unit(cross(n, along));
                // Extend to the carrier's edge across the full ribbon width. A perpendicular end at
                // a centreline
                // crossing leaves triangular holes when the next native facet changes slope.
                double overlap = width / 2 + .0001;
                List<double[]> clipped = triangle;
                clipped = clip(clipped, along, dot(a, along) - overlap, true);
                clipped = clip(clipped, along, dot(b, along) + overlap, false);
                clipped = clip(clipped, side, dot(a, side) - width / 2, true);
                clipped = clip(clipped, side, dot(a, side) + width / 2, false);
                if (clipped.size() < 3) continue;
                double[] pos = new double[clipped.size() * 3],
                        uv = new double[clipped.size() * 2],
                        normal = new double[pos.length];
                for (int k = 0; k < clipped.size(); k++) {
                    var p = clipped.get(k);
                    System.arraycopy(plus(p, scale(n, .0005)), 0, pos, 3 * k, 3);
                    System.arraycopy(n, 0, normal, 3 * k, 3);
                    uv[2 * k] = Math.clamp(.5 + dot(sub(p, a), side) / width, 0, 1);
                    uv[2 * k + 1] = Math.clamp((offset + dot(sub(p, a), along)) / total, 0, 1);
                }
                out.add(new Polygon(pos, uv, normal, Material.INSTRUMENT_CONDUIT));
            }
        }
        return out;
    }

    private static List<double[]> clip(
            List<double[]> input, double[] normal, double at, boolean positive) {
        var out = new ArrayList<double[]>();
        if (input.isEmpty()) return out;
        var a = input.getLast();
        double da = (dot(a, normal) - at) * (positive ? 1 : -1);
        for (var b : input) {
            double db = (dot(b, normal) - at) * (positive ? 1 : -1);
            if ((da >= -1e-9) != (db >= -1e-9)) out.add(plus(a, scale(sub(b, a), da / (da - db))));
            if (db >= -1e-9) out.add(b);
            a = b;
            da = db;
        }
        return out;
    }

    private static Polygon material(Polygon p, Material m) {
        return new Polygon(p.positions(), p.uvs(), p.normals(), m);
    }

    public static List<Polygon> rootHardware(
            EquipmentInstruments.Probe probe, RenderDetail detail) {
        var mesh = new Mesh(detail);
        root(mesh, probe, detail);
        return mesh.facets().stream().map(Mesh::whole).toList();
    }

    private static void root(Mesh mesh, EquipmentInstruments.Probe p, RenderDetail detail) {
        var end =
                p.top()
                        ? plus(p.centre(), new double[] {0, -.115, 0})
                        : plus(p.centre(), scale(p.normal(), -.065));
        var tap = p.tap();
        var axis = unit(sub(end, tap));
        double length = SurfaceSection.distance(end, tap);
        var frame =
                new Frame(
                        tap,
                        axis,
                        Math.abs(axis[1]) > .9 ? new double[] {1, 0, 0} : new double[] {0, 1, 0});
        boolean level = p.quantity() == EquipmentInstruments.Quantity.LEVEL;
        double bore = level ? .10 : p.manual() ? .08 : .06;
        mesh.cylinder(frame, bore / 2, -.012, length, detail.sides(12), Material.INSTRUMENT_STEEL);
        mesh.cylinder(
                frame, level ? .11 : .085, .012, .038, detail.sides(12), Material.INSTRUMENT_STEEL);
        mesh.cylinder(frame, level ? .07 : .055, .038, .09, 6, Material.INSTRUMENT_STEEL);
        if (p.bracket() != null)
            mesh.bar(
                    p.bracket(),
                    plus(p.centre(), new double[] {0, -.12, 0}),
                    .035,
                    new double[] {1, 0, 0},
                    Material.INSTRUMENT_STEEL);
        if (!p.manual()) {
            if (!p.display()) {
                var n = p.top() ? new double[] {0, 1, 0} : p.normal();
                var cap =
                        new Frame(
                                plus(p.centre(), scale(n, -.045)),
                                n,
                                Math.abs(n[1]) > .9
                                        ? new double[] {1, 0, 0}
                                        : new double[] {0, 1, 0});
                mesh.cylinder(cap, .055, 0, .09, 8, Material.INSTRUMENT_STEEL);
            }
        }
    }
}
