package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.conversion.ConversionLaws;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRules;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRule;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid converters on the islands' port mechanism (batch 2026-11-12-fluid-conversion, W2; plan 2, 3.2, 3.5 and 5): an
 * open 1 m3 tank with a drain port (a void behind its valve, at the bottom) and a fill port (a generator behind its valve,
 * at the top), and a Forge tank, on the fixture rules' water rule. What crossed the ports equals the Forge tank's volume
 * change by the volume laws and the islands' boundary bookings; a full destination or an empty source moves nothing and
 * holds nothing; a superseded or lapsed reservation is released; balance mode stops inside its deadband; the facts round-trip.
 */
class ConvertersTest {
    static final String WATER="minecraft:water";

    /** The island's bookings at the converter's ports, summed (the ledger the solver keeps). */
    static final class Counting implements IslandCoordinator.Ports {
        final IslandCoordinator.Ports inner;final Set<Long> nodes;final EventPathWorld world;
        double drainMass,drainEnergy,fillMass,fillEnergy;int drainSlices,fillSlices;final List<Long> booked=new ArrayList<>();
        Counting(IslandCoordinator.Ports inner,EventPathWorld world,Set<Long> nodes){this.inner=inner;this.world=world;this.nodes=nodes;}
        long drain,fill;
        public boolean ready(long island,PassiveNetwork graph,long start){return inner.ready(island,graph,start);}
        public Map<Long,PortPlan> plans(long island,PassiveNetwork graph,IslandClock.Slice slice){return inner.plans(island,graph,slice);}
        public void booked(long island,PassiveNetwork graph,long start,long end,PassiveIntervalSolver.Result result) {
            var w=world.coordinator.model(island).molecularWeights();
            for(var b:result.boundaries()) {
                if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE)continue;
                double m=0;var n=b.moles();for(int i=0;i<n.length;i++)m+=n[i]*w[i];
                if(b.nodeId()==drain&&m!=0){drainMass+=-m;drainEnergy+=-b.totalEnergyJoule();drainSlices++;}
                if(b.nodeId()==fill&&m!=0){fillMass+=m;fillEnergy+=b.totalEnergyJoule();fillSlices++;}
            }
            booked.add(end);inner.booked(island,graph,start,end,result);
        }
        public void decide(){inner.decide();}
    }
    record Rig(EventPathWorld world,Converters converters,Counting counting,long vessel) {
        ConverterUnit unit(){return converters.unit(vessel);}
        Converters.Tank tank(){return converters.tank(vessel);}
        long island(){return world.registry.owner(vessel);}
        PassiveNetwork.Reservoir node(){for(var n:world.coordinator.observe(island()).graph().reservoirs())if(n.id()==vessel)return n;throw new AssertionError("no vessel");}
        String describeAll(){var b=new StringBuilder("registered="+world.registry.registrations().keySet()+" events="+world.ledger.events().size()+" ");for(var s:world.coordinator.snapshots())b.append(world.describe(s.id()));return b.toString();}
        /** The vessel's water, kg. */
        double water(){var n=node();var m=world.coordinator.model(island());int w=m.components().indexOf("Water");return n.inventory().moles()[w]*m.molecularWeight(w);}
        /** Runs until {@code slices} more bookings moved fluid through {@code drain} or the fill port. */
        void slices(int slices,boolean drain) {
            int start=drain?counting.drainSlices:counting.fillSlices;long limit=world.ledger.onlineTick()+slices*200L;
            while((drain?counting.drainSlices:counting.fillSlices)<start+slices){world.tick();assertTrue(world.ledger.onlineTick()<limit,"no slice moved fluid by "+limit+": "+converters.status(vessel)+" "+describeAll());}
        }
    }
    static ConversionRules rules() throws IOException {
        var folder=Path.of("src/test/resources/fluid_conversion/fixture");var files=new TreeMap<String,String>();
        try(var list=Files.list(folder)){for(var p:list.toList()){var name=p.getFileName().toString();files.put("test:"+name.substring(0,name.length()-5),Files.readString(p,StandardCharsets.UTF_8));}}
        var catalog=EventPathWorld.CATALOG;
        return ConversionRules.parse(files,ConversionRules.names(catalog,FluidMaterialCatalog.networkPackage(catalog)),ns->!ns.equals("absentmod"));
    }
    private static PhysicalFluidTopology.Device port(long id,int x,int y,Kind kind,Direction toward,PipeResistance.Geometry g) {
        var at=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,0,PhysicalFluidTopology.PORT_LANE);
        return new PhysicalFluidTopology.Device(id,at,kind,toward,g,new FlowControl.Passive(),List.of(new PhysicalFluidTopology.Port(at,toward,PassiveNetwork.PhasePort.BULK)),
                true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
    }
    /** The open tank, its two lines and the converter (a Forge tank of {@code capacity} mB), off. */
    static Rig rig(long capacity) throws IOException {
        var world=new EventPathWorld();var model=world.model;int m=model.componentCount();
        var water=new double[m];water[m-1]=1;
        var node=new PhysicalFluidTopology.Position(EventPathWorld.DIM,0,64,0);var top=new PhysicalFluidTopology.Position(EventPathWorld.DIM,0,65,0);
        long vessel=world.placeVessel(node,List.of(new PhysicalFluidTopology.Port(node,Direction.EAST,PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(top,Direction.EAST,PassiveNetwork.PhasePort.VAPOR)),1,1,true);
        // Each line: the nozzle's pipe, then the valve (two devices that are not pipes never join), then the port inside it.
        world.place(1,64,0,Kind.PIPE,Direction.EAST,.05);world.place(1,65,0,Kind.PIPE,Direction.WEST,.05);
        long drainValve=world.place(2,64,0,Kind.VALVE,Direction.EAST,FluidDeviceSpec.nitrogen(world.catalog),.05);
        long fillValve=world.place(2,65,0,Kind.VALVE,Direction.WEST,FluidDeviceSpec.nitrogen(world.catalog),.05);
        var g=world.registry.registrations().get(drainValve).device().geometry();
        long drain=world.ledger.nextIdentity(),fill=drain+1;
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(drain,new WorldTopologyLedger.Registration(port(drain,3,64,Kind.VOID,Direction.WEST,g),new FluidDeviceSpec(1,298.15,50000,water),0)),
                new WorldTopologyLedger.Edit(fill,new WorldTopologyLedger.Registration(port(fill,3,65,Kind.GENERATOR,Direction.WEST,g),new FluidDeviceSpec(1,298.15,200000,water),0))),fill+1,null);
        var set=rules();
        var converters=new Converters(world.ledger,world.coordinator::model,world.model.time(),world.ledger::onlineTick,
                id->{var island=world.registry.owner(id);if(island!=null)world.coordinator.portsMoved(island);},()->set);
        var counting=new Counting(converters,world,Set.of(drain,fill));counting.drain=drain;counting.fill=fill;
        world.coordinator.ports(counting);
        converters.register(ConverterUnit.built(vessel,drain,drainValve,fill,fillValve,false,capacity));
        world.runTo(40);
        return new Rig(world,converters,counting,vessel);
    }

    @Test void whatCrossedThePortsEqualsTheForgeVolumeChangeAndTheBookingsEachWay() throws IOException {
        var rig=rig(2000);var world=rig.world;var c=rig.converters;long id=rig.vessel;
        assertEquals(1500,rig.tank().fill(WATER,1500,false));
        assertEquals(0,rig.tank().fill("minecraft:lava",10,false),"a tank holds one fluid");
        c.settings(id,ConverterUnit.Mode.TO_ICHEME,.8,ConverterUnit.DEFAULT_DEADBAND);
        rig.slices(100,false);
        world.assertKeptUp("forward");
        var t=c.totals(id);var u=rig.unit();
        // The bookings and the converter's totals agree; the Forge tank lost exactly the litres the rule's density gives.
        var per=ConversionLaws.perLitre(rules().rule("test:water").toIChemE(),world.coordinator.model(rig.island()));
        assertEquals(rig.counting.fillMass,t.massIn(),1e-9*t.massIn());
        assertEquals(rig.counting.fillEnergy,t.energyIn(),1e-6*Math.abs(t.energyIn())+1e-6);
        assertEquals(ConversionLaws.litres(t.massIn(),per.density()),t.litresIn(),1e-9*t.litresIn());
        assertEquals(1500,u.stored()+u.account()+t.litresIn(),1e-6,"the Forge side: stored "+u.stored()+", account "+u.account()+", converted "+t.litresIn());
        assertTrue(t.litresIn()>50,"100 slices at 0.8 L/s: "+t.litresIn()+" L");
        // What came in is in the open tank (a little evaporates to the atmosphere).
        assertEquals(t.massIn(),rig.water(),.01*t.massIn());
        System.out.println("CONVERTERS forward slices="+rig.counting.fillSlices+" kg="+t.massIn()+" J="+t.energyIn()+" L="+t.litresIn()+" rho="+per.density()+" stored="+u.stored()+" account="+u.account()+" vessel_kg="+rig.water());

        // Reverse: the tank's water back into the Forge tank.
        long before=rig.unit().stored();double remainderBefore=rig.unit().remainder(),accountBefore=rig.unit().account();
        c.settings(id,ConverterUnit.Mode.TO_FORGE,.4,ConverterUnit.DEFAULT_DEADBAND);
        rig.slices(100,true);
        world.assertKeptUp("reverse");
        t=c.totals(id);u=rig.unit();
        assertEquals(rig.counting.drainMass,t.massOut(),1e-9*t.massOut());
        assertEquals(rig.counting.drainEnergy,t.energyOut(),1e-6*Math.abs(t.energyOut())+1e-6);
        // The remainder never loses volume: the Forge tank gained the booked litres, whole mB plus the remainder.
        assertEquals(t.litresOut(),(u.stored()-before)+(u.remainder()-remainderBefore)+(u.account()-accountBefore),1e-6);
        assertTrue(u.remainder()<1,"a remainder below 1 mB: "+u.remainder());
        assertEquals(WATER,u.fluid());
        // V = m / rho at the drawn water's density.
        assertEquals(t.massOut()/t.litresOut()*1000,per.density(),.01*per.density(),"the drawn water's density");
        System.out.println("CONVERTERS reverse slices="+rig.counting.drainSlices+" kg="+t.massOut()+" J="+t.energyOut()+" L="+t.litresOut()+" stored="+u.stored()+" remainder="+u.remainder());
    }

    @Test void aFullDestinationOrAnEmptySourceMovesNothingAndHoldsNothing() throws IOException {
        var rig=rig(300);var world=rig.world;var c=rig.converters;long id=rig.vessel;
        // Forward from an empty Forge tank.
        c.settings(id,ConverterUnit.Mode.TO_ICHEME,5,ConverterUnit.DEFAULT_DEADBAND);
        world.runTo(world.ledger.onlineTick()+400);
        assertEquals(0,rig.counting.fillSlices);assertEquals(Converters.Status.SOURCE_EMPTY,c.status(id));
        // Fill 300 mB; forward until the Forge tank is empty: then it stops.
        rig.tank().fill(WATER,300,false);
        long limit=world.ledger.onlineTick()+20000;
        while(rig.unit().stored()+rig.unit().account()>=1&&world.ledger.onlineTick()<limit)world.tick();
        world.runTo(world.ledger.onlineTick()+200);
        int slices=rig.counting.fillSlices;world.runTo(world.ledger.onlineTick()+400);
        assertEquals(slices,rig.counting.fillSlices,"an empty source gives no flow");
        assertEquals(Converters.Status.SOURCE_EMPTY,c.status(id));
        // Reverse into a Forge tank already full of water.
        rig.tank().fill(WATER,300,false);assertEquals(300,rig.unit().stored());
        c.settings(id,ConverterUnit.Mode.TO_FORGE,5,ConverterUnit.DEFAULT_DEADBAND);
        world.runTo(world.ledger.onlineTick()+400);
        assertEquals(0,rig.counting.drainSlices,"a full destination gives no flow");
        assertEquals(Converters.Status.DESTINATION_FULL,c.status(id));
        // A rule whose ranges do not hold: the vessel's water at 25 C, a 360 K-only set of rules.
        world.assertKeptUp("full and empty");
    }

    @Test void aSupersededOrLapsedReservationIsReleased() throws IOException {
        var rig=rig(2000);var world=rig.world;var c=rig.converters;long id=rig.vessel;
        rig.tank().fill(WATER,1000,false);
        c.settings(id,ConverterUnit.Mode.TO_ICHEME,2,ConverterUnit.DEFAULT_DEADBAND);rig.slices(20,false);
        c.settings(id,ConverterUnit.Mode.TO_FORGE,4,ConverterUnit.DEFAULT_DEADBAND);
        // Plans outside the engine, as a slice dispatched and never booked (a rejected job, a held island): the second
        // plan supersedes the first's reservation, not adds to it.
        long island=rig.island();var graph=world.coordinator.observe(island).graph();long now=world.ledger.onlineTick();
        c.plans(island,graph,new IslandClock.Slice(now,now+20,1));double one=c.reserved(id);
        assertTrue(one>0,"a reverse plan reserves Forge room");
        c.plans(island,graph,new IslandClock.Slice(now,now+20,2));
        assertEquals(one,c.reserved(id),1e-12,"a new plan replaces the reservation");
        var u=rig.unit();
        long free=(long)Math.floor(u.capacity()-u.stored()-one-u.remainder());
        assertEquals(free,rig.tank().fill(WATER,u.capacity(),true),"the Forge side sees the room less the reservation");
        // The reservation lapses after its slice's end plus RESERVATION_TICKS, and an explicit release drops it too.
        world.ledger.tick();for(long t=0;t<20+Converters.RESERVATION_TICKS;t++)world.ledger.tick();
        assertEquals(0,c.reserved(id),"lapsed");
        c.plans(island,graph,new IslandClock.Slice(world.ledger.onlineTick(),world.ledger.onlineTick()+20,3));assertTrue(c.reserved(id)>0);
        c.release(id);assertEquals(0,c.reserved(id),"released");
        assertEquals((long)Math.floor(u.capacity()-u.stored()-u.remainder()),rig.tank().fill(WATER,u.capacity(),true));
    }

    @Test void balanceModeStopsInsideItsDeadband() throws IOException {
        var rig=rig(1000);var world=rig.world;var c=rig.converters;long id=rig.vessel;
        rig.tank().fill(WATER,600,false);
        c.settings(id,ConverterUnit.Mode.BALANCE,5,.05);
        long limit=world.ledger.onlineTick()+40000;
        while(c.status(id)!=Converters.Status.BALANCED&&world.ledger.onlineTick()<limit)world.tick();
        assertEquals(Converters.Status.BALANCED,c.status(id));
        var n=rig.node();double vessel=com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.liquidStreamVolume(n.state())/n.inventory().volume();
        assertTrue(Math.abs(vessel-rig.unit().forgeFill())<=.05+.01,"balanced: vessel "+vessel+", Forge "+rig.unit().forgeFill());
        var t=c.totals(id);world.runTo(world.ledger.onlineTick()+600);
        assertEquals(t.litresIn(),c.totals(id).litresIn(),1e-12,"inside the deadband nothing moves");
        assertEquals(t.litresOut(),c.totals(id).litresOut(),1e-12);
        world.assertKeptUp("balance");
    }

    @Test void theFactsRoundTripAndAnUnknownFluidKeepsItsString() throws IOException {
        var rig=rig(64000);var world=rig.world;long id=rig.vessel;var u=rig.unit();
        world.ledger.converter(new ConverterUnit(id,u.drain(),u.drainValve(),u.fill(),u.fillValve(),true,ConverterUnit.Mode.BALANCE,16,.08,new double[]{.9,Double.NaN,.75,.05},
                64000,"gone:fluid",1234,.625,"gone:fluid",3.25));
        var saved=world.ledger.snapshot();var bytes=FluidFacts.world(saved);
        var read=FluidFacts.world(bytes,0,bytes.length);
        assertEquals(List.copyOf(saved.converters().values()),read.converters());
        var restored=read.snapshot(saved.onlineTick(),List.of(),s->null);
        assertEquals(saved.converters(),restored.converters());
        // A load returns the account to its Forge tank in whole mB (no slice is in flight), the fraction kept.
        var reloaded=new Converters(new WorldTopologyLedger(restored),island->world.model,world.model.time(),()->0L,x->{},()->ConversionRules.NONE);
        var back=reloaded.unit(id);
        assertEquals(1237,back.stored());assertEquals(.25,back.account(),1e-12);assertEquals("gone:fluid",back.fluid());
        // A world before schema 24 has none.
        assertTrue(new FluidFacts.World(saved.nextIdentity(),saved.basis(),List.copyOf(saved.active().values()),saved.constructed(),saved.destroyed(),List.of(),saved.restated(),List.of(),List.of(),List.of(),Map.of(),Map.of(),saved.builds(),List.of()).converters().isEmpty());
    }
}
