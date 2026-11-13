package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.fluid.FluidFacts.Fields;
import com.wormzjl.icheme.runtime.fluid.FluidFacts.Record;
import java.util.List;

/**
 * One subsystem's run of fields in the topology unit's facts record ({@link FluidFacts#world}). The record is positional
 * (AGENTS.md, "Saves and data formats"): its fields are the sections' runs, one after another, in the order of
 * {@link #ALL}, and a record an older build wrote lacks trailing fields only. So:
 * <ul>
 *   <li>a section writes and reads its fields in one fixed order, each read with the default for a world written before
 *   that field existed (the behaviour before it);</li>
 *   <li>a section that is not last never gains a field of the root record: its new facts go inside one of its own nested
 *   records (a unit's record grows at its end), or into a new section;</li>
 *   <li>a new subsystem appends its section at the end of {@link #ALL}, with the schema number that adds it, and a
 *   component and a setter of {@link FluidFacts.World}; the sections before it are untouched.</li>
 * </ul>
 */
interface FactsSection {
    /** The sections in stream order: the registry (schema 1), the columns (6), the machines (10 to 12), the build facts (15),
     *  the batch stills (16) and the fluid converters (24). */
    List<FactsSection> ALL=List.of(new RegistryFacts(),new ColumnFacts(),new MachineFacts(),new BuildFacts(),new StillFacts(),new ConverterFacts());
    /** Writes this section's fields of the root record {@code f} from {@code s}. */
    void write(Fields f,WorldTopologyLedger.Snapshot s);
    /** Reads this section's fields of the root record {@code f}, each at its default when the record ends before it, into {@code world}. */
    void read(Record f,FluidFacts.World.Builder world);
}
