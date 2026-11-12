package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.tutorial.guide.ColumnCondition;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The field guide's crude unit tower runs (batch 2026-11-12-tutorial-system, P5 stage B, proof 1): the {@code small_crude}
 * column template at its design feed on {@link ColumnOperationReviewWorld}'s plant (water-cooled condenser, receiver, reflux
 * pump, its one pumparound's pump and cooler; feed and steam from generators, every product to a void). Check parts finds
 * it COMPLETE, Start is accepted, and the column reaches RUNNING with nothing binding at its whole design feed; the guide's
 * start-up conditions ({@link ColumnCondition}: go, limit, products) hold on that view. The test removes what it placed,
 * its result held until the cleanup is done.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class TutorialUnitGameTests {
    private TutorialUnitGameTests() {}

    /** The guide's tower template (columnpreset/small_crude.json). */
    static final String TEMPLATE = "small_crude";

    /** The plant: the template at its whole feed, on the review plant's layout (its first pumparound place) and units. */
    static ColumnOperationReviewWorld.Options options() {
        var r = ColumnOperationReviewWorld.Options.REVIEW;
        return new ColumnOperationReviewWorld.Options(1, catalog -> catalog.presets().column(TEMPLATE).input(catalog), ColumnComponents.Condenser.EXCHANGER,
                ColumnOperationReviewWorld.exchanger(ColumnOperationReviewWorld.CONDENSER_SIZE), null, AssemblyGameTests.Plant.unit(Kind.EXCHANGER), null,
                r.feedBore(), r.steamBore(), 0, 0, false, false, r.layout());
    }

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "tutorial-unit")
    public static void theGuidesSmallCrudeTowerRunsAtItsDesignFeed(GameTestHelper helper) {
        var rig = ColumnOperationReviewWorld.rig(helper, options());
        var plant = rig.plant;
        String[] failure = {null};
        try {
            rig.build();
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            IChemE.LOGGER.error("tutorial_unit build failed", e);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || plant.valvesInstalled(), "Waiting for the valves"))
                .thenIdle(20)
                // Check parts, then Start: rig.start fails unless the check finds the plant COMPLETE and the start is accepted.
                .thenExecute(() -> {
                    if (failure[0] == null) rig.start(failure);
                })
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || rig.paced() && atDesign(rig, failure), "Waiting for the tower at its design feed"))
                .thenExecute(() -> {
                    if (failure[0] != null) return;
                    var view = view(rig);
                    if (!new ColumnCondition(Optional.of(ColumnCondition.STARTED), Optional.empty()).matches(view)) failure[0] = "the guide's go step: " + view.phase();
                    else if (!new ColumnCondition(Optional.of(List.of("RUNNING")), Optional.empty()).matches(view)) failure[0] = "the guide's limit step: " + view.phase();
                    else if (!new ColumnCondition(Optional.empty(), Optional.of(true)).matches(view)) failure[0] = "the guide's products step: no product leaves";
                    IChemE.LOGGER.info("tutorial_unit phase={} line={} feed_kmol_h={}", view.phase(), view.engine(), String.format(java.util.Locale.ROOT, "%.2f", feed(view) * 3.6));
                })
                .thenExecute(plant::remove)
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    static OperationView view(ColumnOperationReviewWorld.Rig rig) {
        return rig.plant.world.columnView(rig.plant.position(rig.plant.column.origin()), id -> Double.NaN);
    }

    static double feed(OperationView view) {
        return view.streams().stream().filter(s -> s.port() == Port.FEED).mapToDouble(OperationView.Stream::rate).sum();
    }

    /** RUNNING, nothing binding, the whole design feed taken; NOT READY or UPSET fails the test. */
    static boolean atDesign(ColumnOperationReviewWorld.Rig rig, String[] failure) {
        var view = view(rig);
        if (view == null) return false;
        if (view.phase() == OperationView.Phase.NOT_READY || view.phase() == OperationView.Phase.UPSET) failure[0] = "column " + view.phase() + ": " + view.engine();
        double design = Arrays.stream(rig.input.feedComponentMolarFlowsMolPerSecond()).sum();
        return view.phase() == OperationView.Phase.RUNNING && view.binding() == null && view.alpha() == 1 && feed(view) >= .99 * design;
    }
}
