package com.wormzjl.icheme.science.fluid.conversion;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;

/**
 * The volume laws of the fluid converter (batch 2026-11-12-fluid-conversion, plan section 2), 1 L = 1 mB:
 * <ul>
 * <li>Reverse (IChemE to Forge): the Forge tank gains {@code V = m / rho} litres for {@code m} kg drawn at density
 * {@code rho} (the drawn stream's at the vessel's state). 1 L/s of an 850 kg/m3 liquid is 0.85 kg/s.</li>
 * <li>Forward (Forge to IChemE): {@code V} litres give {@code V rho_rule / M} moles, {@code rho_rule} the package's liquid
 * density of the rule composition at the rule's T and P, {@code M} its molar mass; a gas rule gives its own
 * {@code moles_per_litre}.</li>
 * </ul>
 */
public final class ConversionLaws {
    private ConversionLaws() {}
    /** Litres per cubic metre. */
    public static final double LITRES_PER_M3=1000;

    /** The litres {@code kilograms} of a stream at {@code density} (kg/m3) fill. */
    public static double litres(double kilograms,double density) {
        if(!(density>0)||!Double.isFinite(density))throw new IllegalArgumentException("A density above zero");
        return kilograms/density*LITRES_PER_M3;
    }
    /** The kilograms {@code litres} of a stream at {@code density} (kg/m3) weigh. */
    public static double kilograms(double litres,double density){return litres*density/LITRES_PER_M3;}

    /** A forward rule's stream per litre: its mole fractions on a model's list, its moles per litre and its density (kg/m3). */
    public record PerLitre(double[] fractions,double moles,double density,double molarMass) {
        public PerLitre{fractions=fractions.clone();}
        @Override public double[] fractions(){return fractions.clone();}
        /** The amounts (mol) {@code litres} give, on the model's list. */
        public double[] moles(double litres){var n=fractions.clone();for(int i=0;i<n.length;i++)n[i]*=moles*litres;return n;}
    }
    /**
     * Forward rule {@code rule}'s stream per litre on {@code model}'s list (compound ids, water last): for a liquid, the
     * flash of the rule composition at the rule's T and P gives the liquid density ({@link FluidThermodynamics#liquidDensity},
     * the network's own molar volume), and a litre holds {@code rho / M / 1000} mol; for a gas, the rule's moles per litre.
     * Null where the model lacks one of the rule's compounds or the composition holds no liquid there.
     */
    public static PerLitre perLitre(ConversionRule.ToIChemE rule,FluidThermodynamics model) {
        var fractions=fractions(rule,model);if(fractions==null)return null;
        double molar=0;for(int i=0;i<fractions.length;i++)molar+=fractions[i]*model.molecularWeight(i);
        if(!Double.isNaN(rule.molesPerLitre()))return new PerLitre(fractions,rule.molesPerLitre(),rule.molesPerLitre()*molar*LITRES_PER_M3,molar);
        var state=model.flashTP(rule.temperature(),rule.pressure(),fractions,()->{});
        if(!FluidThermodynamics.holdsLiquid(state))return null;
        double density=model.liquidDensity(state);
        return new PerLitre(fractions,density/molar/LITRES_PER_M3,density,molar);
    }
    /**
     * The rule's mole fractions on {@code model}'s list, or null where the model does not carry one of its compounds: an id
     * off its list, or water on a model without water in its cubic. An island that holds no water and can be given none is
     * built dry (batch 2026-11-11-aqueous-solvents, W3b option A): its model lists water last and carries none, so a rule
     * that gives water waits there for the island to grow wet, as for any compound ({@code Converters.grow}).
     */
    public static double[] fractions(ConversionRule.ToIChemE rule,FluidThermodynamics model) {
        var x=fractions(rule,model.components());
        return x==null||!model.wet()&&x[x.length-1]>0?null:x;
    }
    /** The rule's mole fractions on {@code components}, or null where one of its compounds is not on the list. */
    public static double[] fractions(ConversionRule.ToIChemE rule,List<String> components) {
        var x=new double[components.size()];
        for(var e:rule.composition().entrySet()){int i=components.indexOf(e.getKey());if(i<0){if(e.getValue()>0)return null;continue;}x[i]+=e.getValue();}
        return x;
    }
}
