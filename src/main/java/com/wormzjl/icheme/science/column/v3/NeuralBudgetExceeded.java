package com.wormzjl.icheme.science.column.v3;

/**
 * The learned route's own poll allowance ({@code V3InitializationOptions.budgetPolls()}), inside the caller's request
 * deadline.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2): one class, thrown
 * and caught only by the learned route.</p>
 */
final class NeuralBudgetExceeded extends RuntimeException {}
