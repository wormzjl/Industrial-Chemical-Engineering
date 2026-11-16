package com.wormzjl.icheme.science.pollution;

import java.util.List;

/**
 * What a cube's hazard index does to a living thing in it (batch 2026-11-15-pollution-system, WP5; plan sections 2 and 6).
 * Pure: no Minecraft type. Each of the four channels acts on its own index; nothing is added together (owner, 2026-11-15:
 * particulate is a stand-alone kind).
 */
public final class Exposure {
    /** 10 % of the reference: the first effects (for a combustible gas, the gas detector alarm at 10 % LEL). */
    public static final double LOW = 0.1;
    /** 30 % of the reference: the heavier effects, and the first corrosive damage. */
    public static final double MEDIUM = 0.3;
    /** The reference (IDLH, or the LFL): damage. */
    public static final double HIGH = 1.0;
    /** Three times the reference: three times the damage. */
    public static final double EXTREME = 3.0;

    private Exposure() {
    }

    public enum Level {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        EXTREME;

        public boolean atLeast(Level other) {
            return compareTo(other) >= 0;
        }
    }

    /** The status effects the world pass maps onto Minecraft's. */
    public enum Effect {
        POISON,
        WEAKNESS,
        SLOWNESS,
        BLINDNESS,
        MINING_FATIGUE,
        NAUSEA,
        DARKNESS,
        HUNGER
    }

    /** One effect at its amplifier (0 is level I). */
    public record Dose(Effect effect, int amplifier) {
    }

    private static final List<Dose> TOXIC_LOW = List.of(new Dose(Effect.POISON, 0), new Dose(Effect.WEAKNESS, 0));
    private static final List<Dose> TOXIC_MEDIUM = List.of(new Dose(Effect.POISON, 1), new Dose(Effect.SLOWNESS, 0),
            new Dose(Effect.BLINDNESS, 0), new Dose(Effect.WEAKNESS, 0));
    private static final List<Dose> CORROSIVE = List.of(new Dose(Effect.WEAKNESS, 0), new Dose(Effect.MINING_FATIGUE, 0));
    private static final List<Dose> COMBUSTIBLE_LOW = List.of(new Dose(Effect.NAUSEA, 0));
    private static final List<Dose> COMBUSTIBLE_MEDIUM = List.of(new Dose(Effect.NAUSEA, 0), new Dose(Effect.SLOWNESS, 0));
    private static final List<Dose> PARTICULATE_LOW = List.of(new Dose(Effect.DARKNESS, 0));
    private static final List<Dose> PARTICULATE_MEDIUM = List.of(new Dose(Effect.DARKNESS, 0), new Dose(Effect.SLOWNESS, 0),
            new Dose(Effect.HUNGER, 0));

    /** The level of an index; NaN or negative is NONE. */
    public static Level level(double index) {
        if (!(index >= LOW)) {
            return Level.NONE;
        }
        if (index >= EXTREME) {
            return Level.EXTREME;
        }
        if (index >= HIGH) {
            return Level.HIGH;
        }
        return index >= MEDIUM ? Level.MEDIUM : Level.LOW;
    }

    /** Health points per second (a player has 20). A combustible gas never damages: it burns (section 7). */
    public static double damagePerSecond(Channel channel, Level level) {
        if (channel == null || level == null) {
            return 0;
        }
        return switch (channel) {
            case TOXIC -> level == Level.EXTREME ? 6 : level == Level.HIGH ? 2 : 0;
            case CORROSIVE -> level == Level.EXTREME ? 6 : level == Level.HIGH ? 2 : level == Level.MEDIUM ? 0.5 : 0;
            case PARTICULATE -> level == Level.EXTREME ? 3 : level == Level.HIGH ? 1 : 0;
            case COMBUSTIBLE -> 0;
        };
    }

    /** The effects of a channel at a level; empty at NONE. */
    public static List<Dose> effects(Channel channel, Level level) {
        if (channel == null || level == null || level == Level.NONE) {
            return List.of();
        }
        boolean heavy = level.atLeast(Level.MEDIUM);
        return switch (channel) {
            case TOXIC -> heavy ? TOXIC_MEDIUM : TOXIC_LOW;
            case CORROSIVE -> CORROSIVE;
            case COMBUSTIBLE -> heavy ? COMBUSTIBLE_MEDIUM : COMBUSTIBLE_LOW;
            case PARTICULATE -> heavy ? PARTICULATE_MEDIUM : PARTICULATE_LOW;
        };
    }

    /** Whether a corrosive level wears worn armour: MEDIUM and above. */
    public static boolean corrodesArmour(Level corrosive) {
        return corrosive != null && corrosive.atLeast(Level.MEDIUM);
    }
}
