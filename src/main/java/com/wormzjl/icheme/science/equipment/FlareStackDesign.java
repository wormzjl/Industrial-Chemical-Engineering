package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.fluid.network.Flare;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.List;

/**
 * An elevated flare stack ({@link #build}; batch 2026-11-09-flare-stack, owner X3: one size): its tip
 * {@link Flare#MIN_TIP} to {@link Flare#MAX_TIP} m across sets its rated heat release ({@link Flare#rated}) and its
 * stack's height ({@link Flare#stackHeight}).
 */
final class FlareStackDesign extends UnitDesign {
    @Override
    public double minSize() {
        return Flare.MIN_TIP;
    }

    @Override
    public double maxSize() {
        return Flare.MAX_TIP;
    }

    @Override
    public double sizeStep() {
        return Flare.TIP_STEP;
    }

    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public int maxSupport() {
        return 0;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "flare_tip";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("flare_rating", number(Flare.rated(shape.diameter()) / 1e6), number(Flare.stackHeight(shape.diameter())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double tip = shape.diameter();
        return List.of(new Line("flare_stack", size(tip), number(Flare.stackHeight(tip))),
                new Line("flare_stack.duty", number(Flare.rated(tip) / 1e6), number(Flare.FLAME_LIMIT)));
    }

    /**
     * An elevated flare stack (batch 2026-11-09-flare-stack): a riser 0.1 m wider than its tip ({@code diameter}) on its
     * cell's centre, up to {@link com.wormzjl.icheme.science.fluid.network.Flare#stackHeight}, on a tapered base 3 m high;
     * at the top a flare tip (a windshield 0.3 m wider than the riser, 1.2 m high) with three pilot tubes; the vent gas
     * inlet at the front, its centre 1.5 m up. No ladder: a flare's tip is reached from a crane while it is down. The
     * flame is the client's ({@code FxSources}, {@code FlameModel}), drawn while the flare works.
     */
    @Override
    void build(ProcessUnit u) {
        double tip = u.shape.diameter(), od = tip + .1, r = od / 2, a = .5, top = com.wormzjl.icheme.science.fluid.network.Flare.stackHeight(tip);
        // The foot: a straight plinth to 2 m that takes the gas inlet and the data socket, then a taper to the riser at 3 m.
        double plinth = Math.max(2 * r, FLARE_PLINTH);
        int n = ProcessUnit.sides(od), m = ProcessUnit.sides(2 * plinth);
        var up = Frame.of(new double[]{a, 0, a}, 1, 1);
        u.mesh.prism(up, plinth, 0, 2, m, Material.SUPPORT);
        u.mesh.annulus(up, 0, plinth, 0, m, -1, Material.SUPPORT);
        u.mesh.frustum(up, plinth, r, 2, 3, m, Material.SUPPORT);
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, 0, 2, plinth, 0));
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, 2, 3, (plinth + r) / 2, 0));
        u.stack(a, a, od, n, 3, top, Material.STEELWORK, null, false);
        // The tip: its windshield round the riser's mouth, the pilots up its side.
        double shield = r + .15;
        u.mesh.prism(up, shield, top - .2, top + 1, n, Material.MACHINE);
        u.mesh.annulus(up, r, shield, top - .2, n, -1, Material.MACHINE);
        u.mesh.annulus(up, .9 * shield, shield, top + 1, n, 1, Material.MACHINE);
        u.mesh.bore(up, .9 * shield, top, top + 1, n, Material.MACHINE);
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, top - .2, top + 1, shield, 0));
        // The flame stands on the windshield's mouth (W3): its mark, the top centre of the tip.
        u.mouth("flare_tip", a, a, .9 * shield, top + 1);
        for (int k = 0; k < 3; k++) {
            double t = 2 * Math.PI * k / 3 + Math.PI / 2, x = a + (shield + .08) * DeterministicMath.cos(t), z = a + (shield + .08) * DeterministicMath.sin(t);
            u.mesh.bar(new double[]{x, top - 2, z}, new double[]{x, top + 1.3, z}, .05, new double[]{1, 0, 0}, Material.PIPE);
        }
        // The vent gas enters the plinth 1.5 m up, towards the front, as a flare header arrives near grade.
        u.nozzles.add(ProcessUnit.radialSide(Role.FLARE_GAS, a, FLARE_INLET, a, plinth, m, -1, od));
    }

    /**
     * A flare stack's plinth radius at least, m, and its gas inlet's centre, m (batch 2026-11-09-flare-stack): on the cell
     * centre of the second block, where its pipe's centre line runs.
     */
    static final double FLARE_PLINTH = .45, FLARE_INLET = 1.5;
}
