package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Sequential-modular steady state over a graph of {@link Calculation}s (batch 2026-11-02-process-simulator, plan section 3.1).
 * Parts of the graph run in order. A loop tears the fewest streams and repeats: a plain pass first, then bounded Wegstein with
 * one factor per tear. A trial step that fails a unit falls back to plain passes for the rest of that loop. A tear amount that
 * rises by the same step on three passes has slope 1 and cannot close, so the loop stops there and says so. It reads nothing
 * from the world and writes nothing to it. Minecraft-free.
 */
public final class FlowsheetSolver {
    /** Share of a step that counts as the same step on the next pass. */
    private static final double SAME_STEP = .01;
    private static final int SAME_STEPS = 3;

    /**
     * A run's numbers, all in the flowsheet file.
     *
     * @param flowTolerance        a tear closes when no amount moves by more than this share of its total flow, and no pressure by more than this share of itself
     * @param temperatureTolerance and its temperature by no more than this (K)
     * @param maxPasses            passes a loop may take
     * @param lowestFactor         Wegstein's q is held in here
     * @param highestFactor        0 is a plain step
     */
    public record Settings(double flowTolerance, double temperatureTolerance, int maxPasses, double lowestFactor, double highestFactor) {
        public static final Settings DEFAULT = new Settings(1e-4, .01, 30, -5, 0);

        public Settings {
            if (!(flowTolerance > 0) || !(temperatureTolerance > 0) || maxPasses < 1 || !(lowestFactor <= highestFactor) || !(highestFactor <= 0)) throw new IllegalArgumentException("Invalid solver settings");
        }
    }

    private final List<Calculation> calculations;
    private final List<String> axis;
    private final int components;
    private final StreamThermo thermo;
    private final Settings settings;
    private final Runnable checkpoint;
    private final Map<String, FlowStream> estimates;
    private final Board board = new Board();
    private final Map<String, FlowsheetRun.Outcome> outcomes = new LinkedHashMap<>();
    private final Map<String, Report> reports = new HashMap<>();
    private final List<FlowsheetRun.Loop> loops = new ArrayList<>();
    private final List<Note> log = new ArrayList<>();
    private final Set<String> blocked = new HashSet<>();
    private boolean failed, steady = true;
    /** What the tears carried at the end (kg/s). A loop that closed to its tolerance still holds its last step, so mass may miss by that much. */
    private double tornMass;

    private FlowsheetSolver(List<Calculation> calculations, List<String> axis, StreamThermo thermo, Settings settings, Runnable checkpoint, Map<String, FlowStream> estimates) {
        this.estimates = estimates;
        this.calculations = calculations;
        this.axis = axis;
        this.components = axis.size();
        this.thermo = thermo;
        this.settings = settings;
        this.checkpoint = checkpoint;
    }

    /**
     * Runs {@code calculations} on the component axis {@code axis}, water last. {@code checkpoint} runs at each calculation's
     * start, and may throw a {@link CancellationException} to stop the run, which keeps what it has. A tear starts from no flow,
     * or from its stream in {@code estimates}.
     */
    public static FlowsheetRun run(List<Calculation> calculations, List<String> axis, StreamThermo thermo, Settings settings, Runnable checkpoint, Map<String, FlowStream> estimates) {
        return new FlowsheetSolver(calculations, List.copyOf(axis), thermo, settings, checkpoint, estimates).run();
    }

    public static FlowsheetRun run(List<Calculation> calculations, List<String> axis, StreamThermo thermo, Settings settings, Runnable checkpoint) {
        return run(calculations, axis, thermo, settings, checkpoint, Map.of());
    }

    private FlowsheetRun run() {
        for (var c : calculations) outcomes.put(c.id(), FlowsheetRun.Outcome.NOT_SOLVED);
        boolean stopped = false;
        try {
            for (var group : FlowGraph.plan(calculations)) {
                if (group.tears().isEmpty()) once(group.order().getFirst());
                else loop(group);
            }
        } catch (CancellationException stop) {
            stopped = true;
        }
        return result(stopped);
    }

    private FlowsheetRun result(boolean stopped) {
        double massIn = 0, massOut = 0, heatIn = 0, heatOut = 0, duties = 0;
        var read = new HashSet<String>();
        for (var c : calculations) if (c.kind() != Calculation.Kind.CHECK) read.addAll(c.reads());
        for (var c : calculations) {
            var report = reports.get(c.id());
            if (report == null) continue;
            massIn += report.massIn();
            massOut += report.massOut();
            heatIn += report.enthalpyIn() + report.duty();
            heatOut += report.enthalpyOut();
            duties += Math.abs(report.duty());
            for (var wire : c.writes()) {
                if (read.contains(wire) || !board.has(wire)) continue;
                massOut += board.stream(wire).mass();
                heatOut += board.stream(wire).enthalpy();
            }
        }
        var status = stopped ? FlowsheetRun.Status.STOPPED : failed ? FlowsheetRun.Status.FAILED : FlowsheetRun.Status.STEADY;
        if (status == FlowsheetRun.Status.STEADY && !(Math.abs(massIn - massOut) <= settings.flowTolerance() * (Math.max(massIn, massOut) + tornMass))) {
            log.add(Note.error("sheet", "run.mass_gap", number(massIn), number(massOut)));
            steady = false;
        }
        if (status == FlowsheetRun.Status.STEADY && !steady) status = FlowsheetRun.Status.NOT_STEADY;
        return new FlowsheetRun(status, board.streams(), outcomes, reports, loops, log, massIn, massOut, heatIn, heatOut, duties);
    }

    private void once(Calculation c) {
        if (blockedBy(c, Set.of())) {
            block(c);
            return;
        }
        var failure = attempt(c);
        if (failure == null) return;
        if (c.kind() == Calculation.Kind.CHECK) {
            log.add(new Note(Note.Severity.WARNING, failure.subject(), failure.key(), failure.arguments()));
            return;
        }
        fail(c, failure);
        block(c);
    }

    private boolean blockedBy(Calculation c, Set<String> inside) {
        for (var wire : c.reads()) if (blocked.contains(wire) && !inside.contains(wire)) return true;
        for (var wire : c.needs()) if (blocked.contains(wire) && !inside.contains(wire)) return true;
        return false;
    }

    private void block(Calculation c) {
        blocked.addAll(c.writes());
        blocked.addAll(c.gives());
    }

    private void fail(Calculation c, Note failure) {
        failed = true;
        outcomes.put(c.id(), FlowsheetRun.Outcome.FAILED);
        log.add(failure);
    }

    /** Runs {@code c}; the note says why it failed, null if it did not. */
    private @Nullable Note attempt(Calculation c) {
        checkpoint.run();
        var report = new Report();
        try {
            c.law().solve(board, report);
        } catch (FlowsheetFailure refusal) {
            return new Note(Note.Severity.ERROR, c.unit(), refusal.key(), refusal.arguments());
        } catch (CancellationException stop) {
            throw stop;
        } catch (RuntimeException failure) {
            return Note.error(c.unit(), "unit.failed", String.valueOf(failure.getMessage()));
        }
        reports.put(c.id(), report);
        outcomes.put(c.id(), FlowsheetRun.Outcome.SOLVED);
        return null;
    }

    private void loop(FlowGraph.Group group) {
        var inside = new HashSet<String>();
        for (var c : group.order()) {
            inside.addAll(c.writes());
            inside.addAll(c.gives());
        }
        if (group.order().stream().anyMatch(c -> blockedBy(c, inside))) {
            group.order().forEach(this::block);
            return;
        }
        var tears = group.tears().stream().map(id -> new Tear(id, estimates.getOrDefault(id, FlowStream.empty(components)))).toList();
        boolean accelerate = true;
        for (int pass = 1; pass <= settings.maxPasses(); pass++) {
            for (var tear : tears) board.stream(tear.id, tear.guess);
            Calculation failing = null;
            Note failure = null;
            for (var c : group.order()) {
                failure = attempt(c);
                if (failure != null) {
                    failing = c;
                    break;
                }
            }
            if (failure != null) {
                if (tears.stream().noneMatch(t -> t.trial)) {
                    fail(failing, failure);
                    if (pass == 1) log.add(Note.info(group.tears().getFirst(), "loop.first_estimate", failing.unit()));
                    group.order().forEach(this::block);
                    loops.add(new FlowsheetRun.Loop(group.tears(), pass, false));
                    return;
                }
                tears.forEach(Tear::fallBack);
                accelerate = false;
                log.add(Note.info(group.tears().getFirst(), "loop.fallback", failing.unit(), String.valueOf(pass)));
                continue;
            }
            boolean closed = true;
            for (var tear : tears) {
                tear.result = board.stream(tear.id);
                closed &= closed(tear.guess, tear.result);
            }
            if (closed) {
                end(group, tears, pass, true);
                return;
            }
            for (var tear : tears) {
                var step = tear.remember();
                if (step != null) {
                    log.add(Note.error(tear.id, "loop.no_steady_state", axis.get(step), number(tear.result.amountsView()[step] - tear.guess.amountsView()[step])));
                    end(group, tears, pass, false);
                    return;
                }
            }
            for (var tear : tears) tear.advance(accelerate);
        }
        var worst = tears.stream().max(Comparator.comparingDouble(t -> move(t.lastGuess, t.lastResult).amount)).orElseThrow();
        var move = move(worst.lastGuess, worst.lastResult);
        log.add(Note.error(worst.id, "loop.not_closed", String.valueOf(settings.maxPasses()), move.variable, number(move.size)));
        end(group, tears, settings.maxPasses(), false);
    }

    private void end(FlowGraph.Group group, List<Tear> tears, int passes, boolean closed) {
        steady &= closed;
        for (var tear : tears) tornMass += tear.result.mass();
        loops.add(new FlowsheetRun.Loop(group.tears(), passes, closed));
    }

    /** What moved most in a pass: its name, how far, and that in tolerances. */
    private record Move(String variable, double size, double amount) {
    }

    private Move move(FlowStream guess, FlowStream result) {
        double flow = settings.flowTolerance() * Math.max(Math.max(guess.total(), result.total()), Double.MIN_NORMAL);
        var most = new Move("temperature", result.temperature() - guess.temperature(), Math.abs(result.temperature() - guess.temperature()) / settings.temperatureTolerance());
        if (guess.empty() || result.empty()) most = new Move("temperature", 0, 0);
        for (int i = 0; i < components; i++) {
            double size = result.amountsView()[i] - guess.amountsView()[i], amount = Math.abs(size) / flow;
            if (amount > most.amount) most = new Move(axis.get(i), size, amount);
        }
        double pressure = (result.pressure() - guess.pressure()) / (settings.flowTolerance() * Math.max(result.pressure(), guess.pressure()));
        return guess.empty() || result.empty() || Math.abs(pressure) <= most.amount ? most : new Move("pressure", result.pressure() - guess.pressure(), Math.abs(pressure));
    }

    /** Whether the stream a pass made is the stream it started from, to the tolerances. */
    private boolean closed(FlowStream guess, FlowStream result) {
        if (guess.empty() || result.empty()) return guess.empty() && result.empty();
        double flow = settings.flowTolerance() * Math.max(guess.total(), result.total());
        for (int i = 0; i < components; i++) if (Math.abs(result.amountsView()[i] - guess.amountsView()[i]) > flow) return false;
        return Math.abs(result.temperature() - guess.temperature()) <= settings.temperatureTolerance()
                && Math.abs(result.pressure() - guess.pressure()) <= settings.flowTolerance() * Math.max(result.pressure(), guess.pressure());
    }

    /** Wegstein's q from the variable of the tear that moved most, in tolerances; 0 without a slope to read. */
    private double factor(FlowStream lastGuess, FlowStream lastResult, FlowStream guess, FlowStream result) {
        double most = 0, across = 0, along = 0;
        double flow = settings.flowTolerance() * Math.max(Math.max(guess.total(), result.total()), Double.MIN_NORMAL);
        for (int i = 0; i < components; i++) {
            double moved = Math.abs(result.amountsView()[i] - guess.amountsView()[i]) / flow, width = guess.amountsView()[i] - lastGuess.amountsView()[i];
            if (moved > most && width != 0) {
                most = moved;
                across = width;
                along = result.amountsView()[i] - lastResult.amountsView()[i];
            }
        }
        // An empty stream's temperature means nothing, so a slope needs two with flow.
        if (!lastGuess.empty()) {
            double moved = Math.abs(result.temperature() - guess.temperature()) / settings.temperatureTolerance(), width = guess.temperature() - lastGuess.temperature();
            if (moved > most && width != 0) {
                across = width;
                along = result.temperature() - lastResult.temperature();
            }
        }
        if (across == 0) return 0;
        double slope = along / across, factor = slope / (slope - 1);
        return Double.isNaN(factor) ? 0 : Math.clamp(factor, settings.lowestFactor(), settings.highestFactor());
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6g", value);
    }

    /** A loop's cut: what the next pass starts from, and what it remembers of the last two. */
    private final class Tear {
        final String id;
        final ArrayDeque<double[]> steps = new ArrayDeque<>();
        FlowStream guess, result, lastGuess, lastResult, plain;
        boolean trial;

        Tear(String id, FlowStream first) {
            this.id = id;
            guess = plain = first;
        }

        void fallBack() {
            guess = plain;
            trial = false;
        }

        /** Keeps this pass's step; the component whose step has stayed the same for {@link #SAME_STEPS} passes, or null. */
        @Nullable Integer remember() {
            double[] step = new double[components];
            for (int i = 0; i < components; i++) step[i] = result.amountsView()[i] - guess.amountsView()[i];
            steps.addLast(step);
            if (steps.size() > SAME_STEPS) steps.removeFirst();
            if (steps.size() < SAME_STEPS) return null;
            double floor = settings.flowTolerance() * Math.max(guess.total(), result.total());
            for (int i = 0; i < components; i++) {
                if (step[i] <= floor) continue;
                boolean same = true;
                for (double[] earlier : steps) same &= Math.abs(earlier[i] - step[i]) <= SAME_STEP * step[i];
                if (same) return i;
            }
            return null;
        }

        void advance(boolean accelerate) {
            var next = accelerate && lastGuess != null ? accelerated() : result;
            lastGuess = guess;
            lastResult = result;
            plain = result;
            trial = next != result;
            guess = next;
        }

        /** {@code q x + (1 - q) y} on the amounts and the molar enthalpy, at the pass's pressure: the plain step if q is 0 or no state answers. */
        private FlowStream accelerated() {
            double q = factor(lastGuess, lastResult, guess, result);
            if (q == 0 || guess.empty()) return result;
            double[] amounts = new double[components];
            double total = 0;
            for (int i = 0; i < components; i++) {
                amounts[i] = Math.max(0, q * guess.amountsView()[i] + (1 - q) * result.amountsView()[i]);
                total += amounts[i];
            }
            double molar = q * guess.molarEnthalpy() + (1 - q) * result.molarEnthalpy();
            try {
                return thermo.atEnthalpy(result.pressure(), molar * total, amounts, result.temperature(), result.solids());
            } catch (CancellationException stop) {
                throw stop;
            } catch (RuntimeException noState) {
                return result;
            }
        }
    }
}
