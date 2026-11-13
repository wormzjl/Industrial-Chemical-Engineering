package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.R;

import com.wormzjl.icheme.science.material.MaterialCoverage;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The crystals of a {@link FluidTpEquilibrium} (P4 stage 2a, S01): each crystal evaluator checked against the evaluator
 * and the contract and anchored once to this fluid family, with the family's own triple point and the corners of its
 * triangle in UV; per component whether a crystal is of that species; the coverage of each qualified crystal competition
 * whose crystals all have their evaluator here. Built once by the engine's constructor, in the order it built them
 * before (S01 moved the code, not the arithmetic); the solves only read it. Immutable.
 */
final class CrystalSet {
    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;
    private final int count;
    /** The cubic's water component, -1 without one. */
    private final int cubicWater;
    /** The crystals this service evaluates (P4 stage 2a), each anchored once to this fluid family. */
    final List<Crystal> crystals;
    /** Per component, whether a crystal of this service is of that species. */
    final boolean[] crystalSpecies;
    /**
     * The coverage of the answers outside the critical band of each qualified crystal competition whose crystals all have
     * their evaluator here, built once.
     */
    final Map<PhaseCompetition, EquilibriumResult.Coverage> crystalCoverages;

    /**
     * @param convergedCoverage the coverage of the engine's fluid-only answers, the base of each competition's coverage
     * @param crystalEvaluators as the engine's constructor takes them
     */
    CrystalSet(PhaseContract contract, CubicPhaseEvaluator evaluator, int count, int cubicWater,
               EquilibriumResult.Coverage convergedCoverage, List<CrystalPhaseEvaluator> crystalEvaluators) {
        this.contract = contract;
        this.evaluator = evaluator;
        this.count = count;
        this.cubicWater = cubicWater;
        List<Crystal> built = new ArrayList<>();
        for (CrystalPhaseEvaluator solid : List.copyOf(crystalEvaluators)) {
            for (Crystal other : built) {
                if (other.id().equals(solid.crystal())) throw new IllegalArgumentException("Crystal " + solid.crystal() + " given twice");
            }
            built.add(crystal(solid));
        }
        crystals = List.copyOf(built);
        crystalSpecies = new boolean[count];
        for (Crystal c : crystals) crystalSpecies[c.component()] = true;
        Map<PhaseCompetition, EquilibriumResult.Coverage> coverages = new HashMap<>();
        for (PhaseCompetition competition : contract.qualifiedCompetitions()) {
            if (competition.crystals().isEmpty()) continue;
            List<Crystal> set = new ArrayList<>();
            for (String id : competition.crystals()) {
                Crystal c = crystalById(id);
                if (c == null) {
                    set = null;
                    break;
                }
                set.add(c);
            }
            if (set != null) coverages.put(competition, FluidTpEquilibrium.solidCoverage(convergedCoverage, competition, set));
        }
        crystalCoverages = Map.copyOf(coverages);
    }

    /** The crystal of {@code solid} on this service: checked against the evaluator and the contract, anchored once. */
    private Crystal crystal(CrystalPhaseEvaluator solid) {
        int component = evaluator.components().indexOf(solid.component());
        if (component < 0) throw new IllegalArgumentException("Crystal " + solid.crystal() + " is made of " + solid.component()
                + ", which the evaluator does not carry");
        boolean named = false;
        for (PhaseCompetition competition : contract.qualifiedCompetitions()) named |= competition.crystals().contains(solid.crystal());
        if (!named) throw new IllegalArgumentException("Crystal " + solid.crystal() + " is in no competition the contract qualifies");
        IdealGasFunction fluidGas = evaluator.idealGas(component);
        double t = solid.record().anchor().temperature();
        double p = solid.record().anchor().pressure();
        if (fluidGas.enthalpy(t) != solid.idealGas().enthalpy(t) || fluidGas.entropy(t, p) != solid.idealGas().entropy(t, p)) {
            throw new IllegalArgumentException("Crystal " + solid.crystal() + " is split against another ideal gas than the evaluator's "
                    + solid.component() + ": the chemical potentials would not compare");
        }
        var anchor = solid.anchor(evaluator);
        double[] share = component == cubicWater ? liquidShare(component, anchor) : null;
        var anchored = share != null ? solid.anchored(anchor, share[0], share[1], share[2]) : solid.anchored(anchor);
        var coverage = solid.record().coverage();
        int slot = contract.index(solid.component());
        String evidence = solid.crystal() + " (" + solid.record().revision() + ", " + coverage.grade().key() + ": " + coverage.evidence() + ")";
        double[] triple = familyTriplePoint(anchored, component);
        return new Crystal(anchored, solid.crystal(), component, slot, gradeOf(coverage.grade()), evidence,
                triple[0], triple[1], tripleTriangle(anchored, component, triple[0], triple[1]));
    }

    /**
     * The water share {@code (h, s, v)} of the pure liquid of {@code component} at {@code anchor} (W3a D5): the constant
     * share ice carries on the balance book.
     */
    private double[] liquidShare(int component, SolidPhaseEvaluator.TriplePointAnchor anchor) {
        double[] pure = new double[count];
        pure[component] = 1.0;
        PhaseState liquid = evaluator.evaluate(anchor.temperature(), anchor.pressure(), pure, Root.LIQUID, evaluator.newWorkspace(),
                evaluator.newState());
        return new double[] {liquid.shareEnthalpy(), liquid.shareEntropy(), liquid.shareVolume()};
    }

    /**
     * The molar internal energies and volumes of the pure species' vapour, liquid and crystal at its family's triple
     * point, {@code (u_V, v_V, u_L, v_L, u_s, v_s)}: the corners of the triple point's triangle in UV (P5), evaluated once;
     * null when the family refuses that point (no triple-point answers then).
     */
    private double[] tripleTriangle(CrystalPhaseEvaluator.Anchored crystal, int component, double t, double p) {
        double[] pure = new double[count];
        pure[component] = 1.0;
        var scratch = evaluator.newWorkspace();
        try {
            PhaseState vapour = evaluator.evaluate(t, p, pure, Root.VAPOR, scratch, evaluator.newState());
            PhaseState liquid = evaluator.evaluate(t, p, pure, Root.LIQUID, scratch, evaluator.newState());
            PhaseState solid = crystal.evaluate(t, p, new PhaseState(1));
            return new double[] {vapour.balanceInternalEnergy(), vapour.balanceVolume(), liquid.balanceInternalEnergy(),
                    liquid.balanceVolume(), solid.balanceInternalEnergy(), solid.balanceVolume()};
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return null;
        }
    }

    /**
     * The family's own triple point of a crystal's pure species (P5): {@code mu_V = mu_L = mu_s}, Newton on
     * {@code (1/T, ln P)} from the anchor's triple point with {@code d(mu/RT)/d(1/T) = h/R} and
     * {@code d(mu/RT)/d ln P = Z} of each phase, until a step moves {@code 1/T} by at most {@code 1e-13} of itself and
     * {@code ln P} by at most {@code 1e-12} (the chemical potentials, some {@code 200 R T} on the formation datum, carry a
     * rounding of about {@code 1e-13 R T}); the anchor's point when it does not converge (it lies within a fraction of a
     * percent of it on a family fitted to the triple point).
     */
    private double[] familyTriplePoint(CrystalPhaseEvaluator.Anchored crystal, int component) {
        double t = crystal.anchor().temperature();
        double p = crystal.anchor().pressure();
        double[] pure = new double[count];
        pure[component] = 1.0;
        var scratch = evaluator.newWorkspace();
        PhaseState vapour = evaluator.newState();
        PhaseState liquid = evaluator.newState();
        PhaseState solid = new PhaseState(1);
        double x = 1.0 / t;
        double y = DeterministicMath.log(p);
        try {
            for (int k = 0; k < 50; k++) {
                double tk = 1.0 / x;
                double pk = DeterministicMath.exp(y);
                evaluator.evaluate(tk, pk, pure, Root.VAPOR, scratch, vapour);
                evaluator.evaluate(tk, pk, pure, Root.LIQUID, scratch, liquid);
                crystal.evaluate(tk, pk, solid);
                double rt = R * tk;
                double g1 = (vapour.chemicalPotential(component) - liquid.chemicalPotential(component)) / rt;
                double g2 = (liquid.chemicalPotential(component) - solid.molarGibbsEnergy()) / rt;
                double a = (vapour.molarEnthalpy() - liquid.molarEnthalpy()) / R;
                double b = vapour.compressibilityFactor() - liquid.compressibilityFactor();
                double c = (liquid.molarEnthalpy() - solid.molarEnthalpy()) / R;
                double d = liquid.compressibilityFactor() - pk * solid.molarVolume() / rt;
                double det = a * d - b * c;
                double dx = -(g1 * d - b * g2) / det;
                double dy = -(a * g2 - c * g1) / det;
                if (!Double.isFinite(dx) || !Double.isFinite(dy)) break;
                x += dx;
                y += dy;
                if (Math.abs(dx) <= 1.0e-13 * x && Math.abs(dy) <= 1.0e-12) return new double[] {1.0 / x, DeterministicMath.exp(y)};
            }
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            // The anchor's point below.
        }
        return new double[] {t, p};
    }

    Crystal crystalById(String id) {
        for (Crystal c : crystals) if (c.id().equals(id)) return c;
        return null;
    }

    private static EquilibriumResult.CoverageGrade gradeOf(MaterialCoverage.Grade grade) {
        return switch (grade) {
            case QUALIFIED -> EquilibriumResult.CoverageGrade.QUALIFIED;
            case ESTIMATED_DECLARED_ERROR -> EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR;
            case RESEARCH_ONLY -> EquilibriumResult.CoverageGrade.RESEARCH_ONLY;
            case UNAVAILABLE -> EquilibriumResult.CoverageGrade.UNAVAILABLE;
        };
    }

    /**
     * A crystal on this service (P4 stage 2a): its anchored view (the integration constants solved once at
     * construction), the species' EOS index and contract slot, the record's grade
     * and its evidence line, and the family's own triple point (P5: where the crystal and the family's vapour and liquid
     * of the pure species have equal chemical potentials; the anchor's triple point pins the crystal to the liquid only,
     * and the cubic's saturation pressure there differs slightly), with the corners of its triangle in UV
     * ({@link CrystalSet#tripleTriangle}).
     */
    record Crystal(CrystalPhaseEvaluator.Anchored anchored, String id, int component, int slot,
                   EquilibriumResult.CoverageGrade grade, String evidence,
                   double tripleTemperature, double triplePressure, double[] triangle) {
        /** The anchor's fusion enthalpy, J/mol (the record's effective value on this family, D16). */
        double fusionEnthalpy() { return anchored.record().anchor().fusionEnthalpy(); }
    }
}
