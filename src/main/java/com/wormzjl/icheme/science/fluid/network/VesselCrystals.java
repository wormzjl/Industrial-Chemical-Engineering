package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.NetworkCrystals;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PhaseSupport;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The crystal part of a vessel's refresh (owner decisions D22 (5) and D23): the one place crystal mass moves. A crystal
 * is a population of the vessel's solids ({@link NetworkCrystals}); inside a step it is inert, and at each interval and
 * solid-event segment start the vessel's refresh ({@link InventoryEquilibrium#refresh}) equilibrates it with the
 * vessel's fluid, about 5 s of lag, which the owner accepted.
 *
 * <p>Physics. A well-mixed closed tank at fixed energy and volume (a taut wall), or at the ambient pressure and fixed
 * enthalpy (a slack bag, D54), has one equilibrium; where a crystal is in it, the crystal's chemical potential equals its
 * component's in the fluid, and the latent heat of the mass that froze or melted is in the tank's energy. Presence: at
 * the vessel's own closed state the fluid's drive to each crystal, {@code D = ln f_i(fluid) - ln f_s}, is read; a vessel
 * holding no crystal with no positive drive, or holding its crystals at zero drive ({@link #DEADBAND}), keeps every bit
 * (a vessel at rest is a fixed point); otherwise the equilibrium engine's crystal test decides which crystals are
 * present, at fixed volume while the wall is taut and at the ambient pressure while it is slack, switching to the other
 * when a fixed-volume answer lies below the ambient or a constant-pressure one above the capacity. Amount: each present
 * crystal's amount is one more unknown of the vessel's own closure beside its fluid's ({@link Joint}), its chemical
 * potential equal to its component's its row, the energy and wall rows holding the crystal's mass at its record's
 * constants; the vessel's pass loop lays its fluid out ({@link JointClosure}, decision D30), and a crystal the closure
 * would hold below zero mass is absent and the closure solved again without it. Booking: the offset of the mass that
 * froze or melted, and the ambient's work on a moving bag {@code -P_amb (V_bag - V_bag,0)} (D54), are booked as a
 * boundary transfer of energy alone at the vessel. Size (D23, one size per solid in each stock): mass that freezes
 * enters at {@link #precipitateDiameter} of the liquids it freezes from, the vessel's own before the refresh
 * ({@link #freezing}), merged into the stock's one population at the mass-weighted mean
 * ({@link SolidInventory#oneSizePerSolid}); mass that melts leaves at the stock's size (methane with 30 % CO2 freezes at
 * about 0.5 mm; frost from a gas or the snow of a let-down at 0.25 mm). Above the crystals' ceiling a vessel holds none of
 * the package's crystals. The free water's ice is water's own rule, on every package: free water below its melting line
 * freezes here, from its liquid and from the gas's steam; ice and a competing crystal coexist. A vessel holding no fluid
 * is the dry-solids vessel: its crystals have no fluid to exchange with.
 */
final class VesselCrystals {
    private VesselCrystals() {}

    /**
     * The drive {@code D = ln f_i(fluid) - ln f_s} (owner decision D23) within which a vessel's crystals are at their
     * equilibrium: a supersaturation of 1e-8 of the fluid's fugacity, three orders above the closure's resolution
     * ({@link InventoryEquilibrium#CLOSURE}) and far below anything a gauge could tell, so a vessel exactly on its frost
     * line does not cycle between holding a trace of crystal and none.
     */
    static final double DEADBAND=1e-8;
    /** d_dilute (owner decision D23 (2)): the diameter of a crystal formed in a dilute liquid or from a gas, m. */
    static final double DILUTE_DIAMETER=2.5e-4;
    /** The cap of the freezing component's volume share of the liquids (owner decision D23 (1)): the slush. */
    static final double SLUSH_SHARE=0.5;

    /** A vessel after its crystal refresh: its state, its inventory, and the ambient's work on its wall (J into the vessel). */
    record Settled(FluidThermodynamics.State state,PassiveNetwork.Inventory inventory,double wallWork) {}

    /**
     * The crystal refresh of a vessel whose inventory (one size per solid) is {@code inventory} and whose closed state, its
     * crystals inert, is {@code closed}; null when the vessel is at its crystal equilibrium (or the package admits no
     * crystal), which leaves it bit for bit.
     */
    static Settled settle(FluidThermodynamics model,PassiveNetwork.Inventory inventory,FluidThermodynamics.State closed,Runnable checkpoint) {
        return settle(model,inventory,closed,true,checkpoint);
    }
    /**
     * Whether a crystal may be present in a vessel holding {@code inventory} near {@code state}: the free water's ice (WP4)
     * where the vessel holds ice, or holds water at or below the melting line at the state's pressure ({@code T <= T_m(P)},
     * {@link com.wormzjl.icheme.science.thermo.phase.FreeWaterIce#meltingTemperature}), and the package's crystals where
     * the vessel's species are under their competition (at or below the crystals' ceiling, {@link FluidThermodynamics#crystalsCompete}).
     * Physics: above its melting line water cannot freeze, since the latent heat a freeze releases would raise the vessel
     * further above it; ice the vessel holds melts there, and as it melts it cools the vessel, so some may stay at the line.
     */
    static boolean competes(FluidThermodynamics model,PassiveNetwork.Inventory inventory,FluidThermodynamics.State state) {
        var crystals=model.crystalSolids();
        if(PhaseLayout.sum(inventory.moles())==0)return false;
        double[] held=held(crystals,inventory),totals=totals(crystals,inventory,held);
        double t=state.temperature();boolean ice=false;
        for(int k=0;k<held.length;k++)ice|=held[k]>0&&crystals.all().get(k).water();
        return ice||totals[totals.length-1]>0&&t<=model.freeWaterIce().meltingTemperature(state.pressure())||model.crystalsCompete(t,totals);
    }
    private static double[] held(NetworkCrystals crystals,PassiveNetwork.Inventory inventory) {
        var all=crystals.all();double[] held=new double[all.size()];
        for(var population:inventory.solids().crystals())held[all.indexOf(crystals.of(population.material()))]+=population.massKg();
        return held;
    }
    private static double[] totals(NetworkCrystals crystals,PassiveNetwork.Inventory inventory,double[] held) {
        var all=crystals.all();double[] totals=inventory.moles();
        for(int k=0;k<all.size();k++)totals[all.get(k).component()]+=all.get(k).moles(held[k]);
        return totals;
    }
    /**
     * {@link #settle(FluidThermodynamics, PassiveNetwork.Inventory, FluidThermodynamics.State, Runnable)} of a vessel whose
     * fluid could not be closed on its own ({@code isClosed} false; {@code closed} is then its last state): a fluid far
     * past its saturation, which the network carries only until this refresh (D23 (5)), may have no fluid-only state the
     * network can carry (two liquids, a third phase). The crystal test then decides from the inventory alone, with no
     * drive test, and its answer states the vessel; null where it holds no crystal (the caller keeps the closure's failure).
     */
    static Settled settle(FluidThermodynamics model,PassiveNetwork.Inventory inventory,FluidThermodynamics.State closed,boolean isClosed,Runnable checkpoint) {
        var crystals=model.crystalSolids();
        var all=crystals.all();int count=all.size();
        double[] held=held(crystals,inventory);boolean holds=!inventory.solids().crystals().isEmpty();
        // Main's dry-solids vessel (OD-P4): no fluid for a crystal to exchange its mass with.
        if(PhaseLayout.sum(inventory.moles())==0)return null;
        double[] totals=totals(crystals,inventory,held);
        double t=closed.temperature(),p=closed.pressure(),capacity=inventory.wallCapacity();
        double trueEnergy=inventory.internalEnergy();for(int k=0;k<count;k++)trueEnergy+=held[k]*all.get(k).material().crystal().energyOffset();
        // The package's crystals compete at or below their ceiling, the free water's ice wherever the vessel holds water
        // (WP4); neither: what a vessel holds melts. A held crystal that cannot be present melts in the crystal test.
        boolean competing=model.crystalsCompete(t,totals),wet=totals[totals.length-1]>0;
        if(!competing&&!wet)return holds&&isClosed?melt(model,inventory,closed,totals,trueEnergy,checkpoint):null;
        boolean drive=!isClosed;
        for(int k=0;k<count&&!drive;k++) {
            var crystal=all.get(k);
            if(!(crystal.water()?wet:competing)){drive=held[k]>0;continue;}
            double d=drive(model,closed,crystal,t,p);
            if(held[k]>0?!(Math.abs(d)<=DEADBAND):d>DEADBAND)drive=true;
        }
        if(!drive)return null;
        // The engine decides which crystals are present: fixed volume while the wall is taut, the ambient pressure while it
        // is slack, switching once to the other where the answer says the wall is on the other branch.
        var inert=inventory.solids().withoutCrystals();var inertMoments=inert.moments();
        double crystalEnergy=trueEnergy-inertMoments.internalEnergy(t),bag=PhaseLayout.bagVolume(closed,capacity);
        boolean warm=holds&&(closed.liquidProperties()==null||closed.vaporProperties()==null||majorSpecies(totals)>=3);
        boolean taut=PhaseLayout.taut(closed,capacity);FluidThermodynamics.CrystalAnswer answer=null;
        for(int attempt=0;attempt<2;attempt++) {
            answer=taut?model.crystalsAtVolume(totals,crystalEnergy,PhaseLayout.capacityVolume(capacity)-inertMoments.volume(),warm?t:Double.NaN,warm?p:Double.NaN,competing,checkpoint)
                    :model.crystalsAtPressure(totals,crystalEnergy+inventory.ambient()*(bag-inertMoments.volume()),inventory.ambient(),competing,checkpoint);
            // A rigid vessel (batch 2026-11-10-rigid-reservoir) has the fixed-volume branch alone.
            boolean other=taut?!PhaseLayout.rigid(capacity)&&answer.pressure()<inventory.ambient():answer.volume()+inertMoments.volume()>capacity;
            if(!other)break;
            taut=!taut;
        }
        if(!answer.holdsCrystal())return holds&&isClosed?melt(model,inventory,closed,totals,trueEnergy,checkpoint):null;
        double[] moles=answer.crystalMoles();
        // The engine's answer holds no fluid: the crystal holds all of its species, which must be all the vessel holds
        // (traces leave a fluid, D21), and the vessel is a dry-solids vessel.
        if(answer.fluid()==null)return frozen(model,inventory,closed,all,held,moles,totals,trueEnergy,inert,checkpoint);
        var present=new ArrayList<NetworkCrystals.Crystal>();var seeds=new ArrayList<Double>();
        for(int k=0;k<count;k++)if(moles[k]>0){present.add(all.get(k));seeds.add(moles[k]);}
        while(!present.isEmpty()) {
            // The vessel's pass loop lays the fluid beside the crystals out (decision D30), from the engine's answer: a phase
            // the record's crystal leaves no room for leaves, as in the vessel's own closure. Each pass runs the checkpoint.
            var closure=new JointClosure(model,present,seeds,totals,trueEnergy,capacity,inventory.ambient(),bag,inert,checkpoint);
            InventoryEquilibrium.passes(model,answer.fluid(),closure,checkpoint);
            var joint=closure.joint;var solved=closure.solved;
            var absent=new ArrayList<NetworkCrystals.Crystal>();
            for(int j=0;j<present.size();j++)if(!(joint.crystalMoles(solved,j)>0))absent.add(present.get(j));
            if(absent.isEmpty())return settled(model,inventory,closed,all,held,joint,solved,totals,trueEnergy,bag,inert,checkpoint);
            // A crystal the closure holds below zero mass is absent: it melts entirely, and the rest are solved again.
            for(var crystal:absent){int j=present.indexOf(crystal);present.remove(j);seeds.remove(j);}
        }
        return holds&&isClosed?melt(model,inventory,closed,totals,trueEnergy,checkpoint):null;
    }

    /**
     * The fluid's drive to {@code crystal} at {@code state}: {@code ln f_i(fluid) - ln f_s(T, P)}, read in the gas where
     * the state holds the component there, else in the liquid; minus infinity where the fluid holds none of it. For the
     * free water's ice (WP4) the water's fugacity is its steam's partial pressure, else its liquid's saturation pressure;
     * minus infinity in a gas holding no steam (the ice sublimes into it); where the fluid holds no water and no gas the
     * ice has nothing to exchange with but its own melt, and its drive is the liquid's against it where that is negative
     * (above the melting line), else zero.
     */
    static double drive(FluidThermodynamics model,FluidThermodynamics.State state,NetworkCrystals.Crystal crystal,double t,double p) {
        if(!crystal.water())return logFugacity(state,crystal.component(),state.vaporView()[crystal.component()]>0)-crystal.logFugacityCoefficient(t,p);
        if(state.waterVapor()>0||state.waterLiquid()>0)return waterLogFugacity(model,state,state.waterVapor()>0)-crystal.logFugacityCoefficient(t,p);
        if(state.vaporProperties()!=null)return Double.NEGATIVE_INFINITY;
        return Math.min(0,DeterministicMath.log(model.saturationPressure(t)/p)-crystal.logFugacityCoefficient(t,p));
    }
    /** {@code ln (f_w / P)} of the free water in the state's gas ({@code vapour}: its steam's partial pressure) or in its
     * liquid water (its saturation pressure, the free-water rule's fugacity of the liquid); minus infinity without it. */
    private static double waterLogFugacity(FluidThermodynamics model,FluidThermodynamics.State state,boolean vapour) {
        if(vapour)return state.waterVapor()>0?DeterministicMath.log(state.waterPartialPressure()/state.pressure()):Double.NEGATIVE_INFINITY;
        return state.waterLiquid()>0?DeterministicMath.log(model.saturationPressure(state.temperature())/state.pressure()):Double.NEGATIVE_INFINITY;
    }
    /** {@code ln (f_i / P)} of component {@code i} in the state's gas ({@code vapour}) or liquid. */
    private static double logFugacity(FluidThermodynamics.State state,int i,boolean vapour) {
        double[] amounts=vapour?state.vaporView():state.liquidView();
        var phase=vapour?state.vaporProperties():state.liquidProperties();
        if(phase==null||!(amounts[i]>0))return Double.NEGATIVE_INFINITY;
        double lnf=DeterministicMath.log(amounts[i]/PhaseLayout.sum(amounts))+phase.logFugacityView()[i];
        return vapour?lnf+DeterministicMath.log(state.hydrocarbonPartialPressure()/state.pressure()):lnf;
    }

    /**
     * The diameter of crystal mass freezing from {@code fluid} (owner decision D23 (1) and (2), Brauner 2001's
     * dispersed-drop size): {@code d_dilute max(1, 4.04 (phi / (1 - phi))^0.6)}, {@code phi} the crystal component's volume
     * share of {@code fluid}'s liquids as the crystal ({@code n_i,liquid v_s / V_liquids}; for ice, the free liquid water),
     * capped at {@link #SLUSH_SHARE}, 0 where no liquid holds it (frost from a gas) or where there is no fluid (null). 0.25 mm
     * up to phi = 0.09, 1.01 mm at the slush (a CO2-rich liquid, water freezing on its own). Rounded to
     * {@link SolidInventory#SIZE_DIGITS} significant digits from the exact double, as a merged size is.
     */
    static ParticleSize precipitateDiameter(FluidThermodynamics.State fluid,NetworkCrystals.Crystal crystal) {
        double liquids=fluid==null?0:fluid.liquidVolume()+fluid.waterVolume(),phi=0;
        double held=fluid==null?0:crystal.water()?fluid.waterLiquid():fluid.liquidView()[crystal.component()];
        if(liquids>0)phi=Math.min(SLUSH_SHARE,held*crystal.molarMass()/crystal.material().density()/liquids);
        double factor=phi>0?4.04*DeterministicMath.pow(phi/(1-phi),.6):0;
        return size(DILUTE_DIAMETER*Math.max(1,factor));
    }
    /**
     * The fluid a refresh's crystal mass freezes from (D23 (1)): the vessel's own before the freeze, {@code closed}, its
     * liquid holding the crystal's share the step carried condensed; none (null) where no liquid is left beside the
     * crystals ({@code beside}; null: no fluid), whose liquid was then the crystal's own condensate: frost from a gas,
     * the stand-in the step carried it as or the metastable liquid the closure without crystals condenses it to, and the
     * snow of a let-down. phi is 0 there (D23 (1)).
     */
    private static FluidThermodynamics.State freezing(FluidThermodynamics.State closed,FluidThermodynamics.State beside) {
        return beside!=null&&beside.liquidVolume()+beside.waterVolume()>0?closed:null;
    }
    private static ParticleSize size(double metres) {
        return new ParticleSize(new BigDecimal(metres).round(new MathContext(SolidInventory.SIZE_DIGITS,RoundingMode.HALF_EVEN)).toString());
    }

    /** The hydrocarbon species of {@code totals} (water last) above the engine's pseudo-binary trace share, 1e-8 of the
     * hydrocarbons (P5; the P6b warm start's rule, {@code 4c21104}). */
    static int majorSpecies(double[] totals) {
        double sum=0;for(int i=0;i<totals.length-1;i++)sum+=totals[i];
        int majors=0;for(int i=0;i<totals.length-1;i++)if(totals[i]>1e-8*sum)majors++;
        return majors;
    }

    /** Every crystal the vessel holds melts into its fluid: the fluid's closure at the true energy, the ambient's work booked
     * from the bag volume of {@code closed}. */
    private static Settled melt(FluidThermodynamics model,PassiveNetwork.Inventory inventory,FluidThermodynamics.State closed,double[] totals,
                                double trueEnergy,Runnable checkpoint) {
        double capacity=inventory.wallCapacity(),bag=PhaseLayout.bagVolume(closed,capacity);
        var inert=inventory.solids().withoutCrystals();
        var state=InventoryEquilibrium.solve(model,closed.withSolids(inert),inventory.with(totals,trueEnergy,inert),bag,checkpoint);
        double work=inventory.ambient()*(PhaseLayout.bagVolume(state,capacity)-bag);
        return new Settled(state,inventory.with(totals,trueEnergy-work,inert),-work);
    }

    /** The engine froze every amount of the vessel into its crystals: a dry-solids vessel at the energy that leaves. */
    private static Settled frozen(FluidThermodynamics model,PassiveNetwork.Inventory inventory,FluidThermodynamics.State closed,List<NetworkCrystals.Crystal> all,
                                  double[] held,double[] moles,double[] totals,double trueEnergy,SolidInventory inert,Runnable checkpoint) {
        double[] left=totals.clone();double[] mass=new double[all.size()];
        for(int k=0;k<all.size();k++)if(moles[k]>0){int c=all.get(k).component();mass[k]=left[c]*all.get(k).molarMass();left[c]=0;}
        // Traces of the other species leave a fluid (D21): an answer that froze them too is a crystal step the engine does
        // not answer for this vessel, a typed hold the refresh names the vessel with (review section 5).
        for(double n:left)if(n!=0)throw new FluidThermodynamics.UnsupportedPhases(model.domain().packageId(),Double.NaN,Double.NaN,FluidThermodynamics.UnsupportedPhases.Kind.CRYSTAL_STEP,
                "the crystals would hold every amount of a vessel whose other species need a fluid");
        var solids=stock(inventory.solids(),inert,null,all,held,mass);
        double capacity=inventory.wallCapacity(),bag=PhaseLayout.bagVolume(closed,capacity);
        double stored=trueEnergy;for(int k=0;k<all.size();k++)stored-=mass[k]*all.get(k).material().crystal().energyOffset();
        // A bag holding only solids carries no load: slack at the ambient around the solids' own volume (D54), and the
        // ambient works on it as it shrinks there.
        // A rigid vessel (batch 2026-11-10-rigid-reservoir) keeps its volume: no work.
        double work=PhaseLayout.rigid(capacity)?0:inventory.ambient()*(solids.volume()-bag);
        var dry=inventory.with(left,stored-work,solids);
        var state=InventoryEquilibrium.close(model,model.solidState(closed.temperature(),inventory.ambient(),solids),dry,null,Double.NaN,checkpoint);
        return new Settled(state,dry,-work);
    }

    /** The converged closure {@code solved} of {@code joint} as the vessel's new inventory and state. */
    private static Settled settled(FluidThermodynamics model,PassiveNetwork.Inventory inventory,FluidThermodynamics.State closed,List<NetworkCrystals.Crystal> all,
                                   double[] held,Joint joint,double[] solved,double[] totals,double trueEnergy,double bag,SolidInventory inert,Runnable checkpoint) {
        double[] left=joint.fluidAmounts(solved);double[] mass=new double[all.size()];
        for(var crystal:joint.crystals){int c=crystal.component();mass[all.indexOf(crystal)]=(totals[c]-left[c])*crystal.molarMass();}
        var beside=ConservativeTransport.repartition(model,joint.fluid(solved),left,inventory.rigid());
        var solids=stock(inventory.solids(),inert,freezing(closed,beside),all,held,mass);
        double capacity=inventory.wallCapacity();
        var fluid=beside.withSolids(solids);
        double work=inventory.ambient()*(PhaseLayout.bagVolume(fluid,capacity)-bag);
        double stored=trueEnergy-work;for(int k=0;k<all.size();k++)stored-=mass[k]*all.get(k).material().crystal().energyOffset();
        var settled=inventory.with(left,stored,solids);
        // Restated as every refresh states a vessel, so the next refresh finds it at its fixed point.
        return new Settled(InventoryEquilibrium.solve(model,fluid,settled,Double.NaN,checkpoint),settled,-work);
    }

    /**
     * The stock after the refresh: its inert solids, and each crystal at its new mass {@code mass} (kg): mass that froze
     * enters at {@link #precipitateDiameter} of {@code fluid}, the fluid it froze from ({@link #freezing}; null: none),
     * and is merged into the crystal's population at the stock's mean ({@link SolidInventory#oneSizePerSolid}); mass that
     * melted leaves at the population's size.
     */
    private static SolidInventory stock(SolidInventory before,SolidInventory inert,FluidThermodynamics.State fluid,List<NetworkCrystals.Crystal> all,
                                        double[] held,double[] mass) {
        var populations=new ArrayList<>(inert.populations());
        for(int k=0;k<all.size();k++) {
            var crystal=all.get(k);ParticleSize size=before.size(crystal.material().id());
            if(mass[k]<=held[k]){if(mass[k]>0)populations.add(new SolidInventory.Population(crystal.material(),size,mass[k]));continue;}
            if(held[k]>0)populations.add(new SolidInventory.Population(crystal.material(),size,held[k]));
            populations.add(new SolidInventory.Population(crystal.material(),precipitateDiameter(fluid,crystal),mass[k]-held[k]));
        }
        return new SolidInventory(populations).oneSizePerSolid();
    }

    /**
     * The joint closure ({@link Joint}) as the inner closure of the vessel's pass loop ({@link InventoryEquilibrium#passes},
     * decision D30): each pass lays the fluid out with the phases of its start, the crystals at the engine's amounts;
     * {@link #joint} and {@link #solved} are the last converged closure, the one the loop answers with. The engine's
     * answer states its own crystal, whose volume differs from the record's constant one (CO2-I in LNG at 143.8 K: 1625
     * against 1571 kg/m3), so in a rigid tank full of LNG the engine's answer holds a 0.45 L bubble the record's crystal
     * leaves no room for: the closure started in the answer's layout empties the bubble, the loop's trace fold removes
     * the phase, and the closure is solved again without it.
     */
    private static final class JointClosure implements InventoryEquilibrium.Closure {
        private final FluidThermodynamics model;
        private final List<NetworkCrystals.Crystal> present;
        private final List<Double> seeds;
        private final double[] totals;
        private final double trueEnergy,capacity,ambient,workFrom;
        private final SolidInventory inert;
        private final Runnable checkpoint;
        Joint joint;
        double[] solved;

        JointClosure(FluidThermodynamics model,List<NetworkCrystals.Crystal> present,List<Double> seeds,double[] totals,double trueEnergy,double capacity,double ambient,
                     double workFrom,SolidInventory inert,Runnable checkpoint) {
            this.model=model;this.present=List.copyOf(present);this.seeds=List.copyOf(seeds);this.totals=totals;this.trueEnergy=trueEnergy;
            this.capacity=capacity;this.ambient=ambient;this.workFrom=workFrom;this.inert=inert;this.checkpoint=checkpoint;
        }
        private Joint joint(FluidThermodynamics.State start){return new Joint(model,start,present,seeds,totals,trueEnergy,capacity,ambient,workFrom,inert);}
        @Override public boolean rigid(){return PhaseLayout.rigid(capacity);}
        private double[] solve(Joint joint,FluidThermodynamics.State start) {
            return SparseNewton.solve(joint,joint.start(start,seeds),new SparseNewton.Settings(SparseNewton.ITERATIONS,InventoryEquilibrium.CLOSURE,1e-6,24),checkpoint).variables();
        }
        @Override public FluidThermodynamics.State close(FluidThermodynamics.State start) {
            var closing=joint(start);var x=solve(closing,start);
            joint=closing;solved=x;
            return ConservativeTransport.repartition(model,closing.fluid(x),closing.fluidAmounts(x),rigid());
        }
        @Override public FluidThermodynamics.State iterate(FluidThermodynamics.State start,double[] variables){return joint(start).fluid(variables);}
        /** The joint closure from {@code start}; {@code null} where it stops short or refuses the state (owner decision D40 A:
         * a failed closure proposes nothing). */
        @Override public FluidThermodynamics.State split(FluidThermodynamics.State start) {
            try{var closing=joint(start);var x=solve(closing,start);return ConservativeTransport.repartition(model,closing.fluid(x),closing.fluidAmounts(x),rigid());}
            catch(SparseNewton.Nonconvergence|IllegalArgumentException failed){return null;}
        }
    }

    /**
     * A vessel's closure with its present crystals' amounts as unknowns (owner decision D23): the fluid's own layout
     * ({@link PhaseLayout}, no solid unknowns) and one amount per crystal (moles over the layout's amount scale); the
     * layout's rows (component balances, energy with the wall's work, the wall, phase equilibrium) stated for a vessel
     * holding the inert solids and the crystals at those amounts, the fluid's targets less the crystals' moles and the
     * stored energy's target less their offsets, then one row per crystal: its chemical potential equal to its
     * component's, {@code ln f_i(fluid) - ln f_s = 0} ({@link #drive}). A crystal amount may go below zero in a trial (the
     * crystal is then absent, which the caller reads).
     */
    /** The fluid phase a crystal's row reads its component in: the gas, the liquid, or none (the ice holds all the water). */
    private enum Row {VAPOUR,LIQUID,ALL}
    private static final class Joint implements SparseNewton.Equations {
        private final FluidThermodynamics model;
        private final PhaseLayout layout;
        final List<NetworkCrystals.Crystal> crystals;
        private final double[] totals,crystalScale;
        private final Row[] row;
        private final double trueEnergy,capacity,workFrom;
        private final SolidInventory inert;
        private final SolidInventory.Moments inertMoments;
        /** The layout's steam column where the ice's row reads it ({@link Row#VAPOUR}), else -1. */
        private final int steamRead;
        private final int fluidSize;
        private volatile FluidThermodynamics.Prepared prepared;
        /** The wall's branches of the Jacobian in use (decision A423), as {@link PhaseLayout#wallClosure} keeps them. */
        private PhaseLayout.Wall sweeping,built;

        Joint(FluidThermodynamics model,FluidThermodynamics.State seed,List<NetworkCrystals.Crystal> crystals,List<Double> seeds,double[] totals,
              double trueEnergy,double capacity,double ambient,double workFrom,SolidInventory inert) {
            this.model=model;this.crystals=List.copyOf(crystals);this.totals=totals;this.trueEnergy=trueEnergy;this.capacity=capacity;this.workFrom=workFrom;
            this.inert=inert;inertMoments=inert.moments();
            layout=new PhaseLayout(model,seed,null,null,PhaseLayout.support(model,seed,null,null,false),false).atAmbient(ambient);
            fluidSize=layout.size();crystalScale=new double[crystals.size()];row=new Row[crystals.size()];int steam=-1;
            for(int j=0;j<crystals.size();j++) {
                var crystal=crystals.get(j);
                if(crystal.water()) {
                    // The ice against the free water (WP4): its steam where the layout carries steam, else its liquid (the
                    // melting line), else the vessel's water is all ice (no fluid water for it to exchange with).
                    row[j]=layout.carriesWaterVapor()?Row.VAPOUR:layout.carriesWaterLiquid()?Row.LIQUID:Row.ALL;
                    if(row[j]==Row.VAPOUR)steam=layout.waterVaporVariable();
                } else {
                    var support=layout.support(crystal.component());
                    if(support==PhaseSupport.ABSENT)throw new IllegalArgumentException("Crystal "+crystal.material().id()+" beside a fluid that holds none of its species");
                    row[j]=support.vapor()?Row.VAPOUR:Row.LIQUID;
                }
                crystalScale[j]=Math.max(1e-6,seeds.get(j)/layout.amountScale());
            }
            steamRead=steam;
        }
        double[] start(FluidThermodynamics.State seed,List<Double> seeds) {
            double[] x=new double[size()];System.arraycopy(layout.encode(seed),0,x,0,fluidSize);
            for(int j=0;j<seeds.size();j++)x[fluidSize+j]=seeds.get(j)/layout.amountScale();
            return x;
        }
        double crystalMoles(double[] x,int j){return x[fluidSize+j]*layout.amountScale();}
        /** The amounts the fluid holds at {@code x}: the vessel's totals less its crystals' moles, never below zero. */
        double[] fluidAmounts(double[] x) {
            double[] left=totals.clone();
            for(int j=0;j<crystals.size();j++){int c=crystals.get(j).component();left[c]=Math.max(0,totals[c]-crystalMoles(x,j));}
            return left;
        }
        FluidThermodynamics.State fluid(double[] x){return layout.decode(x,0,prepare(x));}
        @Override public int size(){return fluidSize+crystals.size();}
        @Override public double differenceScale(int column,double value) {
            // The ice's row reads the steam through a logarithm, as the saturation row does (PhaseLayout.LOG_READ_SPAN): 5.6e-12
            // Pa over ice at 111.6 K, 1e-10 of a cold vessel at 150 K, which the bulk floor differenced at its own size.
            if(column==steamRead)return layout.steamLogReadScale(value);
            return column<fluidSize?layout.differenceStepScale(column,value):Math.max(Math.abs(value),crystalScale[column-fluidSize]);
        }
        @Override public double maximumStep(double[] x,double[] direction) {
            double alpha=1;for(int c=0;c<fluidSize;c++)if(layout.totalAmountVariable(c)&&x[c]>0&&direction[c]<0)alpha=Math.min(alpha,.99*x[c]/-direction[c]);
            return alpha;
        }
        @Override public int[][] columnRows(){int[][] rows=new int[size()][size()];for(int[] column:rows)for(int i=0;i<column.length;i++)column[i]=i;return rows;}
        @Override public void linearising(double[] x){built=sweeping=PhaseLayout.wall(trial(fluid(x),x),capacity);}
        @Override public void linearised(){sweeping=null;}
        @Override public boolean linearisationHolds(double[] builtAt,double[] now){return built==null||built.equals(PhaseLayout.wall(trial(fluid(now),now),capacity));}
        @Override public double[] residual(double[] x) {
            var local=prepare(x);var fluid=layout.decode(x,0,local);var state=trial(fluid,x);
            double[] targets=totals.clone();double energy=trueEnergy;
            for(int j=0;j<crystals.size();j++) {
                var crystal=crystals.get(j);double moles=crystalMoles(x,j);
                targets[crystal.component()]-=moles;energy-=moles*crystal.molarMass()*crystal.material().crystal().energyOffset();
            }
            double[] f=new double[size()];
            layout.residual(state,targets,energy,capacity,workFrom,f,0,x,local,sweeping);
            double t=fluid.temperature(),p=fluid.pressure();
            for(int j=0;j<crystals.size();j++) {
                var crystal=crystals.get(j);int c=crystal.component();
                if(crystal.water()) {
                    // The water balance over the vessel's water, its ice included: the fluid's own share may be a trace of
                    // steam (5.6e-12 Pa over ice at 111.6 K), against which the ice's amount is not resolved.
                    int balance=layout.waterBalanceRow();
                    if(balance>=0)f[balance]*=layout.componentScale(c)/Math.max(layout.componentScale(c),totals[c]);
                    if(row[j]==Row.ALL){f[fluidSize+j]=(crystalMoles(x,j)-totals[c])/totals[c];continue;}
                    f[fluidSize+j]=waterLogFugacity(model,fluid,row[j]==Row.VAPOUR)-crystal.logFugacityCoefficient(t,p);
                    continue;
                }
                f[fluidSize+j]=logFugacity(fluid,c,row[j]==Row.VAPOUR)-crystal.logFugacityCoefficient(t,p);
            }
            return f;
        }
        /** The vessel of fluid {@code fluid} holding its inert solids and the crystals at the amounts of {@code x}: a trial only,
         * whose volume, energies and mass carry the crystals (a crystal amount below zero included) and whose solid record
         * the inert solids'. */
        private FluidThermodynamics.State trial(FluidThermodynamics.State fluid,double[] x) {
            var base=inert.empty()?fluid:fluid.withSolidState(inert,inertMoments);
            double t=fluid.temperature(),p=fluid.pressure(),volume=0,energy=0,mass=0;
            for(int j=0;j<crystals.size();j++) {
                var material=crystals.get(j).material();double m=crystalMoles(x,j)*crystals.get(j).molarMass();
                volume+=m/material.density();energy+=m*material.specificInternalEnergy(t);mass+=m;
            }
            return new FluidThermodynamics.State(t,p,base.liquidView(),base.vaporView(),base.waterLiquid(),base.waterVapor(),base.hydrocarbonPartialPressure(),
                    base.waterPartialPressure(),base.volume()+volume,base.enthalpy()+energy+p*volume,base.internalEnergy()+energy,base.mass()+mass,
                    base.liquidVolume(),base.waterVolume(),base.vaporVolume(),base.liquidProperties(),base.vaporProperties(),base.solids(),base.solidMoments(),
                    base.waterCompressibility());
        }
        private FluidThermodynamics.Prepared prepare(double[] x) {
            double temperature=layout.temperature(x,0);var local=prepared;
            if(local==null||local.temperature()!=temperature)prepared=local=model.prepare(temperature);
            return local;
        }
    }
}
