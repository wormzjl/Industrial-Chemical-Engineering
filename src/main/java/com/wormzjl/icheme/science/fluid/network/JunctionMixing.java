package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.ArrayList;
import java.util.List;

/** All conserved quantities use the same part of the pinned buffer. Only the moving part exchanges contents. */
final class JunctionMixing {
    private static final double STREAM_LAG_FRACTION = .00025;
    private static final double STAGNANT_TURNOVER = 1e-7;
    private static final double FRONT_COMPOSITION_FRACTION = .001;
    private static final double FRONT_TEMPERATURE_KELVIN = .5;

    private final PassiveNetwork full;
    private final PassiveNetwork.JunctionReserve[] reserves;
    final PassiveNetwork working;

    private JunctionMixing(PassiveNetwork full, PassiveNetwork working, PassiveNetwork.JunctionReserve[] reserves) {
        this.full=full;
        this.working=working;
        this.reserves=reserves;
    }

    static JunctionMixing begin(PassiveNetwork graph, FluidThermodynamics model) {
        PassiveNetwork.JunctionReserve[] reserves = null;
        ArrayList<PassiveNetwork.Reservoir> nodes = null;
        PassiveNetwork.JunctionReserve extracted = null;
        for (int i=0;i<graph.reservoirs().size();i++) {
            var node=graph.reservoirs().get(i);
            if(!node.junction())continue;
            var whole=node.inventory();
            var reserve=whole.reserve();
            if(reserve==null)continue;
            whole=withCarrier(whole,node.state(),model);
            reserve=whole.reserve();
            var moving=whole.movingMoles();
            if(nodes==null) {
                reserves=new PassiveNetwork.JunctionReserve[graph.reservoirs().size()];
                nodes=new ArrayList<>(graph.reservoirs());
                // The working inventory has already been partitioned. Its entire balance circulates.
                extracted=new PassiveNetwork.JunctionReserve(new double[whole.moles().length],0);
            }
            PassiveNetwork.Inventory active;
            if(PhaseLayout.sum(moving)==0) {
                // A saved reserve may contain the entire fluid. Repartition the owned stock instead
                // of presenting an empty working inventory that the junction sizing would mint again.
                active=whole.withReserve(null);
            } else {
                reserves[i]=reserve;
                active=whole.withReserve(extracted).with(moving,whole.movingEnergy(),whole.solids());
            }
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),active));
        }
        return nodes==null?null:new JunctionMixing(graph,new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers()),reserves);
    }

    /** Reduce a backed parcel only when its observed uptake would delay a front at the measured
     * throughput. Retire part of the initial fluid before solving the same step again. */
    JunctionMixing resolveFront(PassiveStepSolver.Result trial,double remainingSeconds,double[] previousDelivery,FluidThermodynamics model) {
        var delivered=new double[reserves.length];
        addDelivery(working,trial.pipeTransfers(),delivered);
        ArrayList<PassiveNetwork.Reservoir> nodes=null;
        for(int i=0;i<reserves.length;i++) {
            if(reserves[i]==null || !(delivered[i]>0))continue;
            var node=working.reservoirs().get(i);
            var stock=node.inventory();
            var before=stock.moles();
            double mass=mass(before,model);
            double throughput=windowDelivery(previousDelivery==null?0:previousDelivery[i],delivered[i],trial.deltaTime(),remainingSeconds);
            var state=node.state();
            double targetMass=Math.max(STREAM_LAG_FRACTION*throughput,carrierMass(stock,state));
            if(mass<=FRONT_COMPOSITION_FRACTION*throughput||mass<=targetMass)continue;
            double solidHeat=stock.solids().moments().enthalpy(state.temperature(),state.pressure());
            double fluidEnergy=stock.internalEnergy()-solidHeat;
            double streamMass=state.mass()-state.solidMoments().mass();
            if(!(streamMass>0))continue;
            // An older stock may still be relaxing toward the published stream. Its uptake does not
            // measure a new front until these two enthalpies agree.
            double stockEnthalpy=fluidEnergy/mass;
            double streamEnthalpy=state.fluidEnthalpy()/streamMass;
            if(Math.abs(streamEnthalpy-stockEnthalpy)>1e-6*Math.max(1e5,Math.abs(stockEnthalpy)))continue;
            boolean temperatureFront=Math.abs(state.temperature()-trial.states().get(i).temperature())*mass>FRONT_TEMPERATURE_KELVIN*throughput;
            if(!temperatureFront&&!delaysComposition(trial,i,before,mass,throughput,model))continue;
            double share=targetMass/mass;
            var reserve=reserves[i];
            var total=full.reservoirs().get(i);
            var retained=total.inventory().moles();
            // Subtract from the pinned total so a component of the reserve never exceeds its backing.
            double resolvedMass=0,resolution=0;
            for(int c=0;c<retained.length;c++) {
                double circulating=share*before[c];
                double molecularWeight=model.molecularWeight(c);
                resolvedMass+=circulating*molecularWeight;
                resolution+=Math.ulp(retained[c])*molecularWeight;
                retained[c]-=circulating;
            }
            if(resolvedMass<=resolution/PassiveStepSolver.NEWTON_TOLERANCE)continue;
            var newReserve=new PassiveNetwork.JunctionReserve(retained,reserve.enthalpy()+(1-share)*fluidEnergy);
            if(nodes==null)nodes=new ArrayList<>(full.reservoirs());
            nodes.set(i,new PassiveNetwork.Reservoir(total.id(),total.elevation(),total.state(),total.kind(),total.inventory().withReserve(newReserve)));
        }
        return nodes==null?this:begin(new PassiveNetwork(nodes,full.pipes(),full.scheduledTransfers()),model);
    }

    record Precision(int node,double delivered,double active,FluidThermodynamics.State settled) {}

    /** Repartition backed fluid only where putting part of the reserve back into circulation keeps the front. */
    JunctionMixing resolvePrecision(List<Precision> requests,FluidThermodynamics model,Runnable checkpoint) {
        ArrayList<PassiveNetwork.Reservoir> nodes=null;
        for(var request:requests) {
            int i=request.node();
            var reserve=reserves[i];
            if(reserve==null)continue;
            var node=full.reservoirs().get(i);
            var stock=node.inventory();
            double active=mass(working.reservoirs().get(i).inventory().moles(),model);
            double extra=request.active()-active;
            if(!(extra>0))continue;
            var parked=reserve.moles();
            double parkedMass=mass(parked,model);
            if(!(parkedMass>=extra))continue;
            double mixed=request.delivered()+active;
            // The released fraction of this substep's blend bounds the effect on every constituent against
            // its actual reserve-to-stream composition difference. The substep is the shortest delivery window.
            if(extra/(mixed+extra)>FRONT_COMPOSITION_FRACTION)continue;
            var state=request.settled();
            var moles=PhaseLayout.totalAmounts(state);
            double released=extra/parkedMass;
            double scale=mixed/state.mass();
            for(int c=0;c<moles.length;c++)moles[c]=scale*moles[c]+released*parked[c];
            double enthalpy=scale*state.enthalpy()+released*reserve.enthalpy();
            FluidThermodynamics.State shifted;
            try{shifted=model.equilibriumPH(state.temperature(),state.pressure(),enthalpy,moles,checkpoint);}
            catch(IllegalArgumentException refused){continue;}
            if(shifted==null||Math.abs(state.temperature()-shifted.temperature())>FRONT_TEMPERATURE_KELVIN)continue;
            double stays=1-released;
            for(int c=0;c<parked.length;c++)parked[c]*=stays;
            var newReserve=new PassiveNetwork.JunctionReserve(parked,reserve.enthalpy()*stays);
            if(nodes==null)nodes=new ArrayList<>(full.reservoirs());
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),stock.withReserve(newReserve)));
        }
        return nodes==null?this:begin(new PassiveNetwork(nodes,full.pipes(),full.scheduledTransfers()),model);
    }

    /** Check the new step's actual transfer in case a different flow branch weakened the forecast delivery. */
    boolean precisionKeepsFront(JunctionMixing resolved,List<Precision> requests,PassiveStepSolver.Result trial,
                                FluidThermodynamics model) {
        var delivered=new double[reserves.length];
        addDelivery(resolved.working,trial.pipeTransfers(),delivered);
        for(var request:requests) {
            int i=request.node();
            var old=reserves[i];
            var next=resolved.reserves[i];
            if(old==null||old==next)continue;
            var parked=old.moles();
            double parkedMass=mass(parked,model);
            double released=parkedMass-mass(next.moles(),model);
            double active=mass(resolved.working.reservoirs().get(i).inventory().moles(),model);
            if(!(delivered[i]>0)||released/(delivered[i]+active)>FRONT_COMPOSITION_FRACTION)return false;
            var prior=request.settled();
            var now=trial.states().get(i);
            if(now.solidMoments().mass()>0||Math.abs(now.temperature()-prior.temperature())>FRONT_TEMPERATURE_KELVIN)return false;
            var before=PhaseLayout.totalAmounts(prior);
            var after=PhaseLayout.totalAmounts(now);
            for(int c=0;c<before.length;c++) {
                double mw=model.molecularWeight(c);
                double oldShare=before[c]*mw/prior.mass(),newShare=after[c]*mw/now.mass(),reserveShare=parked[c]*mw/parkedMass;
                double limit=FRONT_COMPOSITION_FRACTION*Math.abs(reserveShare-oldShare);
                double roundoff=8*Math.max(Math.ulp(oldShare),Math.ulp(newShare));
                if(Math.abs(newShare-oldShare)>Math.max(roundoff,limit))return false;
            }
        }
        return true;
    }

    /** Accepted fluid mass plus the trial's inlet rate projected over the remaining window. */
    static double windowDelivery(double previouslyDelivered,double trialDelivery,double trialSeconds,double remainingSeconds) {
        return previouslyDelivered+trialDelivery*(Math.max(trialSeconds,remainingSeconds)/trialSeconds);
    }

    /** Constituent uptake by the circulating stock versus the measured range of its inlet concentrations. */
    private boolean delaysComposition(PassiveStepSolver.Result trial,int node,double[] before,double mass,double throughput,FluidThermodynamics model) {
        var after=trial.inventories().get(node).moles();
        var low=new double[before.length];
        for(int c=0;c<before.length;c++)low[c]=before[c]*model.molecularWeight(c)/mass;
        double[] high=low.clone();
        for(int e=0;e<working.pipes().size();e++) {
            var pipe=working.pipes().get(e);
            var transfer=trial.pipeTransfers().get(e);
            var inflow=pipe.second()==node?transfer.forward():pipe.first()==node?transfer.reverse():null;
            if(inflow==null)continue;
            double fluid=fluidMass(inflow);
            if(!(fluid>0))continue;
            var amounts=inflow.componentMoles();
            for(int c=0;c<before.length;c++) {
                double concentration=amounts[c]*model.molecularWeight(c)/fluid;
                low[c]=Math.min(low[c],concentration);
                high[c]=Math.max(high[c],concentration);
            }
        }
        for(int c=0;c<before.length;c++) {
            double uptake=Math.abs(after[c]-before[c])*model.molecularWeight(c);
            double limit=FRONT_COMPOSITION_FRACTION*(high[c]-low[c])*throughput;
            if(uptake>Math.max(limit,PassiveStepSolver.NEWTON_TOLERANCE*mass))return true;
        }
        return false;
    }

    static void addDelivery(PassiveNetwork graph,List<PipeTransfer> transfers,double[] delivery) {
        for(int e=0;e<graph.pipes().size();e++) {
            var pipe=graph.pipes().get(e);
            var flow=transfers.get(e);
            delivery[pipe.second()]+=fluidMass(flow.forward());
            delivery[pipe.first()]+=fluidMass(flow.reverse());
        }
    }

    private static double fluidMass(PipeTransfer.Stream stream) {
        return Math.max(0,stream.massKg()-stream.solids().massKg());
    }

    /**
     * Retain enough of a moving slurry's carrier to support its owned solids at the last observed
     * fluid-to-solid ratio. The released fluid keeps the reserve's composition and specific enthalpy.
     */
    static PassiveNetwork.Inventory withCarrier(PassiveNetwork.Inventory stock,FluidThermodynamics.State stream,FluidThermodynamics model) {
        var reserve=stock.reserve();
        if(reserve==null||stock.solids().empty())return stock;
        double need=carrierMass(stock,stream)-mass(stock.movingMoles(),model);
        if(!(need>0))return stock;
        var retained=reserve.moles();
        double available=mass(retained,model);
        if(!(available>0))return stock;
        double left=Math.max(0,1-need/available);
        for(int c=0;c<retained.length;c++)retained[c]*=left;
        return stock.withReserve(new PassiveNetwork.JunctionReserve(retained,reserve.enthalpy()*left));
    }

    private static double carrierMass(PassiveNetwork.Inventory stock,FluidThermodynamics.State stream) {
        double solids=stock.solids().massKg(),streamSolids=stream.solidMoments().mass();
        double streamFluid=stream.mass()-streamSolids;
        return solids>0&&streamSolids>0&&streamFluid>0?solids*(streamFluid/streamSolids):0;
    }

    private static double mass(double[] moles,FluidThermodynamics model) {
        double total=0;
        for(int c=0;c<moles.length;c++)total+=moles[c]*model.molecularWeight(c);
        return total;
    }

    List<PassiveNetwork.Inventory> book(List<PassiveNetwork.Inventory> inventories,boolean frozen) {
        var combined=new ArrayList<>(inventories);
        for(int i=0;i<reserves.length;i++) {
            var total=full.reservoirs().get(i).inventory();
            if(frozen&&total.reserve()!=null) {
                // A rate solve can borrow carrier for its numerical state, but it never partitions the ledger.
                combined.set(i,total);
                continue;
            }
            var reserve=reserves[i];
            if(reserve==null)continue;
            var active=inventories.get(i);
            var amounts=reserve.moles();
            var moving=active.moles();
            for(int c=0;c<amounts.length;c++)amounts[c]+=moving[c];
            combined.set(i,total.withReserve(reserve).with(amounts,reserve.enthalpy()+active.internalEnergy(),active.solids()));
        }
        return combined;
    }

    static double share(PassiveNetwork.Inventory stock, double stockMass, double inflow, double seconds, SimulationTime time) {
        if (stock.reserve()!=null) return 1;
        // For resolved transport no more than one part in 4,000 of the mixture comes from the old stock.
        double turnover = seconds*inflow/stockMass;
        // Recover the positive, fully anchored row continuously for stalled and reversing connections.
        double stagnant = STAGNANT_TURNOVER/(STAGNANT_TURNOVER+turnover);
        // Size the moving parcel on the mint's time scale. The integrator's step size can change
        // by orders of magnitude without changing the amount needed to transport a front.
        double transport = inflow*PassiveStepSolver.holdupTau(time)/stockMass;
        return Math.min(1, Math.max(stagnant, STREAM_LAG_FRACTION*transport));
    }
}
