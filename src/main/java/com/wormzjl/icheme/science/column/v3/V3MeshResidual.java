package com.wormzjl.icheme.science.column.v3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable physical and scaled residual vector in the exact semantic order of the V3 DOF ledger.
 *
 * <p>Held flat, one value and one scale a row beside the ledger's shared equation ids: an evaluation makes three
 * arrays, not a record a row. {@link #rows()} builds the records on demand for diagnostics and tests.</p>
 */
final class V3MeshResidual {
    private final V3DegreeOfFreedomLedger.EquationId[] equations;
    private final double[] physical;
    private final double[] scale;
    private List<Row> rows;

    V3MeshResidual(List<Row> rows) {
        // List.copyOf rejects null elements while creating the immutable snapshot.
        List<Row> copy = List.copyOf(rows);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("V3 MESH residual must contain non-null rows");
        }
        int size = copy.size();
        this.equations = new V3DegreeOfFreedomLedger.EquationId[size];
        this.physical = new double[size];
        this.scale = new double[size];
        for (int row = 0; row < size; row++) {
            equations[row] = copy.get(row).equation();
            physical[row] = copy.get(row).physicalValue();
            scale[row] = copy.get(row).scale();
        }
        this.rows = copy;
    }

    /**
     * A residual over {@code equations}, the ledger's shared ids, with the arrays it is handed and keeps. Each row was
     * checked by {@link #check} as it was evaluated.
     */
    V3MeshResidual(V3DegreeOfFreedomLedger.EquationId[] equations, double[] physical, double[] scale) {
        if (equations.length == 0 || physical.length != equations.length || scale.length != equations.length) {
            throw new IllegalArgumentException("V3 MESH residual must contain non-null rows");
        }
        this.equations = equations;
        this.physical = physical;
        this.scale = scale;
    }

    /** The row check of {@link Row}: a finite value with a finite, positive scale. */
    static void check(double physicalValue, double scale) {
        if (!Double.isFinite(physicalValue) || !Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("V3 MESH residual rows must be finite with a positive scale");
        }
    }

    int size() { return physical.length; }

    V3DegreeOfFreedomLedger.EquationId equation(int row) { return equations[row]; }

    double physicalValue(int row) { return physical[row]; }

    double scale(int row) { return scale[row]; }

    double scaledValue(int row) { return physical[row] / scale[row]; }

    /** The rows as records, built once on the first call. */
    List<Row> rows() {
        var built = rows;
        if (built == null) {
            var list = new ArrayList<Row>(physical.length);
            for (int row = 0; row < physical.length; row++) list.add(new Row(equations[row], physical[row], scale[row]));
            rows = built = Collections.unmodifiableList(list);
        }
        return built;
    }

    double maximumAbsoluteScaledResidual() {
        double maximum = 0.0;
        for (int row = 0; row < physical.length; row++) maximum = Math.max(maximum, Math.abs(scaledValue(row)));
        return maximum;
    }

    /**
     * Row scales of this evaluation, in ledger order.
     *
     * <p>A Jacobian or a line search that must compare two states differentiates or compares the physical
     * rows against one frozen copy of this vector rather than against each candidate's own scales, so that a
     * row scale which is itself a function of the state cannot move underneath the comparison.</p>
     */
    double[] scales() {
        return scale.clone();
    }

    record Row(V3DegreeOfFreedomLedger.EquationId equation, double physicalValue, double scale) {
        Row {
            equation = Objects.requireNonNull(equation, "equation");
            check(physicalValue, scale);
        }

        double scaledValue() { return physicalValue / scale; }
    }
}
