package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/**
 * Dense fluids in the world (S2c of batch 2026-11-15-supercritical-fluids, S2C_NETWORK_REVIEW.md): a dense fluid is one
 * phase whose slot follows its density (owner decision O2 A), and the network carries it as it carries any fluid: a closed
 * carbon dioxide loop that crosses its critical temperature and the slot line every circuit, a nitrogen let-down from
 * 20 MPa, and one dense vessel drawn through its top and bottom nozzles at once. Each test watches its island through the
 * engine's observer ({@link FluidWorldAuthority#observe}) and closes the island's ledger over every accepted interval
 * after its last edit: its inventories (tanks and junction buffers) against its boundary transfers and the pump's work,
 * per component and in energy. Each test removes everything it placed.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class DenseFluidGameTests {
    private DenseFluidGameTests() {}

    /** The ledger's closure bound, relative to max(1, the inventory) per component and to max(1, the energy turned over). */
    static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    /** Carbon dioxide's critical temperature in the network package (chemsep_carbon_dioxide), K. */
    static final double CO2_CRITICAL_TEMPERATURE=304.21;

    /** A pure fluid's composition on the world's conserved axis. */
    static double[] pure(String id) {
        var catalog=MaterialRuntime.active();
        var axis=new MaterialAxis(new ArrayList<>(catalog.conservedComponents(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog))));
        double[] n=new double[axis.size()];n[axis.requireIndex(id)]=1;return n;
    }
    static long identity(AssemblyGameTests.Plant plant,BlockPos at){return ((FluidDeviceBlockEntity)plant.level.getBlockEntity(at)).fluidIdentity();}
    static void block(AssemblyGameTests.Plant plant,BlockPos at,net.minecraft.world.level.block.state.BlockState state){plant.level.setBlock(at,state,Block.UPDATE_ALL);plant.devices.add(at.immutable());}

    /**
     * The island that holds any of {@code watched} (device identities), read on each published interval: the ledger from
     * the first accepted interval of the island's current revision, the worst closure, the hold, and every node state the
     * island stood at.
     */
    static final class Ledger {
        final Set<Long> watched;
        long island=-1,revision=-1;
        double[] origin,in;double energyOrigin,energyIn,energyThrough;
        double worstMoles,worstEnergy;int intervals;String held;
        final List<FluidThermodynamics.State> states=new ArrayList<>();
        final List<List<ConservativeTransport.BoundaryTransfer>> boundaries=new ArrayList<>();
        final Map<Long,Long> seen=new HashMap<>();
        /** The last state of each watched node. */
        final Map<Long,FluidThermodynamics.State> last=new HashMap<>();
        Ledger(Long... watched){this.watched=new HashSet<>(List.of(watched));}
        synchronized void published(List<IslandCoordinator.Snapshot> changed,Map<Long,IslandCoordinator.Metrics> timings) {
            for(var snapshot:changed) {
                if(snapshot.graph().reservoirs().stream().noneMatch(r->watched.contains(r.id())))continue;
                if(snapshot.status().startsWith("HELD")&&held==null)held=snapshot.status();
                var timing=timings.get(snapshot.id());
                if(timing==null||seen.getOrDefault(snapshot.id(),-1L)>=timing.sequence())continue;
                seen.put(snapshot.id(),timing.sequence());
                if(!timing.accepted()||snapshot.lastResult().isEmpty())continue;
                var result=snapshot.lastResult().get();
                double[] moles=null;double energy=0;
                for(var node:snapshot.graph().reservoirs()) {
                    var inventory=node.inventory();if(inventory==null)continue;
                    var n=inventory.moles();if(moles==null)moles=new double[n.length];
                    for(int c=0;c<n.length;c++)moles[c]+=n[c];
                    energy+=inventory.internalEnergy();
                    if(node.state()!=null&&!node.empty()){states.add(node.state());if(watched.contains(node.id()))last.put(node.id(),node.state());}
                }
                if(moles==null)continue;
                if(snapshot.id()!=island||snapshot.revision()!=revision) {
                    island=snapshot.id();revision=snapshot.revision();origin=moles;energyOrigin=energy;in=new double[moles.length];energyIn=0;energyThrough=0;
                    worstMoles=worstEnergy=0;intervals=0;boundaries.clear();continue;
                }
                for(var b:result.boundaries()) {
                    var n=b.moles();for(int c=0;c<n.length;c++)in[c]+=n[c];
                    energyIn+=b.totalEnergyJoule();energyThrough+=Math.abs(b.totalEnergyJoule());
                }
                energyIn+=result.pumpWorkJoule();energyThrough+=Math.abs(result.pumpWorkJoule());
                boundaries.add(result.boundaries());
                for(int c=0;c<moles.length;c++)worstMoles=Math.max(worstMoles,Math.abs(moles[c]-origin[c]-in[c])/Math.max(1,origin[c]));
                worstEnergy=Math.max(worstEnergy,Math.abs(energy-energyOrigin-energyIn)/Math.max(1,Math.max(Math.abs(energyOrigin),energyThrough)));
                intervals++;
            }
        }
        synchronized int intervals(){return intervals;}
        synchronized String summary() {
            return "island "+island+" intervals "+intervals+" worst component closure "+worstMoles+" worst energy closure "+worstEnergy+" held "+held;
        }
    }

    /** Checks a test's ledger: no hold, every interval closing per component and in energy. */
    static String closes(Ledger ledger) {
        synchronized(ledger) {
            if(ledger.held!=null)return "Held: "+ledger.held;
            if(!(ledger.worstMoles<=COMPONENT_LEDGER))return "The components do not close: "+ledger.summary();
            if(!(ledger.worstEnergy<=ENERGY_LEDGER))return "The energy does not close: "+ledger.summary();
            return null;
        }
    }

    /** Removes the plant, waits until nothing it placed is registered, and ends the test with its failure, if any. */
    static void finish(GameTestHelper helper,AssemblyGameTests.Plant plant,net.minecraft.gametest.framework.GameTestSequence sequence,String[] failure) {
        sequence.thenExecute(()->{plant.world.observe(null);plant.remove();})
                .thenWaitUntil(()->helper.assertTrue(plant.removed(),"Waiting for the test's devices to leave"))
                .thenExecute(()->{plant.release();if(failure[0]!=null)helper.fail(failure[0]);}).thenSucceed();
    }

    /**
     * A closed carbon dioxide loop at about 9 MPa: a one-block tank, an in-line pump, an exchanger heating it on hot water at
     * 365 K and one cooling it on water at 285 K, back to the tank. Carbon dioxide at 9 MPa is a dense liquid-like fluid at
     * 300 K (reduced density about 1.6) and a dense gas-like one at 330 K (about 0.55), so each circuit crosses its critical
     * temperature and the slot line. Over 30 accepted intervals after the loop is closed: no hold, the loop's carbon
     * dioxide constant, its energy closing on the exchangers' heat and the pump's work, and its nodes seen on both sides
     * of the critical temperature and in both slots.
     */
    @GameTest(template="empty",timeoutTicks=60000,batch="dense-fluid-loop")
    public static void aClosedCarbonDioxideLoopCrossesItsCriticalPointEveryCircuitWithNoHold(GameTestHelper helper) {
        // The two exchangers side by side along x, 6 cells apart; the tank 6 cells beyond their far end in z.
        var shape=AssemblyGameTests.Plant.unit(EquipmentShape.Kind.EXCHANGER);int[] span=span(shape);
        int pitch=span[1]-span[0]+7,far=span[3]+6;
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(8,3,8)),new BlockPos(span[0]-8,-3,span[2]-8),new BlockPos(pitch+span[1]+8,span[4]+8,far+8));
        var world=plant.world;String[] failure={null};Vessel[] units=new Vessel[2];BlockPos[] tank={null};TankCharge[] charge={null};
        Ledger[] ledger={null};var co2=pure("CarbonDioxide");var water=pure("Water");
        var sequence=helper.startSequence();
        try {
            plant.hold();
            units[0]=plant.place(shape,0,0,0);
            units[1]=plant.place(shape,pitch,0,0);
            // The utilities: each shell side from a generator beyond its inlet's pipe block to a void beyond its outlet's.
            utility(plant,units[0],new FluidDeviceSpec(1,365,3e5,water));
            utility(plant,units[1],new FluidDeviceSpec(1,285,3e5,water));
            tank[0]=plant.base.offset(pitch/2,0,far);block(plant,tank[0],ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());
            charge[0]=new TankCharge(helper,world,tank[0],Direction.EAST,new FluidDeviceSpec(1,300,9e6,co2));
        } catch(RuntimeException e){failure[0]="build: "+e;}
        if(failure[0]==null) {
            sequence=sequence.thenWaitUntil(()->helper.assertTrue(charge[0].charged(),"Waiting for the tank's carbon dioxide charge"))
                    .thenExecute(()->{
                        try {
                            // The loop: the tank's west face to the heater's tube inlet (the pump on that line), the heater's tube
                            // outlet to the cooler's tube inlet, the cooler's tube outlet to the tank's north face.
                            var suction=plant.route(tank[0].west(),AssemblyGameTests.Plant.front(units[0],Role.TUBE_INLET));
                            pump(plant,suction);
                            plant.route(AssemblyGameTests.Plant.front(units[0],Role.TUBE_OUTLET),AssemblyGameTests.Plant.front(units[1],Role.TUBE_INLET));
                            plant.route(AssemblyGameTests.Plant.front(units[1],Role.TUBE_OUTLET),tank[0].north());
                            ledger[0]=new Ledger(identity(plant,tank[0]));world.observe(ledger[0]::published);
                        } catch(RuntimeException e){failure[0]="loop: "+e;}
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        helper.assertTrue(ledger[0].intervals()>=30,"Waiting for 30 accepted intervals of the closed loop: "+ledger[0].summary()+" "+world.view(identity(plant,tank[0])).status());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        double hottest=Double.NEGATIVE_INFINITY,coldest=Double.POSITIVE_INFINITY;int vapour=0,liquid=0;
                        synchronized(ledger[0]) {
                            for(var s:ledger[0].states) {
                                hottest=Math.max(hottest,s.temperature());coldest=Math.min(coldest,s.temperature());
                                if(s.dense()&&s.oneFluidSlot()==FluidThermodynamics.State.VAPOR)vapour++;
                                if(s.dense()&&s.oneFluidSlot()==FluidThermodynamics.State.LIQUID)liquid++;
                            }
                            double heat=0;for(var list:ledger[0].boundaries)for(var b:list)if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.HEAT){
                                for(double n:b.moles())if(n!=0)failure[0]="The closed loop exchanged matter at "+b.nodeId()+": "+b;
                            }else heat+=Math.abs(b.totalEnergyJoule());
                            System.out.println("DENSE_FLUID_GAMETEST loop "+ledger[0].summary()+" hottest="+hottest+" coldest="+coldest+" denseVapourStates="+vapour+" denseLiquidStates="+liquid+" heat="+heat);
                            if(failure[0]!=null)return;
                            if(!(hottest>CO2_CRITICAL_TEMPERATURE+5&&coldest<CO2_CRITICAL_TEMPERATURE-2))failure[0]="The loop did not cross the critical temperature: "+coldest+" to "+hottest+" K";
                            else if(vapour==0||liquid==0)failure[0]="The loop did not cross the slot line: "+vapour+" dense states in the vapour slot, "+liquid+" in the liquid slot";
                            else if(!(heat>0))failure[0]="No heat crossed the exchangers' walls";
                        }
                    });
        }
        finish(helper,plant,sequence,failure);
    }
    /** The cells a shape spans from its origin: {min x, max x, min z, max z, max y}. */
    static int[] span(EquipmentShape shape) {
        int[] s={Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(var c:com.wormzjl.icheme.science.equipment.ShapeRasterizer.layout(shape).shell()){s[0]=Math.min(s[0],c.x());s[1]=Math.max(s[1],c.x());s[2]=Math.min(s[2],c.z());s[3]=Math.max(s[3],c.z());s[4]=Math.max(s[4],c.y());}
        for(var p:com.wormzjl.icheme.science.equipment.ShapeRasterizer.ports(shape)){int x=p.cell().x()+p.dx(),z=p.cell().z()+p.dz();s[0]=Math.min(s[0],x-1);s[1]=Math.max(s[1],x+1);s[2]=Math.min(s[2],z-1);s[3]=Math.max(s[3],z+1);}
        return s;
    }
    /** An exchanger's shell side from a generator at {@code spec} to a void at the atmosphere. */
    static void utility(AssemblyGameTests.Plant plant,Vessel unit,FluidDeviceSpec spec) {
        var inlet=AssemblyGameTests.Plant.front(unit,Role.SHELL_INLET);var supply=inlet.relative(AssemblyGameTests.Plant.out(unit,Role.SHELL_INLET));
        var outlet=AssemblyGameTests.Plant.front(unit,Role.SHELL_OUTLET);var sink=outlet.relative(AssemblyGameTests.Plant.out(unit,Role.SHELL_OUTLET));
        plant.pipe(inlet);plant.pipe(outlet);
        block(plant,supply,ModBlocks.FLUID_GENERATOR.get().defaultBlockState());block(plant,sink,ModBlocks.FLUID_VOID.get().defaultBlockState());
        TankCharge.run(plant.world,identity(plant,supply),spec);
        long voidId=identity(plant,sink);var r=plant.world.registrations().get(voidId);
        plant.world.edit(voidId,r.revision(),r.device(),new FluidDeviceSpec(1,298.15,101_325,spec.composition()));
    }
    /** An in-line pump, powered, on the first straight horizontal pipe of {@code path} with air above it. */
    static void pump(AssemblyGameTests.Plant plant,List<BlockPos> path) {
        for(int i=1;i<path.size()-1;i++) {
            var a=path.get(i-1);var at=path.get(i);var b=path.get(i+1);
            if(a.getY()!=at.getY()||b.getY()!=at.getY()||b.getX()-at.getX()!=at.getX()-a.getX()||b.getZ()-at.getZ()!=at.getZ()-a.getZ())continue;
            var above=at.above();if(!plant.level.getBlockState(above).isAir()||plant.devices.contains(above)||plant.reserved.contains(above))continue;
            var discharge=Direction.fromDelta(b.getX()-at.getX(),0,b.getZ()-at.getZ());
            plant.level.setBlock(at,FluidPumpStartupGameTests.inlinePump(discharge),Block.UPDATE_ALL);
            plant.devices.add(FluidPumpStartupGameTests.powerInlineMover(plant.level,at).immutable());
            return;
        }
        throw new IllegalStateException("No straight pipe with air above it for the pump on "+path);
    }

    /**
     * A nitrogen tank at 20 MPa and 298.15 K let down through a back-pressure valve (it holds its upstream at 1 MPa or
     * above, so it stays open here) into a tank at 0.5 MPa, until the two stand within 1 % of each other. The source
     * empties and cools (its gas expands: the work it does on what leaves it), the receiver fills; no hold, and the closed
     * island's components and energy close over every accepted interval, the receiver's energy balance among them.
     */
    @GameTest(template="empty",timeoutTicks=60000,batch="dense-fluid-letdown")
    public static void aNitrogenTankAtTwentyMegapascalsLetsDownWithNoHoldAndCools(GameTestHelper helper) {
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(4,2,4)),new BlockPos(-4,-2,-4),new BlockPos(8,6,12));
        var world=plant.world;String[] failure={null};
        var source=plant.base;var receiver=plant.base.offset(0,0,4);var line=List.of(source.south(),source.south(2),source.south(3));
        TankCharge[] charges=new TankCharge[2];Ledger[] ledger={null};long[] ids=new long[2];double[] lowest={Double.POSITIVE_INFINITY};
        var sequence=helper.startSequence();
        try {
            plant.hold();
            block(plant,source,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());block(plant,receiver,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());
            ids[0]=identity(plant,source);ids[1]=identity(plant,receiver);
            charges[0]=new TankCharge(helper,world,source,Direction.WEST,new FluidDeviceSpec(1,298.15,2e7,pure("Nitrogen")));
            charges[1]=new TankCharge(helper,world,receiver,Direction.WEST,new FluidDeviceSpec(1,298.15,5e5,pure("Nitrogen")));
        } catch(RuntimeException e){failure[0]="build: "+e;}
        if(failure[0]==null) {
            sequence=sequence.thenWaitUntil(()->{boolean a=charges[0].charged(),b=charges[1].charged();helper.assertTrue(a&&b,"Waiting for both charges");})
                    .thenExecute(()->{
                        try {
                            for(var at:line)plant.pipe(at);
                            var valve=line.get(1);
                            var state=plant.level.getBlockState(valve).setValue(FluidDeviceBlock.FACING,Direction.SOUTH).setValue(FluidDeviceBlock.PRESSURE_CONTROL,true);
                            plant.level.setBlock(valve,FluidDeviceBlock.connectedState(state,plant.level,valve),Block.UPDATE_ALL);
                            long id=identity(plant,valve);
                            if(!world.installPipePressureValve(id,PhysicalFluidTopology.Direction.SOUTH))throw new IllegalStateException("The valve did not install");
                        } catch(RuntimeException e){failure[0]="line: "+e;}
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        long id=identity(plant,line.get(1));var r=world.registrations().get(id);
                        helper.assertTrue(r!=null&&r.device().control() instanceof FlowControl.ControlValve,"Waiting for the valve to stand");
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        long id=identity(plant,line.get(1));var r=world.registrations().get(id);var d=r.device();
                        world.edit(id,r.revision(),PhysicalFluidTopology.Device.builder(d.id(),d.position(),d.kind(),d.facing(),d.geometry(),new FlowControl.PressureValve(1e6))
                                .ports(d.ports()).cutoff(d.cutoff()).inlineFilter(d.inlineFilter()).build(),r.spec());
                        ledger[0]=new Ledger(ids[0],ids[1]);world.observe(ledger[0]::published);
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        var a=world.view(ids[0]).state();var b=world.view(ids[1]).state();
                        if(a!=null)lowest[0]=Math.min(lowest[0],a.temperature());
                        helper.assertTrue(a!=null&&b!=null&&ledger[0].intervals()>0&&Math.abs(a.pressure()-b.pressure())<=.01*a.pressure(),
                                "Waiting for the tanks to stand within 1 %: "+(a==null?"-":a.pressure())+" and "+(b==null?"-":b.pressure())+" Pa; "+ledger[0].summary());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        var a=world.view(ids[0]).state();var b=world.view(ids[1]).state();
                        System.out.println("DENSE_FLUID_GAMETEST letdown "+ledger[0].summary()+" source="+a.temperature()+" K "+a.pressure()+" Pa receiver="+b.temperature()+" K "+b.pressure()+" Pa lowestSource="+lowest[0]);
                        if(!(lowest[0]<298.15-5))failure[0]="The source did not cool as it emptied: lowest "+lowest[0]+" K";
                        else if(!(b.mass()>a.mass()*.5))failure[0]="The receiver did not fill: "+b.mass()+" kg against the source's "+a.mass();
                    });
        }
        finish(helper,plant,sequence,failure);
    }

    /**
     * A one-block tank of carbon dioxide charged at 310 K and 12 MPa (reduced density about 1.4: one dense fluid in the
     * liquid slot), drawn at once through its top (VAPOR port) and its bottom (LIQUID port) into two voids at 11.5 MPa.
     * Both draw the same fluid: on the first accepted interval after the outlets stand, the two voids' boundary transfers
     * hold the same composition and specific enthalpy to 1e-9 relative; the bottom outlet carries the level head of the
     * condensed mass (the slot readers' seam of S2C_NETWORK_REVIEW.md), the top one none.
     */
    @GameTest(template="empty",timeoutTicks=60000,batch="dense-fluid-two-nozzles")
    public static void aDenseVesselDrawnThroughItsTopAndBottomGivesTheSameFluidAtBoth(GameTestHelper helper) {
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(4,4,4)),new BlockPos(-4,-4,-4),new BlockPos(8,8,8));
        var world=plant.world;String[] failure={null};var tank=plant.base;
        var top=tank.above();var topVoid=top.above();var bottom=tank.below();var bottomVoid=bottom.below();
        TankCharge[] charge={null};Ledger[] ledger={null};long[] ids=new long[3];
        var sequence=helper.startSequence();
        try {
            plant.hold();
            block(plant,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());ids[0]=identity(plant,tank);
            charge[0]=new TankCharge(helper,world,tank,Direction.WEST,new FluidDeviceSpec(1,310,1.2e7,pure("CarbonDioxide")));
        } catch(RuntimeException e){failure[0]="build: "+e;}
        if(failure[0]==null) {
            sequence=sequence.thenWaitUntil(()->helper.assertTrue(charge[0].charged(),"Waiting for the carbon dioxide charge"))
                    .thenExecute(()->{
                        try {
                            plant.pipe(top);plant.pipe(bottom);
                            block(plant,topVoid,ModBlocks.FLUID_VOID.get().defaultBlockState());block(plant,bottomVoid,ModBlocks.FLUID_VOID.get().defaultBlockState());
                            ids[1]=identity(plant,topVoid);ids[2]=identity(plant,bottomVoid);
                            for(int k=1;k<3;k++){var r=world.registrations().get(ids[k]);world.edit(ids[k],r.revision(),r.device(),new FluidDeviceSpec(1,310,1.15e7,pure("CarbonDioxide")));}
                            ledger[0]=new Ledger(ids[0]);world.observe(ledger[0]::published);
                        } catch(RuntimeException e){failure[0]="outlets: "+e;}
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        var outlets=world.view(ids[0]).outlets();
                        boolean both;synchronized(ledger[0]){both=!ledger[0].boundaries.isEmpty()&&flows(ledger[0].boundaries.getLast(),ids[1])!=null&&flows(ledger[0].boundaries.getLast(),ids[2])!=null;}
                        helper.assertTrue(both&&outlets.size()==2&&outlets.stream().allMatch(o->o.massFlow()>0),"Waiting for both outlets to carry: "+outlets+" "+ledger[0].summary());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        var outlets=world.view(ids[0]).outlets();
                        var byPort=new EnumMap<PassiveNetwork.PhasePort,com.wormzjl.icheme.runtime.fluid.FluidView.Outlet>(PassiveNetwork.PhasePort.class);for(var o:outlets)byPort.put(o.port(),o);
                        ConservativeTransport.BoundaryTransfer up,down;synchronized(ledger[0]){var last=ledger[0].boundaries.getLast();up=flows(last,ids[1]);down=flows(last,ids[2]);}
                        double hUp=up.totalEnergyJoule()/sum(up.moles()),hDown=down.totalEnergyJoule()/sum(down.moles());
                        double worstShare=0;for(int c=0;c<up.moles().length;c++)worstShare=Math.max(worstShare,Math.abs(up.moles()[c]/sum(up.moles())-down.moles()[c]/sum(down.moles())));
                        var vapour=byPort.get(PassiveNetwork.PhasePort.VAPOR);var liquid=byPort.get(PassiveNetwork.PhasePort.LIQUID);
                        FluidThermodynamics.State held;synchronized(ledger[0]){held=ledger[0].last.get(ids[0]);}
                        System.out.println("DENSE_FLUID_GAMETEST twoNozzles "+ledger[0].summary()+" outlets="+outlets+" hTop="+hUp+" J/mol hBottom="+hDown+" J/mol worstShare="+worstShare+" tank="+world.view(ids[0]).state());
                        if(held==null||held.oneFluidSlot()!=FluidThermodynamics.State.LIQUID||!held.dense())failure[0]="The tank does not hold one dense fluid in the liquid slot: "+held;
                        else if(vapour==null||liquid==null)failure[0]="One top and one bottom outlet: "+outlets;
                        else if(vapour.drawn()!=liquid.drawn())failure[0]="The two outlets draw different phases: "+outlets;
                        else if(!(Math.abs(hUp-hDown)<=1e-9*Math.abs(hDown)))failure[0]="The two outlets' specific enthalpies differ: "+hUp+" and "+hDown+" J/mol";
                        else if(!(worstShare<=1e-9))failure[0]="The two outlets' compositions differ by "+worstShare;
                        else if(!(liquid.head()>0)||vapour.head()!=0)failure[0]="The bottom outlet carries the level head, the top none: "+outlets;
                    });
        }
        finish(helper,plant,sequence,failure);
    }
    static ConservativeTransport.BoundaryTransfer flows(List<ConservativeTransport.BoundaryTransfer> list,long node) {
        for(var b:list)if(b.nodeId()==node&&b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE&&sum(b.moles())!=0)return b;
        return null;
    }
    static double sum(double[] values){double s=0;for(double v:values)s+=v;return s;}
}
