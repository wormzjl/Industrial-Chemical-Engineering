package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.Blueprint;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.AirCooled;
import com.wormzjl.icheme.science.fluid.network.HeatLaw;
import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What a heat unit's screen shows beyond its side's own readings (batch 2026-10-27-heat-exchanger-design,
 * HEAT_GUI_PLAN.md section 1, fluid-16): the actuator its control moves and that actuator's effort, the controller's
 * limit, the stream at every inlet and outlet of the unit (both sides), and an exchanger's control lines part by part.
 * Presentation reads of the islands as last committed, made where the side's view is built at a bucket: nothing here
 * solves, applies an input or advances time.
 */
final class HeatPresentation {
    private HeatPresentation() {}

    /** The authority's reads this needs, each a cached presentation read. */
    interface Source {
        @Nullable WorldTopologyLedger.Registration registration(long id);
        /** The island device {@code device} belongs to, as last presented, or null for none. */
        @Nullable IslandCoordinator.Snapshot island(long device);
        @Nullable FluidWorldAuthority.Lines lines(long side);
        @Nullable WorldTopologyLedger.Registration at(PhysicalFluidTopology.Position position);
        /** The view of a device on an exchanger's lines (a tee, a valve, a metered pipe), for the stream at it. */
        FluidView view(long device);
        /** The menu axis' molecular weights, kg/mol. */
        double[] weights();
        /** The pressure at port {@code port} of {@code node}: its state's, plus the condensed head at a bottom port. */
        double portPressure(PassiveNetwork.Reservoir node,PassiveNetwork.PhasePort port);
    }

    /** {@code heat}, the view of side {@code id}, with its actuator, effort and limit, the unit's ports and its lines. */
    static FluidView.Heat complete(FluidView.Heat heat,long id,HeatUnit unit,@Nullable HeatLaw law,Source source) {
        var ports=new ArrayList<FluidView.Port>(ports(id,unit,source));
        var partner=unit.partner()==0?null:source.registration(unit.partner());
        if(partner!=null&&partner.device().heat()!=null)
            for(var p:ports(unit.partner(),partner.device().heat(),source))if(ports.stream().noneMatch(q->q.role().equals(p.role())))ports.add(p);
        var lines=lines(id,unit,partner==null?null:partner.device().heat(),source);
        String limit=law instanceof HeatSide side?side.limit().name():law instanceof AirCooled cooled?cooled.limit().name():"NONE";
        String actuator;Double effort;
        var reported=source.lines(id);boolean complete=reported!=null&&reported.status()==FluidWorldAuthority.Lines.Status.COMPLETE;
        switch(unit.unit()) {
            case FIRED_HEATER->{actuator="burners";effort=heat.maximumWatt()!=null&&heat.maximumWatt()>0?Math.clamp(Math.abs(heat.heatWatt())/heat.maximumWatt(),0,1):null;}
            // The louvers and fans: one effort, the air's share of full fans (the owner's test of 2026-10-28).
            case AIR_COOLER->{actuator="louvers";effort=law instanceof AirCooled cooled?cooled.draught():heat.fans();}
            default->{
                if(unit.pool()){actuator="none";effort=null;}
                else if(unit.control().mode()==HeatUnit.Mode.RATIO){actuator=complete?"feed_valve":"none";effort=null;}
                else if(complete&&unit.lines()==ExchangerBlueprint.Lines.BYPASS) {
                    // The real bypass valve: the share of the side's feed it passes around the unit.
                    actuator="bypass_valve";
                    var valve=lines==null?null:lines.part(ExchangerBlueprint.bypassValve(unit.side()).part());
                    var inlet=ports.isEmpty()?null:ports.getFirst();
                    double bypassed=valve==null||valve.massFlow()==null?0:Math.abs(valve.massFlow()),through=inlet==null?0:Math.abs(inlet.massFlow());
                    effort=bypassed+through>0?bypassed/(bypassed+through):null;
                }
                else{actuator="bypass";effort=heat.bypass();}
            }
        }
        return heat.with(actuator,effort,limit,ports,lines);
    }

    /** The nozzle roles of a side's inlet and outlet. */
    static String[] roles(HeatSides.Side side) {
        return switch(side) {
            case TUBE->new String[]{"TUBE_INLET","TUBE_OUTLET"};
            case SHELL->new String[]{"SHELL_INLET","SHELL_OUTLET"};
            case PROCESS->new String[]{"PROCESS_INLET","PROCESS_OUTLET"};
            case CONVECTION->new String[]{"CONVECTION_INLET","CONVECTION_OUTLET"};
            case RADIANT->new String[]{"RADIANT_INLET","RADIANT_OUTLET"};
        };
    }

    /**
     * A side's two ports: its inlet node (the nozzle's cell) and its outlet junction, both at the flow of its own pipe; a
     * kettle's shell, its feed, vapour and liquid connections. Each port's phases are its node's holdup scaled to that flow:
     * at steady state a junction holds what passes it.
     */
    private static List<FluidView.Port> ports(long id,HeatUnit unit,Source source) {
        int components=source.weights().length;
        var island=source.island(id);
        if(unit.pool())return island==null?List.of():poolPorts(id,island,source);
        var roles=roles(unit.side());
        if(island==null)return List.of(empty(roles[0],0,components),empty(roles[1],0,components));
        var graph=island.graph();int in=-1,pipe=-1;
        for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)in=i;
        for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).id()==PhysicalFluidTopology.sideIdentity(id))pipe=i;
        if(in<0||pipe<0)return List.of(empty(roles[0],0,components),empty(roles[1],0,components));
        double flow=island.lastResult().map(r->r.averageMassFlows()).orElse(new double[graph.pipes().size()])[pipe];
        return List.of(port(roles[0],graph.reservoirs().get(in),flow,source),port(roles[1],graph.reservoirs().get(graph.pipes().get(pipe).second()),flow,source));
    }

    private static FluidView.Port empty(String role,double flow,int components){return new FluidView.Port(role,null,null,flow,new double[3][components],new double[3]);}

    private static FluidView.Port port(String role,PassiveNetwork.Reservoir node,double flow,Source source) {
        double[] weights=source.weights();
        if(node.empty())return empty(role,flow,weights.length);
        var state=FluidView.State.from(node.state(),Math.max(1e-12,node.state().volume()));
        double[][] moles=state.phaseMoles();double[] volumes=state.phaseVolumes();
        double mass=0;for(var phase:moles)for(int c=0;c<phase.length&&c<weights.length;c++)mass+=phase[c]*weights[c];
        double scale=mass>0?Math.abs(flow)/mass:0;
        for(var phase:moles)for(int c=0;c<phase.length;c++)phase[c]*=scale;
        for(int p=0;p<3;p++)volumes[p]*=scale;
        return new FluidView.Port(role,state.temperature(),state.pressure(),flow,moles,volumes);
    }

    /**
     * A kettle's shell's ports (W7): each of its connections, named by its port class (the FEED nozzle is bulk, the
     * VAPOUR_OUTLET vapour, the LIQUID_OUTLET liquid), with what it carried over the interval. An outflow is at the shell's
     * temperature and the port's pressure; the feed at its far end's.
     */
    private static List<FluidView.Port> poolPorts(long id,IslandCoordinator.Snapshot island,Source source) {
        var graph=island.graph();int node=-1;
        for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)node=i;
        if(node<0)return List.of();
        var result=island.lastResult().orElse(null);double seconds=result==null?0:result.advancedSeconds();
        double[] q=result==null?new double[graph.pipes().size()]:result.averageMassFlows();
        Map<Long,PipeTransfer> transfers=new HashMap<>();if(result!=null)for(var t:result.pipeTransfers())transfers.put(t.pipeId(),t);
        var shell=graph.reservoirs().get(node);var ports=new ArrayList<FluidView.Port>();int components=source.weights().length;
        for(int i=0;i<graph.pipes().size();i++) {
            var pipe=graph.pipes().get(i);
            for(int end=0;end<2;end++) {
                if((end==0?pipe.first():pipe.second())!=node)continue;
                var phasePort=end==0?pipe.firstPort():pipe.secondPort();
                String role=switch(phasePort){case VAPOR->"VAPOUR_OUTLET";case LIQUID->"LIQUID_OUTLET";case BULK->"FEED";default->null;};
                if(role==null)continue;
                final String named=role;if(ports.stream().anyMatch(p->p.role().equals(named)))continue;
                double out=end==0?q[i]:-q[i];boolean feed=role.equals("FEED");
                var far=graph.reservoirs().get(end==0?pipe.second():pipe.first());
                var at=feed&&out<0?far:shell;
                Double temperature=at.empty()?null:at.state().temperature();
                Double pressure=at.empty()?null:feed&&out<0?far.state().pressure():source.portPressure(shell,phasePort);
                // The stream it carried: forward is first to second.
                var transfer=transfers.get(pipe.id());
                double[][] moles=new double[3][components];double[] volumes=new double[3];
                if(transfer!=null&&seconds>0) {
                    var stream=(end==0)==(out>=0)?transfer.forward():transfer.reverse();
                    var n=stream.phaseMoles();var v=stream.phaseVolumes();
                    for(int p=0;p<3;p++){volumes[p]=v[p]/seconds;for(int c=0;c<components&&c<n[p].length;c++)moles[p][c]=n[p][c]/seconds;}
                }
                ports.add(new FluidView.Port(role,temperature,pressure,feed?-out:out,moles,volumes));
            }
        }
        return ports;
    }

    /**
     * An exchanger's lines (W8) as its sides' screens draw them: every part the design of both sides' controls asks for,
     * each in the state its last report gave it (none before a check) with the stream at it. Null for another unit, or an
     * exchanger whose sides ask for no lines.
     */
    private static @Nullable FluidView.Lines lines(long id,HeatUnit unit,@Nullable HeatUnit partner,Source source) {
        if(unit.unit()!=HeatSides.Unit.EXCHANGER)return null;
        var own=unit.lines();var other=partner==null?ExchangerBlueprint.Lines.NONE:partner.lines();
        var design=unit.side()==HeatSides.Side.TUBE?new ExchangerBlueprint.Design(own,other):new ExchangerBlueprint.Design(other,own);
        if(!design.any())return null;
        var reported=source.lines(id);var otherReported=unit.partner()==0?null:source.lines(unit.partner());
        var states=new HashMap<Blueprint.Slot,AssemblyReport.SlotReport>();
        for(var lines:new FluidWorldAuthority.Lines[]{otherReported,reported})if(lines!=null)for(var s:lines.slots())states.put(s.slot(),s);
        String dimension=source.registration(id)==null?null:source.registration(id).device().position().dimension();
        var parts=new ArrayList<FluidView.Part>();
        for(var slot:ExchangerBlueprint.of(design).slots()) {
            if(slot.equals(ExchangerBlueprint.EXCHANGER))continue;
            var r=states.get(slot);
            Double temperature=null,flow=null;
            if(r!=null&&r.member()!=AssemblyReport.NONE&&dimension!=null) {
                var at=net.minecraft.core.BlockPos.of(r.member());
                var found=source.at(new PhysicalFluidTopology.Position(dimension,at.getX(),at.getY(),at.getZ()));
                if(found!=null) {
                    var view=source.view(found.device().id());
                    // A tee is a junction: its temperature. A valve or a metered line: its flow.
                    if(slot.part().endsWith("_tee")) {
                        if(view.state()!=null)temperature=view.state().temperature();
                        else if(view.pipeInfo()!=null&&view.pipeInfo().reading()!=null)temperature=view.pipeInfo().reading().temperature();
                    }else flow=view.massFlow();
                }
            }
            parts.add(new FluidView.Part(slot.part(),r==null?"NOT_VALIDATED":r.state().name(),r==null?"":truncate(r.detail()),r==null?AssemblyReport.NONE:r.at(),temperature,flow));
        }
        return new FluidView.Lines(own==ExchangerBlueprint.Lines.NONE?"NONE":reported==null?"NOT_VALIDATED":reported.status().name(),reported==null?0:reported.missing(),parts);
    }

    private static String truncate(String detail){return detail.length()>64?detail.substring(0,64):detail;}
}
