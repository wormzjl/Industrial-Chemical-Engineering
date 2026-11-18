package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Free water the drum returns with the reflux (trial of batch 2026-11-17-three-phase-separator,
 * {@code REFLUX_WATER_REVIEW.md}).
 *
 * <p>{@code R_w} enters tray one as liquid at the drum's temperature, like a liquid water feed there: tray one's
 * vapour water is {@code (all steam) + R_w - F_1}, the drum receives {@code steam + R_w} and returns {@code R_w},
 * and its decanted product is the rest, so the column's net water product does not move. The first tests read the
 * balance on the three-tray column of {@link V3FreeWaterTrayTest} with exact arithmetic; the last solves the wet
 * medium crude column of {@link V3PumparoundSteamCalculatorTest} with 0, 8 % and 26 % of its decanted water
 * returned.</p>
 */
class V3RefluxFreeWaterTest {
    private static final double STEAM = V3FreeWaterTrayTest.STEAM_MOL_PER_SECOND;
    private static final double RETURNED = 1.25;

    @Test
    void zeroReturnedWaterIsTheInputAndTheProblemAsTheyWere() {
        V3ColumnInput input = V3FreeWaterTrayTest.dryProblem().input();
        assertEquals(0.0, input.refluxFreeWaterMolPerSecond(), 0.0);
        assertFalse(input.returnsRefluxFreeWater());
        assertSame(input, input.withRefluxFreeWater(0.0));
        V3ColumnInput negativeZero = input.withRefluxFreeWater(-0.0);
        assertEquals(input, negativeZero, "a -0.0 reads as the zero it is");
        assertEquals(input.hashCode(), negativeZero.hashCode());
        assertEquals(Double.doubleToLongBits(0.0), Double.doubleToLongBits(negativeZero.refluxFreeWaterMolPerSecond()));
        assertEquals(digest(V3FreeWaterTrayTest.dryProblem()),
                digest(V3ColumnProblemResolver.resolve(negativeZero, V3CondenserPhaseBranch.TWO_PHASE)));

        V3ColumnInput returning = input.withRefluxFreeWater(RETURNED);
        assertFalse(input.equals(returning));
        assertTrue(returning.toString().contains("refluxFreeWater="));
        assertFalse(digest(V3FreeWaterTrayTest.dryProblem()).equals(
                digest(V3ColumnProblemResolver.resolve(returning, V3CondenserPhaseBranch.TWO_PHASE))),
                "a column that returns water never shares a digest with one that does not");
    }

    @Test
    void returnedWaterNeedsSteamAndADrumThatDecants() {
        V3ColumnInput input = V3FreeWaterTrayTest.dryProblem().input();
        assertThrows(IllegalArgumentException.class, () -> input.withRefluxFreeWater(-1.0));
        assertThrows(IllegalArgumentException.class, () -> input.withRefluxFreeWater(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnInput(input.schemaVersion(), input.packageId(),
                input.assayId(), input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(),
                input.feedTemperatureKelvin(), input.stageCount(), input.feedStageNumber(), input.topPressurePascal(),
                input.stagePressureDropPascal(), input.specifications(), List.of(), List.of(), List.of(),
                input.columnDiameterMetres(), RETURNED), "no steam, no water to return");
        // A vapour-only drum keeps all its water in the vapour: it has none to return.
        assertThrows(IllegalArgumentException.class, () -> V3ColumnProblemResolver.resolve(
                input.withRefluxFreeWater(RETURNED), V3CondenserPhaseBranch.VAPOR_ONLY));
    }

    @Test
    void trayOneReceivesTheReturnedWaterAndTheDrumReturnsItFromWhatArrives() {
        V3ColumnProblem base = wet(0.0);
        V3ColumnProblem returning = wet(RETURNED);
        assertEquals(V3ColumnProblem.WaterCondenserRegime.FREE_WATER, returning.waterCondenserRegime());
        assertEquals(STEAM + RETURNED, returning.waterVaporFlowMolPerSecond(1), 0.0);
        for (int node = 2; node <= returning.topology().reboilerNode(); node++) {
            assertEquals(base.waterVaporFlowMolPerSecond(node), returning.waterVaporFlowMolPerSecond(node), 0.0,
                    "node " + node + " sees none of it");
        }
        for (double carried : new double[] {1.0e-9, 0.5, 1.5, 4.0}) {
            V3DryMeshState state = carrying(returning, carried);
            assertEquals(STEAM + RETURNED - carried, returning.waterVaporFlow(state, 1), 0.0);
            assertEquals(base.waterVaporFlow(carrying(base, carried), 2), returning.waterVaporFlow(state, 2), 0.0);
            V3ColumnProblem.WaterCondenserSplit split = returning.waterCondenserSplit(state);
            V3ColumnProblem.WaterCondenserSplit before = base.waterCondenserSplit(carrying(base, carried));
            assertEquals(RETURNED, split.refluxFreeWaterMolPerSecond(), 0.0);
            assertEquals(0.0, before.refluxFreeWaterMolPerSecond(), 0.0);
            // The drum receives the steam and the returned water, sends the returned water back, and decants the rest.
            assertEquals(returning.waterVaporFlow(state, 1) + returning.carriedOverWaterMolPerSecond(state),
                    split.vaporFlowMolPerSecond() + split.freeWaterFlowMolPerSecond()
                            + split.refluxFreeWaterMolPerSecond(), 1.0e-12);
            assertEquals(STEAM, split.vaporFlowMolPerSecond() + split.freeWaterFlowMolPerSecond(), 1.0e-12,
                    "the column's net water out is its steam");
            assertEquals(before.vaporFlowMolPerSecond(), split.vaporFlowMolPerSecond(), 0.0);
            assertEquals(before.freeWaterFlowMolPerSecond(), split.freeWaterFlowMolPerSecond(), 1.0e-12);
        }
        // Carrying over all the water that reaches tray one leaves its vapour dry: a rejected trial, as before.
        V3DryMeshState everything = carrying(returning, STEAM + RETURNED);
        assertThrows(IllegalArgumentException.class, () -> returning.waterVaporFlow(everything, 1));
    }

    /**
     * Tray one gains the returned water's liquid enthalpy at the drum and loses it again as steam in its vapour;
     * the drum books the same liquid leaving it, so the duty grows by the latent heat between tray one and the drum,
     * the column's boundary closes, and the independent drum closure refuses a duty that forgot the returned water.
     */
    @Test
    void theReturnedWatersHeatIsInTrayOneAndTheDrumAndTheBoundaryCloses() {
        V3FreeWaterTrayTest.AffineEnthalpyThermo thermo = new V3FreeWaterTrayTest.AffineEnthalpyThermo();
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        for (double carried : new double[] {0.0, 1.5}) {
            V3ColumnProblem base = carried == 0.0 ? dry(0.0) : wet(0.0);
            V3ColumnProblem returning = carried == 0.0 ? dry(RETURNED) : wet(RETURNED);
            V3DryMeshState state = carrying(returning, carried);
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(returning, thermo, 0.0);
            V3MeshResidualEvaluator baseEvaluator = new V3MeshResidualEvaluator(base, thermo, 0.0);

            double t1 = state.temperatureKelvin(1), p1 = returning.nodePressurePascal(1);
            double t0 = state.temperatureKelvin(0), p0 = returning.nodePressurePascal(0);
            double hydrocarbon = V3WetTraySet.hydrocarbonVaporTotal(state, 1);
            double water = base.waterVaporFlow(state, 1);
            double liquidIn = RETURNED * V3WaterProperties.liquidMolarEnthalpy(t0, p0);
            double steamOut = V3WaterProperties.steamEnthalpyRate(t1, p1, water + RETURNED, hydrocarbon)
                    - V3WaterProperties.steamEnthalpyRate(t1, p1, water, hydrocarbon);
            assertEquals(liquidIn, returning.refluxFreeWaterEnthalpyWatts(state), 0.0);
            assertEquals(liquidIn - steamOut, V3FreeWaterTrayTest.energy(evaluator, state, 1)
                    - V3FreeWaterTrayTest.energy(baseEvaluator, state, 1), 1.0e-6);
            for (int node = 2; node <= returning.topology().reboilerNode(); node++) {
                assertEquals(V3FreeWaterTrayTest.energy(baseEvaluator, state, node),
                        V3FreeWaterTrayTest.energy(evaluator, state, node), 0.0, "node " + node);
            }

            double duty = V3ColumnDutyLedger.condenserDutyWatts(returning, state, evaluator, workspace);
            double baseDuty = V3ColumnDutyLedger.condenserDutyWatts(base, state, baseEvaluator, workspace);
            double latent = steamOut - liquidIn;
            assertTrue(latent > 0.0, "the returned water condenses again in the drum");
            assertEquals(-latent, duty - baseDuty, 1.0e-9 * Math.max(Math.abs(duty), Math.abs(baseDuty)));
            double closure = V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE;
            assertTrue(V3AcceptanceAuditor.condenserEnergyBalance(returning, thermo, state, workspace, duty, closure).passed());
            assertFalse(V3AcceptanceAuditor.condenserEnergyBalance(returning, thermo, state, workspace, baseDuty, closure)
                    .passed(), "a duty that forgot the returned water fails the drum's own closure");
            assertColumnEnergyCloses(returning, state);
        }
    }

    /**
     * The wet medium crude column (sump steam 333 mol/s, 250 kPa, a 59 C drum that decants) with 0, 8 % and 26 % of
     * its decanted water returned with the reflux, each solved cold. Each converges and passes the audit; the drum
     * returns exactly {@code R_w}; the column's water out is its steam; the duty ledger closes on the products; the
     * condenser duty moves by less than 2 % of the returned water's latent heat, tray one cools, the distillate falls.
     */
    @Test
    void theWetMediumCrudeColumnSolvesWithItsDrumReturningWater() {
        V3ColumnInput input = wetMediumCrude();
        Solve base = solve("returned 0", input);
        double decanted = freeWaterProduct(base.success());
        assertTrue(decanted > 0.0);
        assertEquals(0.0, base.split().refluxFreeWaterMolPerSecond(), 0.0);
        for (double fraction : new double[] {0.08, 0.26}) {
            double returned = fraction * decanted;
            Solve solve = solve(String.format(Locale.ROOT, "returned %.0f %%", 100.0 * fraction),
                    input.withRefluxFreeWater(returned));
            V3ColumnOutcome.Success success = solve.success();
            V3AcceptanceAudit audit = success.result().acceptanceAudit();
            assertTrue(audit.accepted(), audit.checks()::toString);
            for (String family : new String[] {"WATER_PROFILE", "WATER_BALANCE", "FREE_WATER_SPLIT",
                    "CONDENSER_ENERGY_BALANCE", "GLOBAL_ENERGY_BALANCE"}) {
                assertTrue(check(audit, family).passed(), family);
            }
            V3ColumnProblem problem = success.result().problem();
            assertEquals(returned, solve.split().refluxFreeWaterMolPerSecond(), 0.0);
            assertEquals(steam(input), solve.split().vaporFlowMolPerSecond() + solve.split().freeWaterFlowMolPerSecond(),
                    1.0e-12 * steam(input), "the column's net water out is its steam");
            assertEquals(solve.split().freeWaterFlowMolPerSecond(), freeWaterProduct(success), 0.0,
                    "the published free-water stream is the decanted product");
            assertEquals(steam(input) + returned, problem.waterVaporFlowMolPerSecond(1), 0.0);
            assertTrue(audit.advisoryEvidence().stream().anyMatch(line -> line.startsWith(
                    "free water returned with the reflux")), audit.advisoryEvidence()::toString);
            assertLedgerCloses(success);
            // The water loop is internal and the reboiler duty is fixed, so the drum's duty barely moves: the latent
            // heat of the returned water, evaporated on tray one and condensed again in the drum, displaces the same
            // heat of hydrocarbon condensation. Tray one cools and the distillate falls.
            double latent = returned * (V3WaterProperties.steamMolarEnthalpy(solve.profile().temperatures()[1],
                    problem.nodePressurePascal(1)) - V3WaterProperties.liquidMolarEnthalpy(
                    solve.profile().temperatures()[0], problem.nodePressurePascal(0)));
            double dutyChange = success.result().dutyLedger().orElseThrow().condenserWatts()
                    - base.success().result().dutyLedger().orElseThrow().condenserWatts();
            assertTrue(Math.abs(dutyChange) < 0.02 * latent, () -> "duty change " + dutyChange + " W against " + latent);
            assertTrue(solve.profile().temperatures()[1] < base.profile().temperatures()[1], "tray one cools");
            assertTrue(flow(success, "distillate_liquid") < flow(base.success(), "distillate_liquid"), "less distillate");
            report(solve, base);
        }
    }

    private static V3InputDigest digest(V3ColumnProblem problem) {
        return V3InputDigest.of(problem, "formulation", "data", "assumptions");
    }

    private static V3ColumnProblem dry(double returned) {
        return V3ColumnProblemResolver.resolve(V3FreeWaterTrayTest.dryProblem().input().withRefluxFreeWater(returned),
                V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3ColumnProblem wet(double returned) {
        V3ColumnProblem dry = dry(returned);
        boolean[] wet = new boolean[dry.topology().nodeCount()];
        wet[1] = true;
        return V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(), V3WetTraySet.of(dry.topology(), wet));
    }

    private static V3DryMeshState carrying(V3ColumnProblem problem, double flow) {
        return V3ColumnInitializer.withFreeWater(V3FreeWaterTrayTest.state(problem.topology()), problem.topology(),
                problem.isWetTray(1) ? V3FreeWaterTrayTest.freeWaterProfile(problem, 1, flow)
                        : new double[problem.topology().nodeCount()]);
    }

    /** The energy rows sum to the boundary closure: the returned water is internal to the column and its drum. */
    private static void assertColumnEnergyCloses(V3ColumnProblem problem, V3DryMeshState state) {
        V3FreeWaterTrayTest.AffineEnthalpyThermo thermo = new V3FreeWaterTrayTest.AffineEnthalpyThermo();
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        double rows = 0.0;
        for (V3MeshResidual.Row row : evaluator.evaluate(state, workspace).rows()) {
            if (row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) rows += row.physicalValue();
        }
        V3MeshResidualEvaluator.LocalNodeTerms condenser = evaluator.localTerms(state, 0, workspace);
        double bottoms = evaluator.localTerms(state, problem.topology().reboilerNode(), workspace).liquidPhaseEnergy();
        double duty = V3ColumnDutyLedger.condenserDutyWatts(problem, state, evaluator, workspace);
        double steam = V3ColumnDutyLedger.steamEnthalpyWatts(problem);
        double decanted = V3ColumnDutyLedger.freeWaterEnergyWatts(problem, state);
        double distillate = 0.5 * condenser.liquidPhaseEnergy();
        double closure = steam + duty - distillate - condenser.vaporPhaseEnergy() - decanted - bottoms;
        double scale = Math.max(Math.max(Math.abs(steam), Math.abs(duty)), Math.max(Math.abs(bottoms),
                Math.abs(condenser.vaporPhaseEnergy())));
        assertEquals(closure, rows, 1.0e-9 * scale, problem.wetTraySet()::toString);
    }

    private record Solve(String label, V3ColumnOutcome outcome, long polls, V3NeuralSeed profile) {
        V3ColumnOutcome.Success success() {
            return assertInstanceOf(V3ColumnOutcome.Success.class, outcome, () -> label + ": " + outcome);
        }

        V3ColumnProblem.WaterCondenserSplit split() {
            V3ColumnProblem problem = success().result().problem();
            return problem.waterCondenserSplit(new V3DryMeshState(problem.topology(), profile.liquid()[0].length,
                    profile.liquid(), profile.vapor(), profile.temperatures(), profile.freeWater()));
        }
    }

    private static Solve solve(String label, V3ColumnInput input) {
        AtomicLong polls = new AtomicLong();
        AtomicReference<V3NeuralSeed> profile = new AtomicReference<>();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {
            if (polls.incrementAndGet() > 40_000_000L) throw new CancellationException(label + ": poll cap");
        }, profile::set);
        if (outcome instanceof V3ColumnOutcome.Failure failure) {
            throw new AssertionError(label + ": " + failure.code() + " " + failure.diagnostics().solvePath()
                    + " " + failure.diagnostics().events());
        }
        assertNotNull(profile.get(), label);
        return new Solve(label, outcome, polls.get(), profile.get());
    }

    private static void report(Solve solve, Solve base) {
        V3ColumnOutcome.Success success = solve.success(), reference = base.success();
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        V3ColumnDutyLedger baseLedger = reference.result().dutyLedger().orElseThrow();
        V3ColumnProblem problem = success.result().problem();
        double returned = problem.refluxFreeWaterMolPerSecond();
        double t0 = solve.profile().temperatures()[0], t1 = solve.profile().temperatures()[1];
        double latent = V3WaterProperties.steamMolarEnthalpy(t1, problem.nodePressurePascal(1))
                - V3WaterProperties.liquidMolarEnthalpy(t0, problem.nodePressurePascal(0));
        System.out.printf(Locale.ROOT, "reflux water %s: R_w %.2f mol/s; condenser %.4f MW (base %.4f, change %.4f MW,"
                        + " R_w x latent %.4f MW); tray 1 %.2f K (base %.2f), wet %b, free water %.3f mol/s;"
                        + " distillate %.3f mol/s (base %.3f); decanted %.3f mol/s (base %.3f); newton %d (base %d),"
                        + " polls %d (base %d); path %s%n",
                solve.label(), returned, ledger.condenserWatts() / 1e6, baseLedger.condenserWatts() / 1e6,
                (ledger.condenserWatts() - baseLedger.condenserWatts()) / 1e6, -returned * latent / 1e6, t1,
                base.profile().temperatures()[1], solve.profile().wetTrays()[1], solve.profile().freeWater()[1],
                flow(success, "distillate_liquid"), flow(reference, "distillate_liquid"), freeWaterProduct(success),
                freeWaterProduct(reference), success.diagnostics().newtonIterations(),
                reference.diagnostics().newtonIterations(), solve.polls(), base.polls(), success.diagnostics().solvePath());
    }

    private static double flow(V3ColumnOutcome.Success success, String streamId) {
        return success.result().streams().stream().filter(stream -> stream.streamId().equals(streamId))
                .mapToDouble(V3ColumnStreamProperties::molarFlowMolPerSecond).sum();
    }

    private static double freeWaterProduct(V3ColumnOutcome.Success success) {
        return flow(success, "free_water");
    }

    private static double steam(V3ColumnInput input) {
        return input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
    }

    private static V3AcceptanceAudit.Check check(V3AcceptanceAudit audit, String family) {
        return audit.checks().stream().filter(candidate -> candidate.family().equals(family)).findFirst()
                .orElseThrow(() -> new AssertionError(family + " missing from " + audit.checks()));
    }

    /** The wet baseline of {@link V3PumparoundSteamCalculatorTest}: no pumparound, no side draw. */
    private static V3ColumnInput wetMediumCrude() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(V3TestInputs.MEDIUM_CRUDE_PACKAGE);
        V3CrudeFeed crude = thermo.crudeFeed(V3TestInputs.MEDIUM_CRUDE_ASSAY);
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 365.0 + 273.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(),
                List.of(new V3SteamFeedSpec(31, 1_200.0 * 1_000.0 / 3_600.0, 533.15)), List.of(),
                V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    /** As {@code V3PumparoundSteamCalculatorTest.assertLedgerCloses}: the duty ledger closes on the products. */
    private static void assertLedgerCloses(V3ColumnOutcome.Success success) {
        V3ColumnDutyLedger ledger = success.result().dutyLedger().orElseThrow();
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(
                success.result().problem().input().packageId());
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        int componentCount = success.result().problem().input().componentBasis().componentCount();
        double products = 0.0;
        for (V3ColumnStreamProperties stream : success.result().streams()) {
            if (stream.streamId().equals("free_water")) {
                products += stream.molarFlowMolPerSecond()
                        * V3WaterProperties.liquidMolarEnthalpy(stream.temperatureKelvin(), stream.pressurePascal());
                continue;
            }
            List<V3ColumnStreamProperties.ComponentFraction> fractions = stream.moleFractions();
            double waterFraction = fractions.size() == componentCount + 1 ? fractions.get(componentCount).moleFraction() : 0.0;
            double waterFlow = stream.molarFlowMolPerSecond() * waterFraction;
            products += V3WaterProperties.steamEnthalpyRate(stream.temperatureKelvin(), stream.pressurePascal(),
                    waterFlow, stream.molarFlowMolPerSecond() - waterFlow);
            double hydrocarbonFlow = stream.molarFlowMolPerSecond() - waterFlow;
            if (!(hydrocarbonFlow > 0.0)) continue;
            double[] composition = new double[componentCount];
            for (int component = 0; component < componentCount; component++) {
                composition[component] = fractions.get(component).moleFraction() / (1.0 - waterFraction);
            }
            products += hydrocarbonFlow * thermo.molarEnthalpy(stream.temperatureKelvin(), stream.pressurePascal(),
                    composition, stream.vaporMoleFraction() == 1.0 ? Root.VAPOR : Root.LIQUID, workspace);
        }
        double supplied = ledger.feedEnthalpyWatts() + ledger.steamEnthalpyWatts() + ledger.reboilerWatts()
                + ledger.stageHeatTotalWatts() + ledger.condenserWatts();
        double largest = Math.max(Math.abs(products), Math.max(Math.abs(ledger.feedEnthalpyWatts()),
                Math.abs(ledger.condenserWatts())));
        double closure = supplied - products;
        assertTrue(Math.abs(closure) <= 1.0e-6 * largest,
                () -> "duty ledger does not close: " + closure + " W against " + largest + " W");
    }
}
