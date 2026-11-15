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
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.VesselGeometry;
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
    /** Carbon dioxide's critical point in the network package (chemsep_carbon_dioxide): K, Pa. */
    static final double CO2_CRITICAL_TEMPERATURE=304.21,CO2_CRITICAL_PRESSURE=7.383e6;

    /** A pure fluid's composition on the world's conserved axis. */
    static double[] pure(String id) {
        var catalog=MaterialRuntime.active();
        var axis=new MaterialAxis(new ArrayList<>(catalog.conservedComponents(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog))));
        double[] n=new double[axis.size()];n[axis.requireIndex(id)]=1;return n;
    }
    static long identity(AssemblyGameTests.Plant plant,BlockPos at){return ((FluidDeviceBlockEntity)plant.level.getBlockEntity(at)).fluidIdentity();}
    static void block(AssemblyGameTests.Plant plant,BlockPos at,net.minecraft.world.level.block.state.BlockState state){plant.level.setBlock(at,state,Block.UPDATE_ALL);plant.devices.add(at.immutable());}
    /** Removes a block the test placed before the plant goes, as a player breaks it. */
    static void unblock(AssemblyGameTests.Plant plant,BlockPos at){plant.level.removeBlock(at,false);plant.devices.remove(at);}

    /**
     * The island that holds any of {@code watched} (device identities), read on each published interval: the ledger from
     * the first accepted interval of the island's current revision ({@link #base}), the worst closure, the hold, and each
     * accepted interval's nodes and boundary transfers. A new island or revision starts the ledger again ({@link #resets}).
     */
    static final class Ledger {
        /** One node as an interval left it: its state (null when empty), inventory energy and amounts. */
        record Node(long id,FluidThermodynamics.State state,double energy,double[] moles,PassiveNetwork.Inventory inventory){}
        /** One accepted interval: its committed tick, its nodes by identity, its boundary transfers and pump work. */
        record Interval(long committedTick,Map<Long,Node> nodes,List<ConservativeTransport.BoundaryTransfer> boundaries,double pumpWork) {
            double moles(){double s=0;for(var n:nodes.values())for(double m:n.moles())s+=m;return s;}
            double energy(){double s=0;for(var n:nodes.values())s+=n.energy();return s;}
        }
        final Set<Long> watched;
        long island=-1,revision=-1;int resets;
        double[] origin,in;double energyOrigin,energyIn,energyThrough;
        double worstMoles,worstEnergy;String held;
        Interval base;
        final List<Interval> intervals=new ArrayList<>();
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
                double[] moles=null;double energy=0;var nodes=new LinkedHashMap<Long,Node>();
                for(var node:snapshot.graph().reservoirs()) {
                    var inventory=node.inventory();if(inventory==null)continue;
                    var n=inventory.moles();if(moles==null)moles=new double[n.length];
                    for(int c=0;c<n.length;c++)moles[c]+=n[c];
                    energy+=inventory.internalEnergy();
                    var state=node.state()!=null&&!node.empty()?node.state():null;
                    nodes.put(node.id(),new Node(node.id(),state,inventory.internalEnergy(),n,inventory));
                    if(state!=null&&watched.contains(node.id()))last.put(node.id(),state);
                }
                if(moles==null)continue;
                var record=new Interval(snapshot.clock().committedTick(),nodes,result.boundaries(),result.pumpWorkJoule());
                if(snapshot.id()!=island||snapshot.revision()!=revision) {
                    island=snapshot.id();revision=snapshot.revision();origin=moles;energyOrigin=energy;in=new double[moles.length];energyIn=0;energyThrough=0;
                    worstMoles=worstEnergy=0;intervals.clear();base=record;resets++;continue;
                }
                for(var b:result.boundaries()) {
                    var n=b.moles();for(int c=0;c<n.length;c++)in[c]+=n[c];
                    energyIn+=b.totalEnergyJoule();energyThrough+=Math.abs(b.totalEnergyJoule());
                }
                energyIn+=result.pumpWorkJoule();energyThrough+=Math.abs(result.pumpWorkJoule());
                intervals.add(record);
                for(int c=0;c<moles.length;c++)worstMoles=Math.max(worstMoles,Math.abs(moles[c]-origin[c]-in[c])/Math.max(1,origin[c]));
                worstEnergy=Math.max(worstEnergy,Math.abs(energy-energyOrigin-energyIn)/Math.max(1,Math.max(Math.abs(energyOrigin),energyThrough)));
            }
        }
        synchronized int intervals(){return intervals.size();}
        synchronized int resets(){return resets;}
        synchronized String summary() {
            return "island "+island+" intervals "+intervals.size()+" worst component closure "+worstMoles+" worst energy closure "+worstEnergy+" held "+held;
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
     * Whether the island holding node {@code node} stands certified (rest or steady flow, confirmed over two intervals): a
     * certified island publishes no accepted interval until something changes, so a wait for a count of intervals also
     * ends there.
     */
    static boolean certified(FluidWorldAuthority world,long node) {
        return world.diagnosticSnapshots().stream().anyMatch(s->s.certificate().isPresent()&&s.graph().reservoirs().stream().anyMatch(r->r.id()==node));
    }
    static String describe(FluidThermodynamics.State s) {
        return String.format(Locale.ROOT,"%.2f K %.4f MPa %s delta %.3f",s.temperature(),s.pressure()/1e6,
                s.oneFluidSlot()==FluidThermodynamics.State.LIQUID?"liquid slot":s.oneFluidSlot()==FluidThermodynamics.State.VAPOR?"vapour slot":"not one fluid",s.reducedDensity());
    }

    /** Intervals of the closed loop the test waits for after its charge goes, and how many of the last it checks circuit by circuit. */
    static final int LOOP_INTERVALS=30,LOOP_CHECKED=10;
    /**
     * A closed carbon dioxide loop at 9 MPa or above: a one-block tank, an in-line pump, an exchanger heating it on hot
     * water at 365 K and one cooling it on water at 285 K, back to the tank. The suction leaves the tank's west face, the
     * return enters its east face; both ends are allowed beside the tank, and no other pipe touches it (run s2c-gt1 refused
     * a return to the north face, beside the suction line).
     * <p>
     * The loop is charged with all of it laid: a generator at 295 K and 9 MPa on the tank's south face fills it while the
     * pump runs and the two exchangers work, and holds it at that pressure until {@link #LOOP_CHARGE} accepted intervals have
     * passed (or the island certifies first) and the tank stands at it; then it goes, and the loop is closed. A loop charged cold and then heated, or charged and
     * then cooled, would move its pressure by several MPa (a liquid-like fluid's thermal expansion against its
     * compressibility; tmp/supercritical-s2/loop_estimate.py: a charge at 300 K settles between 6.6 and 10 MPa, through
     * the critical pressure), so the charge is made at the operating temperatures.
     * <p>
     * The states it should reach (estimates): the heater's outlet about 340 to 355 K at 9 to 12 MPa, reduced density 0.4
     * to 0.8 (one dense fluid, vapour slot), the cooler's outlet and the tank about 290 to 295 K, reduced density about 1.8
     * (liquid slot), the critical temperature and reduced density 1 crossed in each exchanger. Checked: over
     * {@link #LOOP_INTERVALS} accepted intervals after the charge goes (or every one until the island certifies steady, if
     * sooner), no hold and the ledger closing; over the last {@link #LOOP_CHECKED} of them (all, if fewer), in each
     * interval the hottest node (the heater's outlet) above Tc + 5 K at reduced density below 1, the coldest (the cooler's outlet or the tank) below Tc - 2 K in the liquid slot, every node one fluid above
     * the critical pressure; the loop's amount constant (1e-12) and no matter crossing a boundary.
     */
    @GameTest(template="empty",timeoutTicks=60000,batch="dense-fluid-loop")
    public static void aClosedCarbonDioxideLoopCrossesItsCriticalPointEveryCircuitWithNoHold(GameTestHelper helper) {
        // The two exchangers side by side along x, 7 cells beyond their span apart; the tank 6 cells beyond their far end in z.
        var shape=AssemblyGameTests.Plant.unit(EquipmentShape.Kind.EXCHANGER);int[] span=span(shape);
        int pitch=span[1]-span[0]+7,far=span[3]+6;
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(8,3,8)),new BlockPos(span[0]-8,-3,span[2]-8),new BlockPos(pitch+span[1]+8,span[4]+8,far+8));
        var world=plant.world;String[] failure={null};Vessel[] units=new Vessel[2];BlockPos[] at=new BlockPos[3];long[] tankId={0};int[] closedAt={-1};
        Ledger[] ledger={null};var co2=pure("CarbonDioxide");var water=pure("Water");
        var sequence=helper.startSequence();
        try {
            plant.hold();
            units[0]=plant.place(shape,0,0,0);
            units[1]=plant.place(shape,pitch,0,0);
            // The utilities: each shell side from a generator beyond its inlet's pipe block to a void beyond its outlet's.
            utility(plant,units[0],new FluidDeviceSpec(1,365,3e5,water));
            utility(plant,units[1],new FluidDeviceSpec(1,285,3e5,water));
            var tank=plant.base.offset(pitch/2,0,far);at[0]=tank;block(plant,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());tankId[0]=identity(plant,tank);
            // The loop (tmp/supercritical-s2/probe/loop-layout.txt replays these routes off-line): the tank's west face to the
            // heater's tube inlet (the pump on that line), the heater's tube outlet to the cooler's tube inlet, the cooler's tube
            // outlet to the tank's east face.
            var suction=plant.route(tank.west(),AssemblyGameTests.Plant.front(units[0],Role.TUBE_INLET));
            pump(plant,suction);
            plant.route(AssemblyGameTests.Plant.front(units[0],Role.TUBE_OUTLET),AssemblyGameTests.Plant.front(units[1],Role.TUBE_INLET));
            plant.route(AssemblyGameTests.Plant.front(units[1],Role.TUBE_OUTLET),tank.east(),Set.of(tank));
            // The charge: a generator beyond one pipe on the south face, touching nothing else.
            at[1]=tank.south();at[2]=at[1].south();
            plant.pipe(at[1]);block(plant,at[2],ModBlocks.FLUID_GENERATOR.get().defaultBlockState());
            TankCharge.run(world,identity(plant,at[2]),new FluidDeviceSpec(1,295,9e6,co2));
            ledger[0]=new Ledger(tankId[0]);world.observe(ledger[0]::published);
        } catch(RuntimeException e){failure[0]="build: "+e;}
        if(failure[0]==null) {
            sequence=sequence.thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        var tank=world.view(tankId[0]).state();
                        helper.assertTrue((ledger[0].intervals()>=LOOP_CHARGE||ledger[0].intervals()>=1&&certified(world,tankId[0]))&&tank!=null&&tank.pressure()>=.99*9e6,
                                "Waiting for the loop to circulate "+LOOP_CHARGE+" intervals at its charge pressure: "+(tank==null?"-":tank.pressure())+" Pa; "+ledger[0].summary());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        closedAt[0]=ledger[0].resets();
                        unblock(plant,at[2]);unblock(plant,at[1]);
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        helper.assertTrue(ledger[0].resets()>closedAt[0]&&(ledger[0].intervals()>=LOOP_INTERVALS||ledger[0].intervals()>=1&&certified(world,tankId[0])),
                                "Waiting for "+LOOP_INTERVALS+" accepted intervals of the closed loop: "+ledger[0].summary()+" "+world.view(tankId[0]).status());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        synchronized(ledger[0]) {
                            var all=ledger[0].intervals;var checked=all.subList(Math.max(0,all.size()-LOOP_CHECKED),all.size());
                            double baseMoles=ledger[0].base.moles(),drift=0;
                            for(var interval:all) {
                                drift=Math.max(drift,Math.abs(interval.moles()-baseMoles)/baseMoles);
                                for(var b:interval.boundaries())if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.HEAT)for(double n:b.moles())if(n!=0)failure[0]="The closed loop exchanged matter at "+b.nodeId()+": "+b;
                            }
                            double heat=0;for(var interval:all)for(var b:interval.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat+=Math.abs(b.totalEnergyJoule());
                            double hotLow=Double.POSITIVE_INFINITY,hotHigh=0,coldLow=Double.POSITIVE_INFINITY,coldHigh=0,lowest=Double.POSITIVE_INFINITY,highest=0;
                            String hotLast="",coldLast="";
                            for(var interval:checked) {
                                FluidThermodynamics.State hot=null,cold=null;
                                for(var n:interval.nodes().values()) {
                                    var s=n.state();if(s==null)continue;
                                    lowest=Math.min(lowest,s.pressure());highest=Math.max(highest,s.pressure());
                                    if(failure[0]==null&&s.oneFluidSlot()<0)failure[0]="A loop node is not one fluid: "+describe(s);
                                    if(failure[0]==null&&!(s.pressure()>CO2_CRITICAL_PRESSURE))failure[0]="A loop node stands below the critical pressure: "+describe(s);
                                    if(hot==null||s.temperature()>hot.temperature())hot=s;
                                    if(cold==null||s.temperature()<cold.temperature())cold=s;
                                }
                                if(hot==null){failure[0]="An interval with no loop state";break;}
                                hotLow=Math.min(hotLow,hot.temperature());hotHigh=Math.max(hotHigh,hot.temperature());coldLow=Math.min(coldLow,cold.temperature());coldHigh=Math.max(coldHigh,cold.temperature());
                                hotLast=describe(hot);coldLast=describe(cold);
                                if(failure[0]!=null)continue;
                                if(!(hot.temperature()>CO2_CRITICAL_TEMPERATURE+5&&hot.dense()&&hot.oneFluidSlot()==FluidThermodynamics.State.VAPOR&&hot.reducedDensity()<1))
                                    failure[0]="The heater's outlet did not cross Tc and the critical density: "+describe(hot);
                                else if(!(cold.temperature()<CO2_CRITICAL_TEMPERATURE-2&&cold.oneFluidSlot()==FluidThermodynamics.State.LIQUID))
                                    failure[0]="The cooler's outlet did not come back below Tc in the liquid slot: "+describe(cold);
                            }
                            System.out.println("DENSE_FLUID_GAMETEST loop "+ledger[0].summary()+" checked="+checked.size()+" certified="+certified(world,tankId[0])+" amountDrift="+drift+" heat="+heat+" hottest(last "+LOOP_CHECKED+")="+hotLow+".."+hotHigh
                                    +" K coldest="+coldLow+".."+coldHigh+" K pressures="+lowest+".."+highest+" Pa lastHottest=["+hotLast+"] lastColdest=["+coldLast+"]");
                            if(failure[0]==null&&!(drift<=COMPONENT_LEDGER))failure[0]="The loop's amount moved by "+drift;
                            if(failure[0]==null&&!(heat>0))failure[0]="No heat crossed the exchangers' walls";
                        }
                    });
        }
        finish(helper,plant,sequence,failure);
    }
    /** Accepted intervals the loop circulates at its charge pressure before the charge goes. */
    static final int LOOP_CHARGE=10;
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
     * A nitrogen tank at 20 MPa let down through a back-pressure valve (it holds its upstream at 1 MPa or above, so it stays
     * open here) into a tank at 0.5 MPa, until the two stand within 1 % of each other.
     * <p>
     * A tank charged from a 298.15 K generator stands hotter than the line: filling a rigid empty vessel leaves its internal
     * energy equal to the line's enthalpy, about 406 K at 20 MPa (run s2c-gt1: the source started near 407 K by mass
     * conservation and ended at 325.2 K against 326.9 K for an isentropic let-down, CoolProp). So the source's state is
     * recorded when the charges are done, and the line is joined only after: the source-side pipe and the valve first, the
     * receiver-side pipe last, with the observer already watching. Checked, from the recorded state: the source's
     * temperature never rises and falls by at least half the ideal-gas isentropic drop to its end pressure (heat capacity
     * ratio 7/5; the real-gas drop is about 2 % larger); the receiver gains the energy the rest of the island gives up and
     * its boundaries carry; no hold and the ledger closing; the receiver holding at least half the source's mass.
     */
    @GameTest(template="empty",timeoutTicks=60000,batch="dense-fluid-letdown")
    public static void aNitrogenTankAtTwentyMegapascalsLetsDownWithNoHoldAndCools(GameTestHelper helper) {
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(4,2,4)),new BlockPos(-4,-2,-4),new BlockPos(8,6,12));
        var world=plant.world;String[] failure={null};
        var source=plant.base;var receiver=plant.base.offset(0,0,4);var line=List.of(source.south(),source.south(2),source.south(3));
        TankCharge[] charges=new TankCharge[2];Ledger[] ledger={null};long[] ids=new long[2];
        com.wormzjl.icheme.runtime.fluid.FluidView.State[] start={null};int[] joinedAt={-1};
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
                            start[0]=world.view(ids[0]).state();
                            if(start[0]==null)throw new IllegalStateException("The charged source has no state");
                            ledger[0]=new Ledger(ids[0]);world.observe(ledger[0]::published);
                            // The source-side pipe and the valve: a dead end on the source until the receiver-side pipe joins them.
                            plant.pipe(line.get(0));
                            var valve=line.get(1);
                            var state=ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.SOUTH).setValue(FluidDeviceBlock.PRESSURE_CONTROL,true);
                            plant.level.setBlock(valve,FluidDeviceBlock.connectedState(state,plant.level,valve),Block.UPDATE_ALL);plant.devices.add(valve.immutable());
                            long id=identity(plant,valve);
                            if(!world.installPipePressureValve(id,PhysicalFluidTopology.Direction.SOUTH))throw new IllegalStateException("The valve did not install");
                        } catch(RuntimeException e){failure[0]="line: "+e;}
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var r=world.registrations().get(identity(plant,line.get(1)));
                        helper.assertTrue(r!=null&&r.device().control() instanceof FlowControl.ControlValve,"Waiting for the valve to stand");
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        long id=identity(plant,line.get(1));var r=world.registrations().get(id);var d=r.device();
                        world.edit(id,r.revision(),PhysicalFluidTopology.Device.builder(d.id(),d.position(),d.kind(),d.facing(),d.geometry(),new FlowControl.PressureValve(1e6))
                                .ports(d.ports()).cutoff(d.cutoff()).inlineFilter(d.inlineFilter()).build(),r.spec());
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var r=world.registrations().get(identity(plant,line.get(1)));
                        helper.assertTrue(r!=null&&r.device().control() instanceof FlowControl.PressureValve,"Waiting for the back-pressure valve");
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        // The receiver-side pipe joins the line: the let-down starts here, observed.
                        joinedAt[0]=ledger[0].resets();plant.pipe(line.get(2));
                    })
                    .thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        FluidThermodynamics.State a=null,b=null;
                        synchronized(ledger[0]){if(ledger[0].resets>joinedAt[0]&&!ledger[0].intervals.isEmpty()){var nodes=ledger[0].intervals.getLast().nodes();
                            a=nodes.containsKey(ids[0])?nodes.get(ids[0]).state():null;b=nodes.containsKey(ids[1])?nodes.get(ids[1]).state():null;}}
                        helper.assertTrue(a!=null&&b!=null&&Math.abs(a.pressure()-b.pressure())<=.01*a.pressure(),
                                "Waiting for the joined tanks to stand within 1 %: "+(a==null?"-":a.pressure())+" and "+(b==null?"-":b.pressure())+" Pa; "+ledger[0].summary());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        synchronized(ledger[0]) {
                            double t0=start[0].temperature(),p0=start[0].pressure();
                            var temperatures=new ArrayList<Double>();temperatures.add(t0);
                            var base=ledger[0].base;temperatures.add(base.nodes().get(ids[0]).state().temperature());
                            for(var interval:ledger[0].intervals)temperatures.add(interval.nodes().get(ids[0]).state().temperature());
                            for(int k=1;k<temperatures.size();k++)if(temperatures.get(k)>temperatures.get(k-1)+1e-6){failure[0]="The source warmed: "+temperatures;break;}
                            var last=ledger[0].intervals.getLast();var a=last.nodes().get(ids[0]).state();var b=last.nodes().get(ids[1]).state();
                            double isentropic=t0*Math.pow(a.pressure()/p0,2.0/7.0);
                            // The receiver's energy: its gain against what the rest of the island gave up and the boundaries carried.
                            double gain=last.nodes().get(ids[1]).energy()-base.nodes().get(ids[1]).energy();
                            double rest=0;for(var n:last.nodes().values())if(n.id()!=ids[1])rest+=n.energy()-(base.nodes().containsKey(n.id())?base.nodes().get(n.id()).energy():0);
                            double carried=0,through=0;for(var interval:ledger[0].intervals){for(var x:interval.boundaries()){carried+=x.totalEnergyJoule();through+=Math.abs(x.totalEnergyJoule());}carried+=interval.pumpWork();}
                            double closure=Math.abs(gain+rest-carried)/Math.max(1,Math.max(Math.abs(base.energy()),Math.max(Math.abs(gain),through)));
                            System.out.println("DENSE_FLUID_GAMETEST letdown "+ledger[0].summary()+" start="+t0+" K "+p0+" Pa source="+describe(a)+" receiver="+describe(b)
                                    +" isentropicEnd="+isentropic+" K receiverGain="+gain+" J receiverClosure="+closure+" sourceTemperatures="+temperatures);
                            if(failure[0]!=null)return;
                            if(!(t0-a.temperature()>=.5*(t0-isentropic)))failure[0]="The source cooled by "+(t0-a.temperature())+" K, less than half the isentropic "+(t0-isentropic)+" K";
                            else if(!(closure<=ENERGY_LEDGER))failure[0]="The receiver's energy does not close: "+closure;
                            else if(!(b.mass()>a.mass()*.5))failure[0]="The receiver did not fill: "+b.mass()+" kg against the source's "+a.mass();
                        }
                    });
        }
        finish(helper,plant,sequence,failure);
    }

    /** The accepted interval, counted from the first in which both voids receive carbon dioxide, on which the nozzles are compared. */
    static final int NOZZLE_INTERVAL=3;
    /**
     * A one-block tank of carbon dioxide fed through its west face from a generator at 310 K and 12 MPa and drawn at once
     * through its top (VAPOR port) and its bottom (LIQUID port) into two voids at 11.5 MPa. The feed keeps the draw steady
     * (in run s2c-gt1 a closed tank against the two voids gave up 0.7 % of its mass in its first interval and then carried
     * nothing). The tank stands near 310 K and 11.5 to 12 MPa, reduced density about 1.5: one dense fluid in the liquid slot.
     * On the {@link #NOZZLE_INTERVAL}rd accepted interval after both voids first receive (or the last one, if the island
     * certifies steady sooner): both voids' transfers hold the same composition and specific enthalpy to 1e-9 relative, the
     * tank holds one dense fluid in the liquid slot, both outlets draw the same phase, and on the tank's view the bottom
     * outlet's head is {@code PortStreams.portHead}'s prism value for the view's own state (g times its mass over its volume
     * times the tank's 1 m level height; run s2c-gt1's 6118 Pa is 623.9 kg/m3 over 1 m) to 1e-9, the top one's 0.
     */
    @GameTest(template="empty",timeoutTicks=60000,batch="dense-fluid-two-nozzles")
    public static void aDenseVesselDrawnThroughItsTopAndBottomGivesTheSameFluidAtBoth(GameTestHelper helper) {
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(4,4,4)),new BlockPos(-4,-4,-4),new BlockPos(8,8,8));
        var world=plant.world;String[] failure={null};var tank=plant.base;
        var top=tank.above();var topVoid=top.above();var bottom=tank.below();var bottomVoid=bottom.below();var feed=tank.west();var generator=feed.west();
        Ledger[] ledger={null};long[] ids=new long[3];var co2=pure("CarbonDioxide");
        var sequence=helper.startSequence();
        try {
            plant.hold();
            block(plant,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());ids[0]=identity(plant,tank);
            plant.pipe(feed);block(plant,generator,ModBlocks.FLUID_GENERATOR.get().defaultBlockState());
            TankCharge.run(world,identity(plant,generator),new FluidDeviceSpec(1,310,1.2e7,co2));
            plant.pipe(top);plant.pipe(bottom);
            block(plant,topVoid,ModBlocks.FLUID_VOID.get().defaultBlockState());block(plant,bottomVoid,ModBlocks.FLUID_VOID.get().defaultBlockState());
            ids[1]=identity(plant,topVoid);ids[2]=identity(plant,bottomVoid);
            for(int k=1;k<3;k++){var r=world.registrations().get(ids[k]);world.edit(ids[k],r.revision(),r.device(),new FluidDeviceSpec(1,310,1.15e7,co2));}
            ledger[0]=new Ledger(ids[0]);world.observe(ledger[0]::published);
        } catch(RuntimeException e){failure[0]="build: "+e;}
        if(failure[0]==null) {
            sequence=sequence.thenWaitUntil(()->{
                        if(failure[0]!=null)return;
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return;}
                        var view=world.view(ids[0]);boolean ready=false,certified=certified(world,ids[0]);String why="no interval";
                        synchronized(ledger[0]) {
                            var all=ledger[0].intervals;int first=-1;
                            for(int i=0;i<all.size();i++)if(flows(all.get(i).boundaries(),ids[1])!=null&&flows(all.get(i).boundaries(),ids[2])!=null){first=i;break;}
                            if(first>=0) {
                                var last=all.getLast();
                                ready=(all.size()-1>=first+NOZZLE_INTERVAL||certified)&&flows(last.boundaries(),ids[1])!=null&&flows(last.boundaries(),ids[2])!=null;
                                why="both from interval "+first+" of "+all.size()+", certified "+certified;
                            }
                        }
                        helper.assertTrue(ready,"Waiting for both voids to receive and the view to stand on the compared interval: "+why+" "+view.outlets()+" "+ledger[0].summary());
                    })
                    .thenExecute(()->{
                        if(failure[0]!=null)return;
                        var view=world.view(ids[0]);var outlets=view.outlets();var shown=view.state();
                        com.wormzjl.icheme.runtime.fluid.FluidView.Outlet up=null,down=null;
                        for(var o:outlets){if(o.face()==PhysicalFluidTopology.Direction.UP)up=o;if(o.face()==PhysicalFluidTopology.Direction.DOWN)down=o;}
                        Ledger.Interval last;synchronized(ledger[0]){last=ledger[0].intervals.getLast();}
                        var toTop=flows(last.boundaries(),ids[1]);var toBottom=flows(last.boundaries(),ids[2]);
                        double hTop=toTop.totalEnergyJoule()/sum(toTop.moles()),hBottom=toBottom.totalEnergyJoule()/sum(toBottom.moles());
                        double worstShare=0;for(int c=0;c<toTop.moles().length;c++)worstShare=Math.max(worstShare,Math.abs(toTop.moles()[c]/sum(toTop.moles())-toBottom.moles()[c]/sum(toBottom.moles())));
                        var node=last.nodes().get(ids[0]);var held=node.state();var inventory=node.inventory();
                        double density=shown.mass()/shown.volume(),expected=PassiveStepSolver.GRAVITY*shown.mass()*inventory.levelHeight()/shown.volume();
                        System.out.println("DENSE_FLUID_GAMETEST twoNozzles "+ledger[0].summary()+" compared="+(ledger[0].intervals()-1)+" tank=["+describe(held)+"] density="+density+" kg/m3 levelHeight="+inventory.levelHeight()
                                +" m volume="+inventory.volume()+" m3 form="+inventory.geometry().form()+" expectedHead="+expected+" Pa outlets="+outlets+" hTop="+hTop+" J/mol hBottom="+hBottom+" J/mol worstShare="+worstShare);
                        if(held.oneFluidSlot()!=FluidThermodynamics.State.LIQUID||!held.dense())failure[0]="The tank does not hold one dense fluid in the liquid slot: "+describe(held);
                        else if(up==null||down==null||up.port()!=PassiveNetwork.PhasePort.VAPOR||down.port()!=PassiveNetwork.PhasePort.LIQUID)failure[0]="One top (VAPOR) and one bottom (LIQUID) outlet: "+outlets;
                        else if(!(up.massFlow()>0&&down.massFlow()>0)||up.drawn()!=down.drawn())failure[0]="The two outlets do not draw the same phase: "+outlets;
                        else if(!(Math.abs(hTop-hBottom)<=1e-9*Math.abs(hBottom)))failure[0]="The two outlets' specific enthalpies differ: "+hTop+" and "+hBottom+" J/mol";
                        else if(!(worstShare<=1e-9))failure[0]="The two outlets' compositions differ by "+worstShare;
                        else if(inventory.geometry().form()!=VesselGeometry.Form.PRISM)failure[0]="The one-block tank is not a prism: "+inventory.geometry().form();
                        else if(!(Math.abs(down.head()-expected)<=1e-9*expected)||up.head()!=0)failure[0]="The bottom outlet carries the level head "+expected+" Pa, the top none: "+outlets;
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
