package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The level loop's blanketed drum at its start (batch 2026-11-11-aqueous-solvents, FINAL_LANE_FIXES_REVIEW.md section 3),
 * the plant of {@code LevelLoopGameTests} over the event path at the time factor 4: a drum never filled (a slack bag of
 * nitrogen at the ambient) whose open bottom outlet takes the feed and the pad's gas as they arrive. A failed 2 s pass
 * folded its gas, the drum was found running dry and confirmed as a junction of water alone, below the ambient; the next
 * step started from an empty drum and failed at every size. The junction is laid out on the drum's own state
 * ({@code PassiveStepSolver.stockSeeds}): it stands above the ambient and the drum keeps its contents.
 */
class LevelLoopBlanketStartTest {
    static final double BORE=.25,FEED=198.1,FILL=.1,DENSITY=996.5;
    static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,z);}
    @AfterEach void reset(){EventPathWorld.time=SimulationTime.ONLINE;}

    static final PhysicalFluidTopology.Port BLANKET=new PhysicalFluidTopology.Port(at(0,1,0),EAST,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.BLANKET);
    /** The drum as placed: no gas blanketing valve yet. Liquid outlet at the bottom, the feed on its roof. */
    static long drum(EventPathWorld w) {
        long id=w.ledger.nextIdentity();
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1.5,4,0,0);
        var ports=List.of(new PhysicalFluidTopology.Port(at(0,0,0),DOWN,PassiveNetwork.PhasePort.LIQUID),new PhysicalFluidTopology.Port(at(0,1,0),UP,PassiveNetwork.PhasePort.BULK));
        var device=new PhysicalFluidTopology.Device(id,at(0,0,0),Kind.RESERVOIR,NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(g.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition(),SlurryFeed.NONE,g.height(),false,g,null,0);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        return id;
    }
    static void edit(EventPathWorld w,long id,UnaryOperator<PhysicalFluidTopology.Device> device,UnaryOperator<FluidDeviceSpec> spec) {
        var r=w.registry.registrations().get(id);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device.apply(r.device()),spec.apply(r.spec()),r.revision()+1))),w.ledger.nextIdentity(),null);
    }

    /** One start: {@code fit} ticks from the placement to the valve's fitting, {@code wire} more to the laws, {@code lic} more to the level valve's law. */
    static String start(int fit,int wire,int lic,int lag) {
        EventPathWorld.time=SimulationTime.of(4);
        var w=new EventPathWorld();w.lag=lag;
        w.runTo(100);
        long drum=drum(w);
        var water=FluidDeviceSpec.water(w.catalog).composition();
        // Feed: the generator 6 m up the roof nozzle's line, the flow valve two cells out.
        int top=7;
        long generator=w.place(0,top,0,Kind.GENERATOR,UP,new FlowControl.Passive(),new FluidDeviceSpec(1,300,101_325-6*9.81*DENSITY,water),BORE);
        long feedValve=0,levelValve=0;
        for(int y=top-1;y>=2;y--){long id=w.place(0,y,0,Kind.PIPE,y==4?DOWN:UP,BORE);if(y==4)feedValve=id;}
        // Outlet: nine cells down to a 1 atm void, the level valve on the second.
        for(int y=-1;y>=-8;y--){long id=w.place(0,y,0,Kind.PIPE,DOWN,BORE);if(y==-2)levelValve=id;}
        w.place(0,-9,0,Kind.VOID,DOWN,new FlowControl.Passive(),new FluidDeviceSpec(1,300,101_325,water),BORE);
        // Gas: two pipes to a 3 bar nitrogen generator beside the valve's inlet.
        w.place(3,1,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,300,300_000,EventPathWorld.nitrogen().composition()),BORE);
        w.place(2,1,0,Kind.PIPE,EAST,BORE);w.place(1,1,0,Kind.PIPE,EAST,BORE);
        long feed=feedValve,level=levelValve;
        edit(w,feed,d->d.withControl(FlowControl.ControlValve.unconfigured()),s->s);
        edit(w,level,d->d.withControl(FlowControl.ControlValve.unconfigured()),s->s);
        long t0=w.ledger.onlineTick();
        w.runTo(t0+fit);
        // Fitting: the gas blanketing valve.
        edit(w,drum,d->{var ports=new ArrayList<>(d.ports());ports.add(BLANKET);return d.withPlacement(d.position(),ports);},s->s.withBlanket(FluidDeviceSpec.DEFAULT_BLANKET));
        w.runTo(t0+fit+wire);
        // Wiring: the feed valve's law and the generator at 3 bar, in one tick.
        edit(w,feed,d->d.withControl(new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,FILL*FEED,new UUID(1,1),1)),s->s);
        edit(w,generator,d->d,s->new FluidDeviceSpec(1,300,300_000,water));
        long wired=w.ledger.onlineTick();
        while(w.held.isEmpty()&&w.ledger.onlineTick()<wired+1200) {
            if(w.ledger.onlineTick()==wired+lic)edit(w,level,d->d.withControl(new FlowControl.ControlValve(FlowControl.ValveSource.VOLUME_FLOW,200000,0,0,new UUID(1,2),1)),s->s);
            w.tick();
        }
        return w.held.isEmpty()?null:w.held.getFirst().status();
    }

    /** The orders that held: the laws 10 ticks after the fitting, the level valve's law 100 ticks later, on both worker lags. */
    @Test void aBlanketedDrumNeverFilledStartsItsLevelLoopWithoutAHold() {
        for(int lag:new int[]{0,2})for(int fit:new int[]{10,60})assertNull(start(fit,10,100,lag),"lag "+lag+", fitting after "+fit+" ticks");
    }
}
