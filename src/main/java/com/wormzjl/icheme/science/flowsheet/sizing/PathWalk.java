package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.flowsheet.unit.CoolerUnit;
import com.wormzjl.icheme.science.flowsheet.unit.FiredHeaterUnit;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing.*;
import static com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing.Sizing.*;

/** One sizing's pressure paths ({@link SheetSizing}; {@code SIZING_PLAN.md} section 3.6): from each stream that starts one, through the sizes it holds. */
final class PathWalk {
    private final Sizing sizing;
    private final Flowsheet sheet;
    private final FlowsheetRun run;
    private final FlowThermo thermo;
    private final DesignBasis basis;
    private final Map<String, SizedLine> lines;
    private final Map<String, Flowsheet.Stream> streams;
    private final Map<String, Flowsheet.Unit> byId;
    private final Map<String, Map<String, String>> wires;
    private final Map<String, Block> blocks;

    PathWalk(Sizing sizing) {
        this.sizing = sizing;
        sheet = sizing.sheet;
        run = sizing.run;
        thermo = sizing.thermo;
        basis = sizing.basis;
        lines = sizing.lines;
        streams = sizing.streams;
        byId = sizing.byId;
        wires = sizing.wires;
        blocks = sizing.blocks;
    }

    /** Every path, from each stream that starts one, through the sizes {@code sized} holds. */
    List<PressurePath> paths(Map<String, SizedUnit> sized) {
        var paths = new ArrayList<PressurePath>();
        for (var stream : sheet.streams()) {
            thermo.checkpoint().run();
            var flow = run.streams().get(stream.id());
            String start = flow == null || flow.empty() ? null : start(stream);
            if (start == null) continue;
            var level = stream.from() == null || stream.from().port().equals(ColumnUnit.OVERHEAD) ? null : sizing.level(stream.from());
            double head = level == null ? 0 : flow.mass() / flow.state().volume() * PassiveStepSolver.GRAVITY * level / 1e5;
            walk(new Walk(start, flow.pressure() / 1e5, head), stream.id(), new ArrayList<>(), new java.util.HashSet<>(), sized, paths);
        }
        return paths;
    }

    /** Where a path starts. */
    record Walk(String start, double from, double head) {
    }

    /** What a path on {@code stream} starts from, null where the stream only passes on from a unit's side. */
    @Nullable String start(Flowsheet.Stream stream) {
        var from = stream.from();
        if (from == null) return stream.id();
        var unit = byId.get(from.unit());
        if (unit == null) return null;
        String port = from.port();
        return switch (unit.type()) {
            case "pump", "compressor", "steam_turbine", "drum", "storage_tank" -> unit.id();
            case "kettle" -> port.equals("tube_out") ? null : unit.id();
            case COLUMN -> port.equals(ColumnUnit.CONDENSER_OUT) || port.equals(ColumnUnit.REBOILER_OUT) || port.startsWith(ColumnUnit.PUMPAROUND) ? null : unit.id();
            case "valve" -> set(unit) ? unit.id() : null;
            default -> null;
        };
    }

    /** Whether a valve holds its outlet, which ends a path and starts the next; one set to a drop is a step. */
    private static boolean set(Flowsheet.Unit valve) {
        return valve.params().text("target", "outlet").equals("outlet");
    }

    /** Walks on along {@code streamId}, its line one step, to the end of each path it reaches. */
    void walk(Walk walk, String streamId, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
        if (!seen.add(streamId)) return;
        var line = lines.get(streamId);
        steps.add(new PressurePath.Step(streamId, PressurePath.Step.LINE, line == null ? 0 : drop(line), 0));
        var stream = streams.get(streamId);
        var to = stream == null ? null : stream.to();
        var unit = to == null ? null : byId.get(to.unit());
        if (unit == null) {
            end(walk, streamId, null, steps, null, paths);
            return;
        }
        String id = unit.id(), port = to.port();
        var params = unit.params();
        switch (unit.type()) {
            case "exchanger", "kettle" -> {
                if (port.equals("tube_in")) through(walk, id, id, "tube_drop", params.number("tube_drop", 0), streamId, "tube_out", steps, seen, sized, paths);
                else if (port.equals("shell_in")) through(walk, id, id, "shell_drop", params.number("shell_drop", 0), streamId, "shell_out", steps, seen, sized, paths);
                else end(walk, id, null, steps, null, paths);
            }
            case "cooler" -> through(walk, id, id, CoolerUnit.DROP, params.number(CoolerUnit.DROP, 0), streamId, "out", steps, seen, sized, paths);
            case "fired_heater" -> {
                switch (port) {
                    case "in" -> through(walk, id, id, FiredHeaterUnit.COILS, params.number(FiredHeaterUnit.RADIANT_DROP, 0) + params.number(FiredHeaterUnit.CONVECTION_DROP, 0), streamId, "out", steps, seen, sized, paths);
                    case "convection_in" -> through(walk, id, id, FiredHeaterUnit.CONVECTION_DROP, params.number(FiredHeaterUnit.CONVECTION_DROP, 0), streamId, "convection_out", steps, seen, sized, paths);
                    default -> through(walk, id, id, FiredHeaterUnit.RADIANT_DROP, params.number(FiredHeaterUnit.RADIANT_DROP, 0), streamId, "radiant_out", steps, seen, sized, paths);
                }
            }
            case COLUMN -> column(walk, unit, port, streamId, steps, seen, sized, paths);
            case "valve" -> {
                if (set(unit)) end(walk, id, params.number("pressure") / 1e5, steps, id, paths);
                else {
                    double typed = params.number("drop", 0);
                    steps.add(new PressurePath.Step(id, "drop", typed / 1e5, typed / 1e5));
                    onward(walk, id, "out", steps, seen, sized, paths);
                }
            }
            case "mixer", "filter" -> onward(walk, id, "out", steps, seen, sized, paths);
            case "splitter" -> {
                for (var outlet : wires.getOrDefault(id, Map.of()).entrySet())
                    if (!outlet.getKey().equals("in")) walk(walk, outlet.getValue(), new ArrayList<>(steps), new java.util.HashSet<>(seen), sized, paths);
            }
            case "drum" -> end(walk, id, params.text("pressure_mode", "inlet").equals("set") ? params.number("pressure") / 1e5 : null, steps, null, paths);
            case "storage_tank" -> {
                var report = run.reports().get(id);
                var held = report == null ? null : report.readings().get("pressure");
                end(walk, id, held == null ? null : held / 1e5, steps, null, paths);
            }
            default -> end(walk, id, null, steps, null, paths);
        }
    }

    /** A column's inlet: a heat pair's sheet side passes through its member's tubes; a feed or a steam row ends on its tray. */
    void column(Walk walk, Flowsheet.Unit unit, String port, String streamId, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
        String id = unit.id();
        var door = ColumnDoor.of(unit.params());
        if (port.equals(ColumnUnit.CONDENSER_IN)) {
            // An air-then-water condenser's water passes through its trim's tubes.
            var member = member(id, door.condenser() == ColumnComponents.Condenser.AIR_THEN_WATER ? TRIM : "condenser");
            through(walk, id, member, ColumnUnit.CONDENSER_DROP, door.condenserDrop(), streamId, ColumnUnit.CONDENSER_OUT, steps, seen, sized, paths);
            return;
        }
        if (port.equals(ColumnUnit.REBOILER_IN)) {
            through(walk, id, member(id, "reboiler"), ColumnUnit.REBOILER_DROP, door.reboilerDrop(), streamId, ColumnUnit.REBOILER_OUT, steps, seen, sized, paths);
            return;
        }
        for (var pumparound : door.pumparounds()) {
            if (port.equals(ColumnUnit.pumparoundIn(pumparound))) {
                through(walk, id, member(id, PumparoundMember.name(pumparound.id(), PumparoundMember.COOLER)), ColumnUnit.pumparoundDrop(pumparound), pumparound.drop(), streamId, ColumnUnit.pumparoundOut(pumparound), steps, seen, sized, paths);
                return;
            }
        }
        var block = blocks.get(id);
        Integer stage = port.equals(ColumnUnit.FEED) ? (Integer) door.feedStage() : null;
        for (var row : door.steam()) if (port.equals(ColumnUnit.steamPort(row))) stage = row.stage();
        Double tray = block == null || stage == null ? null : block.inspection().nodes().get(stage).pressurePascal() / 1e5;
        end(walk, id, tray, steps, null, paths);
    }

    /**
     * A unit's side: one step, at design flow by {@code item}'s size where it has one the constructor builds, else the door's
     * {@code typed} (Pa); then on along what leaves by {@code outlet}.
     */
    void through(Walk walk, String unit, String item, String passage, double typed, String streamId, String outlet, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
        var in = run.streams().get(streamId);
        var out = sizing.stream(unit, outlet);
        var size = sized.get(item) == null ? null : sized.get(item).size();
        var law = in == null || in.empty() ? null : SizedPassages.drop(size, passage, in, out.empty() ? null : out, basis.designFlow(), thermo);
        steps.add(new PressurePath.Step(item, passage, (law != null ? law : typed) / 1e5, typed / 1e5));
        onward(walk, unit, outlet, steps, seen, sized, paths);
    }

    /** On along what leaves {@code unit} by {@code port}: a free outlet's own stream ends the path there. */
    void onward(Walk walk, String unit, String port, List<PressurePath.Step> steps, java.util.Set<String> seen, Map<String, SizedUnit> sized, List<PressurePath> paths) {
        var wired = wires.getOrDefault(unit, Map.of()).get(port);
        if (wired == null) end(walk, unit + ":" + port, null, steps, null, paths);
        else walk(walk, wired, steps, seen, sized, paths);
    }

    /**
     * The path's end: at {@code set} bar where the sheet sets it; else what the run delivers there on the doors' drops, which
     * reads no head, so a delivered path has its head and the doors' drops to spend.
     */
    private static void end(Walk walk, String end, @Nullable Double set, List<PressurePath.Step> steps, @Nullable String taker, List<PressurePath> paths) {
        double typed = 0;
        for (var step : steps) typed += step.typed();
        paths.add(new PressurePath(walk.start(), walk.from(), walk.head(), end, set != null ? set : walk.from() - typed, set != null, taker, steps));
    }
}
