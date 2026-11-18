package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import java.util.ArrayList;
import java.util.List;

/** All conserved quantities use the same part of the pinned buffer. Only the moving part exchanges contents. */
final class JunctionMixing {
    // Keep at least one percent of the lifetime stock available to future phase transitions.
    static final double MINIMUM_CIRCULATING_SHARE = .01;
    private static final double STREAM_LAG_FRACTION = .00025;
    private static final double STAGNANT_TURNOVER = 1e-7;

    private final PassiveNetwork full;
    private final PassiveNetwork.JunctionReserve[] reserves;
    final PassiveNetwork working;

    private JunctionMixing(PassiveNetwork full, PassiveNetwork working, PassiveNetwork.JunctionReserve[] reserves) {
        this.full=full;
        this.working=working;
        this.reserves=reserves;
    }

    static JunctionMixing begin(PassiveNetwork graph) {
        PassiveNetwork.JunctionReserve[] reserves = null;
        ArrayList<PassiveNetwork.Reservoir> nodes = null;
        for (int i=0;i<graph.reservoirs().size();i++) {
            var node=graph.reservoirs().get(i);
            if(!node.junction())continue;
            var whole=node.inventory();
            var reserve=whole.reserve();
            if(reserve==null)continue;
            if(nodes==null) {
                reserves=new PassiveNetwork.JunctionReserve[graph.reservoirs().size()];
                nodes=new ArrayList<>(graph.reservoirs());
            }
            reserves[i]=reserve;
            var active=whole.withReserve(null).with(whole.movingMoles(),whole.movingEnergy(),whole.solids());
            nodes.set(i,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),active));
        }
        return nodes==null?null:new JunctionMixing(graph,new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers()),reserves);
    }

    List<PassiveNetwork.Inventory> book(List<PassiveNetwork.Inventory> inventories) {
        var combined=new ArrayList<>(inventories);
        for(int i=0;i<reserves.length;i++) {
            var reserve=reserves[i];
            if(reserve==null)continue;
            var active=inventories.get(i);
            var amounts=reserve.moles();
            var moving=active.moles();
            for(int c=0;c<amounts.length;c++)amounts[c]+=moving[c];
            var total=full.reservoirs().get(i).inventory();
            combined.set(i,total.with(amounts,reserve.enthalpy()+active.internalEnergy(),active.solids()));
        }
        return combined;
    }

    static double share(double stockMass, double inflow, double seconds, SimulationTime time) {
        // For resolved transport no more than one part in 4,000 of the mixture comes from the old stock.
        double turnover = seconds*inflow/stockMass;
        // Recover the positive, fully anchored row continuously for stalled and reversing connections.
        double stagnant = STAGNANT_TURNOVER/(STAGNANT_TURNOVER+turnover);
        // Below the mint's time scale refinement must approach the old state, rather than
        // demanding the same discontinuous phase change again at every smaller step size.
        double transport = inflow*Math.max(seconds, PassiveStepSolver.holdupTau(time))/stockMass;
        return Math.min(1, Math.max(stagnant, STREAM_LAG_FRACTION*transport));
    }
}
