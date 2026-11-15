package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.PhaseTraits;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * A phase's traits on every answer (batch 2026-11-13-codebase-review, I-1; THERMO_CORE_REVIEW X36), over every converged
 * answer of {@link EngineDigestTest}'s request list (its TP, PH and UV answers and the onsets' fluid answers).
 *
 * <ul>
 *   <li>Every fluid phase carries traits, and they are its state's ({@link PhaseState#traits()}); a crystal carries none.
 *   A pure coexistence's two states have traits too.</li>
 *   <li>The fluid phases come first, lightest first by molar volume ({@link PhaseState#molarVolume()}, the physical
 *   volume: the dry core sorts by it, the balance steps list the vapour, the liquids lighter first, then the crystals),
 *   the crystals last.</li>
 *   <li>The label of each fluid phase is the rule of {@link PhaseIdentification} on its traits, for every answer whose
 *   phases come from the dry core (with or without a crystal beside them): one fluid phase by
 *   {@link PhaseIdentification#singleLabel} (the critical constants read here, from the phase's own composition); two by
 *   the coexisting-products rule, the denser {@code LIQUID} and the lighter {@code LIQUID} exactly when both are
 *   {@link PhaseIdentification#denseLiquidLike(PhaseTraits)} outside the critical band (the answer's band flag); three by
 *   position, the lightest {@code VAPOR} and the two liquids {@code LIQUID}. A carried split the dry core restated (a
 *   crystal's stand-in beside a vapour) is labelled vapour-liquid whatever its traits: under
 *   {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS} a vapour where the rule says liquid is counted, not a fault. A
 *   merged near-critical split is {@code FLUID} by the merge, and the steps a balance answers (a pure coexistence, a
 *   crystal's step, a triple or three-phase point, a lever step) label their phases by construction: both are counted
 *   and their traits checked, not their labels.</li>
 * </ul>
 */
class PhaseTraitsTest {
    /** The detail notes of the answers whose labels a balance step sets ({@code BalanceSteps}). */
    private static final List<String> BALANCE_NOTES = List.of("pure coexistence at the equal-fugacity point",
            "at the equal-chemical-potential point", "at its family's triple point", "three-phase point of the",
            "on the step between", "vapour-liquid-liquid point of the");
    private static final String MERGED = "merged near-critical split";

    @Test
    void everyConvergedDigestAnswerCarriesItsPhasesTraitsAndItsLabelsFollowFromThem() {
        List<String> faults = new ArrayList<>();
        Map<String, Integer> tally = new TreeMap<>();
        int converged = 0;
        for (EngineDigestTest.Answer answer : EngineDigestTest.answers()) {
            EquilibriumResult r = answer.result();
            if (!r.converged()) continue;
            converged++;
            String id = answer.id();
            for (PhaseState state : r.coexisting()) {
                if (state.kind() != PhaseKind.SOLID && state.traits() == null) faults.add(id + ": a coexisting fluid state has no traits");
            }
            List<PhaseAmounts> fluids = new ArrayList<>();
            boolean crystalSeen = false;
            for (PhaseAmounts phase : r.phases()) {
                PhaseTraits traits = phase.traits();
                if (phase.kind() == PhaseKind.SOLID) {
                    crystalSeen = true;
                    if (traits != null) faults.add(id + ": crystal " + phase.crystal() + " carries traits");
                    continue;
                }
                if (crystalSeen) faults.add(id + ": a fluid phase after a crystal");
                if (traits == null) {
                    faults.add(id + ": a " + phase.kind() + " phase has no traits");
                    continue;
                }
                if (!traits.equals(phase.state().traits())) faults.add(id + ": traits " + traits + " are not its state's " + phase.state().traits());
                if (traits.physicalRootCount() != phase.state().physicalRootCount()) faults.add(id + ": root count " + traits);
                if (!fluids.isEmpty() && !(phase.state().molarVolume() <= fluids.get(fluids.size() - 1).state().molarVolume())) {
                    faults.add(id + ": fluid phases not lightest first by molar volume");
                }
                fluids.add(phase);
            }
            if (fluids.isEmpty()) {
                count(tally, "no fluid phase");
                continue;
            }
            String detail = r.detail();
            if (detail.contains(MERGED)) {
                count(tally, "merged (FLUID by the merge)");
                if (fluids.size() != 1 || fluids.get(0).kind() != PhaseKind.FLUID) faults.add(id + ": a merged split is not one FLUID phase");
                continue;
            }
            if (r.diagnostics().coexistenceFromBalance() || BALANCE_NOTES.stream().anyMatch(detail::contains)) {
                count(tally, "balance step (labels by construction)");
                continue;
            }
            String beside = r.classification().holdsCrystal() ? " beside a crystal" : "";
            switch (fluids.size()) {
                case 1 -> {
                    PhaseAmounts only = fluids.get(0);
                    PhaseIdentification.Label label = PhaseIdentification.singleLabel(only.traits(), only.state().root(),
                            pureSupercritical(answer.engine(), only.state()));
                    count(tally, "single-phase rule" + beside);
                    if (!label.name().equals(only.kind().name())) {
                        faults.add(id + ": single-phase rule says " + label + " for " + only.kind() + " " + only.traits());
                    }
                }
                case 2 -> {
                    PhaseAmounts lighter = fluids.get(0);
                    PhaseAmounts denser = fluids.get(1);
                    boolean twoLiquids = !r.diagnostics().criticalBand() && PhaseIdentification.denseLiquidLike(lighter.traits())
                            && PhaseIdentification.denseLiquidLike(denser.traits());
                    PhaseKind expected = twoLiquids ? PhaseKind.LIQUID : PhaseKind.VAPOR;
                    if (denser.kind() != PhaseKind.LIQUID) faults.add(id + ": the denser of two is " + denser.kind());
                    if (lighter.kind() == expected) {
                        count(tally, "coexisting-products rule" + beside);
                    } else if (lighter.kind() == PhaseKind.VAPOR && r.competition().carried()) {
                        count(tally, "carried split restated vapour-liquid");
                    } else {
                        faults.add(id + ": coexisting-products rule says " + expected + " for the lighter " + lighter.kind() + " "
                                + lighter.traits() + " beside " + denser.traits() + " (band " + r.diagnostics().criticalBand() + ")");
                    }
                }
                default -> {
                    count(tally, "three fluid phases by position" + beside);
                    for (int k = 0; k < fluids.size(); k++) {
                        PhaseKind expected = k == 0 ? PhaseKind.VAPOR : PhaseKind.LIQUID;
                        if (fluids.get(k).kind() != expected) faults.add(id + ": fluid phase " + k + " of three is " + fluids.get(k).kind());
                    }
                }
            }
        }
        System.out.println("phase traits: " + converged + " converged answers; " + tally);
        assertTrue(converged > 0, "the request list gave no converged answer");
        if (!faults.isEmpty()) fail(faults.size() + " faults:\n  " + String.join("\n  ", faults));
    }

    /** Whether the state is one component above its own critical temperature and pressure (the dry core's test). */
    private static boolean pureSupercritical(FluidTpEquilibrium engine, PhaseState state) {
        int only = -1;
        for (int i = 0; i < state.componentCount(); i++) {
            if (!(state.moleFraction(i) > 0.0)) continue;
            if (only >= 0) return false;
            only = i;
        }
        if (only < 0) return false;
        ThermoComponent constants = engine.evaluator().component(only);
        return state.temperature() > constants.criticalTemperatureKelvin() && state.pressure() > constants.criticalPressurePascal();
    }

    private static void count(Map<String, Integer> tally, String key) { tally.merge(key, 1, Integer::sum); }
}
