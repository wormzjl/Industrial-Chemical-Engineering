package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The learned route's failure and fallback paths, and the coarse recovery that must never publish a surrogate.
 *
 * <p>The learned failures here are produced by a cap, not by a clock: an accepted profile displaced off its
 * own solution and corrected under a one-iteration budget fails deterministically.</p>
 */
class V3LearnedRecoveryTest {

    /** A column the classical route solves, with one small authored side draw. */
    private static V3ColumnInput drawInput() {
        return column(2, 1, List.of(new V3SideDrawSpec(1, 2.0)));
    }

    /** The same column with no authored feature at all. */
    private static V3ColumnInput plainInput() {
        return column(2, 1, List.of());
    }

    /**
     * Live boilup keeps this positive recovery fixture away from the old collapsed-phase root.
     */
    private static V3ColumnInput boiledDrawInput() {
        return column(2, 1, List.of(new V3SideDrawSpec(1, 2.0)), 500_000);
    }

    private static V3ColumnInput column(int stages, int feedStage, List<V3SideDrawSpec> draws) {
        return column(stages, feedStage, draws, 500_000);
    }

    private static V3ColumnInput column(int stages, int feedStage, List<V3SideDrawSpec> draws,
            double reboilerDutyWatts) {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl19_dwsim");
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[7] = 50;
        feed[15] = 50;
        return new V3ColumnInput(1, thermo.packageId(), "test:recovery", thermo.componentBasis(), feed, 650,
                stages, feedStage, 250_000, 750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),
                        new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(reboilerDutyWatts)),
                draws, List.of(), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private static V3InitializationOptions learned(V3InitializationOptions.Mode mode, int iterations) {
        return new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO, iterations, 30_000,
                V3InitializationOptions.Correction.PROGRESS);
    }

    /** A learned correction that closes by itself publishes the learned initializer event. */
    @Test void anAcceptedLearnedCorrectionPublishesTheLearnedEvent() {
        V3ColumnInput input = boiledDrawInput();
        V3NeuralInitializer model = fixed(acceptedProfile(input));
        var accepted = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 16), model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        assertTrue(accepted.diagnostics().events().getFirst().contains("initializer=LNN;"),
                accepted.diagnostics().events().toString());
    }

    /**
     * A learned seed displaced far enough that one Newton iteration cannot recover it fails as an
     * initialization failure under {@code LNN_ONLY}; the learned route publishes no partial state.
     */
    @Test void aCappedLearnedCorrectionFailsAsAnInitializationFailure() {
        V3ColumnInput input = drawInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var capped = V3ColumnCalculator.calculate(input, () -> {}, 0, 0,
                learned(V3InitializationOptions.Mode.LNN_ONLY, 1), model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        assertEquals(V3SolverFailureCode.INITIALIZATION_FAILURE,
                assertInstanceOf(V3ColumnOutcome.Failure.class, capped).code(),
                "a learned correction that runs out of iterations fails");
    }

    /** A model that never produces a seed leaves {@code LNN_ONLY} an initialization failure. */
    @Test void anUnavailableModelFailsLnnOnlyAsAnInitializationFailure() {
        V3ColumnInput input = drawInput();
        var failed = assertInstanceOf(V3ColumnOutcome.Failure.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_ONLY, 16), V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        assertEquals(V3SolverFailureCode.INITIALIZATION_FAILURE, failed.code());
    }

    /**
     * When the learned correction fails, {@code LNN_FIRST} publishes the unchanged classical backup: the
     * classical solution exactly, down to the streams, under the backup event.
     */
    @Test void aFailedLearnedCorrectionLeavesLnnFirstItsUnchangedClassicalBackup() {
        V3ColumnInput input = plainInput();
        V3NeuralInitializer model = fixed(displaced(acceptedProfile(input)));
        var direct = assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(input));
        var backup = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input, () -> {},
                0, 0, learned(V3InitializationOptions.Mode.LNN_FIRST, 1), model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        assertEquals(direct.result().streams(), backup.result().streams());
        String event = backup.diagnostics().events().getFirst();
        assertTrue(event.contains("initializer=CURRENT_BACKUP;"), event);
    }

    /**
     * A correction rule always extends and always stops early.
     *
     * <p>An extension without an early stop once compared each residual to itself and declared every attempt
     * stalled on its first iteration (LNN_ONLY 0 of 330 in the campaign arm that registered it). The rule now
     * has no zero window and no walls-only form, so that combination cannot be stated.</p>
     */
    @Test void aCorrectionRuleWithoutAnExtensionOrAnEarlyStopIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new V3InitializationOptions.Correction(8, 48, 8, 0.5, 0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3InitializationOptions.Correction(0, 0, 0, 0.0, 0, 0.0, 0.0));
    }

    @Test void anUnavailableRegroupedModelKeepsClassicalFallbackAvailable() {
        var input=plainInput();
        var classical=assertInstanceOf(V3ColumnOutcome.Success.class,V3TestInputs.calculate(input));
        var fallback=assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculate(input,()->{},0,0,
                V3TestInputs.LEARNED,V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        assertEquals(classical.result().streams(),fallback.result().streams());
    }

    /** The accepted solution of one request, exported through the unchanged classical route. */
    private static V3NeuralSeed acceptedProfile(V3ColumnInput input) {
        var captured = new AtomicReference<V3NeuralSeed>();
        assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {}, captured::set),
                "the recovery tests need a column the classical route solves");
        V3NeuralSeed accepted = captured.get();
        assertNotNull(accepted, "an accepted solve must export its profile");
        return accepted;
    }

    /** The accepted profile, moved off its own solution far enough that one Newton step cannot return. */
    private static V3NeuralSeed displaced(V3NeuralSeed seed) {
        double[][] liquid = seed.liquid(), vapor = seed.vapor();
        double[] temperatures = seed.temperatures();
        for (int n = 0; n < temperatures.length; n++) {
            if (n > 0) temperatures[n] += 12; // node zero carries the prescribed condenser temperature.
            for (int c = 0; c < liquid[n].length; c++) { liquid[n][c] *= 1.5; vapor[n][c] *= 0.7; }
        }
        return new V3NeuralSeed(seed.input(), seed.propertyRevision(), seed.branch(), liquid, vapor, temperatures,
                seed.freeWater(), seed.wetTrays());
    }

    private static V3NeuralInitializer fixed(V3NeuralSeed seed) {
        return new V3NeuralInitializer() {
            public String modelId() { return "learned-recovery-test"; }
            public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
                control.checkpoint();
                return Optional.of(seed);
            }
        };
    }

    // Merged from V3RequestedRecoveryTest.
 private static final Gson JSON=new Gson();
 @Test void coarseRecoveryNeverPublishesTheDrySurrogateForAnAuthoredFeatureRequest() throws Exception {
  V3ColumnInput input;
  try(var stream=getClass().getResourceAsStream("/science/column/v3/regrouped-authored-feature-recovery.json")) {
   assertNotNull(stream);input=input(JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("input"));
  }
  var captured=new AtomicReference<V3NeuralSeed>();
  var result=V3ColumnCalculator.calculateWithAcceptedProfile(input,V3SolveControl.UNBOUNDED,captured::set);
  if(result instanceof V3ColumnOutcome.Success success) {
   assertNotNull(captured.get());assertEquals(input,captured.get().input(),"No accepted surrogate may replace the authored request");
   assertEquals(input.sideDraws().size(),success.result().streams().stream().filter(s->s.streamId().startsWith("side_draw")).count());
   assertTrue(success.result().convergenceEvidence().satisfiesGates());
  } else assertNull(captured.get(),"A failed requested calculation must not export an intermediate label");
 }
    private static V3ColumnInput input(JsonObject json) {
        var specs=new ArrayList<V3ColumnSpecification>();
        for(var value:json.getAsJsonArray("specifications")) {
            var s=value.getAsJsonObject();
            if(s.has("kelvin"))specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if(s.has("ratio"))specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(),json.get("packageId").getAsString(),json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"),String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"),double[].class),json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(),json.get("feedStageNumber").getAsInt(),json.get("topPressurePascal").getAsDouble(),
                json.get("stagePressureDropPascal").getAsDouble(),specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"),V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"),V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"),V3PumparoundSpec[].class)), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }
}
