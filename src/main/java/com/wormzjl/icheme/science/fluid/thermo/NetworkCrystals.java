package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import com.wormzjl.icheme.science.thermo.phase.CrystalFugacity;
import com.wormzjl.icheme.science.thermo.phase.CrystalPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FreeWaterIce;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The crystals of a network package, as solids (owner decisions D22 (5) and D23; the plan
 * {@code documentation/2026-09-24-coolprop-low-temperature/PRECIPITATED_SOLIDS_PLAN.md}): the crystals the package admits
 * and, on every package, the free water's ice (WP4 of the range-extrapolation plan). A crystal is a second kind of
 * population in the vessel's {@code SolidInventory}, beside the custom solids and under the same rules (key, transport,
 * filters, outlet checks, slurry viscosity, the slush), whose material record adds the fluid component it is made of and
 * its energy offset. Inside a step it is an inert population; only a vessel's refresh ({@code InventoryEquilibrium})
 * exchanges its mass with its component, with the crystal's chemical potential ({@link Crystal#logFugacityCoefficient}) equal
 * to the component's: an admitted crystal's anchored model, which the equilibrium engine competes, and the ice's fugacity
 * of the free-water rule ({@link FreeWaterIce}), the steam's partial pressure over it.
 *
 * <p>Constant properties. A population's moments are main's: a constant density and heat capacity, and the stored energy
 * {@code c (T - 298.15)} per kg. The crystal's own model varies: CO2-I (Jaeger and Span) from 1654 kg/m3 at 110 K to 1545
 * at its triple point, its heat capacity from 0.94 to 1.34 kJ/(kg K); ice Ih (the R10-06 fit) from 17.3 J/(mol K) at
 * 111.6 K to 37.8 at its melting point. The record takes them where the pure crystal meets its own fluid at the network's
 * ambient pressure ({@link PhaseLayout#AMBIENT_PRESSURE}, the outside of every vessel, D54): the crystal's normal
 * transition, for CO2-I its normal sublimation point (194.6 K, 1571 kg/m3, 1.255 kJ/(kg K)), the state a slack tank of dry
 * ice sits at, for ice its normal melting point (273.1526 K, 916.7 kg/m3, 2.097 kJ/(kg K)). There the crystal's energy is
 * the model's exactly: {@code u0 = u_s(T_ref, P_amb) - c (T_ref - 298.15)} on the network's datum (an admitted crystal's
 * spine energy less the formation enthalpy, D10; on a fit package and for the ice the model's energy itself, the family
 * being on the network's datum, E27), so a refresh's latent heat is the
 * model's at {@code T_ref} and departs from it by the integral of the heat capacity's change elsewhere: for CO2-I 0.04
 * kJ/mol at its triple point (0.5 % of the fusion enthalpy), 0.17 kJ/mol at 150 K and 0.59 kJ/mol at 110 K (0.7 and 2.3 %
 * of the sublimation enthalpy); for ice 0.0 kJ/mol at 263 K and 1.7 kJ/mol at 111.6 K (14 % of the 12.0 kJ/mol water
 * gives up from 20 °C to ice there).</p>
 */
public final class NetworkCrystals {
    private final List<Crystal> crystals;
    private NetworkCrystals(List<Crystal> crystals){this.crystals=List.copyOf(crystals);}

    /**
     * One crystal: its record as a solid ({@link SolidMaterial#crystal()} set), the network basis index and molar mass
     * (kg/mol) of the component it is made of (the free water's slot, the basis's last, for ice), the temperature its
     * constants were taken at, its anchored model (its record's properties) and its fugacity: the anchored model itself for
     * an admitted crystal, the free-water rule's ice for ice. {@code meltingFoot} (K, Pa) and {@code meltingSlope} (K/Pa)
     * state an admitted crystal's melting line ({@link #meltingTemperature}); the ice reads its own.
     */
    public record Crystal(SolidMaterial material,int component,double molarMass,double referenceTemperature,
                          CrystalPhaseEvaluator.Anchored anchored,CrystalFugacity fugacity,double meltingFootTemperature,
                          double meltingFootPressure,double meltingSlope) {
        public Crystal{Objects.requireNonNull(material);Objects.requireNonNull(anchored);Objects.requireNonNull(fugacity);}
        /**
         * The pure crystal's melting temperature at {@code pressure}, K: above it no amount of this crystal is stable beside
         * any fluid (a fluid's mixing only lowers its species' chemical potential below the pure liquid's). The ice's is the
         * free-water rule's line ({@link FreeWaterIce#meltingTemperature}); an admitted crystal's is the straight line
         * through its record's triple point and the engine's own melting point of the pure species at
         * {@link #MELTING_PRESSURE} (CO2-I: 216.6 K at 0.518 MPa and about 218.6 K at 10 MPa, within 0.13 K of Span and Wagner
         * there). Below the triple-point pressure the line continues: a crystal between its sublimation point and that line
         * is kept, as before.
         */
        public double meltingTemperature(double pressure) {
            if(fugacity instanceof FreeWaterIce ice)return ice.meltingTemperature(pressure);
            return meltingFootTemperature+meltingSlope*(pressure-meltingFootPressure);
        }
        /** {@code ln phi_s}: the crystal's chemical potential against its species' ideal gas at (T, P), the reference the
         * fluid's {@code ln (x phi P)} is stated on (continued past the record's range); for ice {@code ln(f_ice/P)}. */
        public double logFugacityCoefficient(double temperature,double pressure){return fugacity.logFugacityCoefficient(temperature,pressure);}
        /** Whether this is the free water's ice (WP4), made of the free water rather than of a hydrocarbon component. */
        public boolean water(){return fugacity instanceof FreeWaterIce;}
        /** Moles of the component in {@code massKg} of this crystal. */
        public double moles(double massKg){return massKg/molarMass;}
    }

    /** The crystals, the admitted ones in declaration order and the free water's ice last. */
    public List<Crystal> all(){return crystals;}
    /** The crystal whose record is {@code material}, or null for a custom solid. */
    public Crystal of(SolidMaterial material) {
        if(material.crystal()==null)return null;
        for(var c:crystals)if(c.material().equals(material))return c;
        throw new IllegalArgumentException("Crystal "+material.id()+" ("+material.revision()+") is not a crystal of this package");
    }

    /** The crystals of {@code model}'s package as solids: its engine's anchored crystals {@code ids}, then its free water's ice. */
    static NetworkCrystals of(FluidThermodynamics model,NetworkPhaseEngine engine,List<String> ids,String packageId) {
        var species=engine.species();var built=new ArrayList<Crystal>();
        // The anchored model's datum: a spine package's formation datum (D10), a fit package's network datum itself (E27).
        var formation=model.hydrocarbon.spine().map(SpinePackageData::formationReference).orElse(null);
        for(String id:ids) {
            var anchored=engine.engine().anchoredCrystal(id);
            if(anchored==null)throw new IllegalStateException("The network's engine has no evaluator of crystal "+id);
            int component=model.hydrocarbon.components().indexOf(anchored.component());
            if(component<0)throw new IllegalStateException("Crystal "+id+" is made of "+anchored.component()+", which the network basis does not carry");
            double molarMass=model.molecularWeight(component);
            // The pure crystal's transition at the ambient pressure: its normal sublimation (or melting) point.
            double[] pure=new double[species.size()];pure[species.indexOf(anchored.component())]=1;
            var onset=engine.engine().depositionTemperature(id,PhaseLayout.AMBIENT_PRESSURE,species,pure,engine.engine().newWorkspace());
            if(onset.status()!=EquilibriumResult.Status.CONVERGED)
                throw new IllegalStateException("Crystal "+id+": no transition of the pure crystal at the ambient pressure ("+onset.detail()+")");
            // Its melting line (Crystal.meltingTemperature): the record's triple point and the pure species' melting point at
            // MELTING_PRESSURE; upright at the triple point where the engine gives none.
            double footT=anchored.anchor().temperature(),footP=anchored.anchor().pressure(),slope=0;
            var melting=engine.engine().depositionTemperature(id,MELTING_PRESSURE,species,pure,engine.engine().newWorkspace());
            if(melting.converged()&&MELTING_PRESSURE>footP)slope=(melting.temperature()-footT)/(MELTING_PRESSURE-footP);
            built.add(crystal(anchored,anchored,component,molarMass,onset.temperature(),formation==null?0:formation.offsetJoulesPerMole(component),packageId,footT,footP,slope));
        }
        // The free water's ice, at its normal melting point; the free water is on the network's own datum (no formation).
        var ice=model.freeWaterIce();int water=model.hydrocarbon.componentCount();
        built.add(crystal(ice.anchored(),ice,water,model.molecularWeight(water),ice.meltingTemperature(PhaseLayout.AMBIENT_PRESSURE),0,packageId,
                ice.tripleTemperature(),ice.triplePressure(),Double.NaN));
        return new NetworkCrystals(built);
    }
    /** The pressure at which an admitted crystal's melting line takes its second point, Pa: the top of the network's usual
     * states. */
    static final double MELTING_PRESSURE=1e7;
    /** A crystal's record as a solid, its constants taken at {@code t} and the ambient pressure; {@code formation}, J/mol, is
     * the component's formation enthalpy on the anchored model's datum. */
    private static Crystal crystal(CrystalPhaseEvaluator.Anchored anchored,CrystalFugacity fugacity,int component,double molarMass,double t,
                                   double formation,String packageId,double footT,double footP,double slope) {
        double p=PhaseLayout.AMBIENT_PRESSURE;
        var state=anchored.properties(t,p);
        double internalEnergy=state.enthalpy()-formation-p*state.volume();
        double density=molarMass/state.volume(),heatCapacity=state.heatCapacity()/molarMass;
        double offset=internalEnergy/molarMass-heatCapacity*(t-SolidMaterial.REFERENCE_TEMPERATURE);
        var material=new SolidMaterial(anchored.crystal(),anchored.record().revision()+"@"+packageId,density,heatCapacity,new SolidMaterial.Crystal(anchored.component(),offset));
        return new Crystal(material,component,molarMass,t,anchored,fugacity,footT,footP,slope);
    }
}
