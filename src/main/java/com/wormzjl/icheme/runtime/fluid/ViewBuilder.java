package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.science.plant.still.BatchStill;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import java.util.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.ViewIndex;

/**
 * The views and status lines the presentation host reads, for {@link FluidWorldAuthority} (code base review R8): a
 * device's {@link FluidView} from its island as presented, with its status line (the held devices beside it, a device in
 * no island, a mover's refusal, a line's junctions) and its groups (the drive, the pump, the still's page), over one
 * index per presented island per bucket. Every read is of committed or cached state: queued events are not applied, a
 * certified island is previewed, never materialised, and nothing solves.
 */
final class ViewBuilder {
    private final FluidWorldAuthority authority;
    ViewBuilder(FluidWorldAuthority authority){this.authority=authority;}
    /**
     * A casing's or a steam turbine's Drive group (W7, owner decision D31, option A): its train's driver (a turbine is its
     * own), its status and speed, the casing's share and shaft power; {@code NO DRIVER} for a casing with none, where it
     * stands closed; null for a device with no shaft unit. A read of what the trains last decided.
     */
    private FluidView.Drive drive(long id){
        var own=authority.shafts.unitOfDevice(id);if(own==null)return null;
        var mine=authority.shafts.view(own.id());double share=mine==null?0:Math.max(0,mine.share()),power=mine==null?0:mine.shaftPower();
        var driver=own.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.STEAM_TURBINE?own:authority.shafts.driverOf(id);
        if(driver==null)return new FluidView.Drive("",0,0,"NO DRIVER",0,false,0,power);
        var v=authority.shafts.view(driver.id());String status=v==null?"":v.status();if(status.length()>256)status=status.substring(0,256);
        return new FluidView.Drive(authority.shafts.kindOf(driver.id()).name(),driver.ratedPower(),driver.poles(),status,v==null?0:v.speed(),
                false,share,power);
    }
    /**
     * A pump's readings (W10d, owner decision D37): its suction as its last committed slice read it and its health (W10b),
     * a constructed centrifugal pump's design for its curve; null for a device that is no pump. A read of what the engine
     * last committed: no solve.
     */
    private FluidView.Pump pump(long id,WorldTopologyLedger.Registration registration){
        if(!MachineHealth.pump(registration))return null;var h=authority.health.health(id);if(h==null)return null;
        var s=authority.health.suction(id);
        var curve=registration.device().control() instanceof FlowControl.Machine machine&&machine.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalPump law
                ?new FluidView.Curve(law.ratedFlow(),law.ratedHead(),law.efficiency(),law.ratedSpeed()):null;
        // Its life in play time: the health left over the wear rate per process second, over the factor (W5).
        double rate=authority.health.rate(id),life=h>0&&rate>0?h/rate/authority.model.time().factor():Double.NaN;
        return new FluidView.Pump(finite(s.available()),finite(s.required()),finite(s.gas()),Math.clamp(h,0,1),curve,finite(life));
    }
    private static Double finite(double value){return Double.isFinite(value)?value:null;}
    /**
     * Batch still {@code pot}'s page (W6), a read: the engine's cached view, the player's settings, what a data-network
     * controller holds instead (where {@code Stills.firing} or {@code Stills.reflux} differs from the setting), and a solid
     * still's fuel slot as its pot's cell holds it. Null for no still or none in the engine yet.
     */
    private FluidView.Still stillView(long pot) {
        var u=authority.topology.still(pot);var v=authority.stills.view(pot);if(u==null||v==null)return null;
        boolean column=u.condenser()&&u.stages()>0;
        double firing=authority.stills.firing(pot),reflux=authority.stills.reflux(pot);
        Double heldFiring=Double.isFinite(firing)&&firing!=u.firing()?Math.clamp(firing,0,1):null;
        boolean heldValve=column&&!Double.isNaN(reflux)&&reflux!=u.reflux();
        boolean heldClosed=heldValve&&reflux==Double.POSITIVE_INFINITY;
        Double heldReflux=heldValve&&!heldClosed&&reflux>=0?reflux:null;
        String fuel="";int count=0;double energy=0;
        if(u.heater()==BatchStill.Heater.SOLID) {
            var stack=stillFuel(pot);
            if(!stack.isEmpty()){fuel=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();count=Math.min(64,stack.getCount());energy=EquipmentShellBlockEntity.fuelEnergy(stack);}
        }
        double effective=v.reflux();
        var alarms=v.alarms().stream().map(a->a.text).toList();
        return new FluidView.Still(u.heater().name(),u.stages(),u.condenser(),u.design().rating(),u.firing(),u.closedValve()?StillUnit.DEFAULT_REFLUX:u.reflux(),u.closedValve(),
                heldFiring,heldReflux,heldClosed,
                positive(v.potTemperature()),positive(v.headTemperature()),Math.max(0,finiteOr(v.level())),Math.max(0,finiteOr(v.filled())),Math.max(0,finiteOr(v.heat())),
                Math.max(0,finiteOr(v.distillateFlow())),Math.max(0,finiteOr(v.ventFlow())),Math.clamp(finiteOr(v.condensed()),0,1),
                column&&Double.isFinite(effective)&&effective>=0?effective:null,v.status().name(),alarms,fuel,count,Math.max(0,finiteOr(energy)),Math.max(0,finiteOr(u.burning())));
    }
    /** A temperature, K, or null where unknown (NaN before the first slice or column answer). */
    private static Double positive(double value){return Double.isFinite(value)&&value>0?value:null;}
    private static double finiteOr(double value){return Double.isFinite(value)?value:0;}
    /** What a solid still's fuel slot holds (a read of its pot's cell), or an empty stack when the cell is not loaded. */
    private net.minecraft.world.item.ItemStack stillFuel(long pot) {
        var r=authority.registrations().get(pot);if(r==null)return net.minecraft.world.item.ItemStack.EMPTY;
        var p=r.device().position();var level=authority.server.getLevel(FluidWorldAuthority.dimension(p.dimension()));
        var at=new BlockPos(p.x(),p.y(),p.z());
        if(level==null||!level.isLoaded(at)||!(level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell)||shell.fluidIdentity()!=pot)return net.minecraft.world.item.ItemStack.EMPTY;
        var slot=shell.fuel();return slot==null?net.minecraft.world.item.ItemStack.EMPTY:slot.getStackInSlot(0);
    }
    /**
     * The stream pipe {@code id} carries: a junction its own node, another pipe the observation its gauge reads (the donor's
     * temperature, the pressure at the block). A still pipe keeps the last flowing temperature seen, or, with none, what lies
     * in it at rest, both marked still. Null where the pipe has no observation yet.
     */
    private @org.jetbrains.annotations.Nullable FluidView.Reading lineReading(long id,FluidView view) {
        boolean moving=view.pipeInfo().connections().stream().anyMatch(c->c.massRateKgPerSecond()>0);
        FluidView.Reading now;
        if(view.state()!=null)now=new FluidView.Reading(view.state().temperature(),view.state().pressure(),moving);
        else {
            var reading=authority.instrumentPipeReading(id);
            if(reading==null||!(reading.temperature()>0)||!Double.isFinite(reading.pressure()))return authority.lastLines.get(id)==null?null:authority.lastLines.get(id).still();
            now=new FluidView.Reading(reading.temperature(),reading.pressure(),reading.flowing());
        }
        if(now.flowing()){authority.lastLines.put(id,now);return now;}
        var last=authority.lastLines.get(id);
        return last==null?now:new FluidView.Reading(last.temperature(),now.pressure(),false);
    }
    /** A device's view, as a presentation read: queued events are not applied (they apply on the engine's tick), and a
     * certified island is previewed at now, never materialised, booked or woken by it (code base review R5). */
    /** A device in no island: the column that holds it and runs it (C7, plan section 13), or why it has none. */
    /** The held registrations beside the network ({@link PhysicalRegistry#heldBeside}), derived again when the registry's facts change. */
    private PhysicalRegistry.HeldBeside heldBeside=PhysicalRegistry.HeldBeside.NONE;
    private long heldBesideVersion=-1;
    /**
     * The status clause that names the held registrations beside device {@code id}, or beside its island {@code owner}
     * (registry first, REGISTRY_FIRST_PLAN.md 4.5): the position of the one of lowest identity and how many more, ending in
     * " / "; empty when none is. Its words are the client's (FluidDeviceScreen).
     */
    private String heldClause(long id,Long owner) {
        if(heldBesideVersion!=authority.topology.version()) {
            heldBesideVersion=authority.topology.version();var held=new ArrayList<WorldTopologyLedger.Registration>();
            for(var r:authority.topology.active().values())if(r.device().held())held.add(r);
            held.sort(Comparator.comparingLong(r->r.device().id()));
            heldBeside=held.isEmpty()?PhysicalRegistry.HeldBeside.NONE:authority.registry.heldBeside(held);
        }
        var near=heldBeside.of(id,owner);if(near.isEmpty())return "";
        var first=authority.topology.active().get(near.getFirst());if(first==null)return "";
        var p=first.device().position();
        return FluidWorldAuthority.HELD_DEVICE+p.x()+", "+p.y()+", "+p.z()+(near.size()>1?" (and "+(near.size()-1)+" more)":"")+" / ";
    }
    private String islandless(PhysicalFluidTopology.Device d,long id){
        var u=d.suspendedBy()==0?null:authority.topology.column(d.suspendedBy());
        if(u!=null)return "COLUMN: part of the column at "+u.master().x()+", "+u.master().y()+", "+u.master().z()+", running";
        return !authority.topology.active().containsKey(id)?"WAITING: topology event":Objects.requireNonNullElse(authority.registry.diagnostic(id),"NO FLOW: no hydraulic boundary");
    }
    /** The bucket the indexes belong to (its island cache, by identity), and the indexes. */
    private Map<Long,IslandCoordinator.Snapshot> indexedFor;
    private final Map<Long,ViewIndex> viewIndexes=new HashMap<>();
    /** Island {@code island} as {@code islands} presents it, with its index: built once per bucket (code base review R2). */
    private ViewIndex index(long island,Map<Long,IslandCoordinator.Snapshot> islands) {
        var snapshot=islands.computeIfAbsent(island,authority::presentation);
        if(indexedFor!=islands){indexedFor=islands;viewIndexes.clear();}
        var index=viewIndexes.get(island);
        if(index==null||index.snapshot!=snapshot){index=new ViewIndex(snapshot);viewIndexes.put(island,index);}
        return index;
    }
    FluidView view(long id,Map<Long,IslandCoordinator.Snapshot> islands) {
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.viewBuilds);var registration=authority.registrations().get(id);if(registration==null)return new FluidView(id,0,0,authority.topology.onlineTick(),"REMOVED",null,0,List.of());
        Long owner=authority.registry.owner(id);if(owner==null)return new FluidView(id,registration.revision(),0,authority.topology.onlineTick(),heldClause(id,null)+islandless(registration.device(),id),null,0,List.of(),null,0,List.of(),null);
        var ix=index(owner,islands);var snapshot=ix.snapshot;FluidView.State state=null;int nodeIndex=-1;boolean empty=false;
        Integer at=ix.nodes.get(id);
        if(at!=null){nodeIndex=at;var node=snapshot.graph().reservoirs().get(at);empty=node.empty();if(!empty)state=FluidView.State.from(node.state(),node.kind()==PassiveNetwork.NodeKind.RESERVOIR?node.inventory().volume():node.state().volume());}
        InlineFilter filter=null;if(registration.device().filter()){state=null;Integer p=ix.pipes.get(PhysicalFluidTopology.filterIdentity(id));if(p!=null)filter=snapshot.graph().pipes().get(p).filter();}
        var mappings=registration.device().actuator()?PipePresentation.actuatorMappings(registration.device(),snapshot.graph()):authority.registry.pipeViews(id);
        var history=new ArrayList<PipeTransfer>();double flow=0;Double devicePressureChange=null;String status=snapshot.status();
        // Decision D54: a placed tank holds nothing until something fills it.
        if(empty)status="EMPTY: holds nothing / "+status;
        if(snapshot.lastResult().isPresent()) {
            var result=snapshot.lastResult().orElseThrow();var q=result.averageMassFlows();
            for(int i:ix.incident(nodeIndex)){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex)flow+=q[i];if(pipe.second()==nodeIndex)flow-=q[i];}
            for(var mapping:mappings){var values=ix.transfers.get(mapping.pipeId());if(values!=null)history.addAll(values);}
            if(!history.isEmpty())flow=(history.getFirst().forward().massKg()-history.getFirst().reverse().massKg())/result.advancedSeconds();
            if(registration.device().actuator())for(int i:ix.incident(nodeIndex)){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex&&!(pipe.control() instanceof FlowControl.Passive)){flow=q[i];devicePressureChange=result.endpointHeads()[i];var mode=result.endpointModes().get(i);
                if(pipe.control() instanceof FlowControl.Valve&&ix.dependent().get(i))status+=" / CONTROL_CONFLICT: dependent control constraints; holding last accepted opening";
                else if(pipe.control() instanceof FlowControl.Valve&&ix.standing().get(i))status+=" / STANDING_OPEN: a lower flow target on this line regulates";
                // A control valve's trim (batch 2026-11-16-fouling-corrosion): ERODED TRIM, or TRIM FAILED while it stands open.
                if(pipe.control() instanceof FlowControl.ControlValve){var trim=authority.health.trimStatus(id);if(!trim.isEmpty())status+=" / "+trim;}
                if(pipe.control() instanceof FlowControl.Mover mover) {
                    String inlet=mode==FlowControl.Mode.INLET_WRONG_PHASE?inletReason(snapshot.graph(),i,result.advancedSeconds()):"";
                    // The share of its set flow a set-flow mover moved, on its suction's density; a machine has no set flow.
                    var drawn=snapshot.graph().reservoirs().get(pipe.first());
                    double delivered=mover instanceof FlowControl.Machine||drawn.empty()||!(mover.targetVolumeFlow()>0)?Double.NaN
                            :q[i]/(drawn.state().mass()/drawn.state().volume())/mover.targetVolumeFlow();
                    if(mover.liquid())status=PipePresentation.pumpStatus(status,mover,mode,inlet,delivered);
                    if(!mover.liquid()){status+=" / "+mode;if(!inlet.isEmpty())status+=" / "+inlet;var shortfall=PipePresentation.shortfall(mover,mode,delivered);if(!shortfall.isEmpty())status+=" / "+shortfall;}
                    status=FluidWorldAuthority.withHealth(status,mover,authority.health.status(id),Integrity.constructed(registration));
                    // The limit the mover's law holds: a set-flow pump's setting is the rise for water and scales with its
                    // supply's density, the stream its supply walk ends at (owner decision D11, A2); a compressor's is the ratio over
                    // the suction pressure; a machine's reads the suction stream it draws (decision D4).
                    if(!snapshot.graph().reservoirs().get(pipe.first()).empty()&&mode!=FlowControl.Mode.INLET_WRONG_PHASE&&mode!=FlowControl.Mode.PUMP_SUCTION_LIMIT) {var suction=snapshot.graph().reservoirs().get(pipe.first()).state();
                        double density=mover instanceof FlowControl.Pump?InletPhase.density(authority.model,NetworkReduction.reopened(snapshot.graph()),i,mover.targetVolumeFlow(),result.advancedSeconds()):suction.mass()/suction.volume();
                        double limit=mover.riseLimit(density,suction.pressure(),authority.model.pumpReferenceDensity(),mover.liquid()?1.4:authority.model.heatCapacityRatio(suction));
                        status+=String.format(java.util.Locale.ROOT,mover.liquid()?" (limit %.0f Pa on this fluid)":" (limit %.0f Pa at this suction)",limit);}
                }else{status+=" / "+mode;
                    // A steam turbine's health word (N4): WET EXHAUST, or FAILED first.
                    if(pipe.control() instanceof FlowControl.Turbine)status=FluidWorldAuthority.withHealth(status,pipe.control(),authority.health.status(id),Integrity.constructed(registration));}}}
        }
        // A generator or a void in several islands (owner decision D17) reads the status of its owner (the lowest island) and
        // the net flow of all of them.
        if(registration.device().fixed())for(long other:authority.registry.islands(id))if(other!=owner) {
            var sharedIndex=index(other,islands);var shared=sharedIndex.snapshot;if(shared.lastResult().isEmpty())continue;var q=shared.lastResult().orElseThrow().averageMassFlows();
            Integer found=sharedIndex.nodes.get(id);int index=found==null?-1:found;
            for(int i:sharedIndex.incident(index)){var pipe=shared.graph().pipes().get(i);if(pipe.first()==index)flow+=q[i];if(pipe.second()==index)flow-=q[i];}
        }
        // A filter's and a heat side's own pipe (batch 2026-10-27-heat-exchanger-design): the drop across the cake or the passage.
        long own=filter!=null?PhysicalFluidTopology.filterIdentity(id):registration.device().kind()==TopologyCompiler.Kind.HEAT_SIDE?PhysicalFluidTopology.sideIdentity(id):0;
        Integer ownAt=own==0?null:ix.pipes.get(own);var ownPipe=ownAt==null?null:snapshot.graph().pipes().get(ownAt);
        if(ownPipe!=null)devicePressureChange=snapshot.graph().reservoirs().get(ownPipe.first()).state().pressure()-snapshot.graph().reservoirs().get(ownPipe.second()).state().pressure();
        // A fired heater's radiant coil firing at its maximum short of what it should do (batch 2026-10-27-heat-exchanger-design,
        // owner Q11): it names its outlet's shortfall from the set point. The convection coil takes the flue gas as it comes (F1).
        if(ownPipe!=null&&ownPipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.FiredCoil coil&&coil.firing()>0&&coil.firing()<1) {
            double shortfall=coil.setTemperature()-snapshot.graph().reservoirs().get(ownPipe.second()).state().temperature();
            status=(shortfall>0?String.format(java.util.Locale.ROOT,"DUTY INSUFFICIENT: outlet %.1f K below the set point / ",shortfall):"")+status;
        }
        // A side's controller short of its target (W4, plan section 6): its actuator at an end, or held back by its low limit.
        if(ownPipe!=null)status=FluidWorldAuthority.controlStatus(ownPipe.heat(),registration.device().heat())+status;
        // A side's lines (W8): whether its control works the real valves.
        if(registration.device().heat()!=null)status=authority.linesStatus(id,registration.device().heat())+status;
        if(filter!=null&&filter.clogged())status="filter clogged / "+status;
        var closures=snapshot.lastResult().map(PassiveIntervalSolver.Result::rejectionReasons).orElse(Map.of());
        for(var mapping:mappings){Integer p=ix.pipes.get(mapping.pipeId());if(p==null)continue;var pipe=snapshot.graph().pipes().get(p);if(pipe.blockedDirections()!=0&&pipe.filter()==null)status=FluidWorldAuthority.solidClosure(mapping.pipeId(),closures)+" / "+status;}
        // A vessel a blast or corrosion breached (batch 2026-11-15-pollution-system, WP6b) stands open until the Equipment
        // Constructor repairs it; a heat side so failed stands closed (batch 2026-11-16-fouling-corrosion, WP2). The wall's own
        // words (THIN WALL, WEEPING, LEAKING, WRECKED, and a heat side's FOULED, WP5) come first.
        String wall=authority.integrity.status(id);
        boolean heatSide=registration.device().kind()==TopologyCompiler.Kind.HEAT_SIDE;
        if(registration.spec().breached()&&!heatSide)status="BREACHED: open to the air, repair with the Equipment Constructor / "+status;
        else if(registration.spec().breached()&&wall.isEmpty())status="LEAKING: tube leak / "+status;
        if(authority.integrity.fouled(id))status="FOULED / "+status;
        if(!wall.isEmpty())status=wall+" / "+status;
        if(authority.registry.diagnostic(id)!=null)status=authority.registry.diagnostic(id)+" / "+status;
        var active=authority.topology.active().get(id);if(active==null||active.revision()!=registration.revision())status="WAITING: configuration event / "+status;
        if(authority.unboundBindings.contains(id))status="UNBOUND: saved inventory retained / "+status;
        status=heldClause(id,owner)+status;
        var routes=new ArrayList<FluidView.PipeRoute>();
        for(var historyEntry:history){Integer p=ix.pipes.get(historyEntry.pipeId());if(p==null)continue;var pipe=snapshot.graph().pipes().get(p);
            routes.add(new FluidView.PipeRoute(pipe.id(),nodeLabel(snapshot.graph().reservoirs().get(pipe.first()).id()),nodeLabel(snapshot.graph().reservoirs().get(pipe.second()).id())));
        }
        double seconds=snapshot.lastResult().map(PassiveIntervalSolver.Result::advancedSeconds).orElse(0.0);
        var view=new FluidView(id,registration.revision(),snapshot.clock().committedTick(),authority.topology.onlineTick(),status,state,flow,history,devicePressureChange,
                seconds,routes,filter);
        if(registration.device().kind()==TopologyCompiler.Kind.RESERVOIR&&nodeIndex>=0)view=view.withOutlets(TankOutlets.of(authority.model,snapshot.graph(),nodeIndex,snapshot.lastResult().orElse(null),authority.registry.tankFaces(id)))
                .withTank(TankOutlets.tank(authority.model,snapshot.graph().reservoirs().get(nodeIndex),snapshot.lastResult().orElse(null)));
        if(registration.device().kind()==TopologyCompiler.Kind.PIPE||registration.device().filter()||registration.device().actuator()||registration.device().kind()==TopologyCompiler.Kind.HEAT_SIDE)
            view=view.withPipeInfo(PipePresentation.withBulkSpeed(
                PipePresentation.inspect(registration.device(),snapshot.graph(),mappings,history,seconds,authority.componentNames.size()),
                authority.runtime.coordinator().bulkVolumeRates(owner),registration.device().geometry().area()));
        if(registration.spec().tower()!=null) {
            var rates=snapshot.lastResult().map(result->result.towers().get(id)).orElse(null);
            view=view.withTower(new FluidView.Tower(registration.spec().tower(),rates));
            if(rates!=null&&!rates.status().equals("RUNNING"))view=view.withStatus(view.status()+" / "+rates.status());
        }
        // A fan unit's power (W5, reading A68): NO POWER, the notice UNMETERED, or its electric draw and store.
        // W7 (reading A86): the draw and the store go to the Power group; NO POWER or UNMETERED stay on the display.
        var power=authority.electric.power(id);if(power!=null){view=view.withPower(power);if(!power.status().isEmpty())view=view.withStatus(view.status()+" / "+power.status());}
        // A casing's or turbine's driver (W7, option A's Drive group; it replaces W3's SHAFT clause, reading A43).
        var drive=drive(id);if(drive!=null)view=view.withDrive(drive);
        var pump=pump(id,registration);if(pump!=null)view=view.withPump(pump);
        // A heat side (batch 2026-10-27-heat-exchanger-design): its outlet junction's temperature, the heat its HEAT boundaries
        // booked over the interval, an air cooler's fans and an exchanger side's bypass as planned, a fired heater's burners,
        // set point and most heat.
        var heat=registration.device().heat();
        if(heat!=null&&ownPipe!=null) {
            var pipe=ownPipe;double joule=ix.heat(own);
            double share=heat.control().share(),outlet=snapshot.graph().reservoirs().get(pipe.second()).state().temperature();
            var coil=pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.FiredCoil fired?fired:null;
            if(pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.AirCooled cooled)share=cooled.fans();
            Double bypass=pipe.heat() instanceof com.wormzjl.icheme.science.fluid.network.HeatSide side?side.bypass():null;
            view=view.withHeat(new FluidView.Heat(heat.unit().name(),heat.side().name(),heat.partner()!=0,outlet,seconds>0?joule/seconds:0,
                    heat.fans()?share:null,heat.fans()?com.wormzjl.icheme.science.equipment.HeatSides.fanPower(heat.area(),share):null,
                    heat.burners()?share:null,heat.burners()?heat.control().target():null,coil!=null?coil.duty():null,bypass));
        }
        // A fired heater's burners (batch 2026-11-09-furnace-fuel-power): fuel, fired heat, the fans' power and the alarms on both coils' pages.
        if(heat!=null&&heat.burners()&&view.heat()!=null) {
            long b=authority.burners.burnersOf(heat.side()==com.wormzjl.icheme.science.equipment.HeatSides.Side.RADIANT?id:heat.partner());
            var fired=b==0?null:authority.burners.view(b);
            if(fired!=null) {
                var alarms=fired.alarms().stream().map(a->a.text).toList();
                view=view.withHeat(view.heat().withFuel(new FluidView.Fuel(fired.fuelFlow(),fired.heatingValue(),fired.fired(),fired.asked(),fired.fanDraw(),alarms)));
                var fans=authority.electric.power(b);if(fans!=null)view=view.withPower(fans);
                for(var a:alarms)if(!view.status().contains(a))view=view.withStatus(view.status()+" / "+a);
            }
        }
        // A kettle's shell (W7): the heat its pool took over the interval, booked at its key, at the pool's temperature.
        if(heat!=null&&heat.pool()&&nodeIndex>=0) {
            long key=PhysicalFluidTopology.sideIdentity(id);double joule=ix.heat(key);
            view=view.withHeat(new FluidView.Heat(heat.unit().name(),heat.side().name(),true,snapshot.graph().reservoirs().get(nodeIndex).state().temperature(),seconds>0?joule/seconds:0,
                    null,null,null,null,null,null));
        }
        // The heat GUI (HEAT_GUI_PLAN.md section 1): the actuator, the unit's ports and its lines.
        if(heat!=null&&view.heat()!=null) {
            com.wormzjl.icheme.science.fluid.network.HeatLaw law=ownPipe==null?null:ownPipe.heat();
            var source=authority.heatSource(islands);
            view=view.withHeat(HeatPresentation.complete(view.heat(),id,heat,law,source));
            // A heat side's fouling deposit (batch 2026-11-16-fouling-corrosion, WP5): its Rf.
            if(authority.integrity.kindOf(id)==Integrity.Kind.HEAT_SIDE)view=view.withHeat(view.heat().withFouling(authority.integrity.fouling(id)));
            // The unit's UA (decision D16 of the same batch) as its side's last plan read it; a kettle's pool page, which plans
            // none of its own, reads its tube side's from that side's island. Cached readings only: nothing is computed here.
            var conductance=conductance(law);
            if(heat.pool()&&heat.partner()!=0) {
                var tubes=source.island(heat.partner());long tube=PhysicalFluidTopology.sideIdentity(heat.partner());
                if(tubes!=null)for(var p:tubes.graph().pipes())if(p.id()==tube)conductance=conductance(p.heat());
            }
            if(conductance!=null)view=view.withHeat(view.heat().withConductance(conductance.ua(),conductance.clean()));
        }
        // A batch still's pot (batch 2026-11-09-batch-distiller, W3): its status and alarms on the status line, an electric one's power.
        if(registration.device().stillPot()) {
            var line=authority.stills.statusLine(id);if(!line.isEmpty()&&!view.status().contains(line))view=view.withStatus(view.status()+" / "+line);
            // W6: the still's page, its settings, what a controller holds and its fuel slot.
            var still=stillView(id);if(still!=null)view=view.withStill(still);
        }
        // A fluid converter's vessel (batch 2026-11-12-fluid-conversion, W5): its page; its status word and alarms on the status line.
        if(authority.converters.unit(id)!=null) {
            var c=authority.converterView(id,view);
            if(c!=null) {
                view=view.withConverter(c);
                var word=c.status().replace('_',' ');if(!word.isEmpty()&&!view.status().contains(word))view=view.withStatus(view.status()+" / "+word);
                for(var a:c.alarms())if(!view.status().contains(a))view=view.withStatus(view.status()+" / "+a);
            }
        }
        // A flare stack (batch 2026-11-09-flare-stack): its flow, heat, gas and status; its alarms on the status line.
        if(registration.device().flare()) {
            var f=authority.flares.view(id);
            if(f!=null) {
                view=view.withFlare(new FluidView.Flare(f.massFlow(),f.heat(),f.heatingValue(),f.flameTemperature(),f.limit(),f.rated(),registration.device().geometry().diameter(),f.status().name()));
                for(var a:f.alarms())if(!view.status().contains(a.text))view=view.withStatus(view.status()+" / "+a.text);
            }
        }
        // A chimney (batch 2026-11-10-chimney): its release, gas and status; its status line names the flow and the solids.
        if(registration.device().chimney()) {
            var c=authority.chimneys.view(id);
            if(c!=null) {
                view=view.withChimney(new FluidView.Chimney(c.massFlow(),c.solids(),c.temperature(),c.exitVelocity(),c.opacity(),c.draft(),registration.device().geometry().diameter(),c.status().name()));
                var line=c.line();if(!view.status().contains(line))view=view.withStatus(view.status()+" / "+line);
            }
        }
        // A pressure safety valve (batch 2026-11-11-gas-blanketing): what its run out of the guarded node relieved, and whether
        // that run is the open stub to the atmosphere.
        if(registration.spec().relief()>0) {
            double relieved=0;boolean open=true;var q=snapshot.lastResult().map(PassiveIntervalSolver.Result::averageMassFlows).orElse(null);
            for(int i:ix.incident(nodeIndex)){var pipe=snapshot.graph().pipes().get(i);
                if(pipe.control() instanceof FlowControl.PressureValve&&pipe.first()==nodeIndex){open=pipe.id()==PhysicalFluidTopology.reliefIdentity(id);if(q!=null)relieved=Math.max(0,q[i]);}}
            view=view.withRelief(new FluidView.Relief(relieved,open));
        }
        // A plain pipe's line reading (OWNER_TEST_REVIEW.md section 6): the stream it carries, as its gauge reads it.
        if(registration.device().kind()==TopologyCompiler.Kind.PIPE&&view.pipeInfo()!=null)view=view.withPipeInfo(view.pipeInfo().withReading(lineReading(id,view)));
        if(registration.device().kind()==TopologyCompiler.Kind.VOID) {
            var sources=new ArrayList<IslandCoordinator.Snapshot>();
            for(long island:authority.registry.islands(id))sources.add(islands.computeIfAbsent(island,authority::presentation));
            // Independent boundary islands can have different intervals: combine rates, expressed as one second of transport.
            view=view.withPipeInfo(PipePresentation.boundaryInflow(id,sources,authority.componentNames.size()),1);
        }
        // The wall's Integrity group and a control valve's Trim (batch 2026-11-16-fouling-corrosion, WP4): reads of what the walls
        // and the machine health last booked. Their words are on the status line already (above, and the valve's trim clause).
        var integrity=wall(id,registration);var trim=trim(id,registration);
        if(integrity!=null||trim!=null)view=view.withIntegrity(integrity,trim);
        return view;
    }
    /** Device {@code id}'s wall as its Integrity group shows it, or null for a device whose wall is not booked. */
    FluidView.Integrity wall(long id,WorldTopologyLedger.Registration registration){
        var w=authority.integrity.wall(id);if(w==null)return null;
        double used=w.allowance()>0?Math.clamp(w.loss()/w.allowance(),0,1):0;
        double life=w.rate()>0?w.life()/authority.model.time().factor():Double.NaN;
        return new FluidView.Integrity(used,Double.isFinite(w.rate())?Math.max(0,w.rate()):0,w.mechanism()==null?"":w.mechanism().name(),finite(life),registration.spec().metallurgy().name());
    }
    /** Control valve {@code id}'s trim as its Trim group shows it, or null for a device that is no trimmed control valve. */
    FluidView.Trim trim(long id,WorldTopologyLedger.Registration registration){
        if(!MachineHealth.trim(registration))return null;
        Double recorded=authority.health.health(id);double h=recorded==null?1:Math.clamp(recorded,0,1);
        double rate=authority.health.rate(id),life=h>0&&rate>0?h/rate/authority.model.time().factor():Double.NaN;
        return new FluidView.Trim(h,finite(life));
    }
    /**
     * Why a mover is refused (decision D6), as the solver's inlet check states it on the committed state the next slice
     * decides on ({@link InletPhase#reason}), with the node the supply walk ended at named as a device. Pure arithmetic on
     * the committed state's stored phase amounts: no solve and no flash. Its transport closures read as open, as that
     * slice reads them (owner decision D33, {@link NetworkReduction#reopened}).
     */
    private String inletReason(PassiveNetwork graph,int edge,double seconds) {
        var mover=(FlowControl.Mover)graph.pipes().get(edge).control();
        var supply=InletPhase.supply(authority.model,NetworkReduction.reopened(graph),edge,mover.targetVolumeFlow(),seconds);
        return InletPhase.reason(mover,supply).replace("from node "+supply.nodeId()+")","from "+authority.deviceLabel(supply.nodeId())+")");
    }
    private String nodeLabel(long id) {
        var registration=authority.topology.active().get(id);if(registration==null)return "Junction "+id;
        var p=registration.device().position();return p.x()+", "+p.y()+", "+p.z();
    }
    /** The unit's UA a heat law carries as planned (D16): an exchanger side's or an air cooler's; null for any other law. */
    private static com.wormzjl.icheme.science.fluid.network.ExchangerRelay.Conductance conductance(com.wormzjl.icheme.science.fluid.network.HeatLaw law) {
        return law instanceof com.wormzjl.icheme.science.fluid.network.HeatSide side?side.conductance()
                :law instanceof com.wormzjl.icheme.science.fluid.network.AirCooled cooled?cooled.conductance():null;
    }
}
