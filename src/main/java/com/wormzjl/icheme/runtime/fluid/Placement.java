package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.*;
import java.util.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.Laid;

/**
 * What a placed, changed or removed device does to the ledger, for {@link FluidWorldAuthority} (code base review R8): a
 * block's registration and its faces, a laid line and a rack lane, a vessel, a mover, a queued configuration edit, the
 * gas blanketing and pressure safety valves, a diverter's outlet, a removal, a hold, a drop and a filter's recovery. Each
 * is one queued ledger event ({@code submit}); nothing applies or solves here. Every entry is called by the authority,
 * which checks the thread ({@code owned()}) and, where the entry did, that it is open first.
 */
final class Placement {
    private final FluidWorldAuthority authority;
    Placement(FluidWorldAuthority authority){this.authority=authority;}
    /**
     * The faces the device registered at {@code position} draws joined (batch 2026-11-09-drawn-connections): its
     * as-built joins on the projected registry ({@link PhysicalFluidTopology#builtFaces}), so accepted input shows at once
     * and a block draws what the network joins. -1 where no device without ports stands. Reads no block and loads no
     * chunk: a neighbour in a chunk that is not loaded is known by its registration.
     */
    int drawnFaces(PhysicalFluidTopology.Position position){
        var record=authority.topology.latestAt(position);
        if(record==null||!record.device().position().equals(position)||!record.device().ports().isEmpty())return -1;
        return PhysicalFluidTopology.builtFaces(this::latestDevice,record.device());
    }
    /**
     * The faces a block of {@code kind} placed at {@code position} facing {@code facing} will draw joined once registered:
     * a hand-placed block, every face a joint and no fitting, as {@link #place} registers it.
     */
    int placedFaces(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing){
        var placed=PhysicalFluidTopology.Device.builder(Long.MAX_VALUE,position,kind,facing,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive()).generatorEnabled(kind!=TopologyCompiler.Kind.GENERATOR).build();
        return PhysicalFluidTopology.builtFaces(p->p.equals(position)?placed:latestDevice(p),placed);
    }
    private PhysicalFluidTopology.Device latestDevice(PhysicalFluidTopology.Position position){var record=authority.topology.latestAt(position);return record==null?null:record.device();}
    /**
     * Registers a laid line as one ledger event (batch 2026-10-26-pipe-laying-system, plan 4.4): a passive pipe of bore
     * {@code diameter} per cell, with its joints, and the new joints of the laid pipes it starts or ends at
     * ({@code hosts}, by identity), each new pipe painted {@code primary} and {@code secondary}. Returns the new pipes'
     * registrations in the order of {@code cells}.
     */
    List<WorldTopologyLedger.Registration> lay(List<Laid> cells,double diameter,Map<Long,Integer> hosts,int primary,int secondary) {
        if(cells.isEmpty())throw new IllegalArgumentException("An empty line");
        long first=authority.topology.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();var placed=new ArrayList<WorldTopologyLedger.Registration>();
        for(int i=0;i<cells.size();i++) {
            var cell=cells.get(i);
            var device=PhysicalFluidTopology.Device.builder(first+i,cell.position(),TopologyCompiler.Kind.PIPE,cell.facing(),new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
                    new FlowControl.Passive()).joints(cell.joints()).slots(cell.slots()).points(cell.points()).build();
            var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(authority.catalog),0);placed.add(record);edits.add(new WorldTopologyLedger.Edit(first+i,record));
        }
        for(var host:new TreeMap<>(hosts).entrySet()) {
            var old=authority.registrations().get(host.getKey());
            if(old==null||old.device().kind()!=TopologyCompiler.Kind.PIPE)throw new IllegalStateException("A laid line's host pipe is gone");
            edits.add(new WorldTopologyLedger.Edit(host.getKey(),new WorldTopologyLedger.Registration(old.device().withJoints(host.getValue()),old.spec(),Math.addExact(old.revision(),1))));
        }
        authority.submit(edits,Math.addExact(first,cells.size()));
        for(var record:placed)authority.paint(record.device().id(),primary,secondary);
        return placed;
    }
    /** A rack lane registered again from its rack's copy (a chunk load after the D4 reset): its joints, width, face points and bore. */
    WorldTopologyLedger.Registration placeLane(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,int joints,int slots,long points,double diameter) {
        long id=authority.topology.nextIdentity();
        var device=PhysicalFluidTopology.Device.builder(id,position,TopologyCompiler.Kind.PIPE,facing,new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),
                new FlowControl.Passive()).joints(joints).slots(slots).points(points).build();
        var record=new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(authority.catalog),0);
        authority.submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** A complete multi-cell mover uses the same controls and geometry as its one-block counterpart. */
    WorldTopologyLedger.Registration placeMover(PhysicalFluidTopology.Position suction,TopologyCompiler.Kind kind,List<PhysicalFluidTopology.Port> ports) {
        if(kind!=TopologyCompiler.Kind.PUMP&&kind!=TopologyCompiler.Kind.COMPRESSOR||ports.isEmpty())throw new IllegalArgumentException("Expected a multi-cell mover");
        return place(suction,kind,PhysicalFluidTopology.Direction.NORTH,ports);
    }
    WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,List<PhysicalFluidTopology.Port> ports) {
        return place(position,kind,facing,ports,PhysicalFluidTopology.Cutoff.NONE);
    }
    WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,List<PhysicalFluidTopology.Port> ports,PhysicalFluidTopology.Cutoff cutoff) {
        return place(position,kind,facing,ports,cutoff,false,false,TopologyCompiler.Kind.PIPE,PhysicalFluidTopology.ALL_FACES);
    }
    WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,List<PhysicalFluidTopology.Port> ports,PhysicalFluidTopology.Cutoff cutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind inlineMover,int joints) {
        authority.owned();
        if(pressureValve&&kind!=TopologyCompiler.Kind.PIPE)throw new IllegalArgumentException("Only a pipe hosts a pressure-control accessory");
        long id=authority.topology.nextIdentity();FlowControl control=switch(kind){case PUMP,COMPRESSOR->PhysicalFluidTopology.defaultMover(kind);case VALVE->FlowControl.ControlValve.unconfigured();default->new FlowControl.Passive();};
        if(inlineMover!=TopologyCompiler.Kind.PIPE){if(kind!=TopologyCompiler.Kind.PIPE||pressureValve||filter)throw new IllegalArgumentException("Conflicting inline mover");control=PhysicalFluidTopology.defaultMover(inlineMover);}
        if(pressureValve)control=FlowControl.ControlValve.unconfigured();
        double diameter=kind==TopologyCompiler.Kind.PIPE||kind==TopologyCompiler.Kind.DIVERTER_VALVE?placedPipeDiameter(position):.05;
        // A diverter valve is placed open to its straight outlet (batch 2026-11-09-batch-distiller).
        if(kind==TopologyCompiler.Kind.DIVERTER_VALVE&&joints==PhysicalFluidTopology.ALL_FACES)joints=PhysicalFluidTopology.diverterJoints(facing,0);
        var device=PhysicalFluidTopology.Device.builder(id,position,kind,facing,new PipeResistance.Geometry(1,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),control).ports(ports).generatorEnabled(kind!=TopologyCompiler.Kind.GENERATOR).cutoff(cutoff).inlineFilter(filter).joints(joints).build();
        var spec=kind==TopologyCompiler.Kind.GENERATOR?FluidDeviceSpec.water(authority.catalog):FluidDeviceSpec.nitrogen(authority.catalog);
        // A reservoir is placed empty (decision D54): of its settings only the capacity means anything.
        if(kind==TopologyCompiler.Kind.RESERVOIR)spec=new FluidDeviceSpec(authority.options.volume(),298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,spec.composition());
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        authority.submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * Registers a gas intake at {@code position} (batch 2026-11-15-fluid-interfaces, W2): a generator that is the atmosphere,
     * placed on, its charge the ambient air ({@link #intake}).
     */
    WorldTopologyLedger.Registration placeIntake(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing) {
        authority.owned();long id=authority.topology.nextIdentity();
        var record=intake(id,position,facing,placedPipeDiameter(position),authority.model());
        authority.submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * A gas intake's registration (decisions D7 and D8): a generator, switched on, whose spec is the atmosphere
     * ({@code ambient}): {@link com.wormzjl.icheme.science.fluid.network.CoolingTower#AIR_TEMPERATURE} and the game's ambient
     * pressure, {@link com.wormzjl.icheme.science.fluid.network.Air#wet} on {@code model}'s list; 1 m at the joined pipe's
     * bore {@code bore}, its louvres and hood an entry loss of {@link #INTAKE_LOSS} velocity heads.
     */
    static WorldTopologyLedger.Registration intake(long id,PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,double bore,com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model) {
        var device=PhysicalFluidTopology.Device.builder(id,position,TopologyCompiler.Kind.GENERATOR,facing,new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,INTAKE_LOSS),new FlowControl.Passive()).generatorEnabled(true).build();
        double height=com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.LEVEL_HEAD_HEIGHT;
        var spec=new FluidDeviceSpec(1,com.wormzjl.icheme.science.fluid.network.CoolingTower.AIR_TEMPERATURE,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,
                com.wormzjl.icheme.science.fluid.network.Air.wet(model),SlurryFeed.NONE,height,false,com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(1,height),null,0,0,null,true);
        return new WorldTopologyLedger.Registration(device,spec,0);
    }
    /** A gas intake's entry loss, velocity heads at the pipe's bore (D8: a louvred box and a rain hood, K = 3). */
    static final double INTAKE_LOSS=3;
    /**
     * A liquid hopper's registration (batch 2026-11-15-fluid-interfaces, W4, decision D5): a generator, on, its valve its
     * converter's ({@link FlowControl.ValveSource#CONVERTER}), 1 m at the joined pipe's bore {@code bore}, standing at
     * {@link #HOPPER_PRESSURE} with nitrogen until its first plan gives its rule's stream.
     */
    static WorldTopologyLedger.Registration hopper(long id,PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing,double bore,double[] nitrogen) {
        var device=PhysicalFluidTopology.Device.builder(id,position,TopologyCompiler.Kind.GENERATOR,facing,new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),FlowControl.ControlValve.converter()).build();
        return new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,HOPPER_PRESSURE,nitrogen),0);
    }
    /** Where a hopper's liquid stands, Pa: the atmosphere and 0.5 m of water (D5, a fixed number), so it runs down a line by gravity. */
    public static final double HOPPER_PRESSURE=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE+4905;
    /** A hopper's rate, L per process second (D6), and its Forge tank, mB (owner: "Liquid hopper 4000mb"). */
    public static final double HOPPER_RATE=.1;
    public static final long HOPPER_CAPACITY=4000;
    /** Registers a liquid hopper at {@code position} as one queued event, then its converter's facts ({@link ConverterUnit#hopper}). */
    WorldTopologyLedger.Registration placeHopper(PhysicalFluidTopology.Position position,PhysicalFluidTopology.Direction facing) {
        authority.owned();long id=authority.topology.nextIdentity();
        var record=hopper(id,position,facing,placedPipeDiameter(position),FluidDeviceSpec.nitrogen(authority.catalog).composition());
        authority.submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));
        authority.converters().register(ConverterUnit.hopper(id,HOPPER_RATE,HOPPER_CAPACITY));return record;
    }
    /** Inherit the largest mechanically connected adjacent pipe's latest bore, including a closed axial cutoff. */
    private double placedPipeDiameter(PhysicalFluidTopology.Position position) {
        double largest=0;
        for(var direction:PhysicalFluidTopology.Direction.values()) {
            var neighbour=authority.registry.at(position.offset(direction)).orElse(null);
            if(neighbour!=null&&neighbour.device().kind()==TopologyCompiler.Kind.PIPE&&neighbour.device().mechanicallyConnects(PhysicalFluidTopology.opposite(direction)))
                largest=Math.max(largest,neighbour.device().geometry().diameter());
        }
        return largest>0?largest:.05;
    }
    WorldTopologyLedger.Registration placeVessel(PhysicalFluidTopology.Position node,List<PhysicalFluidTopology.Port> ports,double volume,double levelHeight,boolean open,com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry,com.wormzjl.icheme.science.fluid.network.CoolingTower tower) {
        long id=authority.topology.nextIdentity();
        var device=PhysicalFluidTopology.Device.builder(id,node,TopologyCompiler.Kind.RESERVOIR,PhysicalFluidTopology.Direction.NORTH,
                new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive()).ports(ports).build();
        var spec=new FluidDeviceSpec(volume,298.15,com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE,FluidDeviceSpec.nitrogen(authority.catalog).composition(),SlurryFeed.NONE,levelHeight,open,geometry,tower);
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        authority.submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /**
     * Queues a configuration change as a ledger event and returns it, or null when nothing would change. A fired heater's
     * coils share their burners and set point (batch 2026-10-27-heat-exchanger-design): a coil's new control moves its
     * partner coil in the same event.
     */
    WorldTopologyLedger.Event edit(long id,long expectedRevision,PhysicalFluidTopology.Device device,FluidDeviceSpec spec) {
        var old=authority.registrations().get(id);if(old==null||old.revision()!=expectedRevision||device.id()!=id)throw new IllegalStateException("Stale fluid controls");
        if(old.device().equals(device)&&old.spec().equals(spec))return null;
        var edits=new ArrayList<WorldTopologyLedger.Edit>();edits.add(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,Math.addExact(expectedRevision,1))));
        var heat=device.heat();var partner=heat!=null&&heat.burners()?authority.registrations().get(heat.partner()):null;
        if(partner!=null&&partner.device().heat()!=null&&!partner.device().heat().control().equals(heat.control()))
            edits.add(new WorldTopologyLedger.Edit(partner.device().id(),new WorldTopologyLedger.Registration(partner.device().withHeat(partner.device().heat().withControl(heat.control())),partner.spec(),Math.addExact(partner.revision(),1))));
        return authority.submit(edits,authority.topology.nextIdentity());
    }
    /**
     * Fits a closed vessel's gas blanketing valve, its gas inlet {@code port} (role {@code BLANKET}), or removes it
     * ({@code port} null), as one queued ledger event (batch 2026-11-11-gas-blanketing). A fitted valve starts at
     * {@link FluidDeviceSpec#DEFAULT_BLANKET}; a removed one leaves no set pressure. False for a device that takes no valve: one
     * that is not a closed vessel, or whose inlet meets another of its ports.
     */
    boolean blanketValve(long id,PhysicalFluidTopology.Port port) {
        var record=authority.registrations().get(id);
        if(record==null||record.device().kind()!=TopologyCompiler.Kind.RESERVOIR||record.spec().open()||record.device().ports().isEmpty())return false;
        if(port!=null&&port.role()!=PhysicalFluidTopology.ConnectionRole.BLANKET)throw new IllegalArgumentException("A gas blanketing valve's inlet is a blanket gas port");
        var ports=new ArrayList<>(record.device().ports());ports.removeIf(p->p.role()==PhysicalFluidTopology.ConnectionRole.BLANKET);
        if(port!=null){for(var p:ports)if(p.cell().equals(port.cell())&&p.face()==port.face())return false;ports.add(port);}
        // A pressure safety valve already fitted stays above the new valve's vent pressure.
        if(port!=null&&record.spec().relief()>0&&!(record.spec().relief()>com.wormzjl.icheme.science.fluid.network.GasBlanket.ventPressure(FluidDeviceSpec.DEFAULT_BLANKET)))return false;
        authority.edit(id,record.revision(),record.device().withPlacement(record.device().position(),ports),record.spec().withBlanket(port==null?0:FluidDeviceSpec.DEFAULT_BLANKET));
        return true;
    }
    /** Sets a fitted gas blanketing valve's set pressure, Pa absolute, as a queued ledger event; false for a vessel without one or a pressure outside the valve's range. */
    boolean blanketPressure(long id,double pascal) {
        var record=authority.registrations().get(id);
        if(record==null||!(record.spec().blanket()>0)||!(pascal>=FluidDeviceSpec.MIN_BLANKET)||!(pascal<=FluidDeviceSpec.MAX_BLANKET))return false;
        // A pressure safety valve on the same vessel stays above the blanketing valve's vent pressure.
        if(record.spec().relief()>0&&!(record.spec().relief()>com.wormzjl.icheme.science.fluid.network.GasBlanket.ventPressure(pascal)))return false;
        authority.edit(id,record.revision(),record.device(),record.spec().withBlanket(pascal));return true;
    }
    /**
     * Fits a closed vessel's pressure safety valve, its outlet {@code port} (role {@code RELIEF}), or removes it ({@code port}
     * null), as one queued ledger event (batch 2026-11-11-gas-blanketing, the pressure safety valve). A fitted valve starts at
     * {@link FluidDeviceSpec#DEFAULT_RELIEF}, or just above a blanketing valve's vent pressure where that is higher. False for a
     * device that takes no valve: one that is not a closed vessel, or whose outlet meets another of its ports.
     */
    boolean reliefValve(long id,PhysicalFluidTopology.Port port) {
        var record=authority.registrations().get(id);
        if(record==null||record.device().kind()!=TopologyCompiler.Kind.RESERVOIR||record.spec().open()||record.device().ports().isEmpty())return false;
        if(port!=null&&port.role()!=PhysicalFluidTopology.ConnectionRole.RELIEF)throw new IllegalArgumentException("A pressure safety valve's outlet is a relief port");
        var ports=new ArrayList<>(record.device().ports());ports.removeIf(p->p.role()==PhysicalFluidTopology.ConnectionRole.RELIEF);
        if(port!=null){for(var p:ports)if(p.cell().equals(port.cell())&&p.face()==port.face())return false;ports.add(port);}
        double set=port==null?0:fittedRelief(record.spec());if(port!=null&&!(set>0))return false;
        authority.edit(id,record.revision(),record.device().withPlacement(record.device().position(),ports),record.spec().withRelief(set,null));
        return true;
    }
    /** The set pressure a newly fitted pressure safety valve starts at on a device with {@code spec}: the default, or just above its blanketing valve's vent pressure; 0 where none fits. */
    static double fittedRelief(FluidDeviceSpec spec) {
        double set=FluidDeviceSpec.DEFAULT_RELIEF;
        if(spec.blanket()>0)set=Math.max(set,Math.ceil(com.wormzjl.icheme.science.fluid.network.GasBlanket.ventPressure(spec.blanket())*1.1/1000)*1000);
        return spec.reliefTakes(set)?set:0;
    }
    /**
     * Fits a pressure safety valve on pipe {@code id}, its outlet leaving through {@code face}, or removes it ({@code face}
     * null), as one queued ledger event. The pipe's other faces stay joined as a plain pipe's. False for a device that is no
     * plain pipe: a pipe carrying a fitting, a rack lane, or a pipe with ports.
     */
    boolean pipeRelief(long id,PhysicalFluidTopology.Direction face) {
        var record=authority.registrations().get(id);if(record==null)return false;var d=record.device();
        if(d.kind()!=TopologyCompiler.Kind.PIPE||!d.ports().isEmpty()||d.slots()>0||face!=null&&(d.actuator()||d.filter()||d.cutoff()!=PhysicalFluidTopology.Cutoff.NONE))return false;
        authority.edit(id,record.revision(),d,record.spec().withRelief(face==null?0:FluidDeviceSpec.DEFAULT_RELIEF,face));
        return true;
    }
    /** Sets a fitted pressure safety valve's set pressure, Pa absolute, as a queued ledger event; false for a device without one or a pressure it does not take ({@link FluidDeviceSpec#reliefTakes}). */
    boolean reliefPressure(long id,double pascal) {
        var record=authority.registrations().get(id);
        if(record==null||!(record.spec().relief()>0)||!record.spec().reliefTakes(pascal))return false;
        authority.edit(id,record.revision(),record.device(),record.spec().withRelief(pascal,record.spec().reliefFace()));return true;
    }
    /**
     * Sets diverter valve {@code id} to outlet {@code outlet} (0 straight through, 1 and 2 beside it; batch
     * 2026-11-09-batch-distiller): a fact, one queued edit of its joints; its island recompiles on the engine's schedule.
     * Null, or why not.
     */
    @org.jetbrains.annotations.Nullable String divert(long id,int outlet) {
        var r=authority.topology.latest().get(id);
        if(r==null||r.device().kind()!=TopologyCompiler.Kind.DIVERTER_VALVE)return "no diverter valve";
        if(outlet<0||outlet>2)return "a diverter valve has three outlets";
        int joints=PhysicalFluidTopology.diverterJoints(r.device().facing(),outlet);
        if(joints==r.device().joints())return null;
        if(authority.topology.pending(id))return "the diverter valve is being edited";
        authority.edit(id,r.revision(),r.device().withJoints(joints),r.spec());
        return null;
    }
    /** The open outlet of diverter valve {@code id} (0 to 2), or -1 for none. */
    int diverterOutlet(long id) {
        var r=authority.topology.latest().get(id);
        return r==null||r.device().kind()!=TopologyCompiler.Kind.DIVERTER_VALVE?-1:PhysicalFluidTopology.diverterOutlet(r.device().facing(),r.device().joints());
    }
    void remove(long id){authority.controls().removeHost(id);authority.instruments.recoverHost(id);var old=authority.registrations().get(id);if(old!=null)authority.submit(List.of(new WorldTopologyLedger.Edit(id,null)),authority.topology.nextIdentity(),old.device().filter()?new WorldTopologyLedger.Recovery(id,null):null);}
    /**
     * Holds registration {@code id}, or releases it (decision S11, REGISTRY_FIRST_PLAN.md 4.1): held, it keeps what it holds
     * and joins nothing. A topology edit at its next revision, so its islands recompile on the engine's schedule. Whether an
     * edit was queued: none for an unknown identity, a column's port, or one already so.
     */
    boolean hold(long id,boolean held) {
        var r=authority.registrations().get(id);if(r==null||r.device().port()||r.device().held()==held)return false;
        authority.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(r.device().withHeld(held),r.spec(),Math.addExact(r.revision(),1)))),authority.topology.nextIdentity());
        return true;
    }
    /** Every held part (W5c C5): the held registrations, shaft parts, cables, controllers and consoles. */
    int heldCount() {
        int count=authority.held().size();for(var u:authority.topology.shafts().values())if(u.held())count++;
        var facts=authority.controls().facts();return count+facts.held().size()+facts.heldPanels().size();
    }
    /** The held registrations, queued holds included, by identity. */
    List<WorldTopologyLedger.Registration> held() {
        var held=new ArrayList<WorldTopologyLedger.Registration>();
        for(var r:authority.registrations().values())if(r.device().held())held.add(r);
        held.sort(Comparator.comparingLong(r->r.device().id()));return held;
    }
    /**
     * An operator drops held registration {@code id} (REGISTRY_FIRST_PLAN.md 4.5): it leaves as a broken device does, what
     * it holds booked as removed, but nothing drops (decision S9): its block is not in the world. Its accessories leave the
     * accessory store with it. Whether it was held.
     */
    boolean drop(long id) {
        var r=authority.registrations().get(id);if(r==null||!r.device().held())return false;
        authority.instruments.discardHost(id);authority.submit(List.of(new WorldTopologyLedger.Edit(id,null)),authority.topology.nextIdentity());
        return true;
    }
    /**
     * Queues a filter's solid recovery as a ledger event and returns it, as an edit is queued: nothing is applied or
     * delivered here. The engine applies it with the next batch of events, takes the cake the island holds then and
     * delivers the item; an empty filter or a full inventory at that point refuses it in the menu's next reply.
     */
    WorldTopologyLedger.Event recoverFilter(long id,long expectedRevision,net.minecraft.server.level.ServerPlayer player){
        var old=authority.registrations().get(id);if(old==null||old.revision()!=expectedRevision||!old.device().filter())throw new IllegalStateException("Stale filter controls");
        if(player.getInventory().getFreeSlot()<0)throw new IllegalStateException("Inventory full; filter unchanged");
        return authority.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(old.device(),old.spec(),Math.addExact(old.revision(),1)))),authority.topology.nextIdentity(),new WorldTopologyLedger.Recovery(id,player.getUUID()));
    }
}
