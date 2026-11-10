package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.*;

/** Equipment-owned service housings. Geometry, sockets and physical solids share this plan. */
public final class EquipmentServices {
    private EquipmentServices() {}

    /**
     * A low-voltage motor's or generator's service case over its terminal box's point: across its
     * axis, high and along it, m. {@link ProcessUnit} keeps its frame box round it.
     */
    static final double CASE_ACROSS = .4, CASE_HEIGHT = .3, CASE_ALONG = .3;

    /**
     * The case's floor above the shaft of a low-voltage machine of frame radius {@code r}: on a
     * generator's stator panels, on a TEFC motor's fins.
     */
    static double caseFloor(EquipmentShape.Kind kind, double r) {
        return kind == EquipmentShape.Kind.GENERATOR ? .76 * r : r - .02;
    }

    public record Enclosure(double[] low, double[] high, int face) {
        public Enclosure {
            low = low.clone();
            high = high.clone();
        }

        @Override
        public double[] low() {
            return low.clone();
        }

        @Override
        public double[] high() {
            return high.clone();
        }

        public double[] centre() {
            return new double[] {
                (low[0] + high[0]) / 2, (low[1] + high[1]) / 2, (low[2] + high[2]) / 2
            };
        }

        boolean inside(double[] p) {
            for (int a = 0; a < 3; a++) if (p[a] < low[a] || p[a] > high[a]) return false;
            return true;
        }
    }

    public static final class Plan {
        private final List<Enclosure> cases;
        private final SocketGeometry.Socket data, power;
        private final List<Polygon> polygons;
        private final double[] entry;

        private Plan(
                List<Enclosure> cases,
                SocketGeometry.Socket data,
                SocketGeometry.Socket power,
                List<Polygon> polygons,
                double[] entry) {
            this.cases = List.copyOf(cases);
            this.data = data;
            this.power = power;
            this.polygons = List.copyOf(polygons);
            this.entry = entry == null ? null : entry.clone();
        }

        public List<Enclosure> enclosures() {
            return cases;
        }

        public SocketGeometry.Socket data() {
            return data;
        }

        public SocketGeometry.Socket power() {
            return power;
        }

        public List<Polygon> polygons() {
            return polygons;
        }

        public double[] instrumentEntry() {
            return entry == null ? null : entry.clone();
        }

        boolean inside(double[] world) {
            return cases.stream().anyMatch(c -> c.inside(world));
        }
    }

    private static final Set<Material> SUPPORT =
            Set.of(Material.SUPPORT, Material.STEELWORK, Material.CONCRETE);
    private static final Set<Material> MOUNT =
            Set.of(
                    Material.SUPPORT,
                    Material.STEELWORK,
                    Material.CONCRETE,
                    Material.SHELL,
                    Material.TOWER_PANEL);

    static Plan build(EquipmentShape shape, Mesh nativeMesh, ProcessUnit unit) {
        return build(shape, nativeMesh, unit, null);
    }

    static Plan build(EquipmentShape shape, Mesh nativeMesh, ProcessUnit unit, Plan physical) {
        var north = ServiceSurface.north(shape);
        var toNorth = ServiceSurface.between(shape.transform(), north.transform());
        var fromNorth = ServiceSurface.between(north.transform(), shape.transform());
        var nativePolys =
                ServiceSurface.turn(
                        nativeMesh.facets().stream()
                                .filter(f -> f.part() < 0)
                                .map(Mesh::whole)
                                .toList(),
                        toNorth);
        var surface = new ServiceSurface(nativePolys);
        var mesh = new Mesh(nativeMesh.detail());
        var cases = new ArrayList<Enclosure>();
        double[] anchor = null, low = null, high = null;
        int front = 5;
        boolean deck = false, reuse = false;
        var kind = shape.kind();
        double size = shape.diameter();
        var zones = clearances(north, unit);
        double height = 1.5;
        switch (kind) {
            case DRUM -> {
                if (shape.orientation().horizontal()) {
                    anchor = support(surface, 1.3, true, zones);
                } else {
                    double a = north.axis(),
                            offset = Math.min(Math.max(.6, .16 * size), .6 * size / 2),
                            z = a + offset;
                    boolean skirt = north.support() > 0;
                    double y =
                            skirt
                                    ? Math.min(height, Math.max(.12, north.bottomTangent() * .6))
                                    : north.bottomTangent()
                                            + Math.min(.5, north.tangentLength() / 2);
                    anchor =
                            mountingPoint(
                                    surface,
                                    0,
                                    new double[] {0, y, z},
                                    1,
                                    skirt
                                            ? Set.of(Material.SUPPORT)
                                            : Set.of(Material.SHELL, Material.HEAD),
                                    a);
                }
            }
            case COLUMN -> {
                double a = north.axis();
                anchor =
                        mountingPoint(
                                surface,
                                0,
                                new double[] {
                                    0, 1.5, a + Math.min(Math.max(.6, .16 * size), .6 * size / 2)
                                },
                                1,
                                Set.of(Material.SUPPORT),
                                a);
            }
            case TANK -> {
                double a = TankDesign.Plan.of(size, (int) shape.tangentLength()).a(),
                        x = a + Math.min(1.7, .65 * size / 2);
                front = 2;
                anchor =
                        surface.ray(
                                2,
                                north.transform().point(new double[] {x, 1.5, 0}),
                                -1,
                                Set.of(Material.SHELL));
            }
            case SPHERE -> {
                var plan = SphereDesign.Plan.of(size, shape.support());
                var foot = north.transform().point(plan.at(plan.legs(), plan.leg(0), 0));
                front = 2;
                anchor =
                        surface.ray(
                                2,
                                new double[] {foot[0], 1.5, foot[2]},
                                -1,
                                Set.of(Material.SUPPORT));
                for (double y : new double[] {1.36, 1.64})
                    mesh.ring(
                            Frame.of(foot, 1, 1),
                            plan.wl() / 2 - .003,
                            plan.wl() / 2 + .025,
                            y - .025,
                            y + .025,
                            12,
                            3,
                            Material.SERVICE_CASE);
            }
            case STEEL_STACK, BRICK_CHIMNEY -> {
                // A chimney's junction box set into its foot's side (batch 2026-11-10-chimney, as the flare's): a steel stack's
                // plinth, a brick chimney's shell; its cover flush, its gland on the bottom block's centre, inside the frame.
                var body = Set.of(kind == EquipmentShape.Kind.STEEL_STACK ? Material.SUPPORT : Material.CONCRETE);
                var foot = surface.bounds(body);
                double y = .5, z = (foot[2] + foot[5]) / 2;
                double face = Math.min(mountingPoint(surface, 0, new double[] {0, y, z}, 1, body, z)[0], north.sizeX() - .06);
                low = new double[] {face - .12, y - .14, z - .14};
                high = new double[] {face, y + .14, z + .14};
            }
            case FLARE_STACK -> {
                // A flare's junction box set into its plinth's side at the foot (batch 2026-11-09-flare-stack; owner 2026-11-09:
                // on the bottom, blended with the model): its cover flush with the plinth, its gland on the bottom block's
                // centre and inside the frame, clear of the gas inlet at the front.
                var foot = surface.bounds(Set.of(Material.SUPPORT));
                double y = .5, z = (foot[2] + foot[5]) / 2;
                double face = Math.min(mountingPoint(surface, 0, new double[] {0, y, z}, 1, Set.of(Material.SUPPORT), z)[0], north.sizeX() - .06);
                low = new double[] {face - .12, y - .14, z - .14};
                high = new double[] {face, y + .14, z + .14};
            }
            case EXCHANGER, KETTLE, AIR_COOLER, COMPRESSOR, VERTICAL_HEATER, CABIN_HEATER ->
                    anchor = support(surface, height, false, zones);
            case PUMP, CENTRIFUGAL_COMPRESSOR -> {
                var plate = surface.bounds(Set.of(Material.PUMP_BASE));
                double scale = kind == EquipmentShape.Kind.PUMP ? Math.min(1, size / .4) : .7;
                double width = kind == EquipmentShape.Kind.PUMP ? Math.max(.28, .5 * scale) : .35,
                        h = kind == EquipmentShape.Kind.PUMP ? Math.max(.26, .4 * scale) : .30,
                        depth = kind == EquipmentShape.Kind.PUMP ? Math.max(.18, .3 * scale) : .25;
                double x = plate[3] - .025 - depth / 2, z = plate[5] - .05 - width / 2;
                anchor = surface.ray(1, new double[] {x, 0, z}, 1, Set.of(Material.PUMP_BASE));
                low = new double[] {x - depth / 2, anchor[1] + .015, z - width / 2};
                high = plus(low, new double[] {depth, h, width});
                deck = true;
            }
            case INDUCED_DRAFT_COOLING_TOWER -> {
                front = 2;
                anchor =
                        surface.ray(
                                2,
                                north.transform().point(new double[] {size - .5, 1, 0}),
                                -1,
                                Set.of(Material.TOWER_PANEL));
            }
            case NATURAL_DRAFT_COOLING_TOWER -> {
                double a = size / 2 + .5;
                anchor =
                        surface.ray(
                                0,
                                north.transform().point(new double[] {0, 1, a + 1.5}),
                                1,
                                Set.of(Material.CONCRETE));
            }
            case MOTOR, GENERATOR -> {
                if (unit == null || unit.terminalBox == null) return empty();
                var c = north.transform().point(unit.terminalBox);
                if (shape.duty().power() > MotorDesign.LOW_VOLTAGE) {
                    var f = serviceSide(north);
                    SocketGeometry.Socket data = null, power = null;
                    for (boolean electrical : new boolean[] {false, true}) {
                        var n = electrical ? neg(f) : f;
                        int sign = n[0] > 0 ? 1 : -1;
                        var point =
                                surface.ray(
                                        0,
                                        new double[] {0, c[1], c[2]},
                                        sign,
                                        Set.of(
                                                Material.MOTOR_PLAIN,
                                                Material.MOTOR_PANEL,
                                                Material.GENERATOR_PANEL));
                        var lo =
                                new double[] {
                                    point[0] + (sign > 0 ? .015 : -.195), c[1] - .15, c[2] - .16
                                };
                        var hi = plus(lo, new double[] {.18, .30, .32});
                        int face = sign > 0 ? 5 : 4;
                        enclosure(mesh, lo, hi, face, true);
                        cases.add(new Enclosure(lo, hi, face));
                        var socket = socket(lo, hi, n);
                        if (electrical) power = socket;
                        else data = socket;
                        double back = sign > 0 ? lo[0] : hi[0];
                        mesh.box(
                                new double[] {
                                    Math.min(back, point[0] - .002 * sign), c[1] - .12, c[2] - .13
                                },
                                new double[] {
                                    Math.max(back, point[0] - .002 * sign), c[1] + .12, c[2] + .13
                                },
                                ALL_FACES,
                                Material.SERVICE_CASE);
                    }
                    return finish(cases, data, power, mesh, fromNorth, null);
                }
                double r = ProcessUnit.motorDiameter(shape.duty().power()) / 2;
                double shaftY = north.transform().point(unit.shaftEnds().getFirst().face())[1];
                double base = shaftY + caseFloor(kind, r);
                low = new double[] {c[0] - CASE_ACROSS / 2, base, c[2] - CASE_ALONG / 2};
                high = plus(low, new double[] {CASE_ACROSS, CASE_HEIGHT, CASE_ALONG});
                front = 2;
            }
            // A turbine's governor enclosure; an electric still's terminal box (batch 2026-11-09-batch-distiller): its data
            // socket to the front and its FE socket opposite, to the back, each on a free cell of the right column (owner,
            // 2026-11-10). A fired still has no data port: only the electric still takes a controller (owner, 2026-11-09).
            case STEAM_TURBINE, BATCH_STILL -> {
                if (kind == EquipmentShape.Kind.BATCH_STILL && !electricStill(shape)) return empty();
                if (unit == null || unit.serviceHousing == null)
                    throw new IllegalArgumentException("Missing native service enclosure: " + kind);
                var b = unit.serviceHousing;
                low = north.transform().point(Arrays.copyOfRange(b, 0, 3));
                high = north.transform().point(Arrays.copyOfRange(b, 3, 6));
                front = kind == EquipmentShape.Kind.BATCH_STILL ? 2 : 4;
                reuse = true;
            }
            default -> {
                return empty();
            }
        }
        if (low == null) {
            double width = Math.clamp(size * .5, .28, .5),
                    h = Math.clamp(size * .5, .28, .4),
                    depth = Math.clamp(size * .4, .2, .3);
            var b = mount(surface, anchor, front, width, h, depth);
            // A nozzle's neck at its largest pipe keeps its room: the case gives way to it (owner decision 2026-10-21).
            var necks = necks(north);
            if (kind != EquipmentShape.Kind.TANK && !(fits(north, b) && clearOf(necks, b))) {
                var fitted = fitPad(surface, north, anchor, front, width, h, depth, zones, necks);
                // Where no pad clears the necks, the case stays where the frame holds it; the nozzle gate reports it.
                if (fitted == null && !fits(north, b))
                    fitted = fitPad(surface, north, anchor, front, width, h, depth, zones, List.of());
                if (fitted == null && !fits(north, b))
                    throw new IllegalArgumentException(
                            "No native service pad inside the equipment placement frame: " + kind);
                if (fitted != null) {
                    anchor = fitted[0];
                    b = new double[][] {fitted[1], fitted[2]};
                }
            }
            low = b[0];
            high = b[1];
        }
        if (kind == EquipmentShape.Kind.TANK) {
            double safe = Double.POSITIVE_INFINITY;
            for (double x : new double[] {low[0], (low[0] + high[0]) / 2, high[0]})
                safe =
                        Math.min(
                                safe,
                                surface.ray(
                                                2,
                                                new double[] {x, anchor[1], 0},
                                                -1,
                                                Set.of(Material.SHELL))[2]);
            high[2] = safe - .03;
            low[2] = high[2] - .30;
        }
        if (physical != null && !physical.cases.isEmpty()) {
            var c = physical.cases.getFirst();
            low = toNorth.point(c.low);
            high = toNorth.point(c.high);
            for (int a = 0; a < 3; a++)
                if (low[a] > high[a]) {
                    double x = low[a];
                    low[a] = high[a];
                    high[a] = x;
                }
            front = face(toNorth.direction(LineGeometry.step(c.face)));
        }
        if (low[1] < .035) {
            double lift = .035 - low[1];
            low[1] += lift;
            high[1] += lift;
        }
        if (!reuse) enclosure(mesh, low, high, front, true);
        cases.add(new Enclosure(low, high, front));
        if (anchor != null) {
            if (deck)
                mesh.box(
                        new double[] {low[0] + .025, anchor[1] - .004, low[2] + .03},
                        new double[] {high[0] - .025, low[1], high[2] - .03},
                        ALL_FACES,
                        Material.SERVICE_CASE);
            else brackets(mesh, surface, anchor, low, high, front);
        }
        double[] dataSide = serviceSide(north);
        if (dataSide == null) dataSide = LineGeometry.step(front);
        var data = socket(low, high, dataSide);
        SocketGeometry.Socket power = null;
        if (powered(kind)) {
            if (kind != EquipmentShape.Kind.MOTOR && kind != EquipmentShape.Kind.GENERATOR)
                data =
                        socket(
                                low,
                                high,
                                front == 5 ? new double[] {0, 0, -1} : new double[] {-1, 0, 0});
            power = socket(low, high, neg(LineGeometry.step(data.face())));
        } else if (electricStill(shape)) {
            // An electric still's FE gland (batch 2026-11-09-batch-distiller, W6): as every powered unit's, on its terminal
            // box's face opposite the data socket.
            power = socket(low, high, neg(LineGeometry.step(data.face())));
        }
        double[] entry = null;
        if (anchor != null && !deck) {
            entry = anchor.clone();
            int axis = front == 5 ? 0 : 2, other = axis == 0 ? 2 : 0;
            entry[axis] = front == 5 ? low[axis] : high[axis];
            entry[1] = low[1] + .07;
            double[] centre = surface.bounds(MOUNT);
            entry[other] += (centre[other] + centre[other + 3] >= 2 * anchor[other] ? 1 : -1) * .09;
        }
        return finish(cases, data, power, mesh, fromNorth, entry);
    }

    static boolean powered(EquipmentShape.Kind kind) {
        return switch (kind) {
            // A fired heater's combustion-air fans (batch 2026-11-09-furnace-fuel-power).
            case MOTOR, GENERATOR, AIR_COOLER, INDUCED_DRAFT_COOLING_TOWER, VERTICAL_HEATER, CABIN_HEATER -> true;
            default -> false;
        };
    }

    /** Whether {@code shape} is a batch still heated electrically: its terminal box takes FE (batch 2026-11-09-batch-distiller, W6). */
    public static boolean electricStill(EquipmentShape shape) {
        return shape.kind() == EquipmentShape.Kind.BATCH_STILL && shape.still().heater() == BatchStillOptions.Heater.ELECTRIC;
    }

    private static Plan empty() {
        return new Plan(List.of(), null, null, List.of(), null);
    }

    private static Plan finish(
            List<Enclosure> cases,
            SocketGeometry.Socket data,
            SocketGeometry.Socket power,
            Mesh mesh,
            Transform turn,
            double[] entry) {
        var transformed = new ArrayList<Enclosure>();
        for (var c : cases) {
            var a = turn.point(c.low);
            var b = turn.point(c.high);
            transformed.add(
                    new Enclosure(
                            new double[] {
                                Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])
                            },
                            new double[] {
                                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])
                            },
                            face(turn.direction(LineGeometry.step(c.face)))));
        }
        return new Plan(
                transformed,
                turn(data, turn),
                turn(power, turn),
                ServiceSurface.turn(mesh.facets().stream().map(Mesh::whole).toList(), turn),
                entry == null ? null : turn.point(entry));
    }

    private static SocketGeometry.Socket turn(
            SocketGeometry.Socket socket, Transform turn) {
        if (socket == null) return null;
        var p = turn.point(new double[] {socket.x(), socket.y(), socket.z()});
        return new SocketGeometry.Socket(
                p[0], p[1], p[2], face(turn.direction(LineGeometry.step(socket.face()))), false);
    }

    static int face(double[] n) {
        return n[0] > .5 ? 5 : n[0] < -.5 ? 4 : n[2] > .5 ? 3 : 2;
    }

    static SocketGeometry.Socket socket(double[] low, double[] high, double[] normal) {
        int axis = Math.abs(normal[0]) > .5 ? 0 : 2;
        var p =
                new double[] {
                    (low[0] + high[0]) / 2, (low[1] + high[1]) / 2, (low[2] + high[2]) / 2
                };
        p[axis] = (normal[axis] > 0 ? high[axis] : low[axis]) + normal[axis] * .04;
        return new SocketGeometry.Socket(p[0], p[1], p[2], face(normal), false);
    }

    private static double[] serviceSide(EquipmentShape shape) {
        var shafts = shape.shaftEnds();
        if (shafts.isEmpty()) return null;
        var s = shafts.getFirst();
        var n = new double[3];
        n[s.axis()] = s.sign();
        n = shape.transform().direction(n);
        if (s.role() != EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT) n = neg(n);
        return unit(cross(n, new double[] {0, 1, 0}));
    }

    private static void enclosure(Mesh mesh, double[] lo, double[] hi, int face, boolean cover) {
        mesh.box(lo, hi, ALL_FACES, Material.SERVICE_CASE);
        if (cover) {
            int axis = face == 4 || face == 5 ? 0 : 2;
            double sign = face == 3 || face == 5 ? 1 : -1;
            var a = plus(lo, new double[] {.025, .025, .025});
            var b = sub(hi, new double[] {.025, .025, .025});
            double at = sign > 0 ? hi[axis] : lo[axis];
            a[axis] = Math.min(at, at + sign * .012);
            b[axis] = Math.max(at, at + sign * .012);
            mesh.box(a, b, ALL_FACES, Material.SERVICE_COVER);
        }
    }

    private static double[][] mount(
            ServiceSurface surface,
            double[] point,
            int face,
            double width,
            double height,
            double depth) {
        int axis = face == 5 ? 0 : 2, other = axis == 0 ? 2 : 0;
        int sign = face == 5 ? 1 : -1;
        var lo = point.clone();
        var hi = point.clone();
        lo[1] -= height / 2;
        hi[1] += height / 2;
        lo[other] -= width / 2;
        hi[other] += width / 2;
        double a = point[axis] + sign * .03, b = a + sign * depth;
        lo[axis] = Math.min(a, b);
        hi[axis] = Math.max(a, b);
        return new double[][] {lo, hi};
    }

    private static boolean fits(EquipmentShape shape, double[][] box) {
        int[] size = {shape.sizeX(), shape.sizeY(), shape.sizeZ()};
        for (int a = 0; a < 3; a++)
            if (box[0][a] < -.00001 || box[1][a] > size[a] + .00001) return false;
        return true;
    }

    /**
     * Keep an enclosure in the existing frame and clear of the nozzle necks by moving along a real
     * carrier, shrinking only when space requires it; null where no pad does.
     */
    private static double[][] fitPad(
            ServiceSurface surface,
            EquipmentShape shape,
            double[] anchor,
            int face,
            double width,
            double height,
            double depth,
            List<double[]> zones,
            List<double[]> necks) {
        int axis = face == 5 ? 0 : 2, other = axis == 0 ? 2 : 0, sign = face == 5 ? 1 : -1;
        var sizes =
                List.of(
                        new double[] {width, height, depth},
                        new double[] {
                            Math.min(width, .3), Math.min(height, .3), Math.min(depth, .18)
                        },
                        new double[] {.22, .28, .12});
        var slid = slide(surface, shape, anchor, face, other, sizes, zones, necks);
        if (slid != null) return slid;
        // A narrow frame may have no room outside its outer leg. Its inward-facing native leg face
        // is still a supported pad.
        double best = Double.POSITIVE_INFINITY;
        double[][] chosen = null;
        for (var polygon : surface.polygons) {
            if (!MOUNT.contains(polygon.material())) continue;
            var v = polygon.positions();
            var n =
                    cross(
                            sub(Arrays.copyOfRange(v, 3, 6), Arrays.copyOfRange(v, 0, 3)),
                            sub(Arrays.copyOfRange(v, 6, 9), Arrays.copyOfRange(v, 0, 3)));
            if (n[axis] * sign <= 1e-9) continue;
            var point = new double[3];
            for (int i = 0; i < v.length; i++) point[i % 3] += v[i] / polygon.size();
            var box =
                    mount(
                            surface,
                            point,
                            face,
                            Math.min(width, .3),
                            Math.min(height, .3),
                            Math.min(depth, .18));
            double cost = dot(sub(point, anchor), sub(point, anchor));
            if (cost < best
                    && fits(shape, box)
                    && ServiceSurface.clear(point, zones)
                    && clearOf(necks, box)) {
                best = cost;
                chosen = new double[][] {point, box[0], box[1]};
            }
        }
        if (chosen != null) return chosen;
        // Where the necks fill the carrier's width at the case's height (a small vessel whose nozzles
        // take pipes larger than itself), the case moves up or down the carrier, down to a small
        // junction box.
        var small = new ArrayList<>(sizes);
        small.add(new double[] {.2, .16, .1});
        return slide(surface, shape, anchor, face, 1, small, zones, necks);
    }

    /**
     * The first pad, in {@code sizes} order, from {@code anchor} along frame axis {@code along} in
     * 0.025 m steps either way, whose case fits the frame and clears the zones and the necks; null
     * where none does.
     */
    private static double[][] slide(
            ServiceSurface surface,
            EquipmentShape shape,
            double[] anchor,
            int face,
            int along,
            List<double[]> sizes,
            List<double[]> zones,
            List<double[]> necks) {
        int axis = face == 5 ? 0 : 2, sign = face == 5 ? 1 : -1;
        for (var size : sizes)
            for (int step = 0; step <= 80; step++)
                for (int side : new int[] {1, -1}) {
                    var wanted = anchor.clone();
                    wanted[along] += side * step * .025;
                    double[] point;
                    try {
                        point = surface.ray(axis, wanted, sign, MOUNT);
                    } catch (IllegalArgumentException missing) {
                        continue;
                    }
                    var box = mount(surface, point, face, size[0], size[1], size[2]);
                    if (fits(shape, box) && ServiceSurface.clear(point, zones) && clearOf(necks, box))
                        return new double[][] {point, box[0], box[1]};
                }
        return null;
    }

    private static void brackets(
            Mesh mesh,
            ServiceSurface surface,
            double[] anchor,
            double[] lo,
            double[] hi,
            int face) {
        int axis = face == 5 ? 0 : 2, other = axis == 0 ? 2 : 0, sign = face == 5 ? 1 : -1;
        var target = new double[] {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        target[axis] = sign > 0 ? lo[axis] : hi[axis];
        // A bracket roots on the carrier behind the case's back; a face the ray meets in front of it (past a narrow
        // carrier's edge) is no root. The brackets stay on the case: 0.09 m either side, less on a small junction box.
        double across = Math.min(.09, (hi[other] - lo[other]) / 2 - .02), up = Math.min(.09, (hi[1] - lo[1]) / 2 - .02);
        for (int side : new int[] {-1, 1}) {
            var a = anchor.clone();
            a[other] += side * across;
            var b = target.clone();
            b[other] += side * across;
            try {
                a = surface.rayNear(axis, a, sign, MOUNT, anchor[axis]);
                if ((a[axis] - target[axis]) * sign > 1e-9)
                    throw new IllegalArgumentException("A bracket root in front of the case");
            } catch (IllegalArgumentException narrow) {
                a = anchor.clone();
                a[1] += side * up;
                b = target.clone();
                b[1] += side * up;
                a = mountingPoint(surface, axis, a, sign, MOUNT, anchor[other]);
                // Where the carrier's outermost face stands in front of the case, the bracket roots on the anchor's plane.
                if ((a[axis] - target[axis]) * sign > 1e-9) a[axis] = anchor[axis];
            }
            var elbow = a.clone();
            elbow[axis] = b[axis];
            bar(mesh, a, elbow, .018);
            bar(mesh, elbow, b, .018);
        }
    }

    private static void bar(Mesh mesh, double[] a, double[] b, double r) {
        double[] lo = new double[3], hi = new double[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(a[i], b[i]) - r;
            hi[i] = Math.max(a[i], b[i]) + r;
        }
        mesh.box(lo, hi, ALL_FACES, Material.SERVICE_CASE);
    }

    private static double[] mountingPoint(
            ServiceSurface surface,
            int axis,
            double[] wanted,
            int sign,
            Set<Material> materials,
            double centre) {
        try {
            return surface.ray(axis, wanted, sign, materials);
        } catch (IllegalArgumentException interrupted) {
            double best = Double.POSITIVE_INFINITY;
            double[] chosen = null;
            for (var polygon : surface.polygons) {
                if (!materials.contains(polygon.material())) continue;
                var v = polygon.positions();
                var n =
                        cross(
                                sub(Arrays.copyOfRange(v, 3, 6), Arrays.copyOfRange(v, 0, 3)),
                                sub(Arrays.copyOfRange(v, 6, 9), Arrays.copyOfRange(v, 0, 3)));
                if (sign * n[axis] < 1e-10) continue;
                var p = new double[3];
                for (int i = 0; i < v.length; i++) p[i % 3] += v[i] / polygon.size();
                double score = 0;
                for (int a = 0; a < 3; a++)
                    if (a != axis) score += DeterministicMath.pow(p[a] - wanted[a], 2);
                if (score < best) {
                    best = score;
                    chosen = p;
                }
            }
            if (chosen != null) return surface.rayNear(axis, chosen, sign, materials, chosen[axis]);
            throw interrupted;
        }
    }

    private static double[] support(
            ServiceSurface surface, double height, boolean first, List<double[]> zones) {
        try {
            return surface.support(height, first, zones);
        } catch (IllegalArgumentException unavailable) {
            var b = surface.bounds(SUPPORT);
            for (double y :
                    new double[] {
                        Math.clamp(
                                height,
                                b[1] + Math.min(.21, (b[4] - b[1]) / 2),
                                b[4] - Math.min(.21, (b[4] - b[1]) / 2)),
                        (b[1] + b[4]) / 2
                    })
                try {
                    return surface.support(y, first, zones);
                } catch (IllegalArgumentException ignored) {
                }
            throw unavailable;
        }
    }

    /**
     * The room each nozzle's neck and flange take at its largest pipe, boxes {x0, y0, z0, x1, y1, z1} in the
     * frame: a column's nozzle takes what its place clears ({@link NozzleBore}), so a column has none.
     */
    static List<double[]> necks(EquipmentShape north) {
        if (north.kind() == EquipmentShape.Kind.COLUMN) return List.of();
        var result = new ArrayList<double[]>();
        var t = north.transform();
        for (int k = 0; k < north.nozzles().size(); k++) {
            var nozzle = north.nozzles().get(k);
            double od = PipeSizes.outside(NozzleBore.largest(north, k));
            double flange = PipeSizes.flangeThickness(od) + PipeSizes.hub(od);
            var start = nozzle.base().clone();
            var collar = nozzle.face();
            collar[nozzle.axis()] -= nozzle.sign() * Math.min(flange, nozzle.length());
            result.add(neck(t.point(start), t.point(nozzle.face()), od / 2 + .01));
            result.add(neck(t.point(collar), t.point(nozzle.face()), PipeSizes.flange(od) / 2 + .01));
        }
        return List.copyOf(result);
    }

    /** The box round the segment {@code a} to {@code b} along one axis, {@code r} across it. */
    private static double[] neck(double[] a, double[] b, double r) {
        var box = new double[6];
        for (int i = 0; i < 3; i++) {
            boolean along = Math.abs(a[i] - b[i]) > 1e-9;
            box[i] = Math.min(a[i], b[i]) - (along ? 0 : r);
            box[i + 3] = Math.max(a[i], b[i]) + (along ? 0 : r);
        }
        return box;
    }

    /** Whether the box {@code b} ({low, high}) overlaps none of the {@code boxes}. */
    private static boolean clearOf(List<double[]> boxes, double[][] b) {
        for (var z : boxes) {
            boolean overlaps = true;
            for (int i = 0; i < 3; i++) overlaps &= b[0][i] < z[i + 3] && b[1][i] > z[i];
            if (overlaps) return false;
        }
        return true;
    }

    static List<double[]> clearances(EquipmentShape north, ProcessUnit unit) {
        var result = new ArrayList<double[]>();
        var climbs = unit == null ? List.<Access.Climb>of() : unit.ladders();
        for (var climb : climbs) {
            double[] b = {
                Double.POSITIVE_INFINITY,
                climb.base() - .1,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                climb.top() + 1.8,
                Double.NEGATIVE_INFINITY
            };
            for (var corner : climb.footprint()) {
                var p = north.transform().point(new double[] {corner[0], 0, corner[1]});
                b[0] = Math.min(b[0], p[0] - .4);
                b[2] = Math.min(b[2], p[2] - .4);
                b[3] = Math.max(b[3], p[0] + .4);
                b[5] = Math.max(b[5], p[2] + .4);
            }
            result.add(b);
        }
        return List.copyOf(result);
    }
}
