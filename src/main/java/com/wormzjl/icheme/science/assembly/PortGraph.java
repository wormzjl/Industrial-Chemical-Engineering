package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;

import java.util.List;
import java.util.Objects;

/**
 * The pipes around an assembly as its validation sees them (batch 2026-10-26-multi-machine-framework, plan section 6):
 * runs of pipe from a unit's port to the next port, branch, outside terminal or open end, with the fittings on them in
 * order. A server-side walker builds it lazily from the fluid registry and the shells in loaded chunks; tests build it
 * by hand. Minecraft-free: positions are packed block positions.
 */
public interface PortGraph {
    /** The run leaving {@code port} along the pipe at its flange; {@link Unconnected} when no pipe meets the port. */
    Run run(Port port);

    /** The runs leaving the branch at {@code junction}, all but the one ending at {@code arrived}, in a fixed order. */
    List<Run> branches(Junction junction, Run arrived);

    /**
     * A structure: its origin (its identity), its kind, whether it lies and has a boot (a drum's), and its nozzle roles
     * in nozzle order.
     */
    record Unit(long origin, Kind kind, boolean lying, boolean boot, List<Role> nozzles) {
        public Unit {
            Objects.requireNonNull(kind, "kind");
            nozzles = List.copyOf(nozzles);
        }

        /** How many nozzles of {@code role} the unit has. */
        public int count(Role role) {
            return (int) nozzles.stream().filter(r -> r == role).count();
        }
    }

    /** A unit's port: its nozzle role and which one of that role it is, from 0 in nozzle order. */
    record Port(Unit unit, Role role, int ordinal) {
    }

    /**
     * A pipe fitting as an assembly reads it: a control valve (with or without its module), an In-line Pump, another
     * accessory (a shutoff or check valve, a strainer, an instrument), which the structure stage passes over, or a
     * flowmeter on a pipe with no other fitting (batch 2026-10-27-heat-exchanger-design, W8), which a step may ask for and
     * the walk otherwise passes over. Codes are by ordinal: a new kind goes last.
     */
    enum Fitting {
        CONTROL_VALVE, INLINE_PUMP, OTHER, FLOWMETER
    }

    /**
     * A fitting on a run: its kind, its arrow along the run (+1) or against it (-1), its position, and how many pipe
     * blocks of the run lie before it.
     */
    record Element(Fitting kind, int direction, long position, int pipesBefore) {
    }

    /** Where a run ends. */
    sealed interface End permits AtPort, Junction, Terminal, OpenEnd, NotLoaded, Unconnected {
    }

    /** At a unit's port. */
    record AtPort(Port port) implements End {
    }

    /** At a pipe that branches into {@code branches} runs besides the one arriving. */
    record Junction(long position, int branches) implements End {
    }

    /** At a one-block tank, a generator or a void: outside the assembly. */
    record Terminal(long position) implements End {
    }

    /** At a pipe that ends with nothing beyond it. */
    record OpenEnd(long position) implements End {
    }

    /** At a chunk that is not loaded: the walk could not look further. */
    record NotLoaded(long position) implements End {
    }

    /** No pipe meets the port. */
    record Unconnected() implements End {
    }

    /**
     * A run: its fittings in order, its end, its pipe blocks, and the positions it relied on (its pipes and fittings),
     * which the report's footprint keeps.
     */
    record Run(List<Element> elements, End end, int pipes, List<Long> positions) {
        public Run {
            elements = List.copyOf(elements);
            Objects.requireNonNull(end, "end");
            positions = List.copyOf(positions);
        }

        public static Run unconnected() {
            return new Run(List.of(), new Unconnected(), 0, List.of());
        }
    }
}
