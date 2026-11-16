package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.plant.machine.MachineRatings;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The registration of a placed structure's devices with the fluid world: a whole structure by its kind ({@link #register}),
 * its rebinding after a load ({@link #restoreFluidBinding}), its restatement onto a rebuilt layout ({@link #reregister}) and
 * its removal ({@link #unregister}), with the nodes, ports, heat sides, still and converter devices only these read. Server
 * thread only; {@link EquipmentStructure} places and removes the cells.
 */
final class StructureRegistration {
    private StructureRegistration() {
    }

    /**
     * A whole drum joins the fluid network as a reservoir (owner request 2026-10-16): its node at its lowest inside cell,
     * each nozzle a port of the class its position fixes (top: vapour; lowest side: liquid; other sides: bulk), its
     * design volume, and its liquid height for the static head {@code g·m·H/V}. It is placed empty, as a tank is.
     */
    static void register(ServerLevel level, Vessel vessel) {
        place(level, vessel);
        material(level, vessel);
    }

    /**
     * Every device the structure holds at its nodes is built of its shape's material (batch 2026-11-16-fouling-corrosion):
     * one queued event after a registration, a restored binding or a refresh; none when they already are.
     */
    private static void material(ServerLevel level, Vessel vessel) {
        if (!vessel.shape().fluidDevice()) return;
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.material(EquipmentStructure.heldAtNodes(world, level, vessel), vessel.shape().material()));
    }

    private static void place(ServerLevel level, Vessel vessel) {
        if (vessel.shape().kind() == EquipmentShape.Kind.BATCH_STILL) {
            registerStill(level, vessel, 0);
            return;
        }
        if (vessel.shape().kind().converter()) {
            registerConverter(level, vessel, 0);
            return;
        }
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            registerUnit(level, vessel);
            return;
        }
        var node = node(vessel);
        if (vessel.shape().kind() == EquipmentShape.Kind.FLARE_STACK) {
            // A flare's tip at its gas inlet's cell (batch 2026-11-09-flare-stack), its tip diameter the structure's size.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeFlare(Positions.of(level, node), ports(level, vessel), vessel.shape().diameter());
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().kind().chimney()) {
            // A chimney's tip in its top cell (batch 2026-11-10-chimney): the stack from its inlet up is the run into it.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeChimney(Positions.of(level, node), ports(level, vessel), vessel.shape());
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().kind() == EquipmentShape.Kind.EJECTOR) {
            // A steam ejector set on its curve at its suction's cell (batch 2026-11-10-steam-ejector-flow): no shaft.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeMachine(Positions.of(level, node), TopologyCompiler.Kind.EJECTOR, ports(level, vessel), MachineRatings.ejectorControl(vessel.shape()));
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().kind().primeMover()) {
            // A prime mover (batch 2026-11-10-prime-movers): its fuel void at its combustor's pressure behind its FUEL nozzle, a gas
            // turbine's steam coil at its FEEDWATER nozzle; its shaft unit names the fuel void.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var shape = vessel.shape();
                var model = com.wormzjl.icheme.science.equipment.UnitDesign.primeMoverModel(shape);
                var cells = EquipmentStructure.nodes(vessel);
                var fuelPorts = rolePorts(level, vessel, EquipmentShape.Role.FUEL);
                FluidWorldAuthority.UnitSide coil = null;
                if (cells.size() > 1) {
                    var definition = new HeatSides.Definition(HeatSides.Side.EXHAUST, EquipmentShape.Role.FEEDWATER, EquipmentShape.Role.STEAM,
                            com.wormzjl.icheme.science.equipment.UnitDesign.coilSurface(shape), com.wormzjl.icheme.science.equipment.UnitDesign.coilPassage(shape));
                    coil = new FluidWorldAuthority.UnitSide(Positions.of(level, cells.get(1)), sidePorts(level, vessel, definition), definition.passage(),
                            new HeatUnit(HeatSides.Unit.PRIME_MOVER, HeatSides.Side.EXHAUST, com.wormzjl.icheme.science.equipment.UnitDesign.coilSurface(shape), 0));
                }
                var placed = world.placePrimeMover(Positions.of(level, cells.getFirst()), fuelPorts, model.family() == com.wormzjl.icheme.science.machine.PrimeMover.Family.GAS_TURBINE
                        ? model.combustor() : com.wormzjl.icheme.science.machine.PrimeMover.ATMOSPHERE, coil);
                for (int i = 0; i < placed.size(); i++)
                    if (level.getBlockEntity(cells.get(i)) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(placed.get(i).device().id());
                ShaftParts.structure(level, vessel.origin(), shape, placed.getFirst().device().id());
            });
            return;
        }
        if (vessel.shape().kind().mover() || turbine(vessel.shape())) {
            // A casing on its curve, a frame at its displacement, a turbine on its steam (W3): each turns with its shaft train.
            var kind = turbine(vessel.shape()) ? TopologyCompiler.Kind.TURBINE : vessel.shape().kind() == EquipmentShape.Kind.PUMP ? TopologyCompiler.Kind.PUMP : TopologyCompiler.Kind.COMPRESSOR;
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeMachine(Positions.of(level, node), kind, ports(level, vessel), MachineRatings.machineControl(vessel.shape()));
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
                ShaftParts.structure(level, vessel.origin(), vessel.shape(), record.device().id());
            });
            return;
        }
        if (vessel.shape().volume() > FluidDeviceSpec.MAX_VOLUME) {
            IChemE.LOGGER.warn("A {} of {} m³ is over the fluid model's {} m³: not registered", vessel.shape().kind().name().toLowerCase(Locale.ROOT),
                    Math.round(vessel.shape().volume()), Math.round(FluidDeviceSpec.MAX_VOLUME));
            return;
        }
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var record = world.placeVessel(Positions.of(level, node), ports(level, vessel), vessel.shape().volume(), vessel.shape().levelHeight(), open(vessel), vessel.shape().fluidGeometry(),vessel.shape().kind().coolingTower()?vessel.shape().coolingTower():null);
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
        });
    }

    /**
     * Rebind a saved registration, or queue defaults for a complete placed structure with none. Returns false only
     * while chunks are missing, so a later chunk-load event retries it. Partial or stale layouts stay inactive;
     * normal construction or the existing layout refresh handles them. No blocks are changed and nothing solves.
     */
    static boolean restoreFluidBinding(ServerLevel level,Vessel vessel) {
        boolean done=restoreBinding(level,vessel);
        if(done)material(level,vessel);
        return done;
    }
    private static boolean restoreBinding(ServerLevel level,Vessel vessel) {
        // A motor, generator or gearbox's shaft unit (W3); queued again on every load, a no-op when nothing changed.
        if(!vessel.shape().fluidDevice()){ShaftParts.structure(level,vessel.origin(),vessel.shape(),0);return true;}
        var world=FluidWorldAuthority.find(level.getServer()).orElse(null);if(world==null)return false;
        if(vessel.shape().kind()==EquipmentShape.Kind.BATCH_STILL)return restoreStill(level,vessel,world);
        boolean converter=vessel.shape().kind().converter();
        boolean unit=HeatSides.unit(vessel.shape().kind())!=null;
        var heldIds=unit?EquipmentStructure.heldAtNodes(world,level,vessel):List.<Long>of();
        if(unit&&EquipmentStructure.unitRecords(world,heldIds).size()==registrations(vessel.shape())) {
            // A whole pair binds where its cells are loaded; none or half of a pair registers afresh below, once complete.
            boolean loaded=true;
            for(var at:EquipmentStructure.nodes(vessel))loaded&=level.hasChunkAt(at)&&level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell&&EquipmentStructure.ours(shell,vessel.origin(),vessel.shape());
            if(loaded)restateUnit(level,vessel,world,heldIds);
            return true;
        }
        var node=node(vessel);var held=Positions.of(level,node);
        var record=unit?null:world.at(held).orElse(null);
        if(record!=null) {
            if(level.hasChunkAt(node)&&record.device().position().equals(held)&&!record.device().ports().isEmpty()
                    &&level.getBlockEntity(node) instanceof EquipmentShellBlockEntity shell&&EquipmentStructure.ours(shell,vessel.origin(),vessel.shape())) {
                if(vessel.shape().reservoir()&&!record.spec().geometry().equals(vessel.shape().fluidGeometry()))reregister(level,vessel,node,List.of());
                // A converter missing its facts or a hidden port (W3) is restated whole onto its vessel, which keeps what it holds.
                if(converter&&world.converterDevices(record.device().id()).size()<3)reregister(level,vessel,node,List.of());
                // A casing saved before W3 (reading A29): its set-flow control becomes its machine, keeping its set flow.
                var machine=restoredControl(vessel.shape(),record.device().control());
                if(machine!=null)world.edit(record.device().id(),record.revision(),record.device().withControl(machine),record.spec());
                ShaftParts.structure(level,vessel.origin(),vessel.shape(),record.device().id());
                if(shell.fluidIdentity()!=record.device().id())shell.bindIdentity(record.device().id());else world.deviceLoaded(record.device().id());
            }
            return true;
        }
        var origin=vessel.origin();var shape=vessel.shape();
        if(!StructureRefresh.loaded(level,origin,StructureRefresh.corner(origin,shape)))return false;
        var layout=ShapeRasterizer.layout(shape);
        for(var cell:layout.shell()) {
            var at=origin.offset(cell.x(),cell.y(),cell.z());
            if(!(level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell)||!EquipmentStructure.ours(shell,origin,shape)||shell.stale())return true;
        }
        for(var cell:layout.interior())if(!level.getBlockState(origin.offset(cell.x(),cell.y(),cell.z())).is(ModBlocks.EQUIPMENT_INTERIOR.get()))return true;
        if(unit)restateUnit(level,vessel,world,heldIds);else register(level,vessel);
        return true;
    }

    /**
     * A whole heat-exchanging unit joins the fluid network as one heat side per passage (batch
     * 2026-10-27-heat-exchanger-design, {@link HeatSides}): each at its first inlet's cell, its inlet and outlet nozzles
     * its ports, its passage and surface the datasheet's. Its sides are placed in one event, as partners.
     */
    private static void registerUnit(ServerLevel level, Vessel vessel) {
        var shape = vessel.shape();
        var unit = HeatSides.unit(shape.kind());
        var sides = HeatSides.of(shape);
        var nodes = EquipmentStructure.nodes(vessel);
        var placing = new ArrayList<FluidWorldAuthority.UnitSide>();
        for (int i = 0; i < sides.size(); i++) {
            var side = sides.get(i);
            placing.add(new FluidWorldAuthority.UnitSide(Positions.of(level, nodes.get(i)), sidePorts(level, vessel, side), side.passage(), new HeatUnit(unit, side.side(), side.area(), 0).withFans(HeatSides.fans(shape)).withExchangerType(shape.exchangerType())));
        }
        // A kettle's shell (W7): a closed vessel at its pool's node, its phase ports the drum's, its surface its tubes'.
        var pool = HeatSides.pool(shape.kind()) ? new FluidWorldAuthority.UnitVessel(Positions.of(level, nodes.get(sides.size())), ports(level, vessel), shape.volume(), shape.levelHeight(),
                shape.fluidGeometry(), new HeatUnit(unit, HeatSides.Side.SHELL, sides.getFirst().area(), 0)) : null;
        // A fired heater's burners (batch 2026-11-09-furnace-fuel-power): the firebox behind its FUEL nozzle, after its coils.
        var burners = HeatSides.burners(shape.kind()) ? burners(level, vessel, nodes.get(sides.size())) : null;
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var records = world.placeUnit(placing, pool, burners);
            for (int i = 0; i < records.size(); i++)
                if (level.getBlockEntity(nodes.get(i)) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(records.get(i).device().id());
        });
    }

    /** A heat side's nozzles: its inlets and outlets in nozzle order, so its first inlet is its node ({@link ShapeRasterizer#fluidCells}). */
    /** The vessel port of {@code vessel}'s first nozzle of {@code role} (a prime mover's fuel), or none. */
    private static List<PhysicalFluidTopology.Port> rolePorts(Level level, Vessel vessel, EquipmentShape.Role role) {
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (nozzles.get(port.nozzle()).role() != role) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            return List.of(new PhysicalFluidTopology.Port(Positions.of(level, cell), face, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL));
        }
        return List.of();
    }

    private static List<PhysicalFluidTopology.Port> sidePorts(Level level, Vessel vessel, HeatSides.Definition side) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            var role = nozzles.get(port.nozzle()).role();
            var connection = role == side.inlet() ? PhysicalFluidTopology.ConnectionRole.INLET : role == side.outlet() ? PhysicalFluidTopology.ConnectionRole.OUTLET : null;
            if (connection == null) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            ports.add(new PhysicalFluidTopology.Port(Positions.of(level, cell), face, PassiveNetwork.PhasePort.BULK, connection));
        }
        return ports;
    }

    /** A heat unit's registrations: one per side, a kettle's shell (W7) and a fired heater's burners. */
    private static int registrations(EquipmentShape shape) {
        return HeatSides.of(shape).size() + (HeatSides.pool(shape.kind()) ? 1 : 0) + (HeatSides.burners(shape.kind()) ? 1 : 0);
    }

    /** A fired heater's burners to place at {@code node}: the FUEL nozzle its one port (batch 2026-11-09-furnace-fuel-power). */
    private static FluidWorldAuthority.UnitBurners burners(Level level, Vessel vessel, BlockPos node) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (nozzles.get(port.nozzle()).role() != HeatSides.BURNER_ROLE) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            ports.add(new PhysicalFluidTopology.Port(Positions.of(level, cell), face, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL));
            break;
        }
        var radiant = HeatSides.of(vessel.shape()).stream().filter(s -> s.side() == HeatSides.Side.RADIANT).findFirst().orElseThrow();
        return new FluidWorldAuthority.UnitBurners(Positions.of(level, node), ports, new HeatUnit(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.BURNER, radiant.area(), 0));
    }

    /**
     * Moves or restates a unit's sides onto {@code vessel}'s layout (decision D7: each keeps its identity, partner, control
     * and heat in transit), or registers the unit afresh when its sides are not all there. {@code held} are the identities
     * its cells held. A side's passage and surface follow the datasheet.
     */
    private static void restateUnit(ServerLevel level, Vessel vessel, FluidWorldAuthority world, java.util.Collection<Long> held) {
        var sides = HeatSides.of(vessel.shape());
        var records = EquipmentStructure.unitRecords(world, held);
        // A fired heater placed before its burners registered (batch 2026-11-09-furnace-fuel-power): its coils keep their
        // identities, set point and burners' switch; the burners join them at their node, partnered with the radiant coil.
        if (HeatSides.burners(vessel.shape().kind()) && !records.containsKey(HeatSides.Side.BURNER) && records.containsKey(HeatSides.Side.RADIANT)
                && records.containsKey(HeatSides.Side.CONVECTION) && records.size() + 1 == registrations(vessel.shape()))
            records.put(HeatSides.Side.BURNER, world.placeBurners(records.get(HeatSides.Side.RADIANT).device().id(), burners(level, vessel, EquipmentStructure.nodes(vessel).get(sides.size()))));
        if (records.size() != registrations(vessel.shape())) {
            if (!records.isEmpty()) world.removeUnit(records.values().stream().map(r -> r.device().id()).toList());
            registerUnit(level, vessel);
            return;
        }
        var nodes = EquipmentStructure.nodes(vessel);
        for (int i = 0; i < sides.size(); i++) {
            var side = sides.get(i);
            var record = records.get(side.side());
            var node = nodes.get(i);
            var device = record.device();
            // An air cooler's fans follow the drawing (W5), as its surface does.
            var moved = device.withPlacement(Positions.of(level, node), sidePorts(level, vessel, side)).withGeometry(side.passage()).withHeat(device.heat().withArea(side.area()).withFans(HeatSides.fans(vessel.shape())));
            if (!moved.equals(device)) world.edit(device.id(), record.revision(), moved, record.spec());
            for (var other : nodes)
                if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
                if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
                else world.deviceLoaded(device.id());
            }
        }
        if (HeatSides.burners(vessel.shape().kind())) {
            // The burners move to their FUEL nozzle's node with it.
            var record = records.get(HeatSides.Side.BURNER);
            var node = nodes.get(sides.size());
            var device = record.device();
            var moved = device.withPlacement(Positions.of(level, node), burners(level, vessel, node).ports()).withHeat(device.heat().withArea(records.get(HeatSides.Side.RADIANT).device().heat().area()));
            if (!moved.equals(device)) world.edit(device.id(), record.revision(), moved, record.spec());
            for (var other : nodes)
                if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
                if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
                else world.deviceLoaded(device.id());
            }
            return;
        }
        if (!HeatSides.pool(vessel.shape().kind())) return;
        // A kettle's shell (W7) moves to its pool's node with its ports, its vessel follows the datasheet, it keeps what it holds.
        var record = records.get(HeatSides.Side.SHELL);
        var node = nodes.get(sides.size());
        var device = record.device();
        var shape = vessel.shape();
        var moved = device.withPlacement(Positions.of(level, node), ports(level, vessel)).withHeat(device.heat().withArea(sides.getFirst().area()));
        var spec = record.spec();
        // A breach (WP6b) is a fact the refresh keeps.
        var respec = new FluidDeviceSpec(shape.volume(), spec.temperature(), spec.pressure(), spec.composition(), spec.solids(), shape.levelHeight(), false, shape.fluidGeometry(), null).withBreached(spec.breached()).withMetallurgy(shape.material());
        if (!moved.equals(device) || !respec.equals(spec)) world.edit(device.id(), record.revision(), moved, respec);
        for (var other : nodes)
            if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
        if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
            if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
            else world.deviceLoaded(device.id());
        }
    }

    /** A floating-roof tank registers open to the atmosphere (batch 2026-10-21-floating-roof-tanks); a drum and a sphere closed. */
    private static boolean open(Vessel vessel) {
        return vessel.shape().kind() == EquipmentShape.Kind.TANK || vessel.shape().kind().coolingTower();
    }

    /** A reservoir's lowest inside cell, or a mover's suction nozzle cell ({@link ShapeRasterizer#fluidCell}). */
    private static BlockPos node(Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());
        return vessel.origin().offset(cell.x(), cell.y(), cell.z());
    }

    /** Reservoir nozzles keep their phase classes; movers register only their explicitly directed process pair; a kettle's
     * shell (W7) its own nozzles, its tubes' left to their side. */
    private static List<PhysicalFluidTopology.Port> ports(Level level, Vessel vessel) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        var passages = new java.util.HashSet<EquipmentShape.Role>();
        for (var side : HeatSides.of(vessel.shape())) { passages.add(side.inlet()); passages.add(side.outlet()); }
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (passages.contains(nozzles.get(port.nozzle()).role())) continue;
            var role = PhysicalFluidTopology.ConnectionRole.VESSEL;
            if (vessel.shape().kind() == EquipmentShape.Kind.EJECTOR) {
                // A steam ejector's suction, its motive steam as an inlet, its discharge (batch 2026-11-10-steam-ejector-flow).
                role = switch (nozzles.get(port.nozzle()).role()) {
                    case SUCTION -> PhysicalFluidTopology.ConnectionRole.SUCTION;
                    case MOTIVE -> PhysicalFluidTopology.ConnectionRole.INLET;
                    case DISCHARGE -> PhysicalFluidTopology.ConnectionRole.DISCHARGE;
                    default -> null;
                };
                if (role == null) continue;
            } else if (vessel.shape().kind().mover() || turbine(vessel.shape())) {
                // A turbine's steam inlet is its suction, its exhaust its discharge (W3, reading A37).
                role = switch (nozzles.get(port.nozzle()).role()) {
                    case SUCTION, STEAM -> PhysicalFluidTopology.ConnectionRole.SUCTION;
                    case DISCHARGE, EXHAUST -> PhysicalFluidTopology.ConnectionRole.DISCHARGE;
                    default -> null;
                };
                if (role == null) continue; // Oil-cooler and other auxiliary nozzles remain decorative.
            }
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            var phase = switch (port.port()) {
                case VAPOUR -> PassiveNetwork.PhasePort.VAPOR;
                case LIQUID -> PassiveNetwork.PhasePort.LIQUID;
                case BULK -> PassiveNetwork.PhasePort.BULK;
                case OVERFLOW -> PassiveNetwork.PhasePort.OVERFLOW;
            };
            if(turbine(vessel.shape())||vessel.shape().kind()==EquipmentShape.Kind.EJECTOR)phase=PassiveNetwork.PhasePort.BULK;
            if(vessel.shape().kind().coolingTower())phase=switch(nozzles.get(port.nozzle()).role()){case HOT_WATER_INLET->PassiveNetwork.PhasePort.SPRAY;default->phase;};
            if(vessel.shape().boot())phase=switch(nozzles.get(port.nozzle()).role()){case BOOT_DRAIN->PassiveNetwork.PhasePort.BOOT_WATER;case LIQUID_OUTLET->PassiveNetwork.PhasePort.BOOT_HC;default->phase;};
            ports.add(new PhysicalFluidTopology.Port(Positions.of(level, cell), face, phase, role));
        }
        return ports;
    }

    /** The equipment's registration leaves with its structure (a reservoir's contents go with it). */
    static void unregister(ServerLevel level, Vessel vessel) {
        if (vessel.shape().kind() == EquipmentShape.Kind.BATCH_STILL) {
            dropStillFuel(level, vessel);
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.removeUnit(EquipmentStructure.heldAtNodes(world, level, vessel)));
            return;
        }
        if (vessel.shape().kind().converter()) {
            // A converter's vessel and its hidden ports leave together (W3); its facts follow its vessel's removal.
            var at = Positions.of(level, node(vessel));
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> EquipmentStructure.heldAt(world, at)
                    .ifPresent(record -> {var ids = new HashSet<>(world.converterDevices(record.device().id()));ids.add(record.device().id());world.removeUnit(List.copyOf(ids));}));
            return;
        }
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            // A unit's sides leave together: no event leaves half a pair.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.removeUnit(
                    EquipmentStructure.unitRecords(world, EquipmentStructure.heldAtNodes(world, level, vessel)).values().stream().map(r -> r.device().id()).toList()));
            return;
        }
        var position = Positions.of(level, node(vessel));
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> EquipmentStructure.heldAt(world, position)
                .ifPresent(record -> world.remove(record.device().id())));
    }


    // ---------------- the fluid converter (batch 2026-11-12-fluid-conversion, W3) ----------------

    /**
     * A whole fluid converter joins the fluid network as one event ({@link FluidWorldAuthority#placeConverter}): its process
     * vessel at its lowest inside cell with its nozzles, and its two hidden ports, each meeting the vessel from inside
     * through an upward contact, one at its node and one {@link EquipmentShape#converterRise} rows up. A liquid converter
     * drains liquid low and fills high; a gas converter fills low and drains vapour high. Its Forge tank holds the vessel's
     * volume in mB, its rate setting starts at the rated flow. {@code former}: the vessel it refreshes (0 for none).
     */
    private static void registerConverter(ServerLevel level, Vessel vessel, long former) {
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var placed = world.placeConverter(converterDevices(level, vessel, former > 0 ? world.registrations().get(former) : null), former);
            long id = placed.getFirst().device().id();
            if (level.getBlockEntity(node(vessel)) instanceof EquipmentShellBlockEntity entity && entity.fluidIdentity() != id) entity.bindIdentity(id);
            // The Forge port answers from now on: cached fluid handlers look again.
            var port = ShapeRasterizer.forgePort(vessel.shape());
            if (port != null) level.invalidateCapabilities(vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z()));
        });
    }

    /** {@code vessel}'s converter devices, keeping {@code former}'s fitted blanketing valve and safety valve where it has them. */
    static FluidWorldAuthority.ConverterDevices converterDevices(Level level, Vessel vessel, WorldTopologyLedger.@Nullable Registration registration) {
        var former = registration == null ? null : registration.spec();
        var shape = vessel.shape();boolean gas = shape.kind() == EquipmentShape.Kind.GAS_CONVERTER;
        var node = node(vessel);
        var low = new PhysicalFluidTopology.Port(Positions.of(level, node), PhysicalFluidTopology.Direction.UP, gas ? PassiveNetwork.PhasePort.BULK : PassiveNetwork.PhasePort.LIQUID);
        var high = new PhysicalFluidTopology.Port(Positions.of(level, node.above(shape.converterRise())), PhysicalFluidTopology.Direction.UP, gas ? PassiveNetwork.PhasePort.VAPOR : PassiveNetwork.PhasePort.BULK);
        var nozzles = ports(level, vessel);
        double blanket = 0, relief = 0;PhysicalFluidTopology.Direction reliefFace = null;
        if (gas && former != null) {
            var valve = former.blanket() > 0 ? com.wormzjl.icheme.runtime.instrument.InstrumentHosts.BlanketValve.of(level, vessel.origin(), shape) : null;
            if (valve != null) {nozzles = new ArrayList<>(nozzles);nozzles.add(valve.port());blanket = former.blanket();}
            // A fitted safety valve keeps the face it was fitted with, the default where the layout now takes that cell (as a drum's).
            var psv = fittedRelief(level, vessel, registration);
            if (psv != null) {nozzles = new ArrayList<>(nozzles);nozzles.add(psv.port());relief = former.relief();reliefFace = former.reliefFace();}
        }
        return new FluidWorldAuthority.ConverterDevices(Positions.of(level, node), nozzles, shape.volume(), shape.levelHeight(), gas, shape.fluidGeometry(),
                gas ? high : low, gas ? low : high, shape.converterRate(), Math.round(shape.volume() * 1000), blanket, relief, reliefFace);
    }

    /**
     * The pressure safety valve fitted to {@code registration}'s device, its outlet the relief port where {@code vessel}'s
     * layout now puts it: on the face it was fitted with (owner: "outlet should face the user when placed"), the default
     * where the layout now takes that cell; null where the device has none fitted or the layout has no room for it.
     */
    private static com.wormzjl.icheme.runtime.instrument.InstrumentHosts.@Nullable ReliefValve fittedRelief(Level level, Vessel vessel, WorldTopologyLedger.Registration registration) {
        var fitted = registration.device().ports().stream().filter(p -> p.role() == PhysicalFluidTopology.ConnectionRole.RELIEF).map(p -> Direction.valueOf(p.face().name())).findFirst().orElse(null);
        var psv = registration.spec().relief() > 0 ? com.wormzjl.icheme.runtime.instrument.InstrumentHosts.ReliefValve.of(level, vessel.origin(), vessel.shape(), fitted) : null;
        if (registration.spec().relief() > 0 && psv == null) psv = com.wormzjl.icheme.runtime.instrument.InstrumentHosts.ReliefValve.of(level, vessel.origin(), vessel.shape());
        return psv;
    }

    // ---------------- the batch still (batch 2026-11-09-batch-distiller, W3 and W4) ----------------

    /**
     * A whole batch still joins the fluid network as one event ({@link FluidWorldAuthority#placeStill}): its pot, a fired
     * reservoir at its lowest inside cell with its CHARGE, DRAIN and (without the condenser) VAPOUR nozzles; its condenser's
     * water side through WATER IN and OUT; its DISTILLATE and VENT outlets and its burners at FUEL, each at its nozzle's cell.
     * {@code former}, the pot of the still it refreshes (0 for none): that still's devices move onto this layout, keeping
     * their identities, facts and contents (decision D7, owner decision O9); a cell holding one of its identities that its
     * device left lets it go.
     */
    private static void registerStill(ServerLevel level, Vessel vessel, long former) {
        var shape = vessel.shape();var options = shape.still();
        var cells = EquipmentStructure.nodes(vessel);if (cells.isEmpty()) return;
        var heater = switch (options.heater()) {
            case SOLID -> com.wormzjl.icheme.science.plant.still.BatchStill.Heater.SOLID;
            case LIQUID -> com.wormzjl.icheme.science.plant.still.BatchStill.Heater.LIQUID;
            case ELECTRIC -> com.wormzjl.icheme.science.plant.still.BatchStill.Heater.ELECTRIC;
        };
        var design = new com.wormzjl.icheme.science.plant.still.BatchStill.Design(shape.volume(), heater, options.condenses(), options.stages());
        // The pot's liquid stands over its straight side and its head: 1.1 diameters (BatchStillDesign.StillPlan).
        double height = 1.1 * design.diameter();
        var potPorts = stillPorts(level, vessel, java.util.Set.of(EquipmentShape.Role.CHARGE, EquipmentShape.Role.DRAIN, EquipmentShape.Role.VAPOUR_OUTLET), PhysicalFluidTopology.ConnectionRole.VESSEL);
        var pot = new FluidWorldAuthority.UnitVessel(Positions.of(level, cells.getFirst()), potPorts, shape.volume(), height,
                com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(shape.volume(), height),
                new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.POT, design.heatedArea(), 0));
        FluidWorldAuthority.UnitSide water = null;
        if (design.condenser()) {
            var ports = new ArrayList<PhysicalFluidTopology.Port>();
            ports.addAll(stillPorts(level, vessel, java.util.Set.of(EquipmentShape.Role.WATER_INLET), PhysicalFluidTopology.ConnectionRole.INLET));
            ports.addAll(stillPorts(level, vessel, java.util.Set.of(EquipmentShape.Role.WATER_OUTLET), PhysicalFluidTopology.ConnectionRole.OUTLET));
            // The worm's water passage: the tub's (or the shell's) water space, a short wide passage.
            water = new FluidWorldAuthority.UnitSide(ports.getFirst().cell(), ports, new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(1, .1, com.wormzjl.icheme.science.fluid.network.PipeResistance.DEFAULT_ROUGHNESS_METRES, 0),
                    new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.WATER, design.condenserArea(), 0));
        }
        var distillate = stillPort(level, vessel, EquipmentShape.Role.DISTILLATE);
        var vent = stillPort(level, vessel, EquipmentShape.Role.VENT);
        var burners = heater == com.wormzjl.icheme.science.plant.still.BatchStill.Heater.LIQUID ? stillPort(level, vessel, EquipmentShape.Role.FUEL) : null;
        var devices = new FluidWorldAuthority.StillDevices(pot, water, design.condenser() ? distillate : null, design.condenser() ? vent : null, burners, design);
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var records = world.placeStill(devices, former);
            var bound = new java.util.HashMap<BlockPos, Long>();
            for (var record : records) {
                var d = record.device();bound.put(new BlockPos(d.position().x(), d.position().y(), d.position().z()), d.id());
            }
            for (var entry : bound.entrySet())
                if (level.getBlockEntity(entry.getKey()) instanceof EquipmentShellBlockEntity entity) {
                    if (entity.fluidIdentity() != entry.getValue()) entity.bindIdentity(entry.getValue());
                    else world.deviceLoaded(entry.getValue());
                }
            var ids = new java.util.HashSet<>(bound.values());
            for (var at : cells)
                if (!bound.containsKey(at) && level.getBlockEntity(at) instanceof EquipmentShellBlockEntity stale && ids.contains(stale.fluidIdentity())) stale.releaseIdentity();
        });
    }

    /** The still's nozzles of {@code roles} as ports of {@code connection}, each of its nozzle's phase class. */
    private static List<PhysicalFluidTopology.Port> stillPorts(Level level, Vessel vessel, java.util.Set<EquipmentShape.Role> roles, PhysicalFluidTopology.ConnectionRole connection) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (!roles.contains(nozzles.get(port.nozzle()).role())) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            var phase = connection != PhysicalFluidTopology.ConnectionRole.VESSEL ? PassiveNetwork.PhasePort.BULK : switch (port.port()) {
                case VAPOUR -> PassiveNetwork.PhasePort.VAPOR;
                case LIQUID -> PassiveNetwork.PhasePort.LIQUID;
                case BULK, OVERFLOW -> PassiveNetwork.PhasePort.BULK;
            };
            ports.add(new PhysicalFluidTopology.Port(Positions.of(level, cell), face, phase, connection));
        }
        return ports;
    }

    /** A still device's one port at its nozzle of {@code role} (bulk), or null where the still has none. */
    private static PhysicalFluidTopology.@Nullable Port stillPort(Level level, Vessel vessel, EquipmentShape.Role role) {
        var ports = stillPorts(level, vessel, java.util.Set.of(role), PhysicalFluidTopology.ConnectionRole.VESSEL);
        if (ports.isEmpty()) return null;
        var p = ports.getFirst();
        return new PhysicalFluidTopology.Port(p.cell(), p.face(), PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL);
    }

    /** {@link #restoreStill(ServerLevel, Vessel, FluidWorldAuthority, java.util.Collection)} from the identities its cells hold now. */
    private static boolean restoreStill(ServerLevel level, Vessel vessel, FluidWorldAuthority world) {
        for (var at : EquipmentStructure.nodes(vessel)) if (!level.hasChunkAt(at)) return false;
        var layout = ShapeRasterizer.layout(vessel.shape());
        for (var cell : layout.shell()) {
            var at = vessel.origin().offset(cell.x(), cell.y(), cell.z());
            if (!(level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell) || !EquipmentStructure.ours(shell, vessel.origin(), vessel.shape()) || shell.stale()) return true;
        }
        restoreStill(level, vessel, world, EquipmentStructure.heldAtNodes(world, level, vessel));
        return true;
    }

    /**
     * A still's devices onto {@code vessel}'s layout: where each of its cells holds the device it registers (the pot a
     * fired reservoir, the others theirs), the cells bind them; otherwise the still whose pot {@code held} names moves onto
     * the layout whole (its identities, facts and contents kept), and {@code held}'s other units leave. With no still
     * among them the still registers afresh.
     */
    private static void restoreStill(ServerLevel level, Vessel vessel, FluidWorldAuthority world, java.util.Collection<Long> held) {
        var cells = EquipmentStructure.nodes(vessel);
        boolean whole = !cells.isEmpty();long pot = 0;
        for (int i = 0; i < cells.size() && whole; i++) {
            var at = Positions.of(level, cells.get(i));
            var record = EquipmentStructure.heldAt(world, at).orElse(null);
            if (record == null || (i == 0) != record.device().stillPot()) whole = false;
            else if (i == 0) pot = record.device().id();
        }
        if (whole && world.stills().view(pot) != null) {
            for (var at : cells) {
                var p = Positions.of(level, at);
                world.at(p).ifPresent(r -> {
                    if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell) {
                        if (shell.fluidIdentity() != r.device().id()) shell.bindIdentity(r.device().id());
                        else world.deviceLoaded(r.device().id());
                    }
                });
            }
            return;
        }
        long former = 0;
        for (long id : held) {var r = world.registrations().get(id);if (r != null && r.device().stillPot() && world.stills().view(id) != null) former = id;}
        var still = former > 0 ? world.stillDevices(former) : java.util.Set.<Long>of();
        var others = held.stream().filter(id -> !still.contains(id)).toList();
        if (!others.isEmpty()) world.removeUnit(others);
        registerStill(level, vessel, former);
    }

    /** A broken still's fuel slot drops its items at its pot's cell; the heat left in the burning item is lost with it. */
    private static void dropStillFuel(ServerLevel level, Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());if (cell == null) return;
        var at = vessel.origin().offset(cell.x(), cell.y(), cell.z());
        if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell) {
            var stack = shell.dropFuel();
            if (!stack.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, at.getX() + .5, at.getY() + .5, at.getZ() + .5, stack);
        }
    }

    /**
     * A refreshed vessel or machine keeps its registration (decision D7): one queued edit moves it to its new node with
     * its new ports, and a vessel takes its new volume, level height and opening. It keeps its identity, its controls and
     * what it holds ({@code PhysicalRegistry}: a vessel's moles, energy and solids in its new geometry), so nothing the
     * network or a gauge knows of it changes but where it stands. {@code oldNode} held the identity.
     */
    static void reregister(ServerLevel level, Vessel vessel, @Nullable BlockPos oldNode, List<Long> heldIds) {
        refresh(level, vessel, oldNode, heldIds);
        material(level, vessel);
    }

    private static void refresh(ServerLevel level, Vessel vessel, @Nullable BlockPos oldNode, List<Long> heldIds) {
        if (vessel.shape().kind() == EquipmentShape.Kind.BATCH_STILL) {
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> restoreStill(level, vessel, world, heldIds));
            return;
        }
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> restateUnit(level, vessel, world, heldIds));
            return;
        }
        if (vessel.shape().kind().converter()) {
            // A converter moves whole onto its new layout (D7): its vessel and hidden ports keep their identities and contents.
            var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
            if (world == null) return;
            var held = Positions.of(level, oldNode == null ? node(vessel) : oldNode);
            var record = EquipmentStructure.heldAt(world, held).orElse(null);
            if (oldNode != null && !oldNode.equals(node(vessel)) && record != null && level.getBlockEntity(oldNode) instanceof EquipmentShellBlockEntity old && old.fluidIdentity() == record.device().id()) old.releaseIdentity();
            registerConverter(level, vessel, record == null ? 0 : record.device().id());
            return;
        }
        var node = node(vessel);
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var held = Positions.of(level, oldNode == null ? node : oldNode);
        var record = EquipmentStructure.heldAt(world, held).orElse(null);
        if (record == null) {
            register(level, vessel);
            return;
        }
        var device = record.device();
        var spec = record.spec();
        var nozzles = ports(level, vessel);
        // A fitted gas blanketing valve (batch 2026-11-11-gas-blanketing) keeps its set pressure, its inlet where the layout now puts it.
        var valve = spec.blanket() > 0 ? com.wormzjl.icheme.runtime.instrument.InstrumentHosts.BlanketValve.of(level, vessel.origin(), vessel.shape()) : null;
        if (valve != null) { nozzles = new ArrayList<>(nozzles); nozzles.add(valve.port()); }
        // So does a fitted pressure safety valve, its outlet the relief port where the layout now puts it, on its fitted face.
        var relief = fittedRelief(level, vessel, record);
        if (relief != null) { nozzles = new ArrayList<>(nozzles); nozzles.add(relief.port()); }
        world.edit(device.id(), record.revision(), device.withPlacement(Positions.of(level, node), nozzles),
                vessel.shape().kind().mover() || vessel.shape().kind() == EquipmentShape.Kind.FLARE_STACK || vessel.shape().kind().chimney() ? spec : new FluidDeviceSpec(vessel.shape().volume(), spec.temperature(), spec.pressure(), spec.composition(), spec.solids(), vessel.shape().levelHeight(), open(vessel), vessel.shape().fluidGeometry(),spec.tower(), valve == null ? 0 : spec.blanket(), relief == null ? 0 : spec.relief(), null).withBreached(spec.breached()).withMetallurgy(vessel.shape().material()));
        if (oldNode != null && !oldNode.equals(node) && level.getBlockEntity(oldNode) instanceof EquipmentShellBlockEntity old && old.fluidIdentity() == device.id()) old.releaseIdentity();
        if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity && entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
        ShaftParts.structure(level, vessel.origin(), vessel.shape(), device.id());
    }

    /** Whether a shape registers as a steam turbine (W3, reading A37). */
    private static boolean turbine(EquipmentShape shape) {
        return shape.kind() == EquipmentShape.Kind.STEAM_TURBINE;
    }

    /**
     * The control a casing saved before W3 takes at its restore (reading A29), or null when it already has its own: a pump's
     * or centrifugal compressor's set-flow control becomes its machine, its set flow kept as the machine's; a frame takes
     * its displacement, keeping its pressure ratio and switch. A machine's law itself is {@link MachineRatings}' (E9).
     */
    static @Nullable com.wormzjl.icheme.science.fluid.network.FlowControl restoredControl(EquipmentShape shape, com.wormzjl.icheme.science.fluid.network.FlowControl control) {
        return switch (shape.kind()) {
            case PUMP, CENTRIFUGAL_COMPRESSOR -> {
                if (!(control instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Pump) && !(control instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor)) yield null;
                var mover = (com.wormzjl.icheme.science.fluid.network.FlowControl.Mover) control;
                var machine = (com.wormzjl.icheme.science.fluid.network.FlowControl.Machine) MachineRatings.machineControl(shape);
                double set = Math.max(0, Math.min(com.wormzjl.icheme.science.fluid.network.FlowControl.Mover.MAXIMUM_VOLUME_FLOW, mover.requestedVolumeFlow()));
                yield new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(machine.law(), 0, mover.enabled(), set, 0);
            }
            case COMPRESSOR -> control instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor c && c.requestedVolumeFlow() != MachineRatings.frameDisplacement(shape)
                    ? new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(MachineRatings.frameDisplacement(shape), c.maximumPressureRatio(), c.enabled()) : null;
            default -> null;
        };
    }
}
