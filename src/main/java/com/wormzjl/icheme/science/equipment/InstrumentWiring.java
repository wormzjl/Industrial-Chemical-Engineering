package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import java.util.*;

/**
 * Equipment-owned, non-network instrument finish. No blocks, simulation nodes or collision are
 * added.
 *
 * <p>Batch 2026-11-10-custom-shader-system (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.4, W2): each
 * transmitter's cable leaves its head through an Ex gland in the head's bottom M20 entry and drops
 * in a drip loop to the skin (owner X9 A); a 20 mm branch carries that one light-blue IS cable on
 * its strap to the baseline, a 25 mm perforated tray that carries every cable joined beyond it
 * (owner X10 A) to the service enclosure's side entry. Finish v runs in metres along each run; the
 * textures repeat every {@link #PERIOD} (owner X1).
 */
public final class InstrumentWiring {
    private InstrumentWiring() {}

    /** Texture period along every run, m: one saddle or tie per period (owner X1). */
    public static final double PERIOD = .5;

    /** Most cables a tray texture shows; more cables show this many (owner X10 A). */
    public static final int TRAY_MAX = 6;

    /** Branch and tray ribbon widths, m. */
    static final double BRANCH = .020, TRAY = .025;

    /** Single-pair armoured IS cable, m across its sheath (real cables are 10 to 14 mm). */
    static final double CABLE = .012;

    /** The Ex gland below the head's entry, m long (hex body and shroud), and the drip loop's drop below it, m. */
    static final double GLAND = .045, DROP = .09;

    /**
     * One wiring run. {@code cables} holds, per segment between consecutive points, how many cables
     * that segment carries.
     */
    public record Run(String source, List<double[]> points, double width, List<Integer> cables) {
        public Run {
            points = points.stream().map(double[]::clone).toList();
            cables = List.copyOf(cables);
            if (cables.size() != points.size() - 1)
                throw new IllegalArgumentException("One cable count per segment");
        }

        /** A 25 mm run is a tray of cables (X10 A); a 20 mm run is one branch cable on its strap. */
        public boolean tray() {
            return width >= TRAY - 1e-9;
        }
    }

    /**
     * What a wiring polygon shows: steel hardware on the block atlas ({@code cables == 0}), the
     * branch cable (ribbon or drip-loop tube) or a tray of {@code cables}.
     */
    public record Finish(int cables, boolean tray) {
        public static final Finish HARDWARE = new Finish(0, false), BRANCH = new Finish(1, false);

        /** The texture under {@code textures/wiring/}, or null for atlas hardware. */
        public String texture() {
            return cables == 0 ? null : tray ? "tray_" + Math.min(cables, TRAY_MAX) : "branch";
        }
    }

    /** The structure's wiring; {@code finishes} holds one entry per polygon, in order. */
    public record Drawing(
            List<Polygon> polygons, List<Run> runs, List<String> unresolved, List<Finish> finishes) {
        public Drawing {
            polygons = List.copyOf(polygons);
            runs = List.copyOf(runs);
            unresolved = List.copyOf(unresolved);
            finishes = List.copyOf(finishes);
            if (finishes.size() != polygons.size())
                throw new IllegalArgumentException("One finish per polygon");
        }
    }

    /**
     * {@code polygons} for a block-atlas sprite (constructor ghost, offline drawings): each finish
     * polygon's v folded from metres into the one period it starts in, so it stays on its sprite.
     */
    public static List<Polygon> atlas(List<Polygon> polygons) {
        return polygons.stream()
                .map(
                        p -> {
                            if (p.material() != Material.INSTRUMENT_CONDUIT) return p;
                            var uv = p.uvs().clone();
                            double low = Double.POSITIVE_INFINITY;
                            for (int k = 1; k < uv.length; k += 2) low = Math.min(low, uv[k]);
                            double start = Math.floor(low / PERIOD);
                            for (int k = 1; k < uv.length; k += 2)
                                uv[k] = Math.clamp(uv[k] / PERIOD - start, 0, 1);
                            return new Polygon(p.positions(), uv, p.normals(), p.material());
                        })
                .toList();
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
        var cables = new ArrayList<Polygon>();
        for (var physical : instruments.probes())
            if (!physical.manual() || installed.contains(physical.id())) {
                var p = physical.turn(canonical);
                probes.add(p);
                root(hardware, p, detail);
                try {
                    sources.add(lead(surface, hardware, cables, p, detail));
                } catch (IllegalArgumentException missing) {
                    sources.add(p.tap());
                    failures.add(p.id() + ": " + missing.getMessage());
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
                var stations = new double[probes.size()];
                int all = probes.size();
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
                                    joints,
                                    BRANCH,
                                    1),
                            BRANCH,
                            1);
                    joins.add(join);
                    stations[i] = join[axis];
                }
                final int baselineAxis = axis;
                joins.sort(Comparator.comparingDouble(p -> p[baselineAxis]));
                var trunk =
                        section(carriers, joins.getFirst(), joins.getLast(), roof ? 2 : 1);
                if (trunk.size() > 1)
                    runs.add(tray("baseline", trunk, axis, exitPoint[axis], stations));
                // First move across the roof in the orthogonal plan direction, then descend in the
                // cabinet section.
                var roofExit = exitPoint;
                if (roof && shape.kind() != EquipmentShape.Kind.SPHERE) {
                    var wanted = exitPoint.clone();
                    wanted[2] = target[2];
                    roofExit = surface.ray(1, wanted, 1, PROCESS);
                    add(
                            runs,
                            "controller_roof",
                            section(carriers, exitPoint, roofExit, 0),
                            TRAY,
                            all);
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
                                joints,
                                TRAY,
                                all),
                        TRAY,
                        all);
                if (SurfaceSection.distance(nearest, target) > .001) {
                    int constant = sectionAxis;
                    add(
                            runs,
                            "controller_entry",
                            section(carriers, nearest, target, constant),
                            TRAY,
                            all);
                }
            } catch (IllegalArgumentException ex) {
                failures.add("shared baseline: " + ex.getMessage());
            }
        }
        var polygons = new ArrayList<Polygon>(hardware.facets().stream().map(Mesh::whole).toList());
        var finishes = new ArrayList<Finish>(Collections.nCopies(polygons.size(), Finish.HARDWARE));
        polygons.addAll(cables);
        finishes.addAll(Collections.nCopies(cables.size(), Finish.BRANCH));
        var seen = new HashSet<String>();
        for (var run : runs)
            if (!run.source.startsWith("concealed:")) {
                double offset = 0;
                for (int i = 1; i < run.points.size(); i++) {
                    var a = run.points.get(i - 1);
                    var b = run.points.get(i);
                    String key = key(a, b);
                    if (seen.add(key)) {
                        var painted = paint(carriers, a, b, run.width, offset, run.tray());
                        polygons.addAll(painted);
                        finishes.addAll(
                                Collections.nCopies(
                                        painted.size(),
                                        new Finish(run.cables.get(i - 1), run.tray())));
                    }
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
                                                r.width,
                                                r.cables))
                        .toList(),
                failures,
                finishes);
    }

    /**
     * The ATEX entry at a transmitter head (owner X9 A): an Ex gland screwed down into the head's
     * bottom M20 entry, then the cable as a tube dropping in a drip loop below the gland, so water
     * runs off its lowest point before it reaches the gland, to the skin, where its branch starts.
     * Returns that foot.
     */
    private static double[] lead(
            ServiceSurface surface,
            Mesh hardware,
            List<Polygon> cables,
            EquipmentInstruments.Probe p,
            RenderDetail detail) {
        var seat = seat(p);
        if (p.top()) {
            double[] foot;
            List<double[]> path;
            try {
                foot = surface.ray(1, seat, 1, PROCESS);
                var exit = down(seat, gland(hardware, seat, seat[1] - foot[1] - .01, detail));
                path = List.of(exit, foot);
            } catch (IllegalArgumentException overhang) {
                // A very small head can overhang its shell. Bring the cable in along horizontal
                // axes, then descend once onto the actual tap surface; never invent a vertical
                // foot outside the vessel.
                foot = surface.ray(1, p.tap(), 1, PROCESS);
                var exit = down(seat, gland(hardware, seat, GLAND, detail));
                var a = exit.clone();
                a[0] = foot[0];
                var b = a.clone();
                b[2] = foot[2];
                path = List.of(exit, a, b, foot);
            }
            cables.addAll(tube(path, detail));
            return foot;
        }
        int axis = Math.abs(p.normal()[0]) > .5 ? 0 : 2, sign = p.normal()[axis] > 0 ? 1 : -1;
        // The skin behind the head, where the straight lead used to meet it.
        var level = surface.ray(axis, p.entry(), sign, PROCESS);
        var exit = down(seat, gland(hardware, seat, GLAND, detail));
        var wanted = exit.clone();
        wanted[1] -= .03;
        var foot = level;
        try {
            // Meet the skin a little below the gland, on the same skin; else rise to the old foot.
            var low = surface.ray(axis, wanted, sign, PROCESS);
            if (Math.abs(low[axis] - level[axis]) < .06) foot = low;
        } catch (IllegalArgumentException kept) {
            // No skin below the head: the loop rises to the level foot.
        }
        var out = new double[3];
        out[axis] = sign;
        cables.addAll(tube(loop(exit, foot, out), detail));
        return foot;
    }

    /** The cable entry on the head's bottom face, 60 mm (80 mm for level) beside its axis. */
    static double[] seat(EquipmentInstruments.Probe p) {
        var right = cross(new double[] {0, 1, 0}, p.normal());
        double offset = p.quantity() == EquipmentInstruments.Quantity.LEVEL ? .080 : .060;
        return plus(
                p.centre(),
                plus(scale(right, offset), plus(new double[] {0, -.115, 0}, scale(p.normal(), -.025))));
    }

    /**
     * The gland under {@code seat}, at most {@code room} long: a nickel-plated hex body (32 mm
     * across flats) and the shroud round the cable. Returns its length.
     */
    private static double gland(Mesh mesh, double[] seat, double room, RenderDetail detail) {
        double length = Math.clamp(room, 0, GLAND);
        if (length < .004) return 0;
        var frame = new Frame(seat, new double[] {0, -1, 0}, new double[] {1, 0, 0});
        mesh.cylinder(frame, .0185, -.003, .4 * length, 6, Material.INSTRUMENT_STEEL);
        mesh.cylinder(frame, .0125, .4 * length, length, detail.sides(10), Material.INSTRUMENT_STEEL);
        return length;
    }

    private static double[] down(double[] p, double by) {
        return new double[] {p[0], p[1] - by, p[2]};
    }

    /**
     * The drip loop from the gland's exit to the skin at {@code foot}: a cubic curve that drops
     * {@link #DROP} below the exit and meets the skin from {@code out}, its lowest point below both
     * ends.
     */
    static List<double[]> loop(double[] exit, double[] foot, double[] out) {
        var p1 = down(exit, DROP);
        var p2 = down(plus(foot, scale(out, .03)), .8 * DROP);
        var path = new ArrayList<double[]>();
        int steps = 10;
        for (int k = 0; k <= steps; k++) {
            double t = (double) k / steps, s = 1 - t;
            path.add(
                    plus(
                            plus(scale(exit, s * s * s), scale(p1, 3 * s * s * t)),
                            plus(scale(p2, 3 * s * t * t), scale(foot, t * t * t))));
        }
        return path;
    }

    /**
     * The cable along {@code path} as a {@link #CABLE} tube: u round the sheath on the branch
     * texture's sheath half (0.5 to 1), v in metres along the path, radial normals.
     */
    static List<Polygon> tube(List<double[]> points, RenderDetail detail) {
        var out = new ArrayList<Polygon>();
        var path = new ArrayList<double[]>();
        for (var p : points)
            if (path.isEmpty() || SurfaceSection.distance(path.getLast(), p) > 1e-6) path.add(p);
        if (path.size() < 2) return out;
        int sides = detail.sides(8);
        double r = CABLE / 2;
        double[] ref = null;
        double[][] previous = null, previousNormal = null;
        double v = 0, previousV = 0;
        for (int i = 0; i < path.size(); i++) {
            var at = path.get(i);
            var tangent =
                    unit(sub(path.get(Math.min(i + 1, path.size() - 1)), path.get(Math.max(i - 1, 0))));
            if (ref == null)
                ref = unit(cross(tangent, Math.abs(tangent[1]) > .9 ? new double[] {1, 0, 0} : new double[] {0, 1, 0}));
            else ref = unit(sub(ref, scale(tangent, dot(ref, tangent))));
            var side = cross(tangent, ref);
            var ring = new double[sides + 1][];
            var normal = new double[sides + 1][];
            for (int k = 0; k <= sides; k++) {
                double angle = 2 * Math.PI * k / sides;
                normal[k] = plus(scale(ref, Math.cos(angle)), scale(side, Math.sin(angle)));
                ring[k] = plus(at, scale(normal[k], r));
            }
            if (i > 0) v += SurfaceSection.distance(path.get(i - 1), at);
            if (previous != null)
                for (int k = 0; k < sides; k++) {
                    double u0 = .5 + .5 * k / sides, u1 = .5 + .5 * (k + 1) / sides;
                    var pos = new double[12];
                    var nrm = new double[12];
                    double[][] corners = {previous[k], previous[k + 1], ring[k + 1], ring[k]},
                            normals = {previousNormal[k], previousNormal[k + 1], normal[k + 1], normal[k]};
                    for (int c = 0; c < 4; c++) {
                        System.arraycopy(corners[c], 0, pos, 3 * c, 3);
                        System.arraycopy(normals[c], 0, nrm, 3 * c, 3);
                    }
                    out.add(
                            new Polygon(
                                    pos,
                                    new double[] {u0, previousV, u1, previousV, u1, v, u0, v},
                                    nrm,
                                    Material.INSTRUMENT_CONDUIT));
                }
            previous = ring;
            previousNormal = normal;
            previousV = v;
        }
        return out;
    }

    /**
     * The baseline as a tray (owner X10 A), split at every join station and at the exit along
     * {@code axis}: each segment carries the cables joined beyond it, on its far side from the exit
     * at {@code exit}.
     */
    static Run tray(String name, List<double[]> points, int axis, double exit, double[] joins) {
        var out = new ArrayList<double[]>();
        var cables = new ArrayList<Integer>();
        var stations = Arrays.copyOf(joins, joins.length + 1);
        stations[joins.length] = exit;
        out.add(points.getFirst());
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            var cuts = new TreeMap<Double, double[]>();
            double span = b[axis] - a[axis];
            if (Math.abs(span) > 1e-9)
                for (double s : stations) {
                    double t = (s - a[axis]) / span;
                    if (t > 1e-6 && t < 1 - 1e-6) cuts.put(t, plus(a, scale(sub(b, a), t)));
                }
            cuts.put(1.0, b);
            var from = a;
            for (var to : cuts.values()) {
                cables.add(carried((from[axis] + to[axis]) / 2, exit, joins));
                out.add(to);
                from = to;
            }
        }
        return new Run(name, out, TRAY, cables);
    }

    /** The cables a baseline station carries: those joined beyond it, away from the exit; at least one. */
    static int carried(double station, double exit, double[] joins) {
        int beyond = 0, before = 0;
        for (double s : joins) {
            if (s > station + 1e-9) beyond++;
            if (s < station - 1e-9) before++;
        }
        int n = station > exit + 1e-9 ? beyond : station < exit - 1e-9 ? before : Math.max(beyond, before);
        return Math.max(1, n);
    }

    private static List<double[]> section(
            List<Polygon> carriers,
            double[] a,
            double[] b,
            int axis,
            List<Run> runs,
            String source,
            SurfaceJoints[] joints,
            double width,
            int cables) {
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
                    add(runs, "concealed:" + source, List.of(edge.a(), edge.b()), width, cables);
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
                    add(runs, source, List.of(edge.a(), corner, edge.b()), width, cables);
                } else add(runs, source, List.of(edge.a(), edge.b()), width, cables);
            }
            return List.of(a);
        }
    }

    private static List<double[]> section(
            List<Polygon> carriers, double[] a, double[] b, int axis) {
        if (SurfaceSection.distance(a, b) < 1e-7) return List.of(a);
        return SurfaceSection.route(carriers, a, b, axis);
    }

    private static void add(
            List<Run> runs, String name, List<double[]> points, double width, int cables) {
        if (points.size() > 1)
            runs.add(new Run(name, points, width, Collections.nCopies(points.size() - 1, cables)));
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

    static List<Polygon> paint(
            List<Polygon> carriers,
            double[] a,
            double[] b,
            double width,
            double offset,
            boolean tray) {
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
                    // u across: the whole tray texture, or the branch texture's ribbon half; v in
                    // metres along the run, so the texture repeats every PERIOD.
                    double across = Math.clamp(.5 + dot(sub(p, a), side) / width, 0, 1);
                    uv[2 * k] = tray ? across : .5 * across;
                    uv[2 * k + 1] = offset + dot(sub(p, a), along);
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
