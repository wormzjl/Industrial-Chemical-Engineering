package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Breeching;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Ring;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * An API 560 vertical cylindrical heater ({@link #build}): radiant section 2.5 to 12 m across, 1.5
 * to 2.75 diameters high (API 560's slenderness) and at most {@link ProcessUnit#MAX_RADIANT_HEIGHT} m.
 */
final class VerticalHeaterDesign extends UnitDesign {
    VerticalHeaterDesign() {
        // minSupport: The floor 3 m up: room to stand under the burners.
        // Computed in its overrides: minLength, maxLength.
        super(new Sizes(2.5, 12, .5,
                Sizes.COMPUTED, Sizes.COMPUTED,
                3, EquipmentShape.MAX_SUPPORT,
                Orientation.VERTICAL, "radiant_diameter", "radiant_height", "heater_floor", false,
                1));
    }

    @Override
    public int minLength(double size) {
        return Math.max(4, (int) Math.ceil(1.5 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return Math.min(ProcessUnit.MAX_RADIANT_HEIGHT, (int) Math.floor(2.75 * size + 1e-9));
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("heater_duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.VERTICAL_HEATER, shape.diameter(), shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), height = shape.tangentLength();
        return List.of(new Line("radiant.vertical_heater", size(d), number(height)),
                new Line("duty", number(UnitRatings.heaterDuty(EquipmentShape.Kind.VERTICAL_HEATER, d, height))));
    }

    /**
     * A vertical cylindrical heater (owner decision M3 2026-10-17; API 560; {@code MACHINES_HEATERS_SURVEY.md}): a round
     * radiant section of diameter D and height {@code tangentLength} on 6 to 14 H-section legs about 2.3 m apart on concrete
     * pedestals, a ring girder under its floor from leg to leg, X-braced in alternate bays and knee-braced in the others
     * (the front's left open), its floor {@code support} up with the burners (muffler drums under their registers) firing
     * up through it from a circle of 0.4 D; the casing's full-height panels bolted on flanged seams over the legs
     * ({@link ProcessUnit#seam}), girth bands every 3 m; on its roof the convection section in modules with its panelled header boxes
     * ({@link ProcessUnit#convection}), the breeching and the stack with its damper. Platforms (API 560 12.4.1): the
     * hearth platform round the casing at the floor, a 270° one for each further row of peep doors when the radiant
     * section is over 8 m, peep doors 1.4 m over each and an access door over the hearth at the back, rest platforms over
     * the back where a climb would be longer than {@link Access#MAX_CLIMB} ({@link ProcessUnit#ringAccess}); a handrail round the
     * roof's edge; caged ladders at the side legs ({@link Access}), alternating, the last walked through onto the roof.
     * Five nozzles at the front (owner request 2026-10-17): the convection coil in near the top of its front header box and
     * out near its bottom (it flows down against the flue gas), the radiant coil in just under the roof and out just over
     * the floor (the crossover between them is the player's pipe), fuel gas to the burners' ring header.
     */
    @Override
    void build(ProcessUnit u) {
        double dia = u.shape.diameter(), r = dia / 2, height = u.shape.tangentLength(), y0 = u.shape.support() - .3, y1 = u.shape.support() + height;
        int floor = u.shape.support();
        double hx = Math.min(2.25, Math.max(.75, .225 * dia)), hz = Math.min(.425 * dia, Math.sqrt(r * r - hx * hx) - .1), process = Math.min(.6, hx);
        // The convection section high enough for its outlet's row and its inlet's two rows over it (one free block between
        // their pipes, owner rule 2026-10-17).
        double yConvection = Math.ceil(y1 + .2 + process / 2 - .5) + .5, hc = Math.max(Math.min(6, Math.max(2, .4 * dia + 1)), yConvection + 2.2 - y1);
        double wall = r + .12, width = ColumnStructure.MIN_DECK, edge = wall + width;
        double a = ProcessUnit.centre(Math.max(edge + .05, hz + ProcessUnit.HEADER_BOX + .2));
        int n = 4 * (int) Math.ceil(.75 * Math.PI * dia), legs = Math.max(6, 2 * (int) Math.round(Math.PI * dia / 4.6));
        double step = 2 * Math.PI / legs;
        var up = Frame.of(new double[]{a, 0, a}, 1, 1);
        u.mesh.prism(up, r, y0, y1, n, Material.HEATER);
        u.mesh.annulus(up, 0, r, y0, n, -1, Material.HEATER);
        u.mesh.annulus(up, 0, r, y1, n, 1, Material.HEATER);
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, y0, y1, r, 0));
        u.volumes.add(Solid.rod(new double[]{a, 0, a}, 1, y0 + .1, y1 - .1, .94 * r, 0));
        u.round(a, a, y0, y1, r, 1);
        for (double y = floor + 3; y < y1 - 1; y += 3) u.band(up, r, y, n, Material.HEATER);
        // Legs under the casing columns, half a pitch off the front, their webs radial; a ring girder under the floor from
        // leg to leg; X-bracing in alternate bays from the front's right, knee braces in the others but the front's.
        double cased = ProcessUnit.casing(ProcessUnit.legDepth(dia)), girder = Math.min(.6, .3 + .025 * dia), low = y0 - girder;
        var legAt = new double[legs][];
        for (int k = 0; k < legs; k++) {
            double t = -Math.PI / 2 + (k + .5) * step;
            double[] radial = {DeterministicMath.cos(t), 0, DeterministicMath.sin(t)};
            legAt[k] = new double[]{a + (r - .12) * radial[0], a + (r - .12) * radial[2]};
            u.leg(legAt[k][0], legAt[k][1], y0, cased, radial);
            u.seam(new double[]{a + r * radial[0], y0, a + r * radial[2]}, new double[]{a + r * radial[0], y1, a + r * radial[2]}, radial, true);
        }
        for (int k = 0; k < legs; k++) girder(u, legAt[k], legAt[(k + 1) % legs], low, y0, cased);
        u.solids.add(Solid.ring(a, a, low, y0, r - .12 - ProcessUnit.GIRDER_HALF, r - .12 + ProcessUnit.GIRDER_HALF, 0, 2 * Math.PI));
        for (int k = 0; k + 1 < legs; k++)
            if (k % 2 == 1 && k + 1 < legs - 1) u.brace(legAt[k], legAt[k + 1], .6, low, .15, ProcessUnit.BRACE_TIER);
            else u.knees(legAt[k], legAt[k + 1], low, cased);
        // The platforms and their ladders, alternating between the side legs (ringAccess): the hearth platform at
        // the floor, a 270° one at each further row of peep doors, rest platforms over the back where a climb would be
        // longer than Access.MAX_CLIMB (owner decision C1 2026-10-18: a tall support's), then the roof.
        int extra = height > 8 ? (int) Math.ceil(height / 8) - 1 : 0;
        var wanted = new ArrayList<Integer>();
        wanted.add(floor);
        for (int k = 1; k <= extra; k++) wanted.add(floor + (int) Math.round(height * k / (extra + 1)));
        var levels = Access.climbs(0, wanted, (int) Math.ceil(y1 - Access.DECK - 1e-9));
        levels.removeLast();
        int side = (int) Math.ceil(legs / 4.0 - .5);
        double right = -Math.PI / 2 + (side + .5) * step, left = Math.PI - right, gap = DeterministicMath.asin(Math.min(.95, .6 / (wall + Access.GAP)));
        double clear = Access.clearance(wall);
        var rings = new ArrayList<Ring>();
        for (int k = 0; k < levels.size(); k++) {
            int level = levels.get(k);
            double in = k % 2 == 0 ? right : left;
            if (level == floor) rings.add(new Ring(level, in, -Math.PI / 2 + gap, 1.5 * Math.PI - gap));
            else if (wanted.contains(level)) rings.add(in == right ? new Ring(level, in, right, 1.5 * Math.PI - gap) : new Ring(level, in, -Math.PI / 2 + gap, left));
            else rings.add(in == right ? new Ring(level, in, right, left + clear) : new Ring(level, in, right - clear, left));
        }
        var arcs = u.ringAccess(a, a, wall, width, 0, rings);
        for (int k = 0; k < levels.size(); k++) {
            int level = levels.get(k);
            if (!wanted.contains(level)) continue;
            // Peep doors between the columns over the decks, 1.4 m up; the access door over the hearth at the back.
            for (int j = 0; j + 1 < legs; j++) {
                double t = -Math.PI / 2 + (j + 1) * step;
                boolean decked = false;
                for (var arc : arcs.get(k)) decked |= t > arc.from() + .05 && t < arc.to() - .05;
                if (decked) u.door(a, a, r, t, level + Access.DECK + 1.4, .125, .15);
            }
            if (level == floor) u.door(a, a, r, Math.PI / 2, level + Access.DECK + .4, .3, .3);
        }
        double roofIn = levels.size() % 2 == 0 ? right : left;
        u.verticalLadder(a, a, wall, roofIn, levels.getLast() + Access.DECK, y1 + Access.LANDING, true);
        // The roof's handrail on its edge, open at the ladder, the front's nozzle and header boxes that overhang it.
        double rail = r - .03, front = DeterministicMath.asin(Math.min(.95, (Math.max(.6, hx) + .1) / rail)), ladderGap = DeterministicMath.asin(Math.min(.95, (Access.LADDER_HALF + .2) / rail));
        var gaps = new ArrayList<double[]>();
        gaps.add(new double[]{roofIn - ladderGap, roofIn + ladderGap});
        if (hz + ProcessUnit.HEADER_BOX > Math.sqrt(Math.max(0, rail * rail - hx * hx)) - .1) {
            double back = DeterministicMath.asin(Math.min(.95, (hx + .05) / rail));
            gaps.add(new double[]{Math.PI / 2 - back, Math.PI / 2 + back});
        }
        gaps.sort((p, q) -> Double.compare(p[0], q[0]));
        double from = -Math.PI / 2 + front;
        for (var g : gaps) {
            if (g[0] > from + .05) u.railing(new Access.Arc(a, a, r - 1, from, g[0], r, Access.Arc.step(r)), y1 - Access.DECK, 1);
            from = Math.max(from, g[1]);
        }
        if (1.5 * Math.PI - front > from + .05) u.railing(new Access.Arc(a, a, r - 1, from, 1.5 * Math.PI - front, r, Access.Arc.step(r)), y1 - Access.DECK, 1);
        // Burners on a circle under the floor, their ring header and its risers.
        double duty = UnitRatings.heaterDuty(Kind.VERTICAL_HEATER, dia, height), circle = .2 * dia, register = .25 + .02 * dia;
        int burners = Math.max(3, Math.min(16, Math.min((int) Math.round(duty / 2.5), (int) Math.floor(2 * Math.PI * circle / (2.6 * register + .1)))));
        var at = new double[burners][];
        for (int i = 0; i < burners; i++) {
            double t = 2 * Math.PI * (i + .5) / burners;
            at[i] = new double[]{a + circle * DeterministicMath.sin(t), a + circle * DeterministicMath.cos(t)};
        }
        // The ring header low enough that the fuel's line at a pipe's largest passes under the burners' mufflers (owner
        // decision 2026-10-21: a 1 m pipe has a 1 m root, other components give way).
        double yFuel = Math.floor(Math.min(y0 - 1.2, y0 - ProcessUnit.BURNER_DROP - .05 - PipeSizes.MAX_OUTSIDE / 2) - .5) + .5, pipe = .04 + .004 * dia;
        u.burners(at, register, y0, yFuel, pipe);
        for (int i = 0; i < burners; i++) {
            double[] p = {at[i][0], yFuel, at[i][1]}, q = {at[(i + 1) % burners][0], yFuel, at[(i + 1) % burners][1]};
            u.mesh.cylinder(new Frame(p, Mesh.unit(Mesh.sub(q, p)), new double[]{0, 1, 0}), pipe, 0, Math.sqrt(Mesh.dot(Mesh.sub(q, p), Mesh.sub(q, p))), 8, Material.PIPE);
        }
        // The convection section with its header boxes, the stack.
        double y2 = y1 + hc;
        u.convection(a - hx, a + hx, a - hz, a + hz, y1, y2);
        var breeching = new Breeching(a - hx, a + hx, a - hz, a + hz);
        double stack = .25 * dia + .3, neck = breeching.neck(y2, stack), stackTop = Math.min(neck + ProcessUnit.MAX_STACK, ProcessUnit.stackTop(neck, y1));
        u.stack(a, a, stack, ProcessUnit.sides(stack), y2, stackTop, Material.HEATER, breeching, true);
        // The flue gas leaves the stack's mouth (W5): its mark, inside the rim.
        u.mouth("stack_top_0", a, a, .92 * stack / 2, stackTop);
        // The convection coil in near the top of its front header box and out near its bottom; the radiant coil in at the
        // front under the roof, two rows under the convection outlet, and out just over the floor; fuel gas to the ring
        // header's front from the front. Every pipe block keeps one free block from the next.
        double feed = ColumnStructure.size(.1 + .02 * dia, process), zIn = a - hz - ProcessUnit.HEADER_BOX + .03, yConvIn = Math.floor(y2 - .7) + .5;
        double yRadiantIn = Math.min(Math.floor(y1 - .7 - process / 2) + .5, yConvection - 2);
        u.nozzles.add(ProcessUnit.front(Role.CONVECTION_INLET, a, yConvIn, zIn, feed));
        u.nozzles.add(ProcessUnit.front(Role.CONVECTION_OUTLET, a, yConvection, zIn, feed));
        u.nozzles.add(ProcessUnit.radialSide(Role.RADIANT_INLET, a, yRadiantIn, a, r, n, -1, feed));
        u.nozzles.add(ProcessUnit.radialSide(Role.RADIANT_OUTLET, a, floor + .5, a, r, n, -1, feed));
        // The ring's front: a burner's riser where there is an odd number, else the chord between two.
        double zFuel = a - circle * (burners % 2 == 1 ? 1 : DeterministicMath.cos(Math.PI / burners)) + .03, fuel = .3;
        u.nozzles.add(new Nozzle(Role.FUEL, new double[]{a, yFuel, zFuel}, 2, -1, zFuel - Math.floor(a - r - ProcessUnit.NECK),
                ColumnStructure.size(.05 + .003 * duty, fuel), 0));
    }

    /** A girder from leg p to leg q, {x, z} each, from {@code low} to {@code high}, between the legs' casings ({@code depth} across). */
    private static void girder(ProcessUnit u, double[] p, double[] q, double low, double high, double depth) {
        double dx = q[0] - p[0], dz = q[1] - p[1], length = Math.sqrt(dx * dx + dz * dz);
        double[] along = {dx / length, 0, dz / length}, across = {-along[2], 0, along[0]};
        u.mesh.block(new double[]{(p[0] + q[0]) / 2, (low + high) / 2, (p[1] + q[1]) / 2}, Mesh.scale(along, length / 2 - depth / 2), new double[]{0, (high - low) / 2, 0},
                Mesh.scale(across, ProcessUnit.GIRDER_HALF), Material.STEELWORK);
    }
}
