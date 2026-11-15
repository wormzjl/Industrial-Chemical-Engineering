package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Ring;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.fluid.network.Chimney;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * A chimney (batch 2026-11-10-chimney, owner C3 A): a self-supporting steel stack ({@link #steelStack}) or a
 * tapered brick or concrete chimney with a steel liner ({@link #brickChimney}). Its main size is the exit
 * diameter on its form's grid ({@link Chimney.Form}), its second the height in whole metres (owner: "Scalable").
 */
final class ChimneyDesign extends UnitDesign {
    private final Chimney.Form form;

    ChimneyDesign(Chimney.Form form) {
        // Computed in its overrides: minSize, maxSize, sizeStep, minLength, maxLength.
        super(new Sizes(Sizes.COMPUTED, Sizes.COMPUTED, Sizes.COMPUTED,
                Sizes.COMPUTED, Sizes.COMPUTED,
                0, 0,
                Orientation.VERTICAL, "chimney_diameter", "chimney_height", null, false,
                1));
        this.form = form;
    }

    @Override
    public double minSize() {
        return form.minDiameter;
    }

    @Override
    public double maxSize() {
        return form.maxDiameter;
    }

    @Override
    public double sizeStep() {
        return form.diameterStep;
    }

    @Override
    public int minLength(double size) {
        return form.minHeight;
    }

    @Override
    public int maxLength(double size) {
        return form.maxHeight(size);
    }

    /** A new chimney stands at the height good engineering practice gives a 15 m building beside it (37.5 m), within its range. */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), Chimney.gepHeight(15)));
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("chimney_rating", number(Chimney.ratedVolumeFlow(d)), number(form.designVelocity * Math.PI * d * d / 4 / Chimney.SPARE));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        return List.of(new Line("chimney", size(d), whole(shape.tangentLength())),
                new Line("chimney.duty", number(Chimney.ratedVolumeFlow(d)), whole(form.hotLimit - 273.15)));
    }

    @Override
    void build(ProcessUnit u) {
        if (form == Chimney.Form.STEEL) steelStack(u);
        else brickChimney(u);
    }

    /** A steel stack's plinth radius at least, m. */
    static final double STACK_PLINTH = .45;

    /** A brick chimney's wall at its top beyond the liner, m (liner, air gap, brick), and its foot's radius over its top's (owner: "wider at the foot"). */
    static final double CHIMNEY_WALL = .3, CHIMNEY_TAPER = 1.6;

    /**
     * A self-supporting steel stack (batch 2026-11-10-chimney): its shell {@code diameter} across inside up to
     * {@code tangentLength} on a concrete plinth 2 m high that takes the gas inlet at the front, its centre
     * {@link com.wormzjl.icheme.science.fluid.network.Chimney#INLET} up on its pipe block's centre line, and the data socket
     * on its side; caged ladders up the back with rest platforms at most {@link Access#MAX_CLIMB} apart and a ring platform
     * 2 m under the top (owner: "a ladder and a platform near the top").
     */
    private static void steelStack(ProcessUnit u) {
        double d = u.shape.diameter(), od = d + .02, r = od / 2, top = u.shape.tangentLength(), plinth = Math.max(r + .15, STACK_PLINTH);
        double a = ProcessUnit.centre(plinth + Access.MIN_DECK + .1);
        int n = ProcessUnit.sides(od), m = ProcessUnit.sides(2 * plinth);
        var up = Frame.of(new double[]{a, 0, a}, 1, 1);
        u.mesh.prism(up, plinth, 0, 2, m, Material.SUPPORT);
        u.mesh.annulus(up, r, plinth, 2, m, 1, Material.SUPPORT);
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, 0, 2, plinth, 0));
        u.stack(a, a, od, n, 2, top, Material.STEELWORK, null, false);
        u.mouth("stack_top", a, a, .92 * r, top);
        // The tip registers in the top cell on the axis: the stack is the run up to it.
        u.fluidPoint = new double[]{a, top - .5, a};
        // The climb up the back, alternating either side of it, each rest deck running from its ladder to the next one's foot
        // (the heater stack's pattern: the next cage stands at the deck's end, not over it), the last landing a whole ring.
        var levels = Access.climbs(0, List.of(), (int) Math.floor(top - 2));
        double right = Math.PI / 2 - .7, left = Math.PI / 2 + .7, clear = Access.clearance(plinth);
        var rings = new ArrayList<Ring>();
        for (int k = 0; k < levels.size(); k++) {
            double in = k % 2 == 0 ? right : left;
            if (k == levels.size() - 1) rings.add(new Ring(levels.get(k), in, in, in + 2 * Math.PI));
            else rings.add(in == right ? new Ring(levels.get(k), in, right, left + clear) : new Ring(levels.get(k), in, right - clear, left));
        }
        u.ringAccess(a, a, plinth, Access.MIN_DECK, 0, rings);
        u.nozzles.add(ProcessUnit.radialSide(Role.STACK_GAS, a, com.wormzjl.icheme.science.fluid.network.Chimney.INLET, a, plinth, m, -1, Math.min(PipeSizes.MAX_OUTSIDE, od)));
    }

    /**
     * A tapered brick or concrete chimney with a steel liner (batch 2026-11-10-chimney): its liner {@code diameter} across
     * inside, standing 1 m proud of the shell's cap at {@code tangentLength}; the shell {@link #CHIMNEY_WALL} beyond the liner
     * at the top and {@link #CHIMNEY_TAPER} times as wide at the foot; a railed gallery 3 m under the cap (climbed inside),
     * an access door at the back, lightning rods on the rim, the gas inlet at the front through the foot, its centre
     * {@link com.wormzjl.icheme.science.fluid.network.Chimney#INLET} up.
     */
    private static void brickChimney(ProcessUnit u) {
        double d = u.shape.diameter(), liner = d / 2 + .02, top = u.shape.tangentLength(), rt = d / 2 + CHIMNEY_WALL, rf = CHIMNEY_TAPER * rt;
        double gallery = top - 3, rg = rf + (rt - rf) * gallery / top;
        double a = ProcessUnit.centre(Math.max(rf, rg + Access.MIN_DECK) + .1);
        int n = ProcessUnit.sides(2 * rf);
        var up = Frame.of(new double[]{a, 0, a}, 1, 1);
        u.mesh.frustum(up, rf, rt, 0, top, n, Material.CONCRETE);
        u.mesh.annulus(up, liner, rt, top, n, 1, Material.CONCRETE);
        u.solids.add(Solid.cone(new double[]{a, 0, a}, 1, 0, top, rf, rt, 0));
        // The liner's head over the cap.
        u.mesh.prism(up, liner, top, top + 1, n, Material.SHELL);
        u.band(up, liner, top + .6, n, Material.STEELWORK);
        u.mesh.annulus(up, .92 * liner, liner, top + 1, n, 1, Material.SHELL);
        u.mesh.bore(up, .92 * liner, top + .7, top + 1, n, Material.SHELL);
        u.mesh.annulus(up, 0, .92 * liner, top + .7, n, 1, Material.OPENING);
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, top, top + 1, liner, 0));
        u.mouth("stack_top", a, a, .92 * liner, top + 1);
        u.fluidPoint = new double[]{a, top + .5, a};
        for (int k = 0; k < 3; k++) {
            double t = 2 * Math.PI * k / 3 + Math.PI / 2, x = a + (rt - .1) * DeterministicMath.cos(t), z = a + (rt - .1) * DeterministicMath.sin(t);
            u.mesh.bar(new double[]{x, top, z}, new double[]{x, top + 1.5, z}, .03, new double[]{1, 0, 0}, Material.PIPE);
        }
        // The gallery, two railed half rings meeting open.
        double edge = rg + Access.MIN_DECK, step = Access.Arc.step(edge);
        for (int k = 0; k < 2; k++)
            u.platform(new Access.Arc(a, a, rg, k * Math.PI, (k + 1) * Math.PI, edge, step), gallery, Access.MIN_DECK, false, false);
        u.door(a, a, rf, Math.PI / 2, 1.2, .45, 1);
        double inlet = rf + (rt - rf) * com.wormzjl.icheme.science.fluid.network.Chimney.INLET / top;
        u.nozzles.add(ProcessUnit.radialSide(Role.STACK_GAS, a, com.wormzjl.icheme.science.fluid.network.Chimney.INLET, a, inlet, n, -1, PipeSizes.MAX_OUTSIDE));
    }
}
