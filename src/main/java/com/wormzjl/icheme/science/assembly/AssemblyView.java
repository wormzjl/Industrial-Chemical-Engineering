package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.column.operation.OperationView;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * What a column's GUI shows of its assembly (batch 2026-10-26-multi-machine-framework, plan section 8): its status, its
 * last report, and where the structure last changed when a change returned it to not validated; and, for a column's open
 * GUI, its operation (batch 2026-10-28-column-operation, C7). Minecraft-free.
 *
 * @param changedAt the packed position of the change that reverted the report, or {@link AssemblyReport#NONE}
 * @param operation the Operation tab's snapshot, or null (no design, or a watch, which shows none)
 */
public record AssemblyView(Status status, @Nullable AssemblyReport report, long changedAt, @Nullable OperationView operation) {
    /** A column's status (plan section 8). */
    public enum Status {
        /** A column built before this batch, or without a component design. */
        NO_DESIGN,
        NOT_VALIDATED, CHECKED, COMPLETE
    }

    public static final AssemblyView NO_DESIGN = new AssemblyView(Status.NO_DESIGN, null, AssemblyReport.NONE);
    public static final AssemblyView NOT_VALIDATED = new AssemblyView(Status.NOT_VALIDATED, null, AssemblyReport.NONE);

    public AssemblyView {
        Objects.requireNonNull(status, "status");
        if ((status == Status.CHECKED || status == Status.COMPLETE) != (report != null)) throw new IllegalArgumentException("A report goes with a checked status only");
    }

    /** A view with no operation. */
    public AssemblyView(Status status, @Nullable AssemblyReport report, long changedAt) {
        this(status, report, changedAt, null);
    }

    public AssemblyView withOperation(@Nullable OperationView value) {
        return new AssemblyView(status, report, changedAt, value);
    }
}
