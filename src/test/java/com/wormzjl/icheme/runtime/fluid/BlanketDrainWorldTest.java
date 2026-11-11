package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The level loop's direct blanketed start without its server (batch 2026-11-11-valve-followups, item 1), at the game's
 * default time factor 4: the level loop's 7.95 m3 lying drum with a gas blanketing valve at the default set pressure
 * (ambient + 20 kPa) on a 3 bar nitrogen line, fed 198.1 kg/s of water by a flow valve, its liquid out through a volume
 * flow valve and a 0.25 m line 6 m down to a 1 atm void. The outlet valve stays shut until the drum is full of liquid and
 * stands at the feed's pressure (the level controller read nothing for a cadence), then opens wide (1.5 m3/s, the
 * controller's saturated output): the drum drains at what the line passes while the pad keeps its gas at the set pressure.
 */
class BlanketDrainWorldTest {
    static final double BORE=.25,FEED=198.1,SET=101325+20000;
    static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,z);}
    @AfterEach void reset(){EventPathWorld.time=SimulationTime.ONLINE;}

    /** The drum: its liquid outlet at the bottom, the feed at its side, the valve's inlet on its roof. */
    static long drum(EventPathWorld w) {
        long id=w.ledger.nextIdentity();var node=at(0,0,0);
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1.5,4,0,0);
        var ports=List.of(new PhysicalFluidTopology.Port(at(0,0,0),DOWN,PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(at(0,1,0),EAST,PassiveNetwork.PhasePort.BULK),
                new PhysicalFluidTopology.Port(at(0,1,0),UP,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.BLANKET));
        var device=new PhysicalFluidTopology.Device(id,node,Kind.RESERVOIR,NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(g.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition(),SlurryFeed.NONE,g.height(),false,g,null,SET);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        return id;
    }
    static void setValve(EventPathWorld w,long valve,double volumeFlow) {
        var r=w.registry.registrations().get(valve);var d=r.device();var v=(FlowControl.ControlValve)d.control();
        var law=new FlowControl.ControlValve(FlowControl.ValveSource.VOLUME_FLOW,v.targetPressure(),v.massTarget(),volumeFlow,v.sensor(),v.revision()+1);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(valve,new WorldTopologyLedger.Registration(d.withControl(law),r.spec(),r.revision()+1))),w.ledger.nextIdentity(),null);
    }
    static PassiveNetwork.Reservoir node(EventPathWorld w,long id) {
        var island=w.registry.owner(id);if(island==null)return null;
        for(var n:w.coordinator.observe(island).graph().reservoirs())if(n.id()==id)return n;
        return null;
    }

    @Test void aBlanketedDrumOverfilledOnItsFeedDrainsWideOpenWithoutAHold() {
        EventPathWorld.time=SimulationTime.of(4);
        var w=new EventPathWorld();
        w.runTo(100);
        long drum=drum(w);
        var water=FluidDeviceSpec.water(w.catalog).composition();
        w.place(1,1,0,Kind.PIPE,EAST,BORE);
        w.place(2,1,0,Kind.PIPE,WEST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,FEED,new UUID(1,1),1),null,BORE);
        w.place(3,1,0,Kind.PIPE,EAST,BORE);
        w.place(4,1,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,300,300_000,water),BORE);
        w.place(0,2,0,Kind.PIPE,UP,BORE);w.place(0,3,0,Kind.PIPE,UP,BORE);
        w.place(0,4,0,Kind.GENERATOR,UP,new FlowControl.Passive(),new FluidDeviceSpec(1,300,300_000,EventPathWorld.nitrogen().composition()),BORE);
        w.place(0,-1,0,Kind.PIPE,DOWN,BORE);
        long outlet=w.place(0,-2,0,Kind.PIPE,DOWN,new FlowControl.ControlValve(FlowControl.ValveSource.VOLUME_FLOW,200000,0,0,new UUID(1,2),1),null,BORE);
        for(int y=-3;y>=-7;y--)w.place(0,y,0,Kind.PIPE,DOWN,BORE);
        w.place(0,-8,0,Kind.VOID,DOWN,new FlowControl.Passive(),new FluidDeviceSpec(1,300,101_325,water),BORE);
        // Fill shut: at most 120 s of process time (2400 ticks at factor 4).
        long start=w.ledger.onlineTick();double peak=0;
        while(w.held.isEmpty()&&w.ledger.onlineTick()<start+2400) {
            w.tick();var n=node(w,drum);if(n==null)continue;
            peak=Math.max(peak,n.state().pressure());
            if(w.ledger.onlineTick()%40==0)System.out.println("BLANKET_DRAIN fill tick="+w.ledger.onlineTick()+" share="+share(n)+" P="+n.state().pressure()+" "+status(w,drum));
            if(share(n)>.995&&n.state().pressure()>2.5e5)break;
        }
        System.out.println("BLANKET_DRAIN full tick="+w.ledger.onlineTick()+" peak="+peak+" held="+w.held);
        assertTrue(w.held.isEmpty(),"the fill holds: "+w.held);
        setValve(w,outlet,1.5);
        long open=w.ledger.onlineTick();double low=Double.POSITIVE_INFINITY,lowP=Double.POSITIVE_INFINITY,highP=0;
        while(w.held.isEmpty()&&w.ledger.onlineTick()<open+600) {
            w.tick();var n=node(w,drum);if(n==null)continue;
            low=Math.min(low,share(n));
            // The pad's band while liquid stands in the drum, past the first slice's blowdown from the feed's pressure.
            if(share(n)>.1&&share(n)<.95){lowP=Math.min(lowP,n.state().pressure());highP=Math.max(highP,n.state().pressure());}
            if(w.ledger.onlineTick()%20==0)System.out.println("BLANKET_DRAIN drain tick="+w.ledger.onlineTick()+" share="+share(n)+" P="+n.state().pressure()+" "+status(w,drum));
        }
        for(var s:w.coordinator.snapshots())System.out.println("BLANKET_DRAIN_ISLAND "+w.describe(s.id()));
        System.out.println("BLANKET_DRAIN_RESULT held="+(w.held.isEmpty()?"clean":w.held.getFirst().status())+" low="+low+" pressure="+lowP+" to "+highP);
        w.assertKeptUp("the wide-open drain");
        assertTrue(peak>2.5e5,"the shut drum fills to near its feed's pressure: "+peak);
        assertTrue(low<.05,"the open valve drains it: "+low);
        assertTrue(lowP>.9*SET&&highP<GasBlanket.ventPressure(SET),"the pad holds it near its set pressure while it drains: "+lowP+" to "+highP);
    }
    static double share(PassiveNetwork.Reservoir n){return (n.state().liquidVolume()+n.state().waterVolume())/n.inventory().volume();}
    static String status(EventPathWorld w,long drum){var island=w.registry.owner(drum);return island==null?"no island":w.coordinator.observe(island).status();}
}
