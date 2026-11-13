package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.ControlConstraints;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.EAST;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Code base review R2: a view's index of its island answers every lookup as the scan it replaces does: a node's and a pipe's
 * position by identity, a node's pipes in order, the pipe histories by pipe, the HEAT bookings by node (bit for bit, summed
 * in booking order) and the control constraint sets.
 */
class ViewIndexTest {
    @Test void theIndexAnswersAsTheScans() {
        var w=new EventPathWorld();
        w.place(0,64,0,Kind.GENERATOR,EAST,FluidDeviceSpec.nitrogen(w.catalog),.05);
        w.place(1,64,0,Kind.PIPE,EAST,.05);w.place(2,64,0,Kind.PIPE,EAST,.05);
        long sink=w.place(3,64,0,Kind.VOID,EAST,FluidDeviceSpec.nitrogen(w.catalog),.05);
        w.runTo(400);
        var snapshot=w.coordinator.presentation(w.registry.owner(sink));var graph=snapshot.graph();var result=snapshot.lastResult().orElseThrow();
        var index=new FluidWorldAuthority.ViewIndex(snapshot);
        for(int n=0;n<graph.reservoirs().size();n++) {
            long id=graph.reservoirs().get(n).id();int found=-1;
            for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id){found=i;break;}
            assertEquals(found,(int)index.nodes.get(id),"node "+id);
            var touching=new ArrayList<Integer>();for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).first()==n||graph.pipes().get(i).second()==n)touching.add(i);
            assertEquals(touching,index.incident(n),"pipes of node "+id);
            double joule=0;for(var b:result.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT&&b.nodeId()==id)joule+=b.totalEnergyJoule();
            assertEquals(Double.doubleToLongBits(joule),Double.doubleToLongBits(index.heat(id)),"heat of node "+id);
        }
        assertEquals(List.of(),index.incident(-1));
        for(int p=0;p<graph.pipes().size();p++) {
            long id=graph.pipes().get(p).id();assertEquals(p,(int)index.pipes.get(id),"pipe "+id);
            var history=new ArrayList<PipeTransfer>();for(var t:result.pipeTransfers())if(t.pipeId()==id)history.add(t);
            assertEquals(history,index.transfers.getOrDefault(id,List.of()),"history of pipe "+id);
        }
        assertEquals(ControlConstraints.dependent(graph),index.dependent());
        assertEquals(ControlConstraints.standing(graph),index.standing());
    }
}
