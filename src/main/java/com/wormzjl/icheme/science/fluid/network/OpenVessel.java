package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.GAS;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.WATER;

/**
 * A vessel open to the atmosphere under a floating roof ({@link PassiveNetwork.Inventory#open}; batch
 * 2026-10-21-floating-roof-tanks, owner decisions FR1, FR2 and FR8). The roof floats on the liquid, so the surface stands
 * at the ambient pressure (its wall is never taut, {@link PassiveNetwork.Inventory#wallCapacity}) and the vessel keeps
 * only its liquid:
 * <ul>
 * <li><b>Vent.</b> The gas a step starts with leaves over that step at its start composition and enthalpy, as
 * a phase drawn at its capacity is drawn (decision D11): constant terms in the vessel's balance rows, no unknown. Gas
 * that forms within the step leaves in the next one. A vessel that holds only gas vents it the same way and keeps
 * what arrives (owner rule of 2026-10-21, seed 895); one whose releases take all it holds and that nothing reaches ends
 * the step empty ({@code ConservativeTransport.reconstruct}, decision D54).</li>
 * <li><b>Overflow.</b> The liquid held above the vessel's volume (its fill height) up to the shell top is what an
 * {@link PassiveNetwork.PhasePort#OVERFLOW} port can draw over the step, lighter liquid first.</li>
 * <li><b>Spill.</b> The liquid above the shell top, {@link #FREEBOARD} over the fill height, leaves over the step like
 * the vent, lighter liquid first, with its share of the solids.</li>
 * </ul>
 * The liquids stand stratified: the heavier ({@link FluidThermodynamics#heavierLiquid}) from the bottom, the lighter on
 * it, each with its volume share of the solids ({@link FluidThermodynamics#phaseVolume}). Vent and spill leave the world,
 * booked as boundary transfers of their kinds at the vessel ({@link ConservativeTransport.BoundaryTransfer.Kind}).
 *
 * <p>A batch still's pot (batch 2026-11-09-batch-distiller, W3) is open in the same sense: it stands at the ambient pressure
 * through its column, condenser and vent. Its vent and spill go to the still's head ({@code runtime/fluid/Stills}), not the
 * atmosphere, and its column's {@link Pool.Head} keeps in the pot what the column returns.</p>
 */
public final class OpenVessel {
    private OpenVessel() {}
    /** The shell's height over the fill height, m: the roof's own depth when it stands at the overflow nozzle. */
    public static final double FREEBOARD=1;
    /**
     * The time over which an open vessel gives the world what it holds in excess at a step's start (its gas, its liquid
     * above the shell top), s: a step of {@code dt} releases the share {@code dt / RELEASE_TIME} of it, all of it from this
     * long on. Releases are rates (trace 354/678: releasing the whole start excess over any step is a rate without bound as
     * the step shrinks, which no refinement converges; owner choice of 2026-10-21).
     */
    public static final double RELEASE_TIME=1;
    /**
     * The fastest the vent's gas leaves through the roof, m/s: the vent is capped at this speed times the roof's area (the
     * footprint), in gas volume at the vessel's state (owner direction of 2026-10-21: a release to the world flows at a
     * rate the equipment's size sets). The network's speed limit for every stream (owner decision of 2026-10-19): at 1 m/s
     * the gas a 1 m2 tank's feed lines brought outran its vent and its pocket grew without bound (fuzz seed 678).
     */
    public static final double VENT_SPEED=100;

    /** The vessel's footprint, m2: its volume over its fill height. */
    static double footprint(PassiveNetwork.Inventory inventory){return inventory.volume()/inventory.levelHeight();}
    /** The share of its start gas, {@code gasVolume} m3, an open vessel vents over a step of {@code dt} s: {@code dt /
     * RELEASE_TIME}, no faster than {@link #VENT_SPEED} through its roof, at most all of it. */
    static double ventShare(PassiveNetwork.Inventory inventory,double gasVolume,double dt) {
        return gasVolume>0?Math.min(releaseShare(dt),VENT_SPEED*footprint(inventory)*dt/gasVolume):0;
    }
    /** The share of its start excess an open vessel releases over a step of {@code dt} s (its spill's share): {@code dt /
     * RELEASE_TIME}, at most all of it. */
    static double releaseShare(double dt){return Math.min(1,dt/RELEASE_TIME);}

    /** What one release takes out of its vessel over a step: amounts (mol, water last), energy {@code sum m h} (J),
     * solids and their moments. */
    public record Release(double[] moles,double energyJoule,SolidInventory solids,SolidInventory.Moments moments) {
        public Release{moles=moles.clone();}
        @Override public double[] moles(){return moles.clone();}
        double mass(double[] molecularWeight) {
            double mass=moments.mass();for(int c=0;c<moles.length;c++)mass+=moles[c]*molecularWeight[c];
            return mass;
        }
    }
    /** A vessel's vent and spill over one step, either may be null, and the shares of its start gas and of its liquid above
     * the shell top they take ({@link #ventShare}, {@link #spillShare}). */
    public record Releases(Release vent,Release spill,double ventShare,double spillShare) {
        /** The vent and the spill that are present, in that order. */
        java.util.List<Release> each(){return vent==null?spill==null?java.util.List.of():java.util.List.of(spill):spill==null?java.util.List.of(vent):java.util.List.of(vent,spill);}
    }

    /** The shell's volume, m3: the volume at the fill height raised by {@link #FREEBOARD}. */
    public static double topVolume(PassiveNetwork.Inventory inventory) {
        return inventory.volume()*(inventory.levelHeight()+FREEBOARD)/inventory.levelHeight();
    }
    /** The volume the vessel's condensed phases fill, m3 (the liquids and the solids they carry). */
    static double condensedVolume(FluidThermodynamics.State state) {
        return FluidThermodynamics.liquidStreamVolume(state);
    }
    /** Whether {@code node} is an open vessel, or its rate-solve copy, whose start state holds liquid above its fill
     * height: the condition for its OVERFLOW ports to draw anything. */
    static boolean overflows(PassiveNetwork.Reservoir node) {
        return node.inventory().open()&&FluidThermodynamics.holdsLiquid(node.state())&&condensedVolume(node.state())>node.inventory().volume();
    }
    /** Whether an open vessel has anything to give the atmosphere: gas, or liquid above its fill height. Such a vessel is
     * never at rest ({@link NetworkReduction#atRest}). */
    static boolean releasing(PassiveNetwork.Reservoir node) {
        if(GasBlanket.venting(node))return true;
        if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||!node.inventory().open()||node.empty())return false;
        return FluidThermodynamics.holdsPhase(node.state(),GAS)||overflows(node);
    }

    /** Whether {@code released}, {@code node}'s releases over a step, take all it holds: it has no solids and its vent and
     * spill book out every amount it holds (they are clamped to it, so the test is exact). */
    static boolean releasesAll(PassiveNetwork.Reservoir node,Releases released) {
        if(released==null||!node.inventory().solids().empty())return false;
        var left=node.inventory().moles();
        for(var release:released.each()){var n=release.moles();for(int c=0;c<n.length;c++)left[c]-=n[c];}
        for(double n:left)if(n!=0)return false;
        return true;
    }
    /**
     * The volume of each phase (by phase id, gas none) the stratified liquids of {@code state} hold between the heights
     * whose volumes from the bottom are {@code lower} and {@code upper}, m3: the heavier liquid fills from the bottom, the
     * lighter stands on it.
     */
    static double[] band(FluidThermodynamics model,FluidThermodynamics.State state,double lower,double upper) {
        double[] band=new double[3];if(!(upper>lower))return band;
        int heavy=model.heavierLiquid(state),light=heavy==OIL?WATER:OIL;
        double heavyTop=FluidThermodynamics.holdsPhase(state,heavy)?FluidThermodynamics.phaseVolume(state,heavy):0;
        double lightTop=heavyTop+(FluidThermodynamics.holdsPhase(state,light)?FluidThermodynamics.phaseVolume(state,light):0);
        band[heavy]=Math.max(0,Math.min(upper,heavyTop)-Math.max(lower,0));
        band[light]=Math.max(0,Math.min(upper,lightTop)-Math.max(lower,heavyTop));
        return band;
    }
    /**
     * The mass (kg, by phase id) of each liquid an OVERFLOW port can draw over one step from {@code node}'s start state: the
     * band between its fill height and the shell top. Zeros for a vessel that does not overflow ({@link #overflows}).
     */
    static double[] overflowBand(FluidThermodynamics model,PassiveNetwork.Reservoir node) {
        double[] mass=new double[3];if(!overflows(node))return mass;
        var state=node.state();double held=condensedVolume(state);
        var band=band(model,state,node.inventory().volume(),Math.min(held,topVolume(node.inventory())));
        for(int phase=OIL;phase<=WATER;phase++)if(band[phase]>0)mass[phase]=model.phaseMass(state,phase)*band[phase]/FluidThermodynamics.phaseVolume(state,phase);
        return mass;
    }

    /**
     * What {@code node} gives the atmosphere over a step of {@code dt} s that starts from its state: the {@link #ventShare}
     * of its gas (the vent) and the {@link #spillShare} of its liquid above its shell top (the spill). Null for a node that
     * is not an open vessel or gives nothing. Each component's release is at most what the vessel holds of it, so roundoff
     * between a state's phase split and its inventory never books out more than it holds.
     */
    static Releases releases(FluidThermodynamics model,PassiveNetwork.Reservoir node,double dt,double inflow) {
        if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||node.empty()||PassiveStepSolver.holdsNoFluid(node))return null;
        // A closed vessel releases only through its gas blanketing valve's vent (batch 2026-11-11-gas-blanketing), which also
        // reads the condensed volume its liquid is forecast to gain over the step (GasBlanket.inflows).
        if(!node.inventory().open())return GasBlanket.releases(model,node,dt,inflow);
        var state=node.state();var held=node.inventory().moles();
        double top=topVolume(node.inventory()),liquid=condensedVolume(state);
        double ventShare=FluidThermodynamics.holdsPhase(state,GAS)?ventShare(node.inventory(),state.vaporVolume(),dt):0,spillShare=liquid>top?releaseShare(dt):0;
        Release vent=null,spill=null;
        if(ventShare>0) {
            var n=FluidThermodynamics.phaseMoles(state,GAS);
            for(int c=0;c<n.length;c++){n[c]=Math.min(ventShare*n[c],held[c]);held[c]-=n[c];}
            double energy=ventShare*model.phaseMass(state,GAS)*model.phaseSpecificEnthalpy(state,GAS,null);
            // A still's pot (batch 2026-11-09-batch-distiller, W3): its column passes the share of each component its head
            // planned; the rest it condenses and returns, which stays in the pot as liquid at the head's return enthalpy, so
            // only its gas's energy over that leaves with the release.
            var pool=node.inventory().pool();var head=pool==null?null:pool.head();
            if(head!=null&&head.components()==n.length) {
                double returned=0;
                for(int c=0;c<n.length;c++){double up=head.recovery(c)*n[c],back=n[c]-up;returned+=back;held[c]+=back;n[c]=up;}
                energy-=returned*head.returnEnthalpy();
            }
            vent=new Release(n,energy,SolidInventory.EMPTY,SolidInventory.Moments.ZERO);
        }
        if(spillShare>0) {
            var band=band(model,state,top,liquid);double[] n=new double[held.length];double energy=0;
            var solids=SolidInventory.EMPTY;double solidMass=0,solidVolume=0,solidCapacity=0;
            for(int phase=OIL;phase<=WATER;phase++) {
                if(!(band[phase]>0))continue;
                double part=spillShare*band[phase]/FluidThermodynamics.phaseVolume(state,phase);
                var phaseMoles=FluidThermodynamics.phaseMoles(state,phase);
                for(int c=0;c<n.length;c++)n[c]+=part*phaseMoles[c];
                energy+=part*model.phaseMass(state,phase)*model.phaseSpecificEnthalpy(state,phase,null);
                var moments=FluidThermodynamics.phaseSolidMoments(state,phase);
                if(moments.mass()>0) {
                    solids=solids.plus(state.solids().scale(part*FluidThermodynamics.phaseSolidShare(state,phase)));
                    solidMass+=part*moments.mass();solidVolume+=part*moments.volume();solidCapacity+=part*moments.heatCapacity();
                }
            }
            for(int c=0;c<n.length;c++)n[c]=Math.min(n[c],held[c]);
            spill=new Release(n,energy,solids,new SolidInventory.Moments(solidMass,solidVolume,solidCapacity));
        }
        return vent==null&&spill==null?null:new Releases(vent,spill,ventShare,spillShare);
    }
}
