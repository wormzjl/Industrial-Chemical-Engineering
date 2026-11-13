package com.wormzjl.icheme.fluid.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.tutorial.guide.ColumnCondition;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

    /** The guide's tower template and its crude (assets columnpreset/small_crude.json and mixturepreset/small_crude.json). */
    static final String TEMPLATE = "small_crude";

    /** The plant: the template at its whole feed, on the review plant's layout (its first pumparound place) and units. */
    static ColumnOperationReviewWorld.Options options() {
        var r = ColumnOperationReviewWorld.Options.REVIEW;
        return new ColumnOperationReviewWorld.Options(1, TutorialUnitGameTests::input, ColumnComponents.Condenser.EXCHANGER,
                ColumnOperationReviewWorld.exchanger(ColumnOperationReviewWorld.CONDENSER_SIZE), null, AssemblyGameTests.Plant.unit(Kind.EXCHANGER), null,
                r.feedBore(), r.steamBore(), 0, 0, false, false, r.layout());
    }

    /**
     * The guide's tower as the player builds it: its stream template's crude, flow and temperature under its tower template,
     * on the default column preset's package, as the column tests' {@code V3TestInputs.crude} builds it (the templates are
     * the client's assets, not the material catalog's presets; final lane 2026-11-13).
     */
    static V3ColumnInput input(MaterialCatalog catalog) {
        var base = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        var stream = asset("mixturepreset/" + TEMPLATE + ".json");
        double rate = stream.get("flow_kmol_h").getAsDouble();
        var tower = V3ColumnTemplate.read(asset("columnpreset/" + TEMPLATE + ".json")).scaledTo(rate);
        var ids = base.componentBasis().componentIds();
        double[] feed = new double[ids.size()];
        double total = 0;
        for (var row : stream.getAsJsonArray("components")) {
            double amount = row.getAsJsonObject().get("amount").getAsDouble();
            feed[ids.indexOf(row.getAsJsonObject().get("id").getAsString())] = amount;
            total += amount;
        }
        for (int i = 0; i < feed.length; i++) feed[i] *= rate / 3.6 / total;
        String assay = catalog.requirePackage(base.packageId()).assays().containsKey("icheme:" + TEMPLATE) ? "icheme:" + TEMPLATE : base.assayId();
        return new V3ColumnInput(1, base.packageId(), assay, base.componentBasis(), feed, stream.get("temperature_kelvin").getAsDouble(),
                tower.trays() - 2, tower.feedTray() - 1, tower.topPressure() * 1e5, base.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(tower.topTemperature()),
                        new V3ColumnSpecification.OrganicRefluxRatio(tower.refluxRatio()), new V3ColumnSpecification.ReboilerDuty(tower.reboilerDuty() * 1e6)),
                tower.draws().stream().map(d -> new V3SideDrawSpec(d.tray() - 1, d.flow() / 3.6)).toList(),
                tower.steam().stream().map(st -> new V3SteamFeedSpec(st.tray() - 1, st.flow() / 3.6, st.temperature())).toList(),
                tower.pumparounds().stream().map(q -> new V3PumparoundSpec(q.returnTray() - 1, q.drawTray() - 1, -q.cooling() * 1e6, q.split())).toList(),
                tower.diameter());
    }

    /** A file of the mod's {@code assets/icheme/}. */
    private static JsonObject asset(String path) {
        try (var in = IChemE.class.getResourceAsStream("/assets/icheme/" + path)) {
            if (in == null) throw new IllegalStateException("No asset " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
