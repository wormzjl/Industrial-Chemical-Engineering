package com.wormzjl.icheme.network;

import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnDutyLedger;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ControlledQuantity;
import com.wormzjl.icheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * A column's input, component choices and display result on the wire (batch 2026-11-02-process-simulator): what a constructor's template, a Save design and a run's stored
 * results send. Each decoder bounds what it reads. Wire formats need no compatibility work.
 */
public final class ColumnWire {
    // Version 15 supports two-terminal-stage pots and total-tray presentation; 17 carries the saved column design;
    // 18 its operating point.
    public static final int WIRE_SCHEMA_VERSION = 18;

    // Both peers must use the same engine-scheduled inspection protocol. 12: the dataset revision is the package's data
    // identity, every record's declared revision (up to MaterialCatalog.MAX_IDENTITY characters). 13: Save design.
    // 14: a saved design's operating point.

    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_COMPONENT_IDENTIFIER_LENGTH = 64;
    private static final int MAX_DIAGNOSTICS = 32;
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;
    private static final int MAX_REVISION_LENGTH = 128;
    private static final int MAX_STREAM_LABEL_LENGTH = 48;
    private static final int MAX_PHASE_LENGTH = 16;
    /** A rejection's reason, which names a design's refused nozzle, carries a diagnostic's length. */
    // Smallest encoding every declared element of a bounded list can occupy: a VarInt is at least one byte and a
    // double is exactly eight. A declared count that cannot fit in the remaining bytes is a forged length, not a
    // short read, so the decoder rejects it before allocating the list.
    private static final int SIDE_DRAW_MINIMUM_BYTES = 9;
    private static final int STEAM_FEED_MINIMUM_BYTES = 17;
    private static final int PUMPAROUND_MINIMUM_BYTES = 11;

    private ColumnWire() {}

    /** The components a Save design names. */
    static void writeChoices(RegistryFriendlyByteBuf buffer, ColumnComponents.Choices choices) {
        buffer.writeEnum(choices.condenser());
        buffer.writeEnum(choices.refluxPump());
        writePumps(buffer, choices.pumparoundPumps());
    }

    static ColumnComponents.Choices readChoices(RegistryFriendlyByteBuf buffer) {
        var condenser = readKind(buffer, ColumnComponents.Condenser.class);
        var reflux = readKind(buffer, ColumnComponents.Pump.class);
        return new ColumnComponents.Choices(condenser, reflux, readPumps(buffer));
    }

    private static void writePumps(RegistryFriendlyByteBuf buffer, List<ColumnComponents.Pump> pumps) {
        buffer.writeVarInt(pumps.size());
        for (var pump : pumps) buffer.writeEnum(pump);
    }

    private static List<ColumnComponents.Pump> readPumps(RegistryFriendlyByteBuf buffer) {
        int count = readCount(buffer, ColumnTrays.MAX_PUMPAROUNDS, "pumparound pump", 1);
        var pumps = new ArrayList<ColumnComponents.Pump>(count);
        for (int i = 0; i < count; i++) pumps.add(readKind(buffer, ColumnComponents.Pump.class));
        return pumps;
    }

    private static <E extends Enum<E>> E readKind(RegistryFriendlyByteBuf buffer, Class<E> type) {
        int ordinal = buffer.readVarInt();
        E[] kinds = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= kinds.length) throw new DecoderException("Unknown V3 " + type.getSimpleName());
        return kinds[ordinal];
    }

    /** A saved design: the trays, the diameter and the components. */
    static void writeInput(RegistryFriendlyByteBuf buffer, V3ColumnInput input) {
        buffer.writeVarInt(input.schemaVersion());
        buffer.writeUtf(input.packageId(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(input.assayId(), MAX_IDENTIFIER_LENGTH);
        buffer.writeVarInt(input.componentBasis().componentCount());
        for (String componentId : input.componentBasis().componentIds()) {
            buffer.writeUtf(componentId, MAX_COMPONENT_IDENTIFIER_LENGTH);
        }
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        buffer.writeVarInt(flows.length);
        for (double flow : flows) buffer.writeDouble(flow);
        buffer.writeDouble(input.feedTemperatureKelvin());
        buffer.writeVarInt(input.stageCount());
        buffer.writeVarInt(input.feedStageNumber());
        buffer.writeDouble(input.topPressurePascal());
        buffer.writeDouble(input.stagePressureDropPascal());
        buffer.writeDouble(input.columnDiameterMetres());
        buffer.writeVarInt(input.specifications().size());
        for (V3ColumnSpecification specification : input.specifications()) {
            buffer.writeUtf(specification.controlledQuantity().name(), MAX_COMPONENT_IDENTIFIER_LENGTH);
            buffer.writeDouble(specificationValue(specification));
        }
        buffer.writeVarInt(input.sideDraws().size());
        for (V3SideDrawSpec draw : input.sideDraws()) {
            buffer.writeVarInt(draw.trayNumber());
            buffer.writeDouble(draw.molarFlowMolPerSecond());
        }
        buffer.writeVarInt(input.steamFeeds().size());
        for (V3SteamFeedSpec steam : input.steamFeeds()) {
            buffer.writeVarInt(steam.stageNumber());
            buffer.writeDouble(steam.molarFlowMolPerSecond());
            buffer.writeDouble(steam.temperatureKelvin());
        }
        buffer.writeVarInt(input.pumparounds().size());
        for (V3PumparoundSpec pumparound : input.pumparounds()) {
            buffer.writeVarInt(pumparound.returnTray());
            buffer.writeVarInt(pumparound.drawTray());
            // The signed duty crosses the wire exactly as authored; a cooling pumparound stays negative.
            buffer.writeDouble(pumparound.dutyWatts());
            buffer.writeVarInt(pumparound.split().ordinal());
        }
    }

    static V3ColumnInput readInput(RegistryFriendlyByteBuf buffer) {
        try {
            int schemaVersion = buffer.readVarInt();
            if (schemaVersion != V3ColumnInput.SCHEMA_VERSION) throw new DecoderException("Unsupported V3 input schema");
            String packageId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String assayId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            int componentCount = readCount(buffer, V3ComponentBasis.MAX_COMPONENTS, "component");
            if (componentCount < 1) throw new DecoderException("V3 component axis is empty");
            List<String> componentIds = new ArrayList<>(componentCount);
            for (int index = 0; index < componentCount; index++) {
                componentIds.add(buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH));
            }
            int flowCount = readCount(buffer, V3ComponentBasis.MAX_COMPONENTS, "feed flow");
            if (flowCount != componentCount) throw new DecoderException("V3 feed-flow axis disagrees with components");
            double[] flows = new double[flowCount];
            for (int index = 0; index < flowCount; index++) flows[index] = finite(buffer.readDouble(), "feed flow");
            double feedTemperature = finite(buffer.readDouble(), "feed temperature");
            int stages = buffer.readVarInt();
            int feedStage = buffer.readVarInt();
            double topPressure = finite(buffer.readDouble(), "top pressure");
            double pressureDrop = finite(buffer.readDouble(), "pressure drop");
            double columnDiameter = finite(buffer.readDouble(), "column diameter");
            int specificationCount = readCount(buffer, 3, "specification");
            if (specificationCount != 3) throw new DecoderException("V3 specification count must be three");
            List<V3ColumnSpecification> specifications = new ArrayList<>(specificationCount);
            for (int index = 0; index < specificationCount; index++) {
                ControlledQuantity quantity = ControlledQuantity.valueOf(
                        buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH));
                specifications.add(specification(quantity, finite(buffer.readDouble(), "specification")));
            }
            int drawCount = readCount(buffer, V3ColumnInput.MAX_SIDE_DRAWS, "side draw", SIDE_DRAW_MINIMUM_BYTES);
            List<V3SideDrawSpec> draws = new ArrayList<>(drawCount);
            for (int index = 0; index < drawCount; index++) {
                draws.add(new V3SideDrawSpec(buffer.readVarInt(), finite(buffer.readDouble(), "side draw rate")));
            }
            int steamCount = readCount(buffer, V3ColumnInput.MAX_STEAM_FEEDS, "steam feed", STEAM_FEED_MINIMUM_BYTES);
            List<V3SteamFeedSpec> steam = new ArrayList<>(steamCount);
            for (int index = 0; index < steamCount; index++) {
                steam.add(new V3SteamFeedSpec(buffer.readVarInt(), finite(buffer.readDouble(), "steam rate"),
                        finite(buffer.readDouble(), "steam temperature")));
            }
            int pumparoundCount =
                    readCount(buffer, V3ColumnInput.MAX_PUMPAROUNDS, "pumparound", PUMPAROUND_MINIMUM_BYTES);
            List<V3PumparoundSpec> pumparounds = new ArrayList<>(pumparoundCount);
            for (int index = 0; index < pumparoundCount; index++) {
                int returnTray = buffer.readVarInt();
                int drawTray = buffer.readVarInt();
                double duty = finite(buffer.readDouble(), "pumparound duty");
                pumparounds.add(new V3PumparoundSpec(returnTray, drawTray, duty, splitOrdinal(buffer.readVarInt())));
            }
            return new V3ColumnInput(schemaVersion, packageId, assayId, new V3ComponentBasis(componentIds), flows,
                    feedTemperature, stages, feedStage, topPressure, pressureDrop, specifications, draws, steam,
                    pumparounds, columnDiameter);
        } catch (DecoderException invalidWire) {
            throw invalidWire;
        } catch (IllegalArgumentException | NullPointerException | IndexOutOfBoundsException invalid) {
            // A packet that runs short mid-decode is malformed client input, not a server fault: report it on the
            // decoder contract so the connection is dropped rather than the read escaping as a raw runtime failure.
            throw new DecoderException("Invalid V3 input", invalid);
        }
    }

    static void writeDisplayResult(RegistryFriendlyByteBuf buffer, V3ColumnDisplayResult result) {
        buffer.writeByteArray(V3InspectionCodec.encode(result.inspection()));
        buffer.writeUtf(result.inputDigest(), 64);
        buffer.writeUtf(result.formulationRevision(), MAX_REVISION_LENGTH);
        buffer.writeUtf(result.assumptionsRevision(), MAX_REVISION_LENGTH);
        buffer.writeUtf(result.datasetRevision(), com.wormzjl.icheme.science.material.MaterialCatalog.MAX_IDENTITY);
        buffer.writeVarInt(result.newtonIterations());
        buffer.writeDouble(result.maximumScaledResidual());
        buffer.writeVarInt(result.acceptanceCheckCount());
        buffer.writeVarInt(result.streams().size());
        for (V3ColumnStreamProperties stream : result.streams()) {
            buffer.writeUtf(stream.streamId(), MAX_COMPONENT_IDENTIFIER_LENGTH);
            buffer.writeUtf(stream.displayName(), MAX_STREAM_LABEL_LENGTH);
            buffer.writeUtf(stream.phase(), MAX_PHASE_LENGTH);
            buffer.writeDouble(stream.molarFlowMolPerSecond());
            buffer.writeDouble(stream.massFlowKgPerSecond());
            buffer.writeDouble(stream.temperatureKelvin());
            buffer.writeDouble(stream.pressurePascal());
            buffer.writeDouble(stream.vaporMoleFraction());
            buffer.writeVarInt(stream.moleFractions().size());
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                buffer.writeUtf(fraction.componentId(), MAX_COMPONENT_IDENTIFIER_LENGTH);
                buffer.writeDouble(fraction.moleFraction());
                buffer.writeDouble(fraction.massFraction());
            }
        }
        // The optional duty ledger and tray hydraulics stay the trailing blocks of this record.
        buffer.writeDouble(result.closureTolerance());
        buffer.writeBoolean(result.dutyLedger().isPresent());
        result.dutyLedger().ifPresent(ledger -> writeDutyLedger(buffer, ledger));
        buffer.writeBoolean(result.trayHydraulics().isPresent());
        result.trayHydraulics().ifPresent(hydraulics -> {
            buffer.writeDouble(hydraulics.columnDiameterMetres());
            buffer.writeDouble(hydraulics.totalPressureDropPascal());
            buffer.writeDouble(hydraulics.meanTrayPressureDropPascal());
            buffer.writeDouble(hydraulics.maximumFloodFraction());
            buffer.writeVarInt(hydraulics.maximumFloodTray());
            buffer.writeBoolean(hydraulics.correctionApplied());
            buffer.writeDouble(hydraulics.residualMismatchFraction());
            buffer.writeDouble(hydraulics.worstTrayDryPascal());
            buffer.writeDouble(hydraulics.worstTrayLiquidPascal());
        });
    }

    /** Signed duties, positive into the column, cross the wire exactly as calculated. */
    private static void writeDutyLedger(RegistryFriendlyByteBuf buffer, V3ColumnDutyLedger ledger) {
        buffer.writeDouble(ledger.condenserWatts());
        buffer.writeDouble(ledger.reboilerWatts());
        buffer.writeDouble(ledger.stageHeatTotalWatts());
        buffer.writeDouble(ledger.feedEnthalpyWatts());
        buffer.writeDouble(ledger.steamEnthalpyWatts());
        buffer.writeVarInt(ledger.stageDuties().size());
        for (V3ColumnDutyLedger.StageDuty duty : ledger.stageDuties()) {
            buffer.writeVarInt(duty.trayNumber());
            buffer.writeDouble(duty.dutyWatts());
        }
    }

    private static V3ColumnDutyLedger readDutyLedger(RegistryFriendlyByteBuf buffer) {
        double condenser = finite(buffer.readDouble(), "condenser duty");
        double reboiler = finite(buffer.readDouble(), "reboiler duty");
        double stageHeatTotal = finite(buffer.readDouble(), "stage heat total");
        double feedEnthalpy = finite(buffer.readDouble(), "feed enthalpy");
        double steamEnthalpy = finite(buffer.readDouble(), "steam enthalpy");
        int dutyCount = readCount(buffer, V3ColumnDutyLedger.MAX_STAGE_DUTIES, "stage duty");
        List<V3ColumnDutyLedger.StageDuty> duties = new ArrayList<>(dutyCount);
        for (int index = 0; index < dutyCount; index++) {
            duties.add(new V3ColumnDutyLedger.StageDuty(
                    buffer.readVarInt(), finite(buffer.readDouble(), "stage duty")));
        }
        return new V3ColumnDutyLedger(condenser, reboiler, stageHeatTotal, duties, feedEnthalpy, steamEnthalpy);
    }

    private static V3PumparoundSpec.Split splitOrdinal(int ordinal) {
        V3PumparoundSpec.Split[] splits = V3PumparoundSpec.Split.values();
        if (ordinal < 0 || ordinal >= splits.length) throw new DecoderException("Unknown V3 pumparound split");
        return splits[ordinal];
    }

    static V3ColumnDisplayResult readDisplayResult(RegistryFriendlyByteBuf buffer) {
        try {
            var inspection = V3InspectionCodec.decode(buffer.readByteArray(V3InspectionCodec.MAX_BYTES));
            String digest = buffer.readUtf(64);
            String formulation = buffer.readUtf(MAX_REVISION_LENGTH);
            String assumptions = buffer.readUtf(MAX_REVISION_LENGTH);
            String dataset = buffer.readUtf(com.wormzjl.icheme.science.material.MaterialCatalog.MAX_IDENTITY);
            int iterations = buffer.readVarInt();
            double residual = finite(buffer.readDouble(), "maximum residual");
            int acceptanceChecks = buffer.readVarInt();
            int streamCount = readCount(buffer, V3ColumnStreamProperties.MAX_STREAMS, "stream");
            List<V3ColumnStreamProperties> streams = new ArrayList<>(streamCount);
            for (int streamIndex = 0; streamIndex < streamCount; streamIndex++) {
                String streamId = buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH);
                String displayName = buffer.readUtf(MAX_STREAM_LABEL_LENGTH);
                String phase = buffer.readUtf(MAX_PHASE_LENGTH);
                double flow = finite(buffer.readDouble(), "stream flow");
                double massFlow = finite(buffer.readDouble(), "stream mass flow");
                double temperature = finite(buffer.readDouble(), "stream temperature");
                double pressure = finite(buffer.readDouble(), "stream pressure");
                double vaporMoleFraction = finite(buffer.readDouble(), "stream vapor fraction");
                int fractionCount = readCount(buffer, V3ColumnStreamProperties.MAX_COMPONENTS, "stream component");
                if (fractionCount < 1) throw new DecoderException("V3 stream composition is empty");
                List<V3ColumnStreamProperties.ComponentFraction> fractions = new ArrayList<>(fractionCount);
                for (int fractionIndex = 0; fractionIndex < fractionCount; fractionIndex++) {
                    fractions.add(new V3ColumnStreamProperties.ComponentFraction(
                            buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH),
                            finite(buffer.readDouble(), "stream component mole fraction"),
                            finite(buffer.readDouble(), "stream component mass fraction")));
                }
                streams.add(new V3ColumnStreamProperties(
                        streamId, displayName, phase, flow, massFlow, temperature, pressure, vaporMoleFraction, fractions));
            }
            double closure = finite(buffer.readDouble(), "closure tolerance");
            Optional<V3ColumnDutyLedger> ledger = buffer.readBoolean()
                    ? Optional.of(readDutyLedger(buffer)) : Optional.empty();
            Optional<com.wormzjl.icheme.science.column.v3.V3TrayHydraulicsSummary> hydraulics =
                    buffer.readBoolean()
                            ? Optional.of(new com.wormzjl.icheme.science.column.v3.V3TrayHydraulicsSummary(
                                    finite(buffer.readDouble(), "column diameter"),
                                    finite(buffer.readDouble(), "total tray pressure drop"),
                                    finite(buffer.readDouble(), "mean tray pressure drop"),
                                    finite(buffer.readDouble(), "maximum flood fraction"),
                                    buffer.readVarInt(), buffer.readBoolean(),
                                    finite(buffer.readDouble(), "hydraulic residual mismatch"),
                                    finite(buffer.readDouble(), "worst tray dry drop"),
                                    finite(buffer.readDouble(), "worst tray liquid drop")))
                            : Optional.empty();
            return new V3ColumnDisplayResult(
                    digest, formulation, assumptions, dataset, iterations, residual, acceptanceChecks, streams, ledger,
                    closure, hydraulics, inspection);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException("Invalid V3 display result", invalid);
        }
    }

    private static V3ColumnSpecification specification(ControlledQuantity quantity, double value) {
        return switch (quantity) {
            case CONDENSER_OUTLET_TEMPERATURE -> new V3ColumnSpecification.CondenserOutletTemperature(value);
            case ORGANIC_REFLUX_RATIO -> new V3ColumnSpecification.OrganicRefluxRatio(value);
            case REBOILER_DUTY -> new V3ColumnSpecification.ReboilerDuty(value);
        };
    }

    private static double specificationValue(V3ColumnSpecification specification) {
        return switch (specification) {
            case V3ColumnSpecification.CondenserOutletTemperature temperature -> temperature.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio reflux -> reflux.ratio();
            case V3ColumnSpecification.ReboilerDuty duty -> duty.watts();
        };
    }

    private static void writeWireSchema(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(WIRE_SCHEMA_VERSION);
    }

    private static void requireWireSchema(RegistryFriendlyByteBuf buffer) {
        if (buffer.readVarInt() != WIRE_SCHEMA_VERSION) throw new DecoderException("Unsupported V3 wire schema");
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum, String description) {
        return readCount(buffer, maximum, description, 0);
    }

    /**
     * Reads a declared list length and rejects it before the caller allocates. The count must fit its bound and the
     * buffer must still hold the minimum bytes the declared elements need, so a forged length on a short packet is
     * reported as a {@link DecoderException} instead of letting an {@link IndexOutOfBoundsException} escape mid-decode.
     */
    private static int readCount(
            RegistryFriendlyByteBuf buffer, int maximum, String description, int minimumBytesPerElement) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Invalid V3 " + description + " count");
        if (buffer.readableBytes() < (long) count * minimumBytesPerElement) {
            throw new DecoderException("Truncated V3 " + description + " list");
        }
        return count;
    }

    private static long nonNegative(long value, String description) {
        if (value < 0L) throw new DecoderException("Negative V3 " + description);
        return value;
    }

    private static double finite(double value, String description) {
        if (!Double.isFinite(value)) throw new DecoderException("Non-finite V3 " + description);
        return value;
    }

}
