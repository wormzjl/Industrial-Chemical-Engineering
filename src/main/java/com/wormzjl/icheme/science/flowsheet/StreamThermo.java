package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.state.SolidInventory;

/** What the solver asks of the stream thermo (batch 2026-11-02-process-simulator, plan section 3.1). Minecraft-free. */
public interface StreamThermo {
    /**
     * The stream of {@code amounts} (mol/s) and {@code solids} at {@code pressure} Pa with {@code enthalpy} W, solids
     * included, which a loop's accelerated step asks for: the amounts and the energy of a guess are free, its temperature
     * follows. {@code temperatureGuess} (K) starts the search. A failure throws, and the step falls back to a plain pass.
     */
    FlowStream atEnthalpy(double pressure, double enthalpy, double[] amounts, double temperatureGuess, SolidInventory solids);
}
