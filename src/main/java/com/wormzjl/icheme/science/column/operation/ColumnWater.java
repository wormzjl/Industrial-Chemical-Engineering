package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a column's feed water goes, and whether its liquids would settle (batch 2026-11-11-aqueous-solvents, W5 D3 and D5).
 * The sheet's column block ({@code ColumnBlockSolve}) and the running column ({@link ColumnSolve}) read the same rule.
 *
 * <ul>
 *   <li>On the axis (owner choice 1 a): the feed carries a compound that names a homomorph ({@link MaterialCatalog#homomorph},
 *   the batch's mark of a water-miscible organic) above {@link #SHARE} of its moles. The column runs on the list with water,
 *   every amount water included, water's pairs the derived Huron-Vidal ones; a steam row with a flow is refused (choice 2 a).</li>
 *   <li>Off the axis: water is stripping steam as before, and the column runs on the list without water, so its kernel stays on
 *   the classical branch. V3's free water is immiscible by construction.</li>
 * </ul>
 * A feed near the share may flip between the two from one solve to the next (plan section 7: accepted).
 */
public final class ColumnWater {
    /** The water's id on the network axis. */
    public static final String WATER = SplitTable.WATER;
    /** The share of the feed's moles a homomorph-naming compound must exceed to put water on the axis (choice 1 a). */
    public static final double SHARE = 1e-3;
    /** The mole fraction of water and of a compound without a polar group above which a liquid is split (D5). */
    public static final double TRACE = 1e-6;

    private ColumnWater() {}

    /** Whether water goes on the column's axis for a feed of {@code amounts} on {@code ids} of package {@code packageId}. */
    public static boolean onAxis(MaterialCatalog catalog, String packageId, List<String> ids, double[] amounts) {
        double total = 0;
        for (double amount : amounts) total += amount;
        if (!(total > 0)) return false;
        var pack = catalog.requirePackage(packageId);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (WATER.equals(id) || !(amounts[i] > SHARE * total) || !pack.components().contains(id)) continue;
            if (catalog.homomorph(packageId, id).isPresent()) return true;
        }
        return false;
    }

    /**
     * The liquids of an accepted solve with water on its axis that would settle into two (D5): each tray's liquid and the
     * distillate, split once with the engine's {@link FluidThermodynamics#flashTP} at their own temperature and pressure, only
     * where the liquid holds water and a compound without a polar group above {@link #TRACE}. A notice, never a failure: the
     * column takes the liquid as one, the rule of its free water and of the still's trays.
     *
     * @param catalog the catalog the column solved on (its package names the basis)
     * @param model   the network model on the list with water, the axis {@code model.components()}
     */
    public static Settling settling(MaterialCatalog catalog, FluidThermodynamics model, V3ColumnResult result, Runnable checkpoint) {
        var input = result.problem().input();
        var ids = input.componentBasis().componentIds();
        int water = ids.indexOf(WATER);
        if (water < 0 || !(input.feedComponentMolarFlowsMolPerSecond()[water] > 0)) return Settling.NONE;
        var pairs = DerivedPairs.derive(catalog, input.packageId(), ids);
        var axis = model.components();
        int[] at = new int[ids.size()];
        for (int c = 0; c < at.length; c++) at[c] = axis.indexOf(ids.get(c));
        var trays = new ArrayList<Integer>();
        int splits = 0;
        var nodes = result.inspection().orElseThrow().nodes();
        for (int n = 1; n <= input.stageCount(); n++) {
            var node = nodes.get(n);
            if (!(node.liquidMolPerSecond() > 0)) continue;
            Boolean settles = settles(model, pairs, at, water, node.liquidFractions(), node.temperatureKelvin(), node.pressurePascal(), checkpoint);
            if (settles == null) continue;
            splits++;
            if (settles) trays.add(n);
        }
        boolean distillate = false;
        for (var stream : result.streams()) {
            if (!stream.streamId().equals("distillate_liquid")) continue;
            var fractions = new ArrayList<Double>();
            for (String id : ids) fractions.add(stream.moleFractions().stream().filter(f -> f.componentId().equals(id)).mapToDouble(f -> f.moleFraction()).sum());
            Boolean settles = settles(model, pairs, at, water, fractions, stream.temperatureKelvin(), stream.pressurePascal(), checkpoint);
            if (settles != null) {
                splits++;
                distillate = settles;
            }
        }
        return new Settling(List.copyOf(trays), distillate, splits);
    }

    /** Null where the liquid is not split (no water, or no compound without a polar group, above the trace). */
    private static Boolean settles(FluidThermodynamics model, DerivedPairs.Result pairs, int[] at, int water, List<Double> fractions,
                                   double temperature, double pressure, Runnable checkpoint) {
        if (!(fractions.get(water) > TRACE)) return null;
        boolean nonpolar = false;
        for (int c = 0; c < fractions.size(); c++) nonpolar |= c != water && !pairs.polar(c) && fractions.get(c) > TRACE;
        if (!nonpolar) return null;
        double[] amounts = new double[model.componentCount()];
        for (int c = 0; c < fractions.size(); c++) if (at[c] >= 0) amounts[at[c]] = fractions.get(c);
        var state = model.flashTP(temperature, pressure, amounts, checkpoint);
        return FluidThermodynamics.holdsPhase(state, FluidThermodynamics.OIL) && FluidThermodynamics.holdsPhase(state, FluidThermodynamics.AQUEOUS);
    }

    /**
     * The trays (V3's numbers) whose liquid settles, whether the distillate does, and how many liquids were split.
     */
    public record Settling(List<Integer> trays, boolean distillate, int splits) {
        public static final Settling NONE = new Settling(List.of(), false, 0);

        public boolean any() { return !trays.isEmpty() || distillate; }

        /** The trays as the notice names them: {@code "3, 4, 5"}. */
        public String trayList() {
            var b = new StringBuilder();
            for (int tray : trays) b.append(b.isEmpty() ? "" : ", ").append(tray);
            return b.toString();
        }
    }
}
