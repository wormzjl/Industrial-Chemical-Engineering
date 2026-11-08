package com.wormzjl.icheme.network;

import static com.wormzjl.icheme.network.ColumnCodecs.buffer;
import static com.wormzjl.icheme.network.ColumnCodecs.failure;
import static com.wormzjl.icheme.network.ColumnCodecs.readInput;
import static com.wormzjl.icheme.network.ColumnCodecs.readResult;
import static com.wormzjl.icheme.network.ColumnCodecs.wireRoundTrip;
import static com.wormzjl.icheme.network.ColumnCodecs.writeInput;
import static com.wormzjl.icheme.network.ColumnCodecs.writeResult;
import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.v3.*;
import io.netty.handler.codec.DecoderException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Transport contract of the column input and the accepted-result certificate, over the wire codec of
 * {@link ColumnWire}: side draws, steam feeds,
 * pumparounds and the duty ledger, the convergence closure, the column diameter and the tray hydraulics.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class ColumnWireTest {
    private static final List<V3PumparoundSpec> PUMPAROUNDS = List.of(
            new V3PumparoundSpec(1, 1, -2_500_000.0, V3PumparoundSpec.Split.RETURN_TRAY),
            new V3PumparoundSpec(2, 3, -900_000.0, V3PumparoundSpec.Split.UNIFORM),
            new V3PumparoundSpec(2, 4, 450_000.0, V3PumparoundSpec.Split.RETURN_TRAY),
            new V3PumparoundSpec(3, 4, -600_000.0, V3PumparoundSpec.Split.UNIFORM));

    // ---- Side draws and steam feeds

    @Test
    void roundedServerDefaultPublishesAndSolvesTheQualifiedLiteratureSideDraws() throws Exception {
        V3ColumnInput input = com.wormzjl.icheme.science.material.Cdu17TestCatalog.pilotInput();

        assertEquals(29, input.stageCount());
        assertEquals(150_000.0, input.topPressurePascal());
        assertEquals(List.of(13, 17, 22), input.sideDraws().stream().map(V3SideDrawSpec::trayNumber).toList());
        assertArrayEquals(new double[] {92.3, 131.85, 32.96},
                input.sideDraws().stream().mapToDouble(draw -> draw.molarFlowMolPerSecond() * 3.6).toArray(),
                1.0e-12);

        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started > 45_000_000_000L) {
                throw new AssertionError("rounded GUI default exceeded 45 seconds");
            }
        }, 0, 0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
    }

    @Test
    void wireRoundTripsZeroThroughThreeDrawsAndRejectMissingList() throws Exception {
        for (int count = 0; count <= 3; count++) {
            V3ColumnInput input = sideDrawInput(count);
            assertEquals(input, wireRoundTrip(input));
        }
    }

    @Test
    void wireRoundTripsSteamFeedsAndRejectMissingList() throws Exception {
        V3ColumnInput base = sideDrawInput(1);
        V3ColumnInput steam = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), base.sideDraws(),
                List.of(new V3SteamFeedSpec(5, 3.0, 450.0), new V3SteamFeedSpec(2, 1.0, 450.0)), List.of(),
                V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
        assertEquals(steam, wireRoundTrip(steam));
    }

    @Test
    void aFullStreamCertificateRoundTripsThroughTheWireAndRejectsOneMoreStream() throws Exception {
        List<V3ColumnStreamProperties> streams = java.util.stream.IntStream.range(0, V3ColumnStreamProperties.MAX_STREAMS).mapToObj(index ->
                new V3ColumnStreamProperties("product_" + index, "Product " + index, "LIQUID", 10, 1, 400, 250_000, 0,
                        List.of(new V3ColumnStreamProperties.ComponentFraction("a", 1, 1)))).toList();
        V3ColumnDisplayResult result = new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 2, 0, 6,
                streams, Optional.empty(), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, Optional.empty(), Optional.empty());
        assertEquals(result, wireRoundTrip(result));
    }

    @Test
    void malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration() throws Exception {
        // An empty input ends in the three bounded list counts, written in order as one zero byte each. Address them
        // by name: poking the last byte alone once forged a pumparound count while claiming to test side draws, so
        // the assertion only tracked the side-draw bound while MAX_PUMPAROUNDS happened to be smaller.
        assertForgedCountIsRejected(3, V3ColumnInput.MAX_SIDE_DRAWS + 1, "side draw");
        assertForgedCountIsRejected(2, V3ColumnInput.MAX_STEAM_FEEDS + 1, "steam feed");
        assertForgedCountIsRejected(1, V3ColumnInput.MAX_PUMPAROUNDS + 1, "pumparound");
        // In bound but with no payload behind it: still the decoder's to refuse, never a read off the end.
        assertForgedCountIsRejected(1, V3ColumnInput.MAX_PUMPAROUNDS, "truncated pumparound");
    }

    // ---- Pumparounds and the duty ledger

    @Test
    void wireRoundTripsZeroThroughFourPumparoundsAcrossBothSplits() throws Exception {
        for (int count = 0; count <= V3ColumnInput.MAX_PUMPAROUNDS; count++) {
            V3ColumnInput input = pumparoundInput(count, 4.17);
            assertEquals(count, input.pumparounds().size());
            assertEquals(input, wireRoundTrip(input));
        }
    }

    @Test
    void fourPumparoundsAndExactZeroRefluxSurviveBothTransports() throws Exception {
        V3ColumnInput input = pumparoundInput(4, 0.0);
        assertEquals(input, wireRoundTrip(input));
        assertTrue(V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.VAPOR_ONLY)
                .degreeOfFreedomLedger().isValid());
    }

    @Test
    void wireRejectsAFifthPumparoundANonFiniteDutyAndAnUnknownSplit() throws Exception {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeInput(buffer, pumparoundInput(0, 4.17));
            // The trailing byte is the pumparound count varint; an out-of-contract count is refused before allocation.
            buffer.setByte(buffer.writerIndex() - 1, V3ColumnInput.MAX_PUMPAROUNDS + 1);
            assertInstanceOf(DecoderException.class, readWireFailure(buffer));
        } finally {
            buffer.release();
        }

        buffer = buffer();
        try {
            writeInput(buffer, pumparoundInput(1, 4.17));
            // One entry trails as varint return, varint draw, eight duty bytes, varint split ordinal.
            buffer.setDouble(buffer.writerIndex() - 9, Double.NaN);
            assertInstanceOf(DecoderException.class, readWireFailure(buffer));
        } finally {
            buffer.release();
        }

        buffer = buffer();
        try {
            writeInput(buffer, pumparoundInput(1, 4.17));
            buffer.setByte(buffer.writerIndex() - 1, V3PumparoundSpec.Split.values().length);
            assertInstanceOf(DecoderException.class, readWireFailure(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void displayResultCarriesAnAbsentAndAFullyPopulatedDutyLedgerThroughTheWire() throws Exception {
        for (Optional<V3ColumnDutyLedger> ledger :
                List.of(Optional.<V3ColumnDutyLedger>empty(), Optional.of(ledger(1)),
                        Optional.of(ledger(V3ColumnDutyLedger.MAX_STAGE_DUTIES)))) {
            V3ColumnDisplayResult result = certificate(ledger, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, Optional.empty());
            V3ColumnDisplayResult decoded = wireRoundTrip(result);
            assertEquals(result, decoded);
            assertEquals(ledger, decoded.dutyLedger());
        }
    }

    @Test
    void aSeventeenthStageDutyIsRejectedByBothTransports() throws Exception {
        V3ColumnDisplayResult result = certificate(Optional.of(ledger(V3ColumnDutyLedger.MAX_STAGE_DUTIES)),
                V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, Optional.empty());
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeResult(buffer, result);
            // The stage-duty count precedes sixteen tray/duty pairs of nine bytes each, and the ledger is
            // followed by the absent-hydraulics flag.
            buffer.setByte(buffer.writerIndex() - 9 * V3ColumnDutyLedger.MAX_STAGE_DUTIES - 2,
                    V3ColumnDutyLedger.MAX_STAGE_DUTIES + 1);
            assertInstanceOf(DecoderException.class, failure(() -> readResult(buffer)));
        } finally {
            buffer.release();
        }
    }

    @Test
    void coolingDutiesStayNegativeThroughEveryTransportHop() throws Exception {
        V3ColumnInput input = pumparoundInput(2, 4.17);
        assertTrue(input.pumparounds().stream().allMatch(pumparound -> pumparound.dutyWatts() < 0.0));
        for (V3ColumnInput decoded : List.of(wireRoundTrip(input))) {
            assertEquals(input.pumparounds(), decoded.pumparounds());
            for (int index = 0; index < decoded.pumparounds().size(); index++) {
                double duty = decoded.pumparounds().get(index).dutyWatts();
                assertTrue(duty < 0.0, "cooling duty lost its sign at index " + index);
                assertEquals(PUMPAROUNDS.get(index).dutyWatts(), duty, 0.0);
            }
        }

        V3ColumnDutyLedger cooling = new V3ColumnDutyLedger(-4_000_000.0, 8_000_000.0, -3_400_000.0,
                List.of(new V3ColumnDutyLedger.StageDuty(3, -3_400_000.0)), 1_200_000.0, 0.0);
        V3ColumnDisplayResult result = certificate(Optional.of(cooling), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE,
                Optional.empty());
        V3ColumnDutyLedger sent = wireRoundTrip(result).dutyLedger().orElseThrow();
        assertEquals(cooling, sent);
        assertEquals(-3_400_000.0, sent.stageHeatTotalWatts(), 0.0);
    }

    // ---- Convergence closure

    @Test
    void everyAdmittedClosureRoundTripsThroughTheWire() throws Exception {
        for (double closure : new double[] {V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, 1.0e-6, 5.0e-4,
                V3ConvergenceEvidence.MAXIMUM_CLOSURE_TOLERANCE}) {
            for (Optional<V3ColumnDutyLedger> ledger : List.of(Optional.<V3ColumnDutyLedger>empty(),
                    Optional.of(closureLedger()))) {
                V3ColumnDisplayResult result = certificate(ledger, closure, Optional.empty());
                assertEquals(closure, result.closureTolerance(), 0.0);

                V3ColumnDisplayResult decoded = wireRoundTrip(result);
                assertEquals(result, decoded);
                assertEquals(closure, decoded.closureTolerance(), 0.0);
                assertEquals(ledger, decoded.dutyLedger());
            }
        }
    }

    @Test
    void bothTransportsRejectAClosureOutsideTheAdmittedRange() throws Exception {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeResult(buffer, certificate(Optional.empty(), 1.0e-3, Optional.empty()));
            // The closure double precedes the absent-ledger and absent-hydraulics flags of a bare certificate.
            buffer.setDouble(buffer.writerIndex() - 10, Double.NaN);
            assertInstanceOf(DecoderException.class, failure(() -> readResult(buffer)));
        } finally {
            buffer.release();
        }

        RegistryFriendlyByteBuf tooLoose = buffer();
        try {
            writeResult(tooLoose, certificate(Optional.empty(), 1.0e-3, Optional.empty()));
            tooLoose.setDouble(tooLoose.writerIndex() - 10, 1.0e-2);
            assertInstanceOf(DecoderException.class, failure(() -> readResult(tooLoose)));
        } finally {
            tooLoose.release();
        }
    }

    // ---- The component choices a Save design names

    /** Every condenser kind and every member count crosses whole, the water trim's shells among them (COOLER_MODEL_PLAN.md section 6). */
    @Test
    void theComponentChoicesRoundTripWithTheTrimCount() {
        var one = com.wormzjl.icheme.science.equipment.ColumnComponents.Units.ONE;
        for (var condenser : com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser.values())
            for (int trim = 1; trim <= com.wormzjl.icheme.science.equipment.ColumnComponents.MAX_UNITS; trim++)
                for(var type:com.wormzjl.icheme.science.equipment.ExchangerType.CATALOG) {
                var choices = new com.wormzjl.icheme.science.equipment.ColumnComponents.Choices(condenser,
                        com.wormzjl.icheme.science.equipment.ColumnComponents.Pump.IN_LINE,
                        List.of(com.wormzjl.icheme.science.equipment.ColumnComponents.Pump.STANDARD), one.withCondenser(2).withCooler(0, 3).withTrim(trim),
                        new com.wormzjl.icheme.science.equipment.ColumnComponents.Exchangers(type,type,List.of(type)));
                RegistryFriendlyByteBuf buffer = buffer();
                try {
                    ColumnWire.writeChoices(buffer, choices);
                    assertEquals(choices, ColumnWire.readChoices(buffer));
                    assertEquals(0, buffer.readableBytes());
                } finally {
                    buffer.release();
                }
            }
    }

    // ---- Column diameter and tray hydraulics

    @Test
    void bothModesOfTheInputRoundTripThroughTheWire() throws Exception {
        for (double diameter : new double[] {V3ColumnInput.PRESCRIBED_DROP_DIAMETER, 0.5,
                V3ColumnInput.DEFAULT_COLUMN_DIAMETER_METRES, V3ColumnInput.MAX_COLUMN_DIAMETER_METRES}) {
            V3ColumnInput input = diameterInput(diameter);

            V3ColumnInput decoded = wireRoundTrip(input);
            assertEquals(diameter, decoded.columnDiameterMetres());
            assertEquals(input, decoded);
        }
    }

    @Test
    void theHydraulicsSummaryRoundTripsAndIsAbsentFromAPrescribedDropResult() throws Exception {
        for (Optional<V3TrayHydraulicsSummary> hydraulics : List.of(
                Optional.<V3TrayHydraulicsSummary>empty(), Optional.of(summary()))) {
            V3ColumnDisplayResult result = certificate(Optional.empty(), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE,
                    hydraulics);

            V3ColumnDisplayResult decoded = wireRoundTrip(result);
            assertEquals(hydraulics, decoded.trayHydraulics());
            assertEquals(result, decoded);
        }
    }

    @Test
    void anImplausibleSummaryIsRejectedByTheContractRatherThanDisplayed() {
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                0.0, 22_000.0, 565.0, 0.69, 33, true, 0.004, 292.4, 344.1),
                "a summary needs the diameter it was computed at");
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, Double.NaN, 565.0, 0.69, 33, true, 0.004, 292.4, 344.1));
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, 22_000.0, 565.0, 0.69, 0, true, 0.004, 292.4, 344.1), "the worst tray is a tray number");
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, 22_000.0, 565.0, 0.69, V3ColumnInput.MAX_STAGE_COUNT + 1, true, 0.004, 292.4, 344.1));
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, 22_000.0, 565.0, 0.69, 33, true, 0.004, -1.0, 344.1), "drop terms are nonnegative");
        assertTrue(summary().floods() == (summary().maximumFloodFraction() > 1.0));
        assertFalse(summary().vaporLimited(), "the pinned worst tray is froth dominated");
    }

    // ---- Fixtures

    /** Overwrites one trailing list-count byte of an otherwise well-formed input and requires a decoder rejection. */
    private static void assertForgedCountIsRejected(int countsFromEnd, int forgedCount, String field) throws Exception {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeInput(buffer, sideDrawInput(0));
            int index = buffer.writerIndex() - countsFromEnd;
            assertEquals(0, buffer.getByte(index), () -> "expected the empty " + field + " count at this offset");
            buffer.setByte(index, forgedCount);
            Throwable cause = failure(() -> readInput(buffer));
            assertInstanceOf(DecoderException.class, cause,
                    () -> field + " count " + forgedCount + " escaped the guard as " + cause);
        } finally {
            buffer.release();
        }
    }

    private static Throwable readWireFailure(RegistryFriendlyByteBuf buffer) {
        return failure(() -> readInput(buffer));
    }

    private static V3ColumnInput sideDrawInput(int count) {
        List<V3SideDrawSpec> draws = List.of(new V3SideDrawSpec(1, 2), new V3SideDrawSpec(2, 3), new V3SideDrawSpec(4, 5));
        return codecInput(4.17, draws.subList(0, count), List.of());
    }

    private static V3ColumnInput pumparoundInput(int pumparoundCount, double refluxRatio) {
        return codecInput(refluxRatio, List.of(new V3SideDrawSpec(2, 3)), PUMPAROUNDS.subList(0, pumparoundCount));
    }

    private static V3ColumnInput codecInput(double refluxRatio, List<V3SideDrawSpec> draws,
            List<V3PumparoundSpec> pumparounds) {
        return new V3ColumnInput(1, "test:binary", "test:codec", new V3ComponentBasis(List.of("a", "b")),
                new double[] {40, 60}, 450, 4, 2, 250_000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(refluxRatio),
                new V3ColumnSpecification.ReboilerDuty(8_000_000)), draws, List.of(), pumparounds,
                V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private static V3ColumnInput diameterInput(double diameter) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured",
                "test:binary", new V3ComponentBasis(List.of("component-a", "component-b")),
                new double[] {30.0, 60.0}, 400.0, 3, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(330.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(1.0e6)),
                List.of(), List.of(), List.of(), diameter);
    }

    private static V3ColumnDutyLedger ledger(int stageDutyCount) {
        List<V3ColumnDutyLedger.StageDuty> duties = new ArrayList<>(stageDutyCount);
        double total = 0.0;
        for (int tray = 1; tray <= stageDutyCount; tray++) {
            double duty = -100_000.0 * tray;
            total += duty;
            duties.add(new V3ColumnDutyLedger.StageDuty(tray, duty));
        }
        return new V3ColumnDutyLedger(-5_500_000.0, 8_000_000.0, total, duties, 1_250_000.0, 320_000.0);
    }

    private static V3ColumnDutyLedger closureLedger() {
        return new V3ColumnDutyLedger(-5_500_000.0, 8_000_000.0, -3_400_000.0,
                List.of(new V3ColumnDutyLedger.StageDuty(3, -3_400_000.0)), 1_250_000.0, 320_000.0);
    }

    private static V3TrayHydraulicsSummary summary() {
        return new V3TrayHydraulicsSummary(8.0, 22_034.5, 564.9, 0.687, 33, true, 0.0041, 292.4, 344.1);
    }

    /** A one-stream certificate with the given ledger, closure and tray hydraulics. */
    private static V3ColumnDisplayResult certificate(Optional<V3ColumnDutyLedger> ledger, double closure,
            Optional<V3TrayHydraulicsSummary> hydraulics) {
        List<V3ColumnStreamProperties> streams = List.of(new V3ColumnStreamProperties(
                "distillate", "Distillate", "LIQUID", 10, 1, 400, 250_000, 0,
                List.of(new V3ColumnStreamProperties.ComponentFraction("a", 1, 1))));
        return new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 2, 0, 6, streams, ledger,
                closure, hydraulics, Optional.empty());
    }
}
