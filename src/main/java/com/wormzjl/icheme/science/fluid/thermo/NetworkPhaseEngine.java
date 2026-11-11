package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.CrystalPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The equilibrium engine of one network model: {@link FluidTpEquilibrium} on the derived contract of the model's own
 * evaluator ({@link PhaseContract#forDerived}, the very {@link HydrocarbonModel#evaluator}), the one service that decides a
 * node's phases ({@link FluidThermodynamics#equilibriumTP}: the outer active-set check, the inventory refresh, placement and
 * boundary charges) and a pipe junction's own split where the outer check adds a phase ({@link #ph}, D26).
 *
 * <p>W3b of batch 2026-11-11-aqueous-solvents: water is a component of the cubic, so the engine has no free-water model; ice
 * is one of the package's crystals. On a model with water the engine's basis is the network's conserved basis (compounds,
 * water last); on a model without water (option A: an island no water can reach) it is the compounds alone, and a request's
 * water amount, which is exactly zero there, is dropped.</p>
 *
 * <p>The engine's workspaces are per-thread scratch ({@link FluidTpEquilibrium.Workspace}), kept in a thread-local of
 * this instance so a model shared by several island workers never shares one.</p>
 */
final class NetworkPhaseEngine {
    private final FluidTpEquilibrium engine;
    private final List<String> species;
    /** Whether the engine's basis carries water (the network's conserved basis), or the compounds alone. */
    private final boolean wet;
    private final ThreadLocal<FluidTpEquilibrium.Workspace> workspaces;

    private NetworkPhaseEngine(FluidTpEquilibrium engine,boolean wet) {
        this.engine=engine;species=engine.contract().components();this.wet=wet;
        workspaces=ThreadLocal.withInitial(engine::newWorkspace);
    }

    /**
     * The engine of {@code fluid}'s model with {@code settings} ({@link FluidTpEquilibrium.Settings#DEFAULT} for the
     * network): the derived contract of the model's evaluator, with one evaluator per crystal the contract admits (ice
     * among them on a model with water), anchored to this evaluator's family on either ideal-gas source
     * ({@link CrystalPhaseEvaluator#forPackage}).
     */
    static NetworkPhaseEngine of(FluidThermodynamics fluid,MaterialCatalog catalog,String packageId,FluidTpEquilibrium.Settings settings) {
        var evaluator=fluid.hydrocarbon.evaluator();
        var contract=PhaseContract.forDerived(catalog,packageId,evaluator);
        var crystals=fluid.crystals();
        List<CrystalPhaseEvaluator> solids=new ArrayList<>();
        if(!crystals.isEmpty()) {
            var competition=contract.crystalCompetition();
            if(competition==null||!competition.crystals().equals(new java.util.TreeSet<>(crystals)))
                throw new IllegalStateException("Package "+packageId+": the network's crystals "+crystals+" are not the contract's competition "+competition);
            for(String id:crystals)solids.add(CrystalPhaseEvaluator.forPackage(catalog,packageId,id));
        }
        return new NetworkPhaseEngine(new FluidTpEquilibrium(contract,evaluator,settings,null,solids),fluid.wet());
    }

    /** {@code overall} (the network's conserved basis, water last) on the engine's basis: itself with water, the compounds
     * alone without (the water amount must then be zero). */
    private double[] basis(double[] overall) {
        if(wet)return overall;
        if(overall[overall.length-1]!=0)throw new IllegalArgumentException("Water on a model without water");
        return Arrays.copyOf(overall,overall.length-1);
    }
    /** The TP equilibrium of {@code overall} (the network's conserved basis, water last) at {@code (t, p)}: under the
     * package's crystal competition when {@code crystals} ({@link PhaseCompetition#FLUID_AND_CRYSTALS}), fluid phases only
     * otherwise. */
    EquilibriumResult tp(double t,double p,double[] overall,boolean crystals) {
        return tp(t,p,overall,competition(crystals));
    }
    /** {@link #tp(double,double,double[],boolean)} under {@code competition}. */
    EquilibriumResult tp(double t,double p,double[] overall,PhaseCompetition competition) {
        return engine.tp(EquilibriumRequest.tp(t,p,species,basis(overall),competition),workspaces.get());
    }
    /** The PH equilibrium of {@code overall} at {@code p} with enthalpy {@code h} (J, on the engine's datum), under the
     * competition {@code crystals} selects as for {@link #tp}: a pipe junction's own split (D26). */
    EquilibriumResult ph(double p,double h,double[] overall,boolean crystals) {
        return ph(p,h,overall,competition(crystals));
    }
    /** {@link #ph(double,double,double[],boolean)} under {@code competition}. */
    EquilibriumResult ph(double p,double h,double[] overall,PhaseCompetition competition) {
        return engine.ph(EquilibriumRequest.ph(p,h,species,basis(overall),competition),workspaces.get());
    }
    /**
     * The equilibrium of {@code overall} at internal energy {@code u} (J, the engine's datum) and volume {@code v} (m3), the
     * package's crystals competing where {@code crystals} (as for {@link #tp}): the crystal test of a taut vessel's refresh
     * ({@code InventoryEquilibrium}, D23), D21's near-pure step included. Warm-started at {@code (hintT, hintP)} when both
     * are finite.
     */
    EquilibriumResult crystalUv(double u,double v,double[] overall,boolean crystals,double hintT,double hintP) {
        return crystalUv(u,v,overall,competition(crystals),hintT,hintP);
    }
    /** {@link #crystalUv(double,double,double[],boolean,double,double)} under {@code competition}. */
    EquilibriumResult crystalUv(double u,double v,double[] overall,PhaseCompetition competition,double hintT,double hintP) {
        var request=EquilibriumRequest.uv(u,v,species,basis(overall),competition);
        return Double.isFinite(hintT)&&Double.isFinite(hintP)?engine.uv(request,workspaces.get(),hintT,hintP):engine.uv(request,workspaces.get());
    }
    private static PhaseCompetition competition(boolean crystals){return crystals?PhaseCompetition.FLUID_AND_CRYSTALS:PhaseCompetition.FLUID_ONLY;}
    /** The engine itself: the crystal evaluators and the onset functions the network's crystal records are built from. */
    FluidTpEquilibrium engine(){return engine;}
    /** The engine's basis: the compounds, then water on a model with water. */
    List<String> species(){return species;}
}
