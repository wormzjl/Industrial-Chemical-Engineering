package com.wormzjl.icheme.science.fluid.conversion;

import java.util.Objects;

/** The fluid conversion rules the last datapack reload published (batch 2026-11-12-fluid-conversion); none before the first. */
public final class ConversionRuntime {
    private static volatile ConversionRules active=ConversionRules.NONE;
    private ConversionRuntime() {}
    public static ConversionRules active(){return active;}
    public static void publish(ConversionRules rules){active=Objects.requireNonNull(rules);}
    public static void reset(){active=ConversionRules.NONE;}
}
