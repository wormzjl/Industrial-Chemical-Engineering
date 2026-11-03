package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import java.util.Arrays;

/** Scratch (tmp/dry-ice-symptoms/run): package access to the network model's engine for the PH flash cost benchmark. */
public final class EngineAccess {
    /** The engine's PH answer of {@code overall} at {@code p} with the network-datum enthalpy {@code h}, as equilibriumPH rebases it. */
    public static EquilibriumResult ph(FluidThermodynamics m,double p,double h,double[] overall,boolean crystals) {
        var spine=m.hydrocarbon.spine();double target=h;
        if(spine.isPresent()) {
            var formation=spine.get().formationReference();
            target=EnergyReference.sensible(formation.components()).rebase(h,Arrays.copyOf(overall,m.hydrocarbon.componentCount()),formation);
        }
        return m.phaseEngine().ph(p,target,overall,crystals);
    }
    public static EquilibriumResult tp(FluidThermodynamics m,double t,double p,double[] overall,boolean crystals){return m.phaseEngine().tp(t,p,overall,crystals);}
    public static boolean competes(FluidThermodynamics m,double t,double[] overall){return m.crystalsCompete(t,overall);}
}
