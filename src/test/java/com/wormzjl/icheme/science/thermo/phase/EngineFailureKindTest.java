package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.FailureKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The engine's failure kind (batch 2026-11-13-codebase-review, D-E6; THERMO_CORE_REVIEW S06, S07) over every answer of
 * {@link EngineDigestTest}'s request list (its TP, PH and UV answers and the onsets' fluid answers).
 *
 * <ul>
 *   <li>No request is answered {@link FailureKind#INTERNAL}: the list of those that are is pinned, empty. An
 *   {@code INTERNAL} answer is an exception other than a refusal caught by the engine's {@code evaluateTp}: a defect of
 *   the engine read as a refused evaluation before the kind existed.</li>
 *   <li>Each answer's kind and band flag are the types its detail carries, so the kind set beside each text is the one
 *   its readers took from the text: {@link FailureKind#NONE} exactly for a converged answer; the band flag exactly when
 *   the detail starts with {@link EquilibriumResult#CRITICAL_BAND}; the failure's own text (after the band's
 *   parenthesis) names the kind by its typed prefix, a refused evaluation, a state outside the box, or the opening words
 *   of the flash's, the crystal solves' and the searches' untyped failures.</li>
 * </ul>
 */
class EngineFailureKindTest {
    /** The digest requests answered with {@link FailureKind#INTERNAL}: none. */
    private static final List<String> INTERNAL = List.of();

    /** The opening words of each untyped failure text, by the kind its builder sets. */
    private static final Map<FailureKind, List<String>> UNTYPED = Map.of(
            FailureKind.FLASH, List.of("the feed's stability test is unresolved", "no convergence in ", "no two-phase start",
                    "the Newton finish", "the three-slot substitution", "no convergence of the three-slot system"),
            FailureKind.CRYSTAL_SOLVE, List.of("the crystal solve of ", "the fluid left by the crystal ", "the two crystals ",
                    "two crystals"),
            FailureKind.SEARCH, List.of("no convergence of the PH temperature search", "no equal-fugacity temperature",
                    "no convergence of the UV temperature search", "no convergence of the UV pressure search",
                    "no equal-fugacity pressure", "the specified "));

    @Test
    void noDigestRequestIsAnsweredWithAnInternalFailure() {
        List<String> internal = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        for (EngineDigestTest.Answer answer : EngineDigestTest.answers()) {
            if (answer.result().failureKind() != FailureKind.INTERNAL) continue;
            internal.add(answer.id());
            details.append("\n ").append(answer.id()).append(": ").append(answer.result().detail());
        }
        assertEquals(INTERNAL, internal, "digest requests answered INTERNAL (an engine defect caught as a refusal):" + details);
    }

    @Test
    void everyDigestAnswersKindAndBandFlagAreTheTypesItsDetailCarries() {
        List<String> faults = new ArrayList<>();
        Map<String, Integer> tally = new TreeMap<>();
        for (EngineDigestTest.Answer answer : EngineDigestTest.answers()) {
            EquilibriumResult r = answer.result();
            String id = answer.id();
            String detail = r.detail();
            FailureKind kind = r.failureKind();
            tally.merge(r.status() + " " + kind + (r.failedInCriticalBand() ? " in band" : ""), 1, Integer::sum);
            boolean band = detail.startsWith(EquilibriumResult.CRITICAL_BAND);
            if (r.failedInCriticalBand() != band) faults.add(id + ": band flag " + r.failedInCriticalBand() + ", detail " + detail);
            FailureKind expected = switch (r.status()) {
                case CONVERGED -> FailureKind.NONE;
                case UNSUPPORTED -> detail.startsWith(EquilibriumResult.CRYSTAL_PHASES) ? FailureKind.CRYSTAL_PHASES : FailureKind.UNSUPPORTED;
                case NOT_CONVERGED -> {
                    int open = detail.indexOf("): ");
                    if (band && open < 0) {
                        faults.add(id + ": a band failure without its parenthesis: " + detail);
                        yield null;
                    }
                    yield ownKind(band ? detail.substring(open + 3) : detail, kind);
                }
            };
            if (expected == null) {
                faults.add(id + ": " + kind + " for a detail no builder writes: " + detail);
            } else if (expected != kind) {
                faults.add(id + ": kind " + kind + ", the detail says " + expected + ": " + detail);
            }
            if (kind == FailureKind.SEARCH && r.specification() == EquilibriumRequest.Specification.TP) {
                faults.add(id + ": a TP answer of kind SEARCH");
            }
        }
        System.out.println("engine failure kinds: " + tally);
        if (!faults.isEmpty()) fail(faults.size() + " answers whose kind is not their detail's:\n " + String.join("\n ", faults));
    }

    /**
     * The kind a failure's own text names; for a refused evaluation the answer's own kind when it is one of the two
     * that text belongs to ({@code REFUSED}, {@code INTERNAL}); null for a text no builder writes.
     */
    private static FailureKind ownKind(String own, FailureKind kind) {
        if (own.startsWith(EquilibriumResult.UNSTABLE_PRODUCT)) return FailureKind.UNSTABLE_PRODUCT;
        if (own.startsWith(EquilibriumResult.UNRESOLVED_PRODUCT)) return FailureKind.UNRESOLVED_PRODUCT;
        if (own.startsWith(EquilibriumResult.CRYSTAL_STEP)) return FailureKind.CRYSTAL_STEP;
        if (own.startsWith("evaluation refused: ")) {
            return kind == FailureKind.INTERNAL ? FailureKind.INTERNAL : FailureKind.REFUSED;
        }
        if (own.startsWith("the state ") && own.contains(" lies outside the numerical box ")) return FailureKind.OUTSIDE_BOX;
        for (var entry : UNTYPED.entrySet()) {
            for (String opening : entry.getValue()) if (own.startsWith(opening)) return entry.getKey();
        }
        return null;
    }
}
