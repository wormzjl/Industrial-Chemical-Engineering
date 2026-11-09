package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowsheetRun.Outcome;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The solver on stub units with known answers (batch 2026-11-02-process-simulator, W1): one component, a constant heat
 * capacity, 0.1 kg/mol. The real units close against the real thermo in their own gates.
 */
class FlowsheetSolverTest {
    private static final double CP = 100, DATUM = 298.15, MOLAR_MASS = .1, BAR = 1e5;
    private static final List<String> AXIS = List.of("X");
    private static final StreamThermo THERMO = (pressure, enthalpy, amounts, guess, solids) -> stream(DATUM + (amounts[0] == 0 ? 0 : enthalpy / (CP * amounts[0])), pressure, amounts[0]);

    private static FlowStream stream(double temperature, double pressure, double amount) {
        return new FlowStream(temperature, pressure, new double[]{amount}, amount * CP * (temperature - DATUM), amount * MOLAR_MASS, null);
    }

    private static FlowsheetRun run(List<Calculation> calculations) {
        return com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.solve(calculations, AXIS, THERMO, FlowsheetSolver.Settings.DEFAULT, () -> {});
    }

    private static Calculation feed(String out, double amount, double temperature) {
        return Calculation.of("feed " + out, List.of(), List.of(out), (board, report) -> {
            var s = stream(temperature, BAR, amount);
            board.stream(out, s);
            report.enters(s.mass(), s.enthalpy());
        });
    }

    private static Calculation mix(String a, String b, String out) {
        return Calculation.of("mix " + out, List.of(a, b), List.of(out), (board, report) -> {
            var x = board.stream(a);
            var y = board.stream(b);
            double amount = x.total() + y.total();
            board.stream(out, stream(DATUM + (x.enthalpy() + y.enthalpy()) / (CP * amount), BAR, amount));
        });
    }

    private static Calculation split(String in, double share, String back, String on) {
        return Calculation.of("split " + in, List.of(in), List.of(back, on), (board, report) -> {
            var s = board.stream(in);
            board.stream(back, stream(s.temperature(), s.pressure(), s.total() * share));
            board.stream(on, stream(s.temperature(), s.pressure(), s.total() * (1 - share)));
        });
    }

    private static Calculation heat(String in, String out, double watts) {
        return Calculation.of("heat " + out, List.of(in), List.of(out), (board, report) -> {
            var s = board.stream(in);
            if (s.empty()) {
                board.stream(out, s);
                return;
            }
            board.stream(out, stream(s.temperature() + watts / (CP * s.total()), s.pressure(), s.total()));
            report.duty(watts);
        });
    }

    /** Passes one stream through {@code law} on its amount: the loop's return, for a loop with a chosen slope. */
    private static Calculation curve(String in, String out, DoubleUnaryOperator law) {
        return Calculation.of("curve " + out, List.of(in), List.of(out), (board, report) -> board.stream(out, stream(DATUM, BAR, law.applyAsDouble(board.stream(in).total()))));
    }

    private static Calculation sink(String in, String out) {
        return Calculation.of("sink " + out, List.of(in), List.of(out), (board, report) -> board.stream(out, board.stream(in)));
    }

    /** Fresh 10 mol/s, a share of what leaves the mixer back to it: S = 10 / (1 - share), and 10 leaves. */
    @Test void aRecycleClosesOnItsAnswer() {
        double share = .3;
        var run = run(List.of(feed("fresh", 10, 300), mix("fresh", "back", "mixed"), split("mixed", share, "back", "product")));
        assertEquals(Status.STEADY, run.status());
        assertEquals(10 / (1 - share), run.streams().get("mixed").total(), 1e-3);
        assertEquals(10, run.streams().get("product").total(), 1e-3);
        assertEquals(1, run.loops().size());
        assertEquals(1, run.loops().getFirst().tears().size());
        assertTrue(run.loops().getFirst().closed());
        assertEquals(0, run.massGap(), 1e-3 * run.massIn());
    }

    /** Wegstein reads the slope and lands on a linear loop's answer where plain passes only approach it. */
    @Test void wegsteinCutsThePassesOfASlowRecycle() {
        double share = .6;
        var calculations = List.of(feed("fresh", 10, 300), mix("fresh", "back", "mixed"), split("mixed", share, "back", "product"));
        var run = run(calculations);
        assertEquals(Status.STEADY, run.status());
        assertEquals(25, run.streams().get("mixed").total(), 1e-3);
        var plain = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.solve(calculations, AXIS, THERMO, new FlowsheetSolver.Settings(1e-4, .01, 30, 0, 0), () -> {});
        assertEquals(Status.STEADY, plain.status());
        assertTrue(run.loops().getFirst().passes() < plain.loops().getFirst().passes(), run.loops() + " against " + plain.loops());
    }

    /**
     * A furnace on fixed duty after an exchanger with the bottoms: cold in 300 K, effectiveness e, furnace rise F, a column that
     * takes D out of the stream. The bottoms settle at T = 300 + (F - D) / (1 - e). The exchanger reads both sides, so it is one
     * calculation, and the loop's gain is e.
     */
    @Test void aHeatLoopClosesOnItsAnswer() {
        double effectiveness = .5, amount = 10, furnace = 5000, drop = 10, rise = furnace / (CP * amount);
        var exchanger = Calculation.of("exchanger", List.of("cold", "bottoms"), List.of("preheated", "rundown"), (board, report) -> {
            var cold = board.stream("cold");
            var hot = board.stream("bottoms");
            double q = hot.empty() ? 0 : effectiveness * CP * amount * (hot.temperature() - cold.temperature());
            board.stream("preheated", stream(cold.temperature() + q / (CP * amount), BAR, amount));
            board.stream("rundown", hot.empty() ? hot : stream(hot.temperature() - q / (CP * amount), BAR, amount));
        });
        var column = FlowsheetRuns.block(Calculation.of("column", List.of("fired"), List.of("bottoms"), (board, report) -> {
            var s = board.stream("fired");
            board.stream("bottoms", stream(s.temperature() - drop, BAR, s.total()));
            report.duty(-drop * CP * s.total());
        }));
        var run = run(List.of(feed("cold", amount, 300), exchanger, heat("preheated", "fired", furnace), column));
        assertEquals(Status.STEADY, run.status());
        assertEquals(300 + (rise - drop) / (1 - effectiveness), run.streams().get("bottoms").temperature(), .02);
        assertEquals(List.of("bottoms"), run.loops().getFirst().tears(), "a loop tears at a block's outlet");
        assertEquals(0, run.heatGap(), 1e-3 * run.duties());
    }

    /** All of the stream back: 10 mol/s more every pass, for ever. The run says so at once, and does not spend 30 passes. */
    @Test void aLoopWithNoWayOutSaysNotSteady() {
        var run = run(List.of(feed("fresh", 10, 300), mix("fresh", "back", "mixed"), sink("mixed", "back")));
        assertEquals(Status.NOT_STEADY, run.status());
        assertFalse(run.loops().getFirst().closed());
        assertTrue(run.loops().getFirst().passes() <= 4, run.loops().toString());
        assertTrue(run.log().stream().anyMatch(n -> n.key().equals("loop.no_steady_state") && n.subject().equals(run.loops().getFirst().tears().getFirst()) && n.arguments().getFirst().equals("X")), run.log().toString());
        assertTrue(run.streams().containsKey("mixed"), "the last pass stays on show");
    }

    /** A step that repeats only through a slope of 1 still stops, when acceleration keeps moving the guess. */
    @Test void aConstantStepStopsUnderWegsteinToo() {
        var run = run(List.of(curve("loop", "out", x -> x + 3), sink("out", "loop")));
        assertEquals(Status.NOT_STEADY, run.status());
        assertTrue(run.loops().getFirst().passes() <= 4, run.loops().toString());
    }

    /** A loop that swings for ever runs out of passes, keeps the last, and names its tear. */
    @Test void aLoopThatSwingsRunsOutOfPasses() {
        var run = run(List.of(curve("loop", "out", x -> 20 - x), sink("out", "loop")));
        assertEquals(Status.NOT_STEADY, run.status());
        assertEquals(FlowsheetSolver.Settings.DEFAULT.maxPasses(), run.loops().getFirst().passes());
        assertTrue(run.log().stream().anyMatch(n -> n.key().equals("loop.not_closed") && n.arguments().get(1).equals("X")), run.log().toString());
    }

    /**
     * The law is 0.95 x + 1 up to 6, then flat, and it fails above 6.5. Wegstein reads the 0.95 on pass 3 and jumps to 6.7. The
     * unit fails, the pass falls back, and plain passes go on to the answer, 6.
     */
    @Test void aTrialStepThatFailsAUnitFallsBack() {
        var run = run(List.of(curve("loop", "out", x -> {
            if (x > 6.5) throw new FlowsheetFailure("test.too_far");
            return Math.min(.95 * x + 1, 6);
        }), sink("out", "loop")));
        assertEquals(Status.STEADY, run.status(), run.log().toString());
        assertEquals(6, run.streams().get("loop").total(), 1e-3);
        assertTrue(run.log().stream().anyMatch(n -> n.key().equals("loop.fallback")), run.log().toString());
    }

    /** With no trial step to blame, a failure in a loop is the unit's. */
    @Test void aPlainPassThatFailsStopsTheLoop() {
        var run = run(List.of(feed("fresh", 10, 300), mix("fresh", "back", "mixed"), curve("mixed", "out", x -> {
            if (x > 0) throw new FlowsheetFailure("test.refused", "mixed");
            return x;
        }), split("out", .5, "back", "product")));
        assertEquals(Status.FAILED, run.status());
        assertEquals(Outcome.FAILED, run.outcomes().get("curve out"));
        assertEquals("test.refused", run.log().getLast().key());
        assertEquals(List.of("mixed"), run.log().getLast().arguments());
        assertEquals(Outcome.SOLVED, run.outcomes().get("feed fresh"));
    }

    /** A unit that cannot take the first pass's empty tear fails that pass, and the log says the loop starts from a first estimate. */
    @Test void aFirstPassThatFailsAsksForAFirstEstimate() {
        var run = run(List.of(feed("fresh", 10, 300), mix("fresh", "back", "mixed"), curve("mixed", "out", x -> {
            if (x == 0) throw new FlowsheetFailure("test.refused", "mixed");
            return x;
        }), split("out", .5, "back", "product")));
        assertEquals(Status.FAILED, run.status());
        assertEquals(1, run.loops().getFirst().passes());
        var tear = run.loops().getFirst().tears().getFirst();
        assertTrue(run.log().stream().anyMatch(n -> n.key().equals("loop.first_estimate") && n.subject().equals(tear) && n.arguments().equals(List.of("curve out"))), run.log().toString());
    }

    /** A loop that loses pressure each pass, with no pump to give it back, never closes, and the Log says it was the pressure. */
    @Test void aLoopWithNoPumpNamesThePressure() {
        var run = run(List.of(Calculation.of("drop", List.of("loop"), List.of("out"), (board, report) -> {
            var s = board.stream("loop");
            board.stream("out", stream(DATUM, s.pressure() - 1000, 5));
        }), sink("out", "loop")));
        assertEquals(Status.NOT_STEADY, run.status());
        assertTrue(run.log().stream().anyMatch(n -> n.key().equals("loop.not_closed") && n.arguments().get(1).equals("pressure")), run.log().toString());
    }

    /** A failed unit stops what lies after it, the rest of the sheet solves, and the status names the first thing to fix. */
    @Test void aFailedUnitStopsOnlyWhatLiesAfterIt() {
        var run = run(List.of(
                feed("a", 10, 300), curve("a", "b", x -> {
                    throw new FlowsheetFailure("test.refused");
                }), heat("b", "c", 100),
                feed("x", 10, 300), heat("x", "y", 100)));
        assertEquals(Status.FAILED, run.status());
        assertEquals(Outcome.FAILED, run.outcomes().get("curve b"));
        assertEquals(Outcome.NOT_SOLVED, run.outcomes().get("heat c"));
        assertEquals(Outcome.SOLVED, run.outcomes().get("heat y"));
        assertFalse(run.streams().containsKey("c"));
        assertEquals(300 + 100 / (CP * 10), run.streams().get("y").temperature(), 1e-9);
    }

    /** A failure upstream of a loop leaves the loop unsolved, and it never starts. */
    @Test void aLoopAfterAFailureDoesNotRun() {
        int[] solves = {0};
        var run = run(List.of(curve("x", "a", v -> v), feed("x", 1, 300), Calculation.of("broken", List.of("a"), List.of("in"), (board, report) -> {
            throw new FlowsheetFailure("test.refused");
        }), mix("in", "back", "mixed"), Calculation.of("count", List.of("mixed"), List.of("back"), (board, report) -> {
            solves[0]++;
            board.stream("back", board.stream("mixed"));
        })));
        assertEquals(Status.FAILED, run.status());
        assertEquals(0, solves[0]);
        assertEquals(Outcome.NOT_SOLVED, run.outcomes().get("count"));
    }

    /** Stop keeps what was solved, and solves nothing more. */
    @Test void stopKeepsWhatWasSolved() {
        int[] seen = {0};
        var run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.solve(List.of(feed("a", 10, 300), heat("a", "b", 100), heat("b", "c", 100)), AXIS, THERMO, FlowsheetSolver.Settings.DEFAULT, () -> {
            if (++seen[0] == 5) throw new CancellationException();
        });
        assertEquals(Status.STOPPED, run.status());
        assertTrue(run.streams().containsKey("b"));
        assertFalse(run.streams().containsKey("c"));
    }

    /** Stop is an IllegalStateException, so a catch inside a law can eat it. It stays set, so the law's answer, or the failure it made of it, does not count. */
    @Test void aStopALawEatsStillStops() {
        boolean[] stop = {false};
        Runnable checkpoint = () -> {
            if (stop[0]) throw new CancellationException();
        };
        Calculation.Law eats = (board, report) -> {
            stop[0] = true;
            try {
                checkpoint.run();
            } catch (IllegalStateException eaten) {
                board.stream("b", board.stream("a"));
            }
        };
        var solved = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.solve(List.of(feed("a", 10, 300), Calculation.of("eats", List.of("a"), List.of("b"), eats)), AXIS, THERMO, FlowsheetSolver.Settings.DEFAULT, checkpoint);
        assertEquals(Status.STOPPED, solved.status(), "not STEADY, though the last calculation returned");
        assertEquals(Outcome.NOT_SOLVED, solved.outcomes().get("eats"));
        stop[0] = false;
        Calculation.Law fails = (board, report) -> {
            stop[0] = true;
            try {
                checkpoint.run();
            } catch (IllegalStateException eaten) {
                throw new FlowsheetFailure("column.failed", "eaten");
            }
        };
        var failed = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.solve(List.of(feed("a", 10, 300), Calculation.of("fails", List.of("a"), List.of("b"), fails)), AXIS, THERMO, FlowsheetSolver.Settings.DEFAULT, checkpoint);
        assertEquals(Status.STOPPED, failed.status(), "not FAILED");
        assertTrue(failed.log().isEmpty(), "and no unit is blamed for it");
    }

    /** A loop's passes and what ran in them, up to Stop; the order holds the plan, ratings left out. */
    @Test void thePassRecordFollowsTheLoop() {
        var unit = Calculation.of("step", List.of("back"), List.of("next"), (board, report) -> {
            report.duty(5);
            report.reading("duty", 5);
            board.stream("next", board.stream("back"));
        });
        var back = Calculation.of("mix", List.of("a", "next"), List.of("back"), (board, report) -> board.stream("back", board.stream("a")));
        var run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.solve(List.of(feed("a", 10, 300), back, unit), AXIS, THERMO, FlowsheetSolver.Settings.DEFAULT, () -> {
        });
        assertEquals(1, run.loops().size());
        assertEquals(run.loops().getFirst().passes(), run.passes().size(), "one record a pass");
        for (int i = 0; i < run.passes().size(); i++) {
            var pass = run.passes().get(i);
            assertEquals(i + 1, pass.pass());
            assertEquals(run.loops().getFirst().tears(), pass.tears());
            assertEquals(2, pass.steps().size());
            assertTrue(pass.seconds() >= 0);
            assertEquals(run.loops().getFirst().tears().getFirst(), pass.tear());
        }
        assertEquals(2, run.order().size(), "the source, then the loop");
        assertEquals(run.loops().getFirst().tears(), run.order().get(1).tears());
        assertEquals(5.0, run.passes().getFirst().steps().stream().filter(s -> s.calculation().equals("step")).findFirst().orElseThrow().duty());
    }

    /** The same stream through a block's two calculations is no loop: a heat side reads the door's duty, not the block's solve. */
    @Test void aBlocksHeatSideIsNoLoop() {
        double duty = 2000;
        var block = FlowsheetRuns.block(Calculation.of("T-101", List.of("feed"), List.of("bottoms"), (board, report) -> board.stream("bottoms", board.stream("feed"))));
        var side = heat("cold", "feed", duty);
        var run = run(List.of(feed("cold", 10, 300), side, block));
        assertEquals(Status.STEADY, run.status());
        assertTrue(run.loops().isEmpty());
        assertEquals(300 + duty / (CP * 10), run.streams().get("bottoms").temperature(), 1e-9);
    }

    /** A rating reads a stream without using it up, and its failure stops nothing. */
    @Test void aCheckUsesNothingUp() {
        var check = new Calculation("rating", "E", List.of("a"), List.of(), List.of(), List.of(), Calculation.Kind.CHECK, (board, report) -> {
            throw new FlowsheetFailure("test.refused");
        });
        var run = run(List.of(feed("a", 10, 300), check));
        assertEquals(Status.STEADY, run.status(), run.log().toString());
        assertEquals(run.massIn(), run.massOut(), 1e-12, "the stream still leaves the sheet");
        assertEquals("test.refused", run.log().getLast().key());
        assertEquals(Note.Severity.WARNING, run.log().getLast().severity());
    }

    /** A link hands a duty from one side to the other, and the run takes the sides in that order. */
    @Test void aLinkOrdersAnExchangersSides() {
        double outlet = 310;
        var order = new ArrayList<String>();
        var cold = new Calculation("E/cold", "E", List.of("c"), List.of("c out"), List.of("E duty"), List.of(), Calculation.Kind.STEP, (board, report) -> {
            order.add("cold");
            var s = board.stream("c");
            board.stream("c out", stream(s.temperature() + board.link("E duty") / (CP * s.total()), s.pressure(), s.total()));
        });
        var hot = new Calculation("E/hot", "E", List.of("h"), List.of("h out"), List.of(), List.of("E duty"), Calculation.Kind.STEP, (board, report) -> {
            order.add("hot");
            var s = board.stream("h");
            board.link("E duty", CP * s.total() * (s.temperature() - outlet));
            board.stream("h out", stream(outlet, s.pressure(), s.total()));
        });
        var run = run(List.of(feed("c", 10, 300), feed("h", 10, 400), cold, hot));
        assertEquals(Status.STEADY, run.status());
        assertEquals(List.of("hot", "cold"), order);
        assertEquals(390, run.streams().get("c out").temperature(), 1e-9);
        assertEquals(run.heatIn(), run.heatOut(), 1e-6);
    }
}
