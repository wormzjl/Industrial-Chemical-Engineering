package com.wormzjl.icheme.science.fluid.transport;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

/** Outlet-specific transport eligibility. A bulk end requests MIXED, or GAS on a donor holding solids but no liquid,
 * whose bulk end draws its gas (owner decision D33: solids ride the liquid, {@code FluidThermodynamics.bulkDrawsGas});
 * a phase port requests the outlet of the phase it draws first ({@code SolidEventIntegrator}; decision D11 of the
 * phase-ports batch, the bottom port a decant): GAS, ORGANIC_LIQUID or WATER. So no outlet drawing solids lacks a
 * liquid to carry them, short of a vessel holding solids and no fluid at all (a dry bed, which does not flow). */
public final class SolidMobility {
    public enum Outlet { MIXED, ORGANIC_LIQUID, WATER, GAS }
    /**
     * {@code POPULATION_LIMIT} is the one reason that is not a property of the donor: a conserved
     * stock holds at most {@link SolidInventory#MAXIMUM_POPULATIONS} distinct material/size
     * populations, and a connection whose delivery would push what receives it past that has to
     * stop carrying rather than let the reconstruction refuse a step no smaller step can make work.
     */
    public enum Reason { MOBILE, IMMOBILE_LIQUID, PACKED_SLURRY, PARTICLE_TOO_LARGE, DEPOSITION, FILTER_CLOGGED, POPULATION_LIMIT }
    public record Check(Reason reason, double velocity, double minimumVelocity) {
        public boolean allowed(){return reason==Reason.MOBILE;}
    }
    private SolidMobility() {}
    public static boolean immobileLiquid(FluidThermodynamics model,FluidThermodynamics.State state) {
        return immobile(model,state,FluidThermodynamics.OIL)||immobile(model,state,FluidThermodynamics.AQUEOUS);
    }
    /** Whether the liquid labelled {@code label} is held and immobile (W3b: either liquid, by its own viscosity). */
    private static boolean immobile(FluidThermodynamics model,FluidThermodynamics.State state,int label) {
        return state.labelVolume(label)>0&&SlurryTransport.immobile(model.viscosity.liquid(state.temperature(),state.labelAmountsView(label)).pascalSeconds(),model.solidSettings.immobileViscosity());
    }
    /** Whether a connection of {@code graph} can close for its solids at the interval's t0: a filter, a vessel holding solids
     * or an immobile liquid, or a feed of solids. {@code SolidEventIntegrator} reads the island's closures at t0 only then; it
     * was also the approximate fallback's refusal until owner decision D37 (S2) removed that mode. */
    public static boolean closable(FluidThermodynamics model,PassiveNetwork graph) {
        return graph.pipes().stream().anyMatch(p->p.filter()!=null)
                ||graph.reservoirs().stream().anyMatch(n->n.state().solidMoments().mass()>0||immobileLiquid(model,n.state()))
                ||graph.scheduledTransfers().stream().anyMatch(t->t instanceof com.wormzjl.icheme.science.fluid.network.ScheduledTransfer.Injection in&&!in.solidsPerSecond().empty());
    }
    public static boolean monitored(PassiveNetwork graph) {
        return graph.pipes().stream().anyMatch(p->p.filter()!=null)||graph.reservoirs().stream().anyMatch(n->n.state().solidMoments().mass()>0||n.state().labelVolume(FluidThermodynamics.OIL)>0)
                ||graph.scheduledTransfers().stream().anyMatch(t->t instanceof com.wormzjl.icheme.science.fluid.network.ScheduledTransfer.Injection in&&!in.solidsPerSecond().empty());
    }
    /**
     * Everything {@link #check} decides from the donor alone: the two carrier-viscosity tests,
     * whether any of its solids is large enough to matter for blockage, the largest of their sizes
     * and the largest suspension velocity they ask for.
     *
     * <p>One size per solid (owner decision D23, amended 2026-10-15). A solid's populations are read as one: its total
     * volume decides whether it matters for blockage, and its size is the stock's size of it
     * ({@link SolidInventory#size}, the mass-weighted mean diameter), the size the next refresh merges them into. So the
     * outlet follows where the mass is, and a merge never changes an outlet decision: 99 % of a solid at 0.25 mm with 1 % at
     * 1 mm is a 0.26 mm stock, not a 1 mm one.</p>
     *
     * <p>A stage guard asks the same donor about every connection leaving it, in both directions,
     * several times per step. Every one of those asked the carrier viscosities again - two
     * correlation evaluations for a donor that may hold no particles at all - and evaluated the
     * carrier density and mixture viscosity once per active population although neither depends on
     * the population. Preparing the donor once per node per stage removes both without moving a
     * single decision: what stays per connection is the liquid velocity, the bore, and whether the
     * connection carries a filter.
     */
    public record Donor(boolean immobileOrganic, boolean immobileWater, boolean active,
                        double liquidVolume, double solidVolume, double largestDiameter, double requiredVelocity) {}
    /** {@link Donor} for one state. A donor with no solid above the trace volume fraction
     * costs the two carrier tests and nothing else: no deposition velocity, no mixture viscosity. */
    public static Donor donor(FluidThermodynamics model, FluidThermodynamics.State state) {
        boolean immobileOrganic=immobile(model,state,FluidThermodynamics.OIL),immobileWater=immobile(model,state,FluidThermodynamics.AQUEOUS);
        double liquidVolume=state.condensedVolume();
        boolean active=false;double largest=0,required=0,carrierDensity=0,carrierViscosity=0;
        var solids=state.solids();var populations=solids.populations();
        // A solid's populations are adjacent in key order (material first).
        for(int first=0,next;first<populations.size();first=next) {
            var material=populations.get(first).material();double volume=0;
            for(next=first;next<populations.size()&&populations.get(next).material().id().equals(material.id());next++)volume+=populations.get(next).volume();
            if(!SlurryTransport.activeForBlockage(volume,state.volume(),model.solidSettings.traceVolumeFraction()))continue;
            double diameter=(next==first+1?populations.get(first).size():solids.size(material.id())).diameterMetres();
            if(!active&&liquidVolume>0) {
                double liquidMass=model.slotMass(state,FluidThermodynamics.State.LIQUID)+model.slotMass(state,FluidThermodynamics.State.AQUEOUS);
                double mu=0;
                for(int slot:new int[]{FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS})
                    if(state.slotVolume(slot)>0)mu+=state.slotVolume(slot)*model.viscosity.liquid(state.temperature(),state.amountsView(slot)).pascalSeconds();
                carrierDensity=liquidMass/liquidVolume;carrierViscosity=mu/liquidVolume;
            }
            active=true;
            largest=Math.max(largest,diameter);
            if(liquidVolume>0)required=Math.max(required,SlurryTransport.depositionVelocity(diameter,
                    material.density(),carrierDensity,carrierViscosity,model.solidSettings.suspensionMultiplier()));
        }
        return new Donor(immobileOrganic,immobileWater,active,liquidVolume,state.solidMoments().volume(),largest,required);
    }
    /**
     * Whether a connection drawing {@code massFlow} from {@code donor} keeps its solids moving. {@code prepared} must be
     * {@link #donor} of this same state. The connection draws {@code drawnMass} kilograms of stream per the donor's whole
     * liquid and solid content: the donor's own mass for a bulk withdrawal, and for a phase port drawing one liquid that
     * liquid's stream mass over its share of the liquid (it carries its volume share of the donor's liquid and solids).
     * The liquid velocity is {@code |q| / drawnMass * liquidVolume / area}. The largest active solid's size decides the bore
     * refusal and the largest suspension velocity the deposition test: the original walked the populations in order and
     * refused on the first one too wide for the bore, which reports no suspension velocity at all, so taking the largest
     * refuses on exactly the same donors and reports the same velocity everywhere it is read. A slurry whose solids fill
     * {@link SlurryTransport#SLUSH_FRACTION} of its condensed volume is a slush and does not flow (owner decision D23).
     */
    public static Check check(Donor prepared, FluidThermodynamics.State donor,
                              PassiveNetwork.Pipe pipe, double massFlow, Outlet outlet, double drawnMass) {
        if(outlet==Outlet.GAS)return new Check(Reason.MOBILE,0,0);
        if((outlet==Outlet.MIXED||outlet==Outlet.ORGANIC_LIQUID)&&prepared.immobileOrganic)return new Check(Reason.IMMOBILE_LIQUID,0,0);
        if((outlet==Outlet.MIXED||outlet==Outlet.WATER)&&prepared.immobileWater)return new Check(Reason.IMMOBILE_LIQUID,0,0);
        double liquidVolume=prepared.liquidVolume;
        double velocity=Math.abs(massFlow)/drawnMass*liquidVolume/pipe.maximumArea();
        if(prepared.active&&prepared.largestDiameter>=pipe.minimumDiameter())return new Check(Reason.PARTICLE_TOO_LARGE,velocity,0);
        double required=prepared.requiredVelocity;
        if(prepared.active&&prepared.solidVolume/(liquidVolume+prepared.solidVolume)>=SlurryTransport.SLUSH_FRACTION)
            return new Check(Reason.PACKED_SLURRY,velocity,required);
        return new Check(pipe.filter()==null&&prepared.active&&velocity<=required?Reason.DEPOSITION:Reason.MOBILE,velocity,required);
    }
}