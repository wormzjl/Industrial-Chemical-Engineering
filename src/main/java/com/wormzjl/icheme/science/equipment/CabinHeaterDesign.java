package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Breeching;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.ArrayList;
import java.util.List;

/**
 * An API 560 cabin heater ({@link #build}): radiant section 4 to 12 m wide and 1.2 widths to 48 m
 * long; above {@link ProcessUnit#MAX_CABIN_CELL} a twin cell of two half-length cells.
 */
final class CabinHeaterDesign extends UnitDesign {
    @Override
    public double minSize() {
        return 4;
    }

    @Override
    public double maxSize() {
        return 12;
    }

    @Override
    public double sizeStep() {
        return 1;
    }

    @Override
    public int minLength(double size) {
        return Math.max(6, (int) Math.ceil(1.2 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return 2 * ProcessUnit.MAX_CABIN_CELL;
    }

    /** The floor 3 m up: room to stand under the burners. */
    @Override
    public int minSupport(double size, int length) {
        return 3;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public String sizeRow() {
        return "radiant_width";
    }

    @Override
    public String lengthRow() {
        return "radiant_length";
    }

    @Override
    public String supportRow() {
        return "heater_floor";
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("heater_duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.CABIN_HEATER, shape.diameter(), shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), length = shape.tangentLength();
        int cells = ProcessUnit.cabinCells(length);
        return List.of(new Line("radiant.cabin_heater" + (cells > 1 ? ".twin" : ""), size(d), number(length / cells)),
                new Line("duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.CABIN_HEATER, d, length))));
    }

    /**
     * A cabin heater (owner decision M3 2026-10-17; API 560; {@code MACHINES_HEATERS_SURVEY.md}): a box radiant section
     * of width W and length {@code tangentLength} along z on H-section legs every 3 m or so on concrete pedestals, a girder
     * under the floor round its edge, X-braced along both sides in the end bays and every third and knee-braced in the
     * others (the burner aisles open), its walls W + 1 high, a hipped roof rising to the convection section
     * along its whole length, its floor {@code support} up with one or two rows of burners (muffler drums under their
     * registers) firing up through it. The wall panels bolted on flanged seams over the legs ({@link ProcessUnit#seam}) with an eave
     * beam, peep doors 1.4 m over each walkway in every bay and an access door over the hearth walkway; panelled header
     * boxes on both end walls for the side walls' tube bends and for the convection section's (in modules,
     * {@link ProcessUnit#convection}), an insulated crossover between the
     * back radiant header boxes (the coil runs back along the right wall and returns along the left); one stack per 15 m
     * with its damper. Walkways on both sides (API 560
     * 12.4.1, {@link Access}): the hearth walkway the full length, a second one under the eaves when the walls are 6 m or
     * more and on a taller wall one at each row of peep doors between (at most 8 m apart), the convection walkway over the
     * roof's slope; a ladder walked through from grade onto the hearth walkway (the
     * left's at the back, the right's at the front), side-step ladders against the wall between the side walkways and one
     * walked through onto the convection walkway. Five nozzles at the front (owner request 2026-10-17): the convection
     * coil in near the top of its front header box and out near its bottom, the radiant coil into the left front radiant
     * header box (+x, seen from the constructor) near its top and out of the right one just over the floor (the crossover between the coils is the
     * player's pipe), fuel gas to the burner rows' headers.
     */
    @Override
    void build(ProcessUnit u) {
        double w = u.shape.diameter(), total = u.shape.tangentLength(), y0 = u.shape.support() - .3, y1 = u.shape.support() + ProcessUnit.cabinWall(w);
        int floor = u.shape.support(), cells = ProcessUnit.cabinCells(total);
        double length = total / cells, wc = Math.min(4, Math.max(1.5, .4 * w)), yr = y1 + .6 * (w - wc) / 2, walk = 1.2, process = Math.min(.6, wc / 2);
        // The convection section high enough for its outlet's row and its inlet's two rows over it (one free block between
        // their pipes, owner rule 2026-10-17).
        double yConvection = Math.ceil(yr + .2 + process / 2 - .5) + .5, hc = Math.max(Math.min(5, Math.max(2, .25 * w + 1)), yConvection + 2.2 - yr);
        // One cell in the middle, or two either side of an aisle; the convection section over the middle, over a twin's
        // aisle and half its throat into each cell. A wall outside the convection section is an eave, its roof slope rising
        // to the section's side; a twin's inner wall stands under the section, up to it.
        double gap = cells == 2 ? AISLE : 0, half = (cells * w + gap) / 2;
        double a = ProcessUnit.centre(half + .12 + walk + .05 + 2 * Access.CAGE_HALF + .1), zs = 1, ze = zs + length, c0 = a - (wc + gap) / 2, c1 = a + (wc + gap) / 2;
        double[] cellX = cells == 1 ? new double[]{a} : new double[]{a - (gap + w) / 2, a + (gap + w) / 2};
        int bays = Math.max(2, (int) Math.round(length / 3)), out = (int) Math.floor(w / 2 - .5);
        double cased = ProcessUnit.casing(ProcessUnit.legDepth(w)), low = y0 - ProcessUnit.legDepth(w), rise = yr - y1;
        for (double ac : cellX) {
            double x0 = ac - w / 2, x1 = ac + w / 2;
            boolean eave0 = x0 < c0 - 1e-9, eave1 = x1 > c1 + 1e-9;
            double top0 = eave0 ? y1 : yr, top1 = eave1 ? y1 : yr, run0 = c0 - x0, run1 = x1 - c1;
            // The walls, the end walls up to the roof, the floor's underside and the roof's slopes.
            u.mesh.flat(List.of(new double[]{x0, y0, zs}, new double[]{x0, top0, zs}, new double[]{x0, top0, ze}, new double[]{x0, y0, ze}), new double[]{-1, 0, 0}, Material.HEATER);
            u.mesh.flat(List.of(new double[]{x1, y0, zs}, new double[]{x1, y0, ze}, new double[]{x1, top1, ze}, new double[]{x1, top1, zs}), new double[]{1, 0, 0}, Material.HEATER);
            u.mesh.flat(List.of(new double[]{x0, y0, zs}, new double[]{x0, y0, ze}, new double[]{x1, y0, ze}, new double[]{x1, y0, zs}), new double[]{0, -1, 0}, Material.HEATER);
            for (double z : new double[]{zs, ze}) {
                var outline = new ArrayList<double[]>(List.of(new double[]{x0, y0, z}, new double[]{x1, y0, z}, new double[]{x1, top1, z}));
                if (eave1) outline.add(new double[]{c1, yr, z});
                if (eave0) outline.add(new double[]{c0, yr, z});
                outline.add(new double[]{x0, top0, z});
                u.mesh.flat(outline, new double[]{0, 0, z == zs ? -1 : 1}, Material.HEATER);
            }
            if (eave0) u.mesh.flat(List.of(new double[]{x0, y1, zs}, new double[]{c0, yr, zs}, new double[]{c0, yr, ze}, new double[]{x0, y1, ze}), new double[]{-rise, run0, 0}, Material.HEATER);
            if (eave1) u.mesh.flat(List.of(new double[]{x1, y1, zs}, new double[]{c1, yr, zs}, new double[]{c1, yr, ze}, new double[]{x1, y1, ze}), new double[]{rise, run1, 0}, Material.HEATER);
            u.solids.add(Solid.box(new double[]{x0, y0, zs}, new double[]{x1, y1, ze}));
            u.volumes.add(Solid.box(new double[]{x0 + .1, y0 + .1, zs + .1}, new double[]{x1 - .1, y1, ze - .1}));
            // Under the roof in three steps, each inside the slopes at its top.
            for (int k = 0; k < 3; k++) {
                double b0 = y1 + k * rise / 3, b1 = b0 + rise / 3, lo = eave0 ? x0 + (k + 1) * run0 / 3 + .05 : x0, hi = eave1 ? x1 - (k + 1) * run1 / 3 - .05 : x1;
                u.solids.add(Solid.box(new double[]{lo, b0, zs}, new double[]{hi, b1, ze}));
                u.volumes.add(Solid.box(new double[]{lo, b0, zs + .1}, new double[]{hi, b1, ze - .1}));
            }
            for (double[] p : new double[][]{{x0, zs, x1, zs}, {x1, zs, x1, ze}, {x1, ze, x0, ze}, {x0, ze, x0, zs}}) u.segment(new double[]{p[0], y1, p[1]}, new double[]{p[2], y1, p[3]});
            // Fireproofed legs and casing columns every 3 m or so; a girder under the floor round its edge; X-bracing in the
            // end bays and every third, knee braces in the others and across both ends; an eave beam along each eave wall.
            for (int k = 0; k <= bays; k++) {
                double z = zs + length * k / bays;
                for (int side = -1; side <= 1; side += 2) {
                    double wall = ac + side * w / 2, leg = ac + side * (w / 2 - .15);
                    u.leg(leg, z, y0, cased, new double[]{1, 0, 0});
                    u.seam(new double[]{wall, y0, z}, new double[]{wall, side < 0 ? top0 : top1, z}, new double[]{side, 0, 0}, true);
                    if (k == bays) continue;
                    double[] p = {leg, z}, q = {leg, zs + length * (k + 1) / bays};
                    if (k == 0 || k == bays - 1 || k % 3 == 1) u.brace(p, q, .6, low, .15, ProcessUnit.BRACE_TIER);
                    else u.knees(p, q, low, cased);
                }
            }
            for (int side = -1; side <= 1; side += 2) {
                double leg = ac + side * (w / 2 - .15);
                u.box(new double[]{leg - ProcessUnit.GIRDER_HALF, low, zs - cased / 2}, new double[]{leg + ProcessUnit.GIRDER_HALF, y0, ze + cased / 2}, Material.STEELWORK, true);
            }
            for (double z : new double[]{zs, ze}) {
                double left = ac - (w / 2 - .15), right = ac + (w / 2 - .15);
                u.box(new double[]{left + cased / 2, low, z - ProcessUnit.GIRDER_HALF}, new double[]{right - cased / 2, y0, z + ProcessUnit.GIRDER_HALF}, Material.STEELWORK, true);
                u.knees(new double[]{left, z}, new double[]{right, z}, low, cased);
            }
            for (int side = -1; side <= 1; side += 2) {
                if (!(side < 0 ? eave0 : eave1)) continue;
                double wall = ac + side * w / 2, outer = wall + side * .15;
                u.box(new double[]{Math.min(wall, outer), y1 - .25, zs - .1}, new double[]{Math.max(wall, outer), y1, ze + .1}, Material.HEATER, false);
            }
            // Header boxes on both end walls for the side walls' tube bends, panelled; the crossover between the back ones.
            for (int side = -1; side <= 1; side += 2) {
                double x = ac + side * out;
                for (int end = 0; end < 2; end++) {
                    double[] lo = {x - .4, floor, end == 0 ? zs - ProcessUnit.HEADER_BOX : ze}, hi = {x + .4, y1 - .4, end == 0 ? zs : ze + ProcessUnit.HEADER_BOX};
                    u.mesh.box(lo, hi, Mesh.ALL_FACES & ~(end == 0 ? 32 : 16), Material.HEATER);
                    u.solids.add(Solid.box(lo, hi));
                    u.panels(lo, hi, 2, end == 0 ? -1 : 1, 1, Math.max(2, (int) Math.round((hi[1] - lo[1]) / 1.3)));
                }
            }
            double cross = .06 + .008 * w, yCross = Math.floor(y1 - 1.2) + .5, zCross = ze + ProcessUnit.HEADER_BOX + .35;
            u.line(cross, Material.CLADDING, new double[]{ac + out, yCross, ze + ProcessUnit.HEADER_BOX - .05}, new double[]{ac + out, yCross, zCross}, new double[]{ac - out, yCross, zCross},
                    new double[]{ac - out, yCross, ze + ProcessUnit.HEADER_BOX - .05});
        }
        if (cells == 2) u.mesh.flat(List.of(new double[]{a - gap / 2, yr, zs}, new double[]{a + gap / 2, yr, zs}, new double[]{a + gap / 2, yr, ze}, new double[]{a - gap / 2, yr, ze}),
                new double[]{0, -1, 0}, Material.HEATER);
        // Burners in one row of each cell (two from 7 m wide) under the floor, a header along each row and one across the front.
        double duty = UnitRatings.heaterDuty(Kind.CABIN_HEATER, w, total), register = .25 + .015 * w, yFuel = Math.floor(y0 - 1.2 - .5) + .5, pipe = .05 + .004 * w * Math.sqrt(cells);
        var rows = new ArrayList<Double>();
        for (double ac : cellX) {
            if (w <= 6) rows.add(ac);
            else {
                rows.add(ac - w / 4);
                rows.add(ac + w / 4);
            }
        }
        int each = Math.max(2, Math.min((int) Math.round(duty / 4 / rows.size()), (int) Math.floor((length - 1) / (2.6 * register + .2))));
        var at = new double[rows.size() * each][];
        for (int row = 0; row < rows.size(); row++)
            for (int i = 0; i < each; i++) at[row * each + i] = new double[]{rows.get(row), zs + length * (i + .5) / each};
        u.burners(at, register, y0, yFuel, pipe);
        double zHeader = zs + .3;
        for (double x : rows) u.mesh.cylinder(Frame.of(new double[]{x, yFuel, 0}, 2, 1), pipe, zHeader, zs + length * (each - .5) / each, 8, Material.PIPE);
        if (rows.size() > 1) u.mesh.cylinder(Frame.of(new double[]{0, yFuel, zHeader}, 0, 1), pipe, rows.getFirst(), rows.getLast(), 8, Material.PIPE);
        // The convection section along the whole roof, a stack over each of its parts (a twin's flue gas through stacks
        // √2 as wide).
        double y2 = yr + hc, stack = Math.min(3.5, (.25 * w + .3) * Math.sqrt(cells));
        int convection = (int) Math.ceil(yr + .05);
        u.convection(c0, c1, zs, ze, yr, y2);
        int stacks = Math.max(1, (int) Math.round(length / 15));
        for (int j = 0; j < stacks; j++) {
            double zj = zs + length * (j + .5) / stacks, room = Math.min(length / stacks / 2 - .2, Math.max((c1 - c0) / 2, stack / 2 + .3));
            var breeching = new Breeching(c0, c1, zj - room, zj + room);
            double neck = breeching.neck(y2, stack), stackTop = Math.min(neck + ProcessUnit.MAX_STACK, ProcessUnit.stackTop(neck, y1));
            u.stack(a, zj, stack, ProcessUnit.sides(stack), y2, stackTop, Material.HEATER, breeching, true);
            u.mouth("stack_top_" + j, a, zj, .92 * stack / 2, stackTop);
        }
        // The walkways and ladders of each eave wall: the hearth walkway; on walls 6 m or more the side walkways at the rows
        // of peep doors up the wall at most 8 m apart (as the vertical heater's platforms, so no caged climb passes
        // Access.MAX_CLIMB: owner decision C1 2026-10-18), the highest under the eaves, each reached from the one under it
        // by a side-step ladder at alternate ends of the wall; the convection walkway from the highest. Peep doors over
        // each walkway in every bay but where a ladder stands, an access door over the hearth walkway in the middle bay.
        var walkways = new ArrayList<Integer>();
        if (y1 - 2.4 - floor >= 3) {
            int eaves = (int) (y1 - 2.4), between = eaves - floor > 8 ? (int) Math.ceil((eaves - floor) / 8.0) - 1 : 0;
            for (int k = 1; k <= between; k++) walkways.add(floor + (int) Math.round((eaves - floor) * k / (between + 1.0)));
            walkways.add(eaves);
        }
        int count = walkways.size();
        for (int side = -1; side <= 1; side += 2) {
            double wall = side < 0 ? cellX[0] - w / 2 : cellX[cellX.length - 1] + w / 2, wallX = wall + side * .12, edge = side < 0 ? c0 : c1;
            double[] outward = {side, 0, 0}, along = {0, 0, 1};
            // The wall's end the first side-step ladder stands at (the back's on the left, the front's on the right), the
            // grade ladder's at the other, and the way along z from the first to the other.
            double first = side < 0 ? zs : ze, last = side < 0 ? ze : zs, in = side < 0 ? 1 : -1;
            double zGrade = last - in * .6, zMid = first + in * .5, start = zs - .3;
            u.platform(new Access.Line(new double[]{wallX, 0, start}, along, outward, length + .6), floor, walk, true, true, zGrade - Access.WALK_THROUGH - start, zGrade + Access.WALK_THROUGH - start);
            // The climb from grade up the walkway's outer edge, resting on posts beyond it where the floor stands over 9 m.
            double zRest = Math.min(zGrade, zGrade - in * 1.2) - .6;
            u.climb(new Access.Line(new double[]{wallX + side * walk, 0, zRest}, along, outward, 2.4), 0, floor + Access.DECK, zGrade - in * 1.2 - zRest, zGrade - zRest, .15);
            int top = floor;
            var ladderAt = new double[count];
            for (int i = 0; i < count; i++) {
                boolean atFirst = i % 2 == 0;
                double zl = ladderAt[i] = atFirst ? zMid : last - in * .5;
                // The walkway from beside its ladder (open there: the climber steps off sideways) to the wall's other end.
                double open = zl + (atFirst ? in : -in) * .55, closed = atFirst ? last + in * .3 : first - in * .3, from = Math.min(open, closed), to = Math.max(open, closed);
                u.platform(new Access.Line(new double[]{wallX, 0, from}, along, outward, to - from), walkways.get(i), walk, from == closed, to == closed);
                u.ladder(wallX + side * Access.STANDOFF, zl, outward, Access.STANDOFF, top + Access.DECK, walkways.get(i) + Access.DECK + Access.LANDING, false);
                top = walkways.get(i);
            }
            // The convection walkway's ladder on the highest walkway, away from the ladder that reached it.
            double zConv = count > 0 && count % 2 == 0 ? first + in * 1.6 : last - in * 1.6;
            u.platform(new Access.Line(new double[]{edge, 0, zs}, along, outward, length), convection, Math.abs(wall - edge) + .12, true, true, zConv - Access.WALK_THROUGH - zs, zConv + Access.WALK_THROUGH - zs);
            u.ladder(wallX + side * .05, zConv, outward, .05, top + Access.DECK, convection + Access.DECK + Access.LANDING, true);
            for (int k = 0; k < bays; k++) {
                double z = zs + length * (k + .5) / bays;
                for (int j = 0; j <= count; j++) {
                    boolean ladderThere = j == count && Math.abs(z - zConv) < .7 || j < count && Math.abs(z - ladderAt[j]) < .7 || j > 0 && Math.abs(z - ladderAt[j - 1]) < .7;
                    if (!ladderThere) u.door(wall, z, side, (j == 0 ? floor : walkways.get(j - 1)) + Access.DECK + 1.4, .125, .15);
                }
                if (k == bays / 2 && Math.abs(z - zConv) > 1 && Math.abs(z - zMid) > 1) u.door(wall, z + .7, side, floor + Access.DECK + .4, .3, .3);
            }
        }
        // Nozzles at the front: the convection coil's at its header box; one cell's radiant coil in at the left header box
        // (+x, seen from the constructor) and out of the right one; a twin's coils fed from an inlet manifold between their inner header boxes and joined in
        // an outlet manifold between their outer ones, both across the front of the aisle.
        double feed = ColumnStructure.size(.1 + .02 * w * Math.sqrt(cells), process), zIn = zs - ProcessUnit.HEADER_BOX + .03, yIn = Math.floor(y1 - 1.2) + .5, yOut = floor + .5;
        double yConvIn = Math.floor(y2 - .7) + .5;
        u.nozzles.add(ProcessUnit.front(Role.CONVECTION_INLET, a, yConvIn, zIn, feed));
        u.nozzles.add(ProcessUnit.front(Role.CONVECTION_OUTLET, a, yConvection, zIn, feed));
        if (cells == 1) {
            u.nozzles.add(ProcessUnit.front(Role.RADIANT_INLET, a + out, yIn, zIn, feed));
            u.nozzles.add(ProcessUnit.front(Role.RADIANT_OUTLET, a - out, yOut, zIn, feed));
        } else {
            double manifold = feed / 2 + .05, zm = zs - ProcessUnit.HEADER_BOX - manifold - .1, face = zs - ProcessUnit.HEADER_BOX;
            for (double[] ends : new double[][]{{cellX[0] + out, cellX[1] - out, yIn}, {cellX[0] - out, cellX[1] + out, yOut}})
                u.line(manifold, Material.PIPE, new double[]{ends[0], ends[2], face}, new double[]{ends[0], ends[2], zm}, new double[]{ends[1], ends[2], zm}, new double[]{ends[1], ends[2], face});
            u.nozzles.add(ProcessUnit.front(Role.RADIANT_INLET, a, yIn, zm, feed));
            u.nozzles.add(ProcessUnit.front(Role.RADIANT_OUTLET, a, yOut, zm, feed));
        }
        double zFuel = zHeader + .03, fuel = .3;
        u.nozzles.add(new Nozzle(Role.FUEL, new double[]{a, yFuel, zFuel}, 2, -1, zFuel - Math.floor(zs - ProcessUnit.NECK),
                ColumnStructure.size(.05 + .003 * duty, fuel), 0));
    }

    /** A twin cabin's aisle between its cells' inner walls, m: room for a player between the legs' pedestals. */
    static final double AISLE = 1.5;
}
