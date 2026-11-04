package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a multi-machine assembly asks for (batch 2026-10-26-multi-machine-framework, plan section 5): its slots and the
 * links between them. A link is a required pipe path from a bound slot's port, through the units and fittings its steps
 * name, to another port or outside. {@link AssemblyMatcher} follows the links in order; a slot is filled where its step
 * meets a unit or fitting of its form. Minecraft-free.
 *
 * @param master the slot the assembly is validated from
 * @param slots  every slot, in report order
 * @param links  the links, in matching order: a link starts at a slot an earlier link (or the master) fills
 */
public record Blueprint(Slot master, List<Slot> slots, List<Link> links) {
    public Blueprint {
        Objects.requireNonNull(master, "master");
        slots = List.copyOf(slots);
        links = List.copyOf(links);
    }

    /**
     * A part of the assembly: its name (a translation key suffix, {@code gui.icheme.assembly.slot.<part>}) and which one
     * of its part it is, from 1 for repeated parts, 0 for a single one.
     */
    public record Slot(String part, int index) {
        public Slot {
            Objects.requireNonNull(part, "part");
        }
    }

    /** A slot's port: its nozzle role and which one of that role, from 0 in nozzle order. */
    public record PortSpec(Slot slot, Role role, int ordinal) {
    }

    /** What may fill a slot. */
    public sealed interface Form permits UnitForm, FittingForm, TeeForm {
    }

    /**
     * A unit of {@code kind}, entered at {@code in}; a stream passes it to {@code out} (null: the link ends at the unit).
     * {@code lyingWithBoot}: a drum must lie and carry a boot. {@code utilities}: its utility ports, advice only (D2).
     */
    public record UnitForm(Kind kind, boolean lyingWithBoot, Role in, @Nullable Role out, Set<Role> utilities) implements Form {
        public UnitForm {
            utilities = Set.copyOf(utilities);
        }
    }

    /** A pipe fitting of {@code kind}, its arrow along the stream. */
    public record FittingForm(PortGraph.Fitting kind) implements Form {
    }

    /** A branch of the line, where it splits into {@code branches} (a tee). */
    public record TeeForm(List<Branch> branches) implements Form {
        public TeeForm {
            branches = List.copyOf(branches);
        }
    }

    /** One branch of a tee: its steps and where it ends; {@code owner} takes the end's faults. */
    public record Branch(List<Step> steps, Finish finish, Slot owner) {
        public Branch {
            steps = List.copyOf(steps);
        }
    }

    /**
     * A step of a link: the slot it fills, the designed form, and the other forms of the slot's family, which fill it as
     * the wrong kind (a standing drum for the receiver, a standard pump where the design has an In-line Pump).
     * {@code anyOrder}: this step and the next may be met in either order (a pumparound's pump and cooler). {@code count}:
     * how many identical units of a unit form stand in parallel on the step, between a split and a merge on its link
     * (batch 2026-11-02-process-simulator, S3b); 1 is one unit in line.
     */
    public record Step(Slot slot, Form form, List<Form> family, boolean anyOrder, int count) {
        public Step {
            family = List.copyOf(family);
            if (count < 1) throw new IllegalArgumentException("A step takes at least one unit");
            if (count > 1 && !(form instanceof UnitForm)) throw new IllegalArgumentException("Only a unit step takes units in parallel");
        }

        public Step(Slot slot, Form form, List<Form> family, boolean anyOrder) {
            this(slot, form, family, anyOrder, 1);
        }

        public static Step of(Slot slot, Form form, Form... others) {
            return new Step(slot, form, List.of(others), false, 1);
        }

        public Step withAnyOrder() {
            return new Step(slot, form, family, true, count);
        }

        /** This step with {@code units} identical units in parallel. */
        public Step withCount(int units) {
            return new Step(slot, form, family, anyOrder, units);
        }
    }

    /** Where a link starts: a filled slot's port, or the port its unit passes a stream on to ({@code afterEntry}). */
    public record Start(Slot slot, @Nullable Role role, int ordinal, boolean afterEntry) {
        public static Start at(Slot slot, Role role, int ordinal) {
            return new Start(slot, role, ordinal, false);
        }

        /** The partner of the port where the slot's unit was entered: a condenser's outlet. */
        public static Start after(Slot slot) {
            return new Start(slot, null, 0, true);
        }
    }

    /** Where a link ends. */
    public sealed interface Finish permits AtUnit, ToPort, Outside {
    }

    /** At the unit its last step fills. */
    public record AtUnit() implements Finish {
    }

    /** At a filled slot's port. */
    public record ToPort(PortSpec port) implements Finish {
    }

    /** Outside the assembly, beyond its last step (a boundary valve). */
    public record Outside() implements Finish {
    }

    /**
     * A required path: from {@code start}, through {@code steps}, to {@code finish}. {@code upstream}: the link is
     * walked against the stream (from the column's feed out to its source), so its valves point back along the walk.
     * {@code owner} takes the faults of the path's end when no step is left to take them.
     */
    public record Link(String name, Start start, List<Step> steps, Finish finish, boolean upstream, Slot owner) {
        public Link {
            steps = List.copyOf(steps);
        }
    }
}
