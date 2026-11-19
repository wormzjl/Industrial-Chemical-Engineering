package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.ElectricStores;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;

import net.minecraft.core.BlockPos;

import java.util.*;

/** The terminal cell and store owner of an installed machine, derived from the build registry. */
public final class PowerTerminals {
    private record Shell(Position origin, DataBus.Site terminal, Position node, boolean shaft) {
        static Shell of(Position origin, EquipmentShape shape) {
            if (!PowerPorts.eligible(shape)) return null;
            var terminal = ShapeRasterizer.terminal(shape);
            if (terminal == null) return null;
            boolean shaft = shape.kind() == EquipmentShape.Kind.MOTOR || shape.kind() == EquipmentShape.Kind.GENERATOR;
            var fluid = shaft ? null : HeatSides.burners(shape.kind())
                    ? ShapeRasterizer.fluidCells(shape).getLast() : ShapeRasterizer.fluidCell(shape);
            if (!shaft && fluid == null) return null;
            return new Shell(origin, site(offset(origin, terminal)), fluid == null ? null : offset(origin, fluid), shaft);
        }

        long unit(WorldTopologyLedger topology) {
            if (shaft) {
                var unit = topology.shaftAt(origin);
                return unit != null && ElectricStores.powered(unit) && !unit.held() ? unit.id() : 0;
            }
            var node = topology.latestAt(this.node);
            if (node == null || node.device().held()) return 0;
            var still = topology.still(node.device().id());
            return ElectricStores.powered(node) || still != null && ElectricStores.powered(still)
                    ? node.device().id() : 0;
        }
    }

    private final WorldTopologyLedger topology;
    private final Map<Position, Shell> legacy = new HashMap<>();
    private WorldTopologyLedger.Builds builds;
    private Map<Position, Shell> origins = Map.of();
    private Map<DataBus.Site, Shell> terminals = Map.of();

    PowerTerminals(WorldTopologyLedger topology) {
        this.topology = topology;
    }

    void refresh() {
        var current = topology.builds();
        if (current == builds) return;
        if (builds != null) legacy.keySet().removeAll(builds.structures().keySet());
        var indexed = new HashMap<>(legacy);
        current.structures().forEach((origin, structure) -> {
            indexed.remove(origin);
            var shell = Shell.of(origin, structure.shape());
            if (shell != null) indexed.put(origin, shell);
        });
        var byTerminal = new HashMap<DataBus.Site, Shell>();
        indexed.values().forEach(shell -> byTerminal.put(shell.terminal(), shell));
        origins = Map.copyOf(indexed);
        terminals = Map.copyOf(byTerminal);
        builds = current;
    }

    void legacyShell(Position origin, EquipmentShape shape) {
        if (topology.structure(origin) != null) return;
        var shell = Shell.of(origin, shape);
        if (shell != null && legacy.putIfAbsent(origin, shell) == null) builds = null;
    }

    /** The store identity at the physical terminal. Registry edits and held state are read live. */
    long unit(DataBus.Site terminal) {
        var shell = terminals.get(terminal);
        if (shell != null) return shell.unit(topology);
        var inline = topology.latestAt(position(terminal));
        return inline(inline) && !inline.device().held() ? inline.device().id() : 0;
    }

    DataBus.Site terminal(ShaftUnit unit) {
        var shell = origins.get(unit.position());
        return shell == null || !shell.shaft() || shell.unit(topology) != unit.id() ? null : shell.terminal();
    }

    /** Resolve the same store for a loaded shell capability, including old shells before realignment. */
    public static long unit(WorldTopologyLedger topology, Position origin, EquipmentShape shape) {
        var shell = Shell.of(origin, shape);
        return shell == null ? 0 : shell.unit(topology);
    }

    Set<DataBus.Site> sites() {
        var sites = new HashSet<>(terminals.keySet());
        for (var registration : topology.latest().values())
            if (inline(registration)) sites.add(site(registration.device().position()));
        return sites;
    }

    /** Known engine anchors from before the build registry held structure geometry. */
    Set<DataBus.Site> legacyAnchors() {
        var known = new HashSet<Long>();
        terminals.values().forEach(shell -> known.add(shell.unit(topology)));
        var anchors = new HashSet<DataBus.Site>();
        for (var registration : topology.active().values()) {
            long id = registration.device().id();
            var still = topology.still(id);
            if (!known.contains(id) && !inline(registration)
                    && (ElectricStores.powered(registration) || still != null && ElectricStores.powered(still)))
                anchors.add(site(registration.device().position()));
        }
        for (var shaft : topology.shafts().values())
            if (ElectricStores.powered(shaft) && !origins.containsKey(shaft.position()) && !shaft.ends().isEmpty())
                anchors.add(site(shaft.ends().getFirst().cell()));
        return anchors;
    }

    private static boolean inline(WorldTopologyLedger.Registration registration) {
        return registration != null && registration.device().position().lane() == 0
                && registration.device().kind() == TopologyCompiler.Kind.PIPE
                && registration.device().control() instanceof FlowControl.Mover;
    }

    static DataBus.Site site(Position position) {
        return new DataBus.Site(position.dimension(), new BlockPos(position.x(), position.y(), position.z()), 0);
    }

    private static Position position(DataBus.Site site) {
        var p = site.cell();
        return new Position(site.dimension(), p.getX(), p.getY(), p.getZ());
    }

    private static Position offset(Position position, ShapeRasterizer.Cell cell) {
        return new Position(position.dimension(), position.x() + cell.x(), position.y() + cell.y(), position.z() + cell.z());
    }
}
