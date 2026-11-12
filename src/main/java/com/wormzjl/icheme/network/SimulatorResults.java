package com.wormzjl.icheme.network;

import com.wormzjl.icheme.client.gui.flowsheet.SimulatorHost;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * A run's results, on the block beside its sheet (batch 2026-11-02-process-simulator, plan sections 3.4 and 3.9). Results are state: this format changes freely. A mismatch of any
 * kind, a format number, a damaged byte, a state the thermo refuses, drops them and the next run rebuilds them, so {@link #read} answers null and never throws. Only clients read
 * them: the server stores the bytes it is sent, and they come from another player, so every count is bounded. They carry the revision tokens of what they ran on, which
 * {@code RevisionNotice.dropsResults} weighs. A stream's phases go by amounts, and a reader builds their properties again ({@code FluidThermodynamics.state}, no flash);
 * a column's warm start is no part of this, so the first run after opening starts cold.
 *
 * <p>When a body cannot hold all of it, {@link #fitted} gives way in order: a sheet is a fact and never gives way, each column's door display goes first, since
 * every reading stands without it, and then the results whole.
 */
public final class SimulatorResults {
    /** 2: a report carries its time-series readings ({@link Report.Series}, batch 2026-11-09-batch-distiller, W7); 3: a column
     * display's tray hydraulics carry their total dry drop (batch 2026-11-10-column-failure-modes, W3.3). */
    public static final int FORMAT = 3;
    private static final int MAX_LIST = 4096, MAX_TEXT = 512, MAX_TOKEN = Short.MAX_VALUE, MAX_ARGUMENTS = 16, SAME_SHEET = 0, SAME_AS_RAN = 1, OWN_SHEET = 2;

    private SimulatorResults() {
    }

    /** What can stand on the block beside {@code sheet}: empty if nothing fits. */
    public static byte[] fitted(SimulatorHost.@Nullable Finished finished, Flowsheet sheet, byte[] sheetBytes) {
        if (finished == null) return new byte[0];
        for (boolean displays : new boolean[]{true, false}) {
            var bytes = write(finished, sheet, sheetBytes, displays);
            if (SheetBody.fits(sheetBytes.length, bytes.length)) return bytes;
        }
        return new byte[0];
    }

    /** @param displays whether each column's door display goes too */
    static byte[] write(SimulatorHost.Finished finished, Flowsheet sheet, byte[] sheetBytes, boolean displays) {
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            b.writeVarInt(FORMAT);
            write(b, new TreeMap<>(finished.ranOn()).entrySet().stream().toList(), (o, e) -> {
                o.writeUtf(e.getKey(), MAX_TEXT);
                o.writeUtf(e.getValue(), MAX_TOKEN);
            });
            b.writeDouble(finished.seconds());
            byte[] ran = sheet(b, finished.sheet(), sheet, sheetBytes, null);
            var alone = finished.alone();
            b.writeBoolean(alone != null);
            if (alone != null) {
                b.writeUtf(alone.unit(), MAX_TEXT);
                sheet(b, alone.sheet(), sheet, sheetBytes, ran);
                b.writeEnum(alone.status());
                b.writeBoolean(alone.why() != null);
                if (alone.why() != null) note(b, alone.why());
                b.writeDouble(alone.seconds());
            }
            run(b, finished.run(), displays);
            var bytes = new byte[b.readableBytes()];
            b.readBytes(bytes);
            return bytes;
        } finally {
            b.release();
        }
    }

    /** Results as stored, or null if they are to be dropped. */
    public static SimulatorHost.@Nullable Finished read(byte[] bytes, Flowsheet sheet, FluidThermodynamics model) {
        if (bytes.length == 0) return null;
        var b = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
        try {
            if (b.readVarInt() != FORMAT) return null;
            var ranOn = new TreeMap<String, String>();
            for (int i = count(b); i > 0; i--) ranOn.put(b.readUtf(MAX_TEXT), b.readUtf(MAX_TOKEN));
            double seconds = b.readDouble();
            var ran = sheet(b, sheet, sheet);
            SimulatorHost.Alone alone = null;
            if (b.readBoolean()) {
                String unit = b.readUtf(MAX_TEXT);
                var on = sheet(b, sheet, ran);
                var status = b.readEnum(FlowsheetRun.Status.class);
                var why = b.readBoolean() ? note(b) : null;
                alone = new SimulatorHost.Alone(unit, on, status, why, b.readDouble());
            }
            var run = run(b, model);
            if (b.isReadable()) return null;
            return new SimulatorHost.Finished(ran, run, seconds, alone, ranOn);
        } catch (RuntimeException | StackOverflowError damaged) {
            return null;
        } finally {
            b.release();
        }
    }

    private static int count(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > MAX_LIST || count > b.readableBytes()) throw new DecoderException("Invalid list length");
        return count;
    }

    private static <T> void write(RegistryFriendlyByteBuf b, List<T> items, BiConsumer<RegistryFriendlyByteBuf, T> one) {
        b.writeVarInt(items.size());
        for (var item : items) one.accept(b, item);
    }

    private static <T> List<T> list(RegistryFriendlyByteBuf b, Function<RegistryFriendlyByteBuf, T> one) {
        var items = new ArrayList<T>();
        for (int i = count(b); i > 0; i--) items.add(one.apply(b));
        return items;
    }

    private static void text(RegistryFriendlyByteBuf b, String text) {
        b.writeUtf(text, MAX_TEXT);
    }

    private static String text(RegistryFriendlyByteBuf b) {
        return b.readUtf(MAX_TEXT);
    }

    /** A sheet by what it is the same as, since nearly always it is the one in the body. {@code ran} is the run's sheet's bytes, where this comes after it. Answers this sheet's bytes. */
    private static byte[] sheet(RegistryFriendlyByteBuf b, Flowsheet sheet, Flowsheet body, byte[] bodyBytes, byte @Nullable [] ran) {
        if (sheet == body) {
            b.writeByte(SAME_SHEET);
            return bodyBytes;
        }
        var bytes = FlowsheetFile.write(sheet);
        if (Arrays.equals(bytes, bodyBytes)) b.writeByte(SAME_SHEET);
        else if (ran != null && Arrays.equals(bytes, ran)) b.writeByte(SAME_AS_RAN);
        else {
            b.writeByte(OWN_SHEET);
            b.writeByteArray(bytes);
        }
        return bytes;
    }

    private static Flowsheet sheet(RegistryFriendlyByteBuf b, Flowsheet body, Flowsheet ran) {
        return switch (b.readByte()) {
            case SAME_SHEET -> body;
            case SAME_AS_RAN -> ran;
            case OWN_SHEET -> FlowsheetFile.read(b.readByteArray(SheetBody.MAX_UNPACKED));
            default -> throw new DecoderException("Invalid sheet mark");
        };
    }

    private static void note(RegistryFriendlyByteBuf b, Note note) {
        b.writeEnum(note.severity());
        text(b, note.subject());
        text(b, note.key());
        write(b, note.arguments(), SimulatorResults::text);
    }

    private static Note note(RegistryFriendlyByteBuf b) {
        var severity = b.readEnum(Note.Severity.class);
        String subject = text(b), key = text(b);
        var arguments = list(b, SimulatorResults::text);
        if (arguments.size() > MAX_ARGUMENTS) throw new DecoderException("Invalid note");
        return new Note(severity, subject, key, arguments);
    }

    private static void run(RegistryFriendlyByteBuf b, FlowsheetRun run, boolean displays) {
        b.writeEnum(run.status());
        write(b, new TreeMap<>(run.streams()).entrySet().stream().toList(), (o, e) -> {
            text(o, e.getKey());
            stream(o, e.getValue());
        });
        write(b, new TreeMap<>(run.outcomes()).entrySet().stream().toList(), (o, e) -> {
            text(o, e.getKey());
            o.writeEnum(e.getValue());
        });
        write(b, new TreeMap<>(run.reports()).entrySet().stream().toList(), (o, e) -> {
            text(o, e.getKey());
            report(o, e.getValue(), displays);
        });
        write(b, run.loops(), (o, loop) -> {
            write(o, loop.tears(), SimulatorResults::text);
            o.writeVarInt(loop.passes());
            o.writeBoolean(loop.closed());
        });
        write(b, run.log(), SimulatorResults::note);
        for (double value : new double[]{run.massIn(), run.massOut(), run.heatIn(), run.heatOut(), run.duties()}) b.writeDouble(value);
        write(b, run.order(), (o, stage) -> {
            write(o, stage.calculations(), SimulatorResults::text);
            write(o, stage.tears(), SimulatorResults::text);
        });
        write(b, run.passes(), SimulatorResults::pass);
    }

    private static FlowsheetRun run(RegistryFriendlyByteBuf b, FluidThermodynamics model) {
        var status = b.readEnum(FlowsheetRun.Status.class);
        var streams = new HashMap<String, FlowStream>();
        for (int i = count(b); i > 0; i--) streams.put(text(b), stream(b, model));
        var outcomes = new HashMap<String, FlowsheetRun.Outcome>();
        for (int i = count(b); i > 0; i--) outcomes.put(text(b), b.readEnum(FlowsheetRun.Outcome.class));
        var reports = new HashMap<String, Report>();
        for (int i = count(b); i > 0; i--) reports.put(text(b), report(b));
        var loops = list(b, o -> new FlowsheetRun.Loop(list(o, SimulatorResults::text), o.readVarInt(), o.readBoolean()));
        var log = list(b, SimulatorResults::note);
        double massIn = b.readDouble(), massOut = b.readDouble(), heatIn = b.readDouble(), heatOut = b.readDouble(), duties = b.readDouble();
        var order = list(b, o -> new FlowsheetRun.Stage(list(o, SimulatorResults::text), list(o, SimulatorResults::text)));
        var passes = list(b, SimulatorResults::pass);
        return new FlowsheetRun(status, streams, outcomes, reports, loops, log, massIn, massOut, heatIn, heatOut, duties, order, passes);
    }

    private static void pass(RegistryFriendlyByteBuf b, FlowsheetRun.Pass pass) {
        write(b, pass.tears(), SimulatorResults::text);
        b.writeVarInt(pass.pass());
        write(b, pass.steps(), (o, step) -> {
            text(o, step.calculation());
            text(o, step.unit());
            o.writeDouble(step.duty());
            o.writeBoolean(step.start() != null);
            if (step.start() != null) o.writeEnum(step.start());
            o.writeDouble(step.seconds());
            o.writeBoolean(step.failed());
        });
        b.writeDouble(pass.seconds());
        b.writeBoolean(pass.tear() != null);
        if (pass.tear() != null) text(b, pass.tear());
        b.writeDouble(pass.moved());
        b.writeDouble(pass.temperature());
    }

    private static FlowsheetRun.Pass pass(RegistryFriendlyByteBuf b) {
        var tears = list(b, SimulatorResults::text);
        int pass = b.readVarInt();
        var steps = list(b, o -> new FlowsheetRun.Step(text(o), text(o), o.readDouble(), o.readBoolean() ? o.readEnum(Report.Start.class) : null, o.readDouble(), o.readBoolean()));
        double seconds = b.readDouble();
        String tear = b.readBoolean() ? text(b) : null;
        return new FlowsheetRun.Pass(tears, pass, steps, seconds, tear, b.readDouble(), b.readDouble());
    }

    private static void report(RegistryFriendlyByteBuf b, Report report, boolean displays) {
        write(b, new TreeMap<>(report.readings()).entrySet().stream().toList(), (o, e) -> {
            text(o, e.getKey());
            o.writeDouble(e.getValue());
        });
        write(b, report.notes(), SimulatorResults::note);
        b.writeBoolean(report.start() != null);
        if (report.start() != null) b.writeEnum(report.start());
        for (double value : new double[]{report.duty(), report.massIn(), report.enthalpyIn(), report.massOut(), report.enthalpyOut()}) b.writeDouble(value);
        write(b, report.series().entrySet().stream().toList(), (o, e) -> {
            text(o, e.getKey());
            doubles(o, e.getValue().time());
            write(o, e.getValue().channels().entrySet().stream().toList(), (p, channel) -> {
                text(p, channel.getKey());
                doubles(p, channel.getValue());
            });
        });
        boolean shown = displays && report.display() != null;
        b.writeBoolean(shown);
        if (shown) ColumnWire.writeDisplayResult(b, report.display());
    }

    private static Report report(RegistryFriendlyByteBuf b) {
        var report = new Report();
        for (int i = count(b); i > 0; i--) report.reading(text(b), b.readDouble());
        for (int i = count(b); i > 0; i--) report.note(note(b));
        if (b.readBoolean()) report.start(b.readEnum(Report.Start.class));
        report.duty(b.readDouble());
        double massIn = b.readDouble(), enthalpyIn = b.readDouble(), massOut = b.readDouble(), enthalpyOut = b.readDouble();
        report.enters(massIn, enthalpyIn);
        report.leaves(massOut, enthalpyOut);
        for (int i = count(b); i > 0; i--) {
            String key = text(b);
            double[] time = doubles(b);
            var channels = new java.util.LinkedHashMap<String, double[]>();
            for (int k = count(b); k > 0; k--) channels.put(text(b), doubles(b));
            report.series(key, new Report.Series(time, channels));
        }
        if (b.readBoolean()) report.display(ColumnWire.readDisplayResult(b));
        return report;
    }

    private static void doubles(RegistryFriendlyByteBuf b, double[] values) {
        b.writeVarInt(values.length);
        for (double value : values) b.writeDouble(value);
    }

    private static double[] doubles(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > MAX_LIST || count * 8L > b.readableBytes()) throw new DecoderException("Invalid array length");
        var values = new double[count];
        for (int i = 0; i < count; i++) values[i] = b.readDouble();
        return values;
    }

    private static void stream(RegistryFriendlyByteBuf b, FlowStream stream) {
        for (double value : new double[]{stream.temperature(), stream.pressure(), stream.enthalpy(), stream.mass()}) b.writeDouble(value);
        doubles(b, stream.amountsView());
        var state = stream.state();
        b.writeBoolean(state != null);
        if (state == null) return;
        doubles(b, state.vaporAmountsView());
        doubles(b, state.liquidAmountsView());
        doubles(b, state.aqueousAmountsView());
        write(b, state.solids().populations(), (o, population) -> {
            var material = population.material();
            text(o, material.id());
            text(o, material.revision());
            o.writeDouble(material.density());
            o.writeDouble(material.heatCapacity());
            o.writeBoolean(material.crystal() != null);
            if (material.crystal() != null) {
                text(o, material.crystal().component());
                o.writeDouble(material.crystal().energyOffset());
            }
            text(o, population.size().metres());
            o.writeDouble(population.massKg());
        });
    }

    private static SolidMaterialCatalog.SolidMaterial material(RegistryFriendlyByteBuf b) {
        String id = text(b), revision = text(b);
        double density = b.readDouble(), capacity = b.readDouble();
        var crystal = b.readBoolean() ? new SolidMaterialCatalog.SolidMaterial.Crystal(text(b), b.readDouble()) : null;
        return new SolidMaterialCatalog.SolidMaterial(id, revision, density, capacity, crystal);
    }

    private static FlowStream stream(RegistryFriendlyByteBuf b, FluidThermodynamics model) {
        double temperature = b.readDouble(), pressure = b.readDouble(), enthalpy = b.readDouble(), mass = b.readDouble();
        var amounts = doubles(b);
        if (!b.readBoolean()) return new FlowStream(temperature, pressure, amounts, enthalpy, mass, null);
        double[] vapor = doubles(b), liquid = doubles(b), aqueous = doubles(b);
        var populations = list(b, o -> new SolidInventory.Population(material(o), new ParticleSize(text(o)), o.readDouble()));
        var state = model.adoptingState(temperature, pressure, vapor, liquid, aqueous, null);
        return new FlowStream(temperature, pressure, amounts, enthalpy, mass, populations.isEmpty() ? state : state.withSolids(new SolidInventory(populations)));
    }
}
