package com.wormzjl.icheme;

import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scratch stub of the mod main class for the Gradle-free probe: the logger and the option readers the runtime names; no
 * NeoForge, no config. Compiled first into classes/, so javac's -Xprefer:newer takes it over src/main/java's IChemE.java. */
public final class IChemE {
    public static final String MOD_ID = "icheme";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public record FluidOptions(int initialCadenceTicks,long wallBudgetNanos,double volume,boolean debugChat,boolean adaptiveCadence,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solids,com.wormzjl.icheme.runtime.fluid.CertificatePolicy certificates) {}
    public static FluidOptions fluidOptions() { throw new UnsupportedOperationException("stub"); }
    public static V3InitializationOptions columnV3InitializationOptions() { throw new UnsupportedOperationException("stub"); }
    public static V3NeuralInitializer columnV3NeuralModel() { throw new UnsupportedOperationException("stub"); }
    public static double columnV3StageTraceCutoffMolPercent() { throw new UnsupportedOperationException("stub"); }
    public static double columnV3ConvergenceClosurePercent() { throw new UnsupportedOperationException("stub"); }
    public static double columnV3LiquidSupplyScreenRatio() { throw new UnsupportedOperationException("stub"); }
}
