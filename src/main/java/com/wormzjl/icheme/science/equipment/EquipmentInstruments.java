package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.*;

/** Physical probe locations and the manual/built-in policy, shared by placement and drawing. */
public final class EquipmentInstruments {
    private EquipmentInstruments() {}

    public enum Quantity {
        PRESSURE,
        TEMPERATURE,
        FLOW,
        LEVEL,
        SPEED
    }

    public record Probe(
            String id,
            Quantity quantity,
            double[] centre,
            double[] tap,
            double[] normal,
            boolean top,
            boolean manual,
            boolean display,
            double[] tip,
            double[] bracket) {
        public Probe {
            Objects.requireNonNull(id);
            Objects.requireNonNull(quantity);
            centre = centre.clone();
            tap = tap.clone();
            normal = normal.clone();
            tip = tip == null ? null : tip.clone();
            bracket = bracket == null ? null : bracket.clone();
        }

        @Override
        public double[] centre() {
            return centre.clone();
        }

        @Override
        public double[] tap() {
            return tap.clone();
        }

        @Override
        public double[] normal() {
            return normal.clone();
        }

        @Override
        public double[] tip() {
            return tip == null ? null : tip.clone();
        }

        @Override
        public double[] bracket() {
            return bracket == null ? null : bracket.clone();
        }

        public int face() {
            return EquipmentServices.face(normal);
        }

        public double depth() {
            return tip == null ? .12 : Math.sqrt(dot(sub(tip, tap), sub(tip, tap)));
        }

        /** The one electrical entry datum used by the head and its protected short surface drop. */
        public double[] entry() {
            var right = cross(new double[] {0, 1, 0}, normal);
            double offset = quantity == Quantity.LEVEL ? .080 : .060;
            return plus(
                    centre,
                    plus(
                            scale(right, offset),
                            top
                                    ? plus(new double[] {0, -.115, 0}, scale(normal, -.025))
                                    : scale(normal, -.065)));
        }

        Probe turn(Transform t) {
            return new Probe(
                    id,
                    quantity,
                    t.point(centre),
                    t.point(tap),
                    t.direction(normal),
                    top,
                    manual,
                    display,
                    tip == null ? null : t.point(tip),
                    bracket == null ? null : t.point(bracket));
        }
    }

    public record Reservation(String id, String purpose, double[] low, double[] high) {
        public Reservation {
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
    }

    public record Plan(List<Probe> probes, List<Reservation> reserved) {
        public Plan {
            probes = List.copyOf(probes);
            reserved = List.copyOf(reserved);
        }
    }

    public static boolean automatic(EquipmentShape.Kind kind) {
        return kind.mover()
                || kind.coolingTower()
                || switch (kind) {
                    case COLUMN,
                                    EXCHANGER,
                                    KETTLE,
                                    AIR_COOLER,
                                    VERTICAL_HEATER,
                                    CABIN_HEATER,
                                    STEAM_TURBINE,
                                    MOTOR,
                                    GENERATOR ->
                            true;
                    default -> false;
                };
    }

    public static Plan of(EquipmentShape shape) {
        return ShapeRasterizer.instruments(shape);
    }

    private static final Set<Material> BODY = Set.of(Material.HEAD, Material.SHELL);

    static Plan build(EquipmentShape shape, List<Polygon> nativePolys, ProcessUnit unit) {
        var north = ServiceSurface.north(shape);
        var toNorth = ServiceSurface.between(shape.transform(), north.transform());
        var turn = ServiceSurface.between(north.transform(), shape.transform());
        var surface = new ServiceSurface(ServiceSurface.turn(nativePolys, toNorth));
        var points = new ArrayList<Probe>();
        var reserved = new ArrayList<Reservation>();
        double d = shape.diameter();
        var kind = shape.kind();
        switch (kind) {
            case DRUM -> {
                var b = surface.bounds(BODY);
                double cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
                if (!shape.orientation().horizontal()) {
                    double r = Math.max(.30, Math.min(.3 * d / 2, 1.05));
                    points.add(
                            top(
                                    surface,
                                    "pressure_top",
                                    Quantity.PRESSURE,
                                    cx - r,
                                    cz - r,
                                    new double[] {0, 0, -1},
                                    cx,
                                    cz,
                                    d / 2));
                    points.add(
                            top(
                                    surface,
                                    "temperature_top",
                                    Quantity.TEMPERATURE,
                                    cx + r,
                                    cz - r,
                                    new double[] {0, 0, -1},
                                    cx,
                                    cz,
                                    d / 2));
                    points.add(
                            top(
                                    surface,
                                    "level_body",
                                    Quantity.LEVEL,
                                    cx,
                                    cz - r,
                                    new double[] {0, 0, -1},
                                    cx,
                                    cz,
                                    d / 2));
                    for (int i = 0; i < 2; i++) {
                        double pad = Math.min(r, .16 * d),
                                half = Math.min(.5, .175 * d),
                                x = cx + (i == 0 ? -pad : pad);
                        var tap = surface.ray(1, new double[] {x, 0, cz + 1.2 * pad}, 1, BODY);
                        var lo = plus(tap, new double[] {-half, .08, -half});
                        reserved.add(
                                new Reservation(
                                        i == 0 ? "F1" : "F2",
                                        i == 0 ? "Gas blanketing valve" : "PSV",
                                        lo,
                                        plus(lo, new double[] {2 * half, 1.5, 2 * half})));
                    }
                } else {
                    double length = shape.tangentLength();
                    boolean boot = shape.boot();
                    // The two top flanges at the rear of the crown, beside each other as on a standing drum's roof (owner,
                    // 2026-11-11: "these 2 should be placed on top of drums").
                    for (int i = 0; i < 2; i++) {
                        double pad = .16 * d, half = Math.min(.5, .175 * d), x = cx + (i == 0 ? -pad : pad);
                        var tap = surface.ray(1, new double[] {x, 0, cz + .5 * length - half - .05}, 1, BODY);
                        var lo = plus(tap, new double[] {-half, .08, -half});
                        reserved.add(new Reservation(i == 0 ? "F1" : "F2", i == 0 ? "Gas blanketing valve" : "PSV", lo, plus(lo, new double[] {2 * half, 1.5, 2 * half})));
                    }
                    double[] positions = {
                        cz + (boot ? .03 : -.2) * length,
                        cz + (boot ? .16 : 0) * length,
                        cz + (boot ? .28 : .2) * length
                    };
                    for (int i = 0; i < 3; i++)
                        points.add(
                                top(
                                        surface,
                                        i == 0
                                                ? "pressure_top"
                                                : i == 1 ? "temperature_top" : "level_body",
                                        i == 0
                                                ? Quantity.PRESSURE
                                                : i == 1 ? Quantity.TEMPERATURE : Quantity.LEVEL,
                                        cx,
                                        positions[i],
                                        new double[] {1, 0, 0},
                                        cx,
                                        cz,
                                        d / 2));
                    if (north.bootPlace() != null) {
                        var bootPlace = north.bootPlace();
                        var axis =
                                north.transform()
                                        .point(
                                                new double[] {
                                                    north.axis(), bootPlace.station(), north.axis()
                                                });
                        var p =
                                top(
                                        surface,
                                        "level_boot",
                                        Quantity.LEVEL,
                                        axis[0],
                                        axis[2],
                                        new double[] {1, 0, 0},
                                        cx,
                                        cz,
                                        d / 2);
                        var tip =
                                north.transform()
                                        .point(
                                                new double[] {
                                                    north.axis()
                                                            - d / 2
                                                            - bootPlace.length()
                                                            - bootPlace.diameter() / 4,
                                                    bootPlace.station(),
                                                    north.axis()
                                                });
                        tip[1] += .2;
                        points.add(
                                new Probe(
                                        p.id,
                                        p.quantity,
                                        p.centre,
                                        p.tap,
                                        p.normal,
                                        true,
                                        true,
                                        true,
                                        tip,
                                        null));
                    }
                }
            }
            case TANK -> {
                double a = TankDesign.Plan.of(d, (int) shape.tangentLength()).a(), r = d / 2;
                for (int i = 0; i < 3; i++) {
                    double x = a + (i == 0 ? -.3 : i == 1 ? .3 : -.85), y = i == 2 ? 1.35 : .8;
                    var tap =
                            surface.ray(
                                    2,
                                    north.transform().point(new double[] {x, y, 0}),
                                    -1,
                                    Set.of(Material.SHELL));
                    var centre = plus(tap, new double[] {0, 0, -.16});
                    points.add(
                            new Probe(
                                    i == 0
                                            ? "pressure_bottom"
                                            : i == 1 ? "temperature_bottom" : "level_body",
                                    i == 0
                                            ? Quantity.PRESSURE
                                            : i == 1 ? Quantity.TEMPERATURE : Quantity.LEVEL,
                                    centre,
                                    tap,
                                    new double[] {0, 0, -1},
                                    false,
                                    true,
                                    true,
                                    null,
                                    null));
                }
            }
            case SPHERE -> {
                var p = SphereDesign.Plan.of(d, shape.support());
                double y = p.level() + Access.DECK, r = p.rp() + .085;
                var zones = EquipmentServices.clearances(north, unit);
                String[] ids = {"pressure_top", "temperature_top", "level_body"};
                Quantity[] kinds = {Quantity.PRESSURE, Quantity.TEMPERATURE, Quantity.LEVEL};
                double[] preferred = {-Math.PI / 4, -3 * Math.PI / 4, -Math.PI / 2};
                for (int index = 0; index < 3; index++) {
                    int which = index;
                    var angles = new ArrayList<Double>();
                    for (int i = 0; i < 72; i++) angles.add(i * Math.PI / 36);
                    angles.sort(
                            Comparator.comparingDouble(
                                    a ->
                                            Math.abs(
                                                    DeterministicMath.atan2(
                                                            DeterministicMath.sin(
                                                                    a - preferred[which]),
                                                            DeterministicMath.cos(
                                                                    a - preferred[which])))));
                    boolean found = false;
                    for (double angle : angles) {
                        var n =
                                new double[] {
                                    DeterministicMath.cos(angle), 0, DeterministicMath.sin(angle)
                                };
                        var centre =
                                north.transform()
                                        .point(
                                                new double[] {
                                                    p.a() + r * n[0], y + .85, p.a() + r * n[2]
                                                });
                        if (points.stream()
                                .anyMatch(
                                        q ->
                                                dot(sub(q.centre, centre), sub(q.centre, centre))
                                                        < .49)) continue;
                        if (surface.overlaps(
                                        sub(centre, new double[] {.17, .15, .17}),
                                        plus(centre, new double[] {.17, .15, .17}),
                                        Set.of(Material.HEAD, Material.GRATING))
                                || !ServiceSurface.clear(centre, zones)) continue;
                        try {
                            var edge =
                                    north.transform()
                                            .point(
                                                    new double[] {
                                                        p.a() + (p.rp() - .06) * n[0],
                                                        y,
                                                        p.a() + (p.rp() - .06) * n[2]
                                                    });
                            var bracket = surface.ray(1, edge, 1, Set.of(Material.GRATING));
                            if (Math.abs(bracket[1] - edge[1]) > 1e-7) continue;
                            var tap = surface.ray(1, centre, 1, BODY);
                            double[] tip =
                                    kinds[index] == Quantity.TEMPERATURE
                                            ? plus(
                                                    surface.ray(1, centre, -1, BODY),
                                                    new double[] {0, .2, 0})
                                            : null;
                            points.add(
                                    new Probe(
                                            ids[index],
                                            kinds[index],
                                            centre,
                                            tap,
                                            neg(n),
                                            true,
                                            true,
                                            true,
                                            tip,
                                            bracket));
                            found = true;
                            break;
                        } catch (IllegalArgumentException unavailable) {
                            /* Try another point on the actual platform rim. */
                        }
                    }
                    if (!found)
                        throw new IllegalArgumentException(
                                "No clear top-platform instrument position for " + ids[index]);
                }
            }
            case EXCHANGER -> {
                for (var port : north.nozzles()) {
                    var axis =
                            plus(
                                    north.transform().point(port.face()),
                                    scale(north.transform().direction(port.direction()), -.28));
                    var tap = surface.ray(0, axis, 1, Set.of(Material.PIPE));
                    points.add(
                            new Probe(
                                    "temperature_" + port.role().name().toLowerCase(Locale.ROOT),
                                    Quantity.TEMPERATURE,
                                    plus(tap, new double[] {.18, 0, 0}),
                                    tap,
                                    new double[] {1, 0, 0},
                                    false,
                                    false,
                                    false,
                                    axis,
                                    null));
                }
            }
            case INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER -> {
                var tower = north.coolingTower();
                boolean natural = kind == EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER;
                var hot =
                        north.nozzles().stream()
                                .filter(n -> n.role() == EquipmentShape.Role.HOT_WATER_INLET)
                                .findFirst()
                                .orElseThrow();
                var at =
                        plus(
                                north.transform().point(hot.face()),
                                scale(north.transform().direction(hot.direction()), -.35));
                var tap = surface.ray(1, at, 1, Set.of(Material.PIPE));
                points.add(
                        new Probe(
                                "temperature_inlet",
                                Quantity.TEMPERATURE,
                                plus(tap, new double[] {0, .25, 0}),
                                tap,
                                new double[] {1, 0, 0},
                                true,
                                false,
                                false,
                                null,
                                null));
                double lx = natural ? tower.width() / 2 + .5 : tower.width() * tower.columns() / 2,
                        lz = natural ? tower.width() + .84 : tower.width() * tower.rows() + .34;
                var wall = natural ? Set.of(Material.CONCRETE) : Set.of(Material.TOWER_PANEL);
                var bottom =
                        surface.ray(
                                2,
                                north.transform().point(new double[] {lx - .8, .60, 0}),
                                1,
                                wall);
                points.add(
                        new Probe(
                                "temperature_basin",
                                Quantity.TEMPERATURE,
                                plus(bottom, new double[] {0, 0, .20}),
                                bottom,
                                new double[] {0, 0, 1},
                                false,
                                false,
                                false,
                                null,
                                null));
                var centre = north.transform().point(new double[] {lx, 1, lz});
                var levelTap = surface.ray(2, centre, 1, wall);
                points.add(
                        new Probe(
                                "level_body",
                                Quantity.LEVEL,
                                centre,
                                levelTap,
                                new double[] {0, 0, 1},
                                false,
                                false,
                                true,
                                null,
                                null));
            }
            default -> {}
        }
        var reserves = new ArrayList<Reservation>();
        for (var r : reserved) {
            var a = turn.point(r.low);
            var b = turn.point(r.high);
            reserves.add(
                    new Reservation(
                            r.id,
                            r.purpose,
                            new double[] {
                                Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])
                            },
                            new double[] {
                                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])
                            }));
        }
        return new Plan(points.stream().map(p -> p.turn(turn)).toList(), reserves);
    }

    private static Probe top(
            ServiceSurface surface,
            String id,
            Quantity quantity,
            double x,
            double z,
            double[] normal,
            double cx,
            double cz,
            double radius) {
        var head = new double[] {x, 0, z};
        double[] tap;
        try {
            tap = surface.ray(1, head, 1, BODY);
        } catch (IllegalArgumentException outside) {
            double dx = x - cx,
                    dz = z - cz,
                    length = Math.sqrt(dx * dx + dz * dz),
                    scale = Math.min(1, .68 * radius / length);
            tap = surface.ray(1, new double[] {cx + dx * scale, 0, cz + dz * scale}, 1, BODY);
        }
        // Small heads use a supported short process extension to keep the three readouts apart.
        head[1] = tap[1] + .55;
        double[] tip =
                quantity == Quantity.TEMPERATURE
                        ? plus(surface.ray(1, tap, -1, BODY), new double[] {0, .20, 0})
                        : null;
        return new Probe(id, quantity, head, tap, normal, true, true, true, tip, null);
    }
}
