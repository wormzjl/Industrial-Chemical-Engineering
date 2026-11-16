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
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Dense fluids in the world (S2c of batch 2026-11-15-supercritical-fluids, S2C_NETWORK_REVIEW.md): a dense fluid is one
 * phase whose slot follows its density (owner decision O2 A), and the network carries it as it carries any fluid: a closed
 * carbon dioxide loop that crosses its critical temperature and the slot line every circuit, a nitrogen let-down from
 * 20 MPa, and one dense vessel drawn through its top and bottom nozzles at once. Each test watches its island through the
 * engine's observer ({@link FluidWorldAuthority#observe}) and closes the island's ledger over every accepted interval
 * after its last edit: its inventories (tanks and junction buffers) against its boundary transfers and the pump's work,
 * per component and in energy.
 * <p>
 * Namespace {@code icheme_dense_test} (its own {@code empty} template: NeoForge enables a test by its template's
 * namespace), which the fluid GameTest task runs beside {@code icheme_fluid_test} by default;
 * {@code -PfluidGameTestNamespaces=icheme_dense_test} runs these four alone (the fourth, the plunger pump, S8).
 * <p>
 * Each test removes everything it places on every exit, a failure included, as {@link LevelLoopGameTests} does: every
 * step records its exception as the test's failure instead of throwing, every wait records a failure once the test's own
 * deadline ({@link #TIMEOUT} less {@link #TEARDOWN} ticks) passes, so the sequence always reaches its removal; the failure
 * is raised only after the plant is gone. The harness itself removes nothing a failed test placed.
 */
@GameTestHolder("icheme_dense_test")
@PrefixGameTestTemplate(false)
public final class DenseFluidGameTests {
    private DenseFluidGameTests() {}

    /** The ledger's closure bound, relative to max(1, the inventory) per component and to max(1, the energy turned over). */
    static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    /** Carbon dioxide's critical point in the network package (chemsep_carbon_dioxide): K, Pa. */
    static final double CO2_CRITICAL_TEMPERATURE=304.21,CO2_CRITICAL_PRESSURE=7.383e6;
    /** Each test's harness timeout, and the ticks its own deadline leaves for the removal. */
    static final int TIMEOUT=60000,TEARDOWN=4000;

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
    /** A tank charge whose generator and pipe count as the plant's, so a failure before the charge ends removes them too. */
    static TankCharge charge(GameTestHelper helper,AssemblyGameTests.Plant plant,BlockPos tank,Direction face,FluidDeviceSpec spec) {
        var charge=new TankCharge(helper,plant.world,tank,face,spec);for(var at:charge.blocks())plant.devices.add(at.immutable());return charge;
    }

    /** {@code body} unless the test already failed; an exception it throws becomes the test's failure. */
    static Runnable step(String[] failure,String what,Runnable body) {
        return ()->{
            if(failure[0]!=null)return;
            try{body.run();}catch(RuntimeException e){failure[0]=what+": "+e;}
        };
    }
    /**
     * A wait: {@code waiting} returns null once done, else what it waits for. It passes once done or failed; an exception
     * it throws, or the test's own deadline passing, becomes the test's failure.
     */
    static Runnable until(GameTestHelper helper,String[] failure,String what,Supplier<String> waiting) {
        return ()->{
            if(failure[0]!=null)return;
            String still;
            try{still=waiting.get();}catch(RuntimeException e){failure[0]=what+": "+e;return;}
            if(still==null)return;
            if(helper.getTick()>TIMEOUT-TEARDOWN){failure[0]="timed out "+what+": "+still;return;}
            throw new GameTestAssertException(still);
        };
    }

    /**
     * The island that holds any of {@code watched} (device identities), read on each published interval: the ledger from
     * the first accepted interval of the island's current revision ({@link #base}), the worst closure, the hold, and each
     * accepted interval's nodes, boundary transfers and step counters. A new island or revision starts the ledger again
     * ({@link #resets}).
     */
    static final class Ledger {
        /** One node as an interval left it: its state (null when empty), inventory energy and amounts. */
        record Node(long id,FluidThermodynamics.State state,double energy,double[] moles,PassiveNetwork.Inventory inventory){}
        /** One accepted interval: its committed tick, nodes by identity, boundary transfers, pump work and step counters. */
        record Interval(long committedTick,Map<Long,Node> nodes,List<ConservativeTransport.BoundaryTransfer> boundaries,double pumpWork,
                        double seconds,int accepted,int rejected,Map<String,Integer> reasons,List<FlowControl.Mode> modes,double largestFlow) {
            double moles(){double s=0;for(var n:nodes.values())for(double m:n.moles())s+=m;return s;}
            double energy(){double s=0;for(var n:nodes.values())s+=n.energy();return s;}
            double mass(){double s=0;for(var n:nodes.values())if(n.state()!=null)s+=n.state().mass();return s;}
        }
        final Set<Long> watched;
        long island=-1,revision=-1;int resets;
        double[] origin,in;double energyOrigin,energyIn,energyThrough;
        double worstMoles,worstEnergy;String held;
        Interval base;
        final List<Interval> intervals=new ArrayList<>();
        final Map<Long,Long> seen=new HashMap<>();
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
                    nodes.put(node.id(),new Node(node.id(),node.state()!=null&&!node.empty()?node.state():null,inventory.internalEnergy(),n,inventory));
                }
                if(moles==null)continue;
                double largest=0;for(double f:result.averageMassFlows())largest=Math.max(largest,Math.abs(f));
                var record=new Interval(snapshot.clock().committedTick(),nodes,result.boundaries(),result.pumpWorkJoule(),result.advancedSeconds(),
                        result.acceptedSubsteps(),result.rejectedSubsteps(),result.rejectionReasons(),result.endpointModes(),largest);
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
        synchronized Interval last(){return intervals.isEmpty()?null:intervals.getLast();}
        synchronized String summary() {
            return "island "+island+" intervals "+intervals.size()+" worst component closure "+worstMoles+" worst energy closure "+worstEnergy+" held "+held;
        }
    }

    /** A test's ledger verdict: null while there is no hold and every interval closes per component and in energy. */
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
        sequence.thenExecute(()->{
                    plant.world.observe(null);
                    try{plant.remove();}catch(RuntimeException e){if(failure[0]==null)failure[0]="removal: "+e;}
                })
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
    /** Accepted intervals the loop circulates at its charge pressure before the charge goes. */
    static final int LOOP_CHARGE=10;
    /**
     * A closed carbon dioxide loop at 9 MPa or above: a one-block tank, an in-line pump, an exchanger heating it on hot
     * water at 365 K and one cooling it on water at 285 K, back to the tank. The suction leaves the tank's west face, the
     * return enters its east face; both ends are allowed beside the tank, and no other pipe touches it (run s2c-gt1 refused
     * a return to the north face, beside the tank and the suction line). The pump is installed on a laid pipe as the item
     * installs it (run s2c-gt2 set a pump's block state on a laid pipe, which registers no mover, and the loop stood still).
     * <p>
     * The loop is charged with all of it laid: a generator at 295 K and 9 MPa on the tank's south face fills it while the
     * pump runs and the two exchangers work, and holds it at that pressure until {@link #LOOP_CHARGE} accepted intervals have
     * passed (or the island certifies first) and the tank stands at it; then it goes, and the loop is closed. A loop charged
     * cold and then heated, or charged and then cooled, would move its pressure by several MPa (a liquid-like fluid's
     * thermal expansion against its compressibility; tmp/supercritical-s2/loop_estimate.py: a charge at 300 K settles
     * between 6.6 and 10 MPa, through the critical pressure), so the charge is made at the operating temperatures.
     * <p>
     * The states it should reach (estimates): the heater's outlet about 340 to 355 K at 9 to 12 MPa, reduced density 0.4
     * to 0.8 (one dense fluid, vapour slot), the cooler's outlet and the tank about 290 to 295 K, reduced density about 1.8
     * (liquid slot), the critical temperature and reduced density 1 crossed in each exchanger. Checked: over
     * {@link #LOOP_INTERVALS} accepted intervals after the charge goes (or every one until the island certifies steady, if
     * sooner), no hold and the ledger closing; over the last {@link #LOOP_CHECKED} of them (all, if fewer), in each
     * interval the hottest node (the heater's outlet) above Tc + 5 K at reduced density below 1, the coldest (the cooler's
     * outlet or the tank) below Tc by the engine's own traits (not dense) in the liquid slot (run s2c-dense1: 302.4 K, 1.8 K
     * under Tc, against 285 K water: the pseudo-critical heat capacity takes most of the cooler's duty), every node one fluid above the critical pressure, the loop
     * flowing; the loop's amount constant (1e-12) and no matter crossing a boundary. Printed: each checked interval's step
     * counters, its nodes by slot, the slot changes since the interval before, its largest flow and the circuit time.
     */
    @GameTest(template="empty",timeoutTicks=TIMEOUT,batch="dense-fluid-loop")
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
            sequence=sequence.thenWaitUntil(until(helper,failure,"the charge",()->{
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return null;}
                        var tank=world.view(tankId[0]).state();
                        if((ledger[0].intervals()>=LOOP_CHARGE||ledger[0].intervals()>=1&&certified(world,tankId[0]))&&tank!=null&&tank.pressure()>=.99*9e6)return null;
                        return "Waiting for the loop to circulate "+LOOP_CHARGE+" intervals at its charge pressure: "+(tank==null?"-":tank.pressure())+" Pa; "+ledger[0].summary();
                    }))
                    .thenExecute(step(failure,"closing the loop",()->{
                        closedAt[0]=ledger[0].resets();
                        unblock(plant,at[2]);unblock(plant,at[1]);
                    }))
                    .thenWaitUntil(until(helper,failure,"the closed loop",()->{
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return null;}
                        if(ledger[0].resets()>closedAt[0]&&(ledger[0].intervals()>=LOOP_INTERVALS||ledger[0].intervals()>=1&&certified(world,tankId[0])))return null;
                        return "Waiting for "+LOOP_INTERVALS+" accepted intervals of the closed loop: "+ledger[0].summary()+" "+world.view(tankId[0]).status();
                    }))
                    .thenExecute(step(failure,"the loop's checks",()->{
                        synchronized(ledger[0]) {
                            var all=ledger[0].intervals;int from=Math.max(0,all.size()-LOOP_CHECKED);var checked=all.subList(from,all.size());
                            double baseMoles=ledger[0].base.moles(),drift=0;
                            for(var interval:all) {
                                drift=Math.max(drift,Math.abs(interval.moles()-baseMoles)/baseMoles);
                                for(var b:interval.boundaries())if(b.kind()!=ConservativeTransport.BoundaryTransfer.Kind.HEAT)for(double n:b.moles())if(n!=0&&failure[0]==null)failure[0]="The closed loop exchanged matter at "+b.nodeId()+": "+b;
                            }
                            double heat=0;for(var interval:all)for(var b:interval.boundaries())if(b.kind()==ConservativeTransport.BoundaryTransfer.Kind.HEAT)heat+=Math.abs(b.totalEnergyJoule());
                            var lines=new StringBuilder();int flips=0;double seconds=0,circuit=Double.NaN;
                            for(int k=0;k<checked.size();k++) {
                                var interval=checked.get(k);var before=from+k>0?all.get(from+k-1):ledger[0].base;
                                FluidThermodynamics.State hot=null,cold=null;int vapour=0,liquid=0,changed=0;
                                for(var n:interval.nodes().values()) {
                                    var s=n.state();if(s==null)continue;
                                    if(failure[0]==null&&s.oneFluidSlot()<0)failure[0]="A loop node is not one fluid: "+describe(s);
                                    if(failure[0]==null&&!(s.pressure()>CO2_CRITICAL_PRESSURE))failure[0]="A loop node stands below the critical pressure: "+describe(s);
                                    if(s.oneFluidSlot()==FluidThermodynamics.State.VAPOR)vapour++;else if(s.oneFluidSlot()==FluidThermodynamics.State.LIQUID)liquid++;
                                    var was=before.nodes().get(n.id());if(was!=null&&was.state()!=null&&was.state().oneFluidSlot()!=s.oneFluidSlot())changed++;
                                    if(hot==null||s.temperature()>hot.temperature())hot=s;
                                    if(cold==null||s.temperature()<cold.temperature())cold=s;
                                }
                                if(hot==null){if(failure[0]==null)failure[0]="An interval with no loop state";break;}
                                flips+=changed;seconds+=interval.seconds();
                                if(interval.largestFlow()>0)circuit=interval.mass()/interval.largestFlow();
                                lines.append(String.format(Locale.ROOT," [%d: %.1f s, substeps %d/%d rejected %s, modes %s, nodes %d vapour %d liquid, slot changes %d, hottest %s, coldest %s, largest flow %.2f kg/s]",
                                        from+k,interval.seconds(),interval.accepted(),interval.rejected(),interval.reasons(),interval.modes(),vapour,liquid,changed,describe(hot),describe(cold),interval.largestFlow()));
                                if(failure[0]!=null)continue;
                                if(!(hot.temperature()>CO2_CRITICAL_TEMPERATURE+5&&hot.dense()&&hot.oneFluidSlot()==FluidThermodynamics.State.VAPOR&&hot.reducedDensity()<1))
                                    failure[0]="The heater's outlet did not cross Tc and the critical density: "+describe(hot);
                                else if(!(cold.temperature()<CO2_CRITICAL_TEMPERATURE&&!cold.dense()&&cold.oneFluidSlot()==FluidThermodynamics.State.LIQUID))
                                    failure[0]="The cooler's outlet did not come back below Tc in the liquid slot: "+describe(cold);
                                else if(!(interval.largestFlow()>0))failure[0]="The loop does not circulate";
                            }
                            System.out.println("DENSE_FLUID_GAMETEST loop "+ledger[0].summary()+" checked="+checked.size()+" certified="+certified(world,tankId[0])+" amountDrift="+drift+" heat="+heat
                                    +" J slotChanges="+flips+" over "+seconds+" s, circuit "+circuit+" s"+lines);
                            if(failure[0]==null&&!(drift<=COMPONENT_LEDGER))failure[0]="The loop's amount moved by "+drift;
                            if(failure[0]==null&&!(heat>0))failure[0]="No heat crossed the exchangers' walls";
                        }
                    }));
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
    /**
     * An in-line pump on the first straight horizontal pipe of {@code path} with air above it, installed on the laid pipe as
     * the item installs it ({@link HeatExchangerReviewWorld}'s pump: its block state, {@code installPipeMover}, the drawn
     * geometry), then powered from the block above (a placed in-line mover's store starts empty, M-04). Returns its identity.
     */
    static long pump(AssemblyGameTests.Plant plant,List<BlockPos> path) {
        for(int i=1;i<path.size()-1;i++) {
            var a=path.get(i-1);var at=path.get(i);var b=path.get(i+1);
            if(a.getY()!=at.getY()||b.getY()!=at.getY()||b.getX()-at.getX()!=at.getX()-a.getX()||b.getZ()-at.getZ()!=at.getZ()-a.getZ())continue;
            var above=at.above();if(!plant.level.getBlockState(above).isAir()||plant.devices.contains(above)||plant.reserved.contains(above))continue;
            var discharge=Direction.fromDelta(b.getX()-at.getX(),0,b.getZ()-at.getZ());
            var state=plant.level.getBlockState(at).setValue(FluidDeviceBlock.FACING,discharge).setValue(FluidDeviceBlock.INLINE_MOVER,FluidDeviceBlock.InlineMover.PUMP);
            plant.level.setBlock(at,FluidDeviceBlock.connectedState(state,plant.level,at),Block.UPDATE_ALL);
            long id=identity(plant,at);
            if(!plant.world.installPipeMover(id,PhysicalFluidTopology.Direction.valueOf(discharge.name()),TopologyCompiler.Kind.PUMP))
                throw new IllegalStateException("The pipe at "+at.toShortString()+" takes no pump");
            if(plant.level.getBlockEntity(at) instanceof FluidDeviceBlockEntity entity)entity.refreshGeometry(entity.bore(),true);
            plant.devices.add(FluidPumpStartupGameTests.powerInlineMover(plant.level,at).immutable());
            return id;
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
     * recorded when the charges are done, and the line is joined only after: the source-side pipe and the valve first (the
     * valve laid as a pipe, then given its state and installed, as a player fits it: run s2c-gt2 placed it with its state,
     * which registers the valve already, and the install refused), the receiver-side pipe last, with the observer already
     * watching. Checked, from the recorded state: the source's temperature never rises and falls by at least half the
     * ideal-gas isentropic drop to its end pressure (heat capacity ratio 7/5; the real-gas drop is about 2 % larger); the
     * receiver gains the energy the rest of the island gives up and its boundaries carry; no hold and the ledger closing;
     * the receiver holding at least half the source's mass.
     */
    @GameTest(template="empty",timeoutTicks=TIMEOUT,batch="dense-fluid-letdown")
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
            charges[0]=charge(helper,plant,source,Direction.WEST,new FluidDeviceSpec(1,298.15,2e7,pure("Nitrogen")));
            charges[1]=charge(helper,plant,receiver,Direction.WEST,new FluidDeviceSpec(1,298.15,5e5,pure("Nitrogen")));
        } catch(RuntimeException e){failure[0]="build: "+e;}
        if(failure[0]==null) {
            sequence=sequence.thenWaitUntil(until(helper,failure,"the charges",()->{boolean a=charges[0].charged(),b=charges[1].charged();return a&&b?null:"Waiting for both charges";}))
                    .thenExecute(step(failure,"the line",()->{
                        start[0]=world.view(ids[0]).state();
                        if(start[0]==null)throw new IllegalStateException("The charged source has no state");
                        ledger[0]=new Ledger(ids[0]);world.observe(ledger[0]::published);
                        // The source-side pipe and the valve: a dead end on the source until the receiver-side pipe joins them.
                        plant.pipe(line.get(0));plant.pipe(line.get(1));
                        var valve=line.get(1);
                        var state=plant.level.getBlockState(valve).setValue(FluidDeviceBlock.FACING,Direction.SOUTH).setValue(FluidDeviceBlock.PRESSURE_CONTROL,true);
                        plant.level.setBlock(valve,FluidDeviceBlock.connectedState(state,plant.level,valve),Block.UPDATE_ALL);
                        if(!world.installPipePressureValve(identity(plant,valve),PhysicalFluidTopology.Direction.SOUTH))throw new IllegalStateException("The valve did not install");
                    }))
                    .thenWaitUntil(until(helper,failure,"the valve",()->{
                        var r=world.registrations().get(identity(plant,line.get(1)));
                        return r!=null&&r.device().control() instanceof FlowControl.ControlValve?null:"Waiting for the valve to stand";
                    }))
                    .thenExecute(step(failure,"the valve's setting",()->{
                        long id=identity(plant,line.get(1));var r=world.registrations().get(id);var d=r.device();
                        world.edit(id,r.revision(),PhysicalFluidTopology.Device.builder(d.id(),d.position(),d.kind(),d.facing(),d.geometry(),new FlowControl.PressureValve(1e6))
                                .ports(d.ports()).cutoff(d.cutoff()).inlineFilter(d.inlineFilter()).build(),r.spec());
                    }))
                    .thenWaitUntil(until(helper,failure,"the back-pressure valve",()->{
                        var r=world.registrations().get(identity(plant,line.get(1)));
                        return r!=null&&r.device().control() instanceof FlowControl.PressureValve?null:"Waiting for the back-pressure valve";
                    }))
                    .thenExecute(step(failure,"joining the line",()->{
                        // The receiver-side pipe joins the line: the let-down starts here, observed.
                        joinedAt[0]=ledger[0].resets();plant.pipe(line.get(2));
                    }))
                    .thenWaitUntil(until(helper,failure,"the let-down",()->{
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return null;}
                        FluidThermodynamics.State a=null,b=null;
                        synchronized(ledger[0]){if(ledger[0].resets>joinedAt[0]&&!ledger[0].intervals.isEmpty()){var nodes=ledger[0].intervals.getLast().nodes();
                            a=nodes.containsKey(ids[0])?nodes.get(ids[0]).state():null;b=nodes.containsKey(ids[1])?nodes.get(ids[1]).state():null;}}
                        if(a!=null&&b!=null&&Math.abs(a.pressure()-b.pressure())<=.01*a.pressure())return null;
                        return "Waiting for the joined tanks to stand within 1 %: "+(a==null?"-":a.pressure())+" and "+(b==null?"-":b.pressure())+" Pa; "+ledger[0].summary();
                    }))
                    .thenExecute(step(failure,"the let-down's checks",()->{
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
                    }));
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
    @GameTest(template="empty",timeoutTicks=TIMEOUT,batch="dense-fluid-two-nozzles")
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
            sequence=sequence.thenWaitUntil(until(helper,failure,"both draws",()->{
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return null;}
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
                        return ready?null:"Waiting for both voids to receive: "+why+" "+view.outlets()+" "+ledger[0].summary();
                    }))
                    .thenExecute(step(failure,"the nozzles' checks",()->{
                        var view=world.view(ids[0]);var outlets=view.outlets();var shown=view.state();
                        com.wormzjl.icheme.runtime.fluid.FluidView.Outlet up=null,down=null;
                        for(var o:outlets){if(o.face()==PhysicalFluidTopology.Direction.UP)up=o;if(o.face()==PhysicalFluidTopology.Direction.DOWN)down=o;}
                        var last=ledger[0].last();
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
                    }));
        }
        finish(helper,plant,sequence,failure);
    }
    /** Accepted intervals the plunger pump runs before its checks (or until its island certifies steady, if sooner). */
    static final int PLUNGER_INTERVALS=10;
    /** The plunger pump's delivered volume against its displacement less its slip: relative. */
    static final double PLUNGER_VOLUME=5e-3;
    /**
     * A triplex plunger pump on dense carbon dioxide (S8 of batch 2026-11-15-supercritical-fluids, S8_PLUNGER_PUMP_REVIEW.md):
     * a PP-10 casing on a 4-pole motor through a gearbox (the gearbox's ratio is the train's, 1500 to the pump's 300 rpm), fed
     * from a generator at 8 MPa and the temperature where carbon dioxide stands at reduced density 0.7 in the network's model,
     * delivering into a void held at 20 MPa. The motor is fed FE every tick (a placed motor's store starts empty).
     * <p>
     * Expected: a displacement pump delivers its volume at any density. Over {@link #PLUNGER_INTERVALS} accepted intervals (or
     * every one until the island certifies), no hold and the ledger closing; in the last, the suction's volume flow (the mass
     * the generator gives over the suction pipe's density) within {@link #PLUNGER_VOLUME} of the displacement at the train's
     * speed share less the slip at the pump's rise ({@code 1 - 0.004 rise / 25 MPa}), the suction one fluid at reduced density
     * 0.6 to 0.8, the rise above 11 MPa.
     */
    @GameTest(template="empty",timeoutTicks=TIMEOUT,batch="dense-fluid-plunger")
    public static void aPlungerPumpDeliversItsDisplacementOfDenseCarbonDioxideAgainstTwentyMegapascals(GameTestHelper helper) {
        var plant=new AssemblyGameTests.Plant(helper,helper.absolutePos(new BlockPos(12,2,12)),new BlockPos(-16,-2,-16),new BlockPos(28,10,28));
        var world=plant.world;var level=plant.level;String[] failure={null};Ledger[] ledger={null};long[] ids=new long[5];
        com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel[] motor={null},gear={null};double[] suctionTemperature={0};FluidThermodynamics.State[] suctionState={null};
        var model=com.wormzjl.icheme.science.machine.PlungerPump.model(2);var law=com.wormzjl.icheme.science.machine.PlungerPump.of(model);
        var sequence=helper.startSequence();
        try {
            plant.hold();
            // The suction temperature: reduced density 0.7 at 8 MPa on the network's own model.
            var catalog=MaterialRuntime.active();
            var thermo=FluidThermodynamics.forNetwork(catalog,com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog));
            double[] z=new double[thermo.componentCount()];z[thermo.components().indexOf("CarbonDioxide")]=1;
            double lo=CO2_CRITICAL_TEMPERATURE+.5,hi=360;
            for(int i=0;i<100&&hi-lo>1e-9;i++){double mid=(lo+hi)/2;if(thermo.flashTP(mid,8e6,z,()->{}).reducedDensity()>.7)lo=mid;else hi=mid;}
            suctionTemperature[0]=(lo+hi)/2;suctionState[0]=thermo.flashTP(suctionTemperature[0],8e6,z,()->{});
            var pd=EquipmentShape.designed(EquipmentShape.Kind.PLUNGER_PUMP,2,1,EquipmentShape.Heading.NORTH,com.wormzjl.icheme.science.equipment.UnitDesign.of(EquipmentShape.Kind.PLUNGER_PUMP).defaultSupport(2,1),com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE);
            var pump=plant.place(pd,0,0,0);
            var suction=AssemblyGameTests.Plant.front(pump,Role.SUCTION);var generator=suction.relative(AssemblyGameTests.Plant.out(pump,Role.SUCTION));
            var discharge=AssemblyGameTests.Plant.front(pump,Role.DISCHARGE);var sink=discharge.relative(AssemblyGameTests.Plant.out(pump,Role.DISCHARGE));
            plant.pipe(suction);plant.pipe(discharge);
            block(plant,generator,ModBlocks.FLUID_GENERATOR.get().defaultBlockState());block(plant,sink,ModBlocks.FLUID_VOID.get().defaultBlockState());
            ids[0]=identity(plant,generator);ids[1]=identity(plant,sink);ids[2]=identity(plant,suction);ids[3]=identity(plant,discharge);
            var cell=com.wormzjl.icheme.science.equipment.ShapeRasterizer.fluidCell(pump.shape());
            ids[4]=((com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity)level.getBlockEntity(pump.origin().offset(cell.x(),cell.y(),cell.z()))).fluidIdentity();
            var co2=pure("CarbonDioxide");
            TankCharge.run(world,ids[0],new FluidDeviceSpec(1,suctionTemperature[0],8e6,co2));
            var r=world.registrations().get(ids[1]);world.edit(ids[1],r.revision(),r.device(),new FluidDeviceSpec(1,suctionTemperature[0],2e7,co2));
            // The train: the gearbox on the pump's driven end, the motor on the gearbox's, rated for the pump at its relief rise.
            double kilowatts=com.wormzjl.icheme.science.machine.Motor.ratingFor(law.maximumShaftPower())/1000;
            gear[0]=gearbox(plant,pump,kilowatts);
            motor[0]=plant.motor(gear[0],kilowatts,4);
            ledger[0]=new Ledger(ids[2],ids[3],ids[4]);world.observe(ledger[0]::published);
        } catch(RuntimeException e){failure[0]="build: "+e;}
        helper.onEachTick(()->{
            if(failure[0]!=null||motor[0]==null)return;
            try{energyPort(plant,motor[0]).receiveEnergy(Integer.MAX_VALUE,false);}catch(RuntimeException e){failure[0]="feed: "+e;}
        });
        if(failure[0]==null) {
            // Auto is an explicit selection: a new gearbox starts at fast over slow 4 (run s8-plunger-2 turned the pump at 19.67
            // times its speed). On Auto the train puts the pump at its 300 rpm with the motor at its own.
            var gearAt=new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position(level.dimension().location().toString(),gear[0].origin().getX(),gear[0].origin().getY(),gear[0].origin().getZ());
            sequence=sequence.thenWaitUntil(until(helper,failure,"the gearbox's registration",()->world.shafts().unitAt(gearAt)!=null?null:"Waiting for the gearbox's registration"))
                    .thenExecute(step(failure,"the gearbox on Auto",()->{
                        var unit=world.shafts().unitAt(gearAt);var settings=unit.settings();
                        world.shafts().queue(new com.wormzjl.icheme.runtime.fluid.ShaftTrains.Configure(unit.id(),new com.wormzjl.icheme.runtime.fluid.ShaftUnit.Settings(settings.running(),settings.mode(),settings.setPoint(),settings.powerLimit(),0)));
                    }));
            sequence=sequence.thenWaitUntil(until(helper,failure,"the plunger pump",()->{
                        var verdict=closes(ledger[0]);if(verdict!=null){failure[0]=verdict;return null;}
                        var drive=world.view(ids[4]).drive();
                        boolean running=drive!=null&&drive.status().startsWith("RUNNING")&&drive.share()>.9&&drive.share()<1.1;
                        // Counted from the first interval the generator gives in: before the motor runs the island certifies at rest and
                        // publishes nothing new, so a count or a certificate alone passes on the rest intervals (run s8-plunger-1).
                        int first=-1,size;boolean delivering;
                        synchronized(ledger[0]) {
                            var all=ledger[0].intervals;size=all.size();
                            for(int i=0;i<size;i++)if(flows(all.get(i).boundaries(),ids[0])!=null){first=i;break;}
                            delivering=size>0&&flows(all.getLast().boundaries(),ids[0])!=null;
                        }
                        if(running&&delivering&&(size-1-first>=PLUNGER_INTERVALS||certified(world,ids[4])))return null;
                        return "Waiting for "+PLUNGER_INTERVALS+" delivering intervals of the running pump: "+(drive==null?"no drive":drive.status()+" share "+drive.share())+", first delivering "+first+" of "+size+", "+ledger[0].summary()+" "+world.view(ids[4]).status();
                    }))
                    .thenExecute(step(failure,"the plunger pump's checks",()->{
                        synchronized(ledger[0]) {
                            var last=ledger[0].intervals.getLast();var view=world.view(ids[4]);var drive=view.drive();
                            // Pipe blocks are edges, not nodes: the suction's state is the generator's own (8 MPa at the temperature of
                            // reduced density 0.7, on the network's model; the one pipe block's drop is a few hundred Pa).
                            var given=flows(last.boundaries(),ids[0]);var s=suctionState[0];
                            if(given==null||view.devicePressureChange()==null){failure[0]="No flow from the generator or no rise on the pump's view: "+last.boundaries()+" "+view.status();return;}
                            double perMole=s.mass()/(sum(s.vaporAmounts())+sum(s.liquidAmounts())+sum(s.aqueousAmounts())),mass=0;
                            // Every substep books its own transfer (run s8-plunger-4 read the first of three): the interval is their sum.
                            for(var b:last.boundaries())if(b.nodeId()==ids[0]&&b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE)mass+=Math.abs(sum(b.moles()))*perMole;
                            double density=s.mass()/s.volume(),volume=mass/last.seconds()/density,rise=view.devicePressureChange();
                            double expected=law.displacement()*drive.share()*(1-.004*rise/law.reliefRise());
                            System.out.println("DENSE_FLUID_GAMETEST plunger "+ledger[0].summary()+" suctionT="+suctionTemperature[0]+" suction=["+describe(s)+"] nodes="+last.nodes().keySet()+" density="+density
                                    +" kg/m3 volume="+volume*3600+" m3/h expected="+expected*3600+" m3/h share="+drive.share()+" rise="+rise+" Pa shaft="+drive.shaftPower()+" W status="+view.status()
                                    +" substeps "+last.accepted()+"/"+last.rejected()+" "+last.reasons()+" modes "+last.modes());
                            if(s.oneFluidSlot()<0||!(s.reducedDensity()>=.6&&s.reducedDensity()<=.8))failure[0]="The suction is not one fluid at reduced density 0.6 to 0.8: "+describe(s);
                            else if(!(rise>11e6))failure[0]="The pump does not rise to the void's 20 MPa: "+rise+" Pa";
                            else if(!(Math.abs(volume-expected)<=PLUNGER_VOLUME*expected))failure[0]="Delivered "+volume*3600+" m3/h against "+expected*3600+" m3/h displaced less slip";
                        }
                    }));
        }
        finish(helper,plant,sequence,failure);
    }
    /** The FE port of {@code vessel}'s terminal box, from its top face (as {@link ElectricGameTests}). */
    static net.neoforged.neoforge.energy.IEnergyStorage energyPort(AssemblyGameTests.Plant plant,com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel vessel) {
        var t=com.wormzjl.icheme.science.equipment.ShapeRasterizer.terminal(vessel.shape());
        if(t==null)throw new IllegalStateException("No terminal box on the "+vessel.shape().kind());
        var port=plant.level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,vessel.origin().offset(t.x(),t.y(),t.z()),Direction.UP);
        if(port==null)throw new IllegalStateException("No FE port on the "+vessel.shape().kind());
        return port;
    }
    /**
     * A gearbox rated {@code kilowatts} beyond a shaft block on {@code pump}'s driven end, headed so its output faces the pump
     * (as {@link CreateCouplingGameTests}): the plant's, removed with it. Its input end is left for {@link AssemblyGameTests.Plant#motor}.
     */
    static com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel gearbox(AssemblyGameTests.Plant plant,com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel pump,double kilowatts) {
        com.wormzjl.icheme.science.equipment.ShapeRasterizer.Shaft driven=null;
        for(var s:com.wormzjl.icheme.science.equipment.ShapeRasterizer.shafts(pump.shape()))if(s.role()==EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT)driven=s;
        if(driven==null)throw new IllegalStateException("No driven end on the "+pump.shape().kind());
        var d=Direction.fromDelta(driven.dx(),driven.dy(),driven.dz());
        var shaft=pump.origin().offset(driven.block().x(),driven.block().y(),driven.block().z());
        if(!plant.level.getBlockState(shaft).canBeReplaced())throw new IllegalStateException("No room for a shaft at "+shaft.toShortString());
        var design=com.wormzjl.icheme.science.equipment.UnitDesign.of(EquipmentShape.Kind.GEARBOX);double size=design.snap(kilowatts);int length=design.defaultLength(size);
        for(var heading:EquipmentShape.Heading.values()) {
            var shape=EquipmentShape.designed(EquipmentShape.Kind.GEARBOX,size,length,heading,design.defaultSupport(size,length),com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE);
            for(var e:com.wormzjl.icheme.science.equipment.ShapeRasterizer.shafts(shape)) {
                if(e.role()!=EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT||Direction.fromDelta(e.dx(),e.dy(),e.dz())!=d.getOpposite())continue;
                var origin=shaft.subtract(new BlockPos(e.block().x(),e.block().y(),e.block().z()));
                plant.level.setBlock(shaft,ModBlocks.SHAFT.get().defaultBlockState().setValue(com.wormzjl.icheme.world.level.block.ShaftBlock.AXIS,d.getAxis()),Block.UPDATE_ALL);
                plant.devices.add(shaft.immutable());
                return plant.place(shape,origin.getX()-plant.base.getX(),origin.getY()-plant.base.getY(),origin.getZ()-plant.base.getZ());
            }
        }
        throw new IllegalStateException("No gearbox heading faces the pump's driven end "+d);
    }
    static ConservativeTransport.BoundaryTransfer flows(List<ConservativeTransport.BoundaryTransfer> list,long node) {
        for(var b:list)if(b.nodeId()==node&&b.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE&&sum(b.moles())!=0)return b;
        return null;
    }
    static double sum(double[] values){double s=0;for(double v:values)s+=v;return s;}
}
