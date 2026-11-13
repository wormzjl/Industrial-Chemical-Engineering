package com.wormzjl.icheme.science.column.v3;

/**
 * The correction's own poll allowance ({@code HYDRAULIC_CORRECTION_BUDGET_POLLS}), inside the caller's request deadline.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2): one class, thrown
 * and caught only by the hydraulic correction.</p>
 */
final class HydraulicBudgetExceeded extends RuntimeException {}
