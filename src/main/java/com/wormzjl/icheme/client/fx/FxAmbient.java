package com.wormzjl.icheme.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The visual ambient at an exhaust (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.1, owner decision X2 A): the air temperature and
 * relative humidity that decide how much white plume shows. Visual only; the server keeps 25 °C and 50 % for the heat duty.
 * The mapping is in pure static methods ({@link #of}), testable without a game.
 * <ul>
 * <li><b>Temperature.</b> Minecraft's biome temperature t maps linearly to °C so that the snow line t = 0.15 is 0 °C and
 * plains, t = 0.8, are 25 °C, the mod's process ambient: T = 25 (t − 0.15) / 0.65. Forest (0.7) gives 21 °C, taiga (0.25)
 * 4 °C, jungle (0.95) 31 °C, snowy plains (0.0) −6 °C; deserts and badlands (2.0) clamp at 40 °C, frozen peaks at −20 °C.
 * Above y = 80 the biome temperature falls by 0.05 per 40 blocks, as vanilla's height adjustment does without its ±8-block
 * noise term ({@code Biome.getTemperature(pos)} is private in 1.21.1): about 4.8 K per 100 blocks.</li>
 * <li><b>Humidity.</b> RH = 0.3 + 0.6 × downfall: deserts (0) 30 %, plains (0.4) 54 %, forests (0.8) 78 %, jungles and
 * swamps (0.9) 84 %. Rain raises it toward 100 %: RH + 0.8 (1 − RH) × rain level.</li>
 * <li><b>Weather and time.</b> Rain cools by 3 K × rain level; night by 5 K × night share, the night share rising from 0
 * to 1 as the sun sinks from 0.1 above to 0.1 below the horizon (sun height as the cosine of the sun angle).</li>
 * </ul>
 */
public final class FxAmbient {
    public static final double MIN_CELSIUS = -20, MAX_CELSIUS = 40;
    /** Biome temperature at 0 °C (the snow line) and at 25 °C (plains). */
    private static final double FREEZING = .15, PLAINS = .8;
    private static final double RAIN_COOLING = 3, NIGHT_COOLING = 5;

    private FxAmbient() {
    }

    /** An ambient: dry-bulb temperature, °C, and relative humidity, 0 to 1. */
    public record Ambient(double celsius, double humidity) {
    }

    /** °C of a biome temperature {@code t}, before weather and night, clamped to −20..40 °C. */
    public static double celsius(double biomeTemperature) {
        return clamp(25 * (biomeTemperature - FREEZING) / (PLAINS - FREEZING), MIN_CELSIUS, MAX_CELSIUS);
    }

    /** The biome temperature at height {@code y}, as vanilla's height adjustment without its noise term. */
    public static double heightAdjusted(double baseTemperature, int y) {
        return y > 80 ? baseTemperature - (y - 80) * .05 / 40 : baseTemperature;
    }

    /** Relative humidity from a biome's downfall (0 to 1) and the rain level (0 to 1). */
    public static double humidity(double downfall, double rain) {
        double rh = .3 + .6 * clamp(downfall, 0, 1);
        return clamp(rh + .8 * (1 - rh) * clamp(rain, 0, 1), 0, 1);
    }

    /** The night share from the sun height (cosine of the sun angle): 0 by day, 1 by night. */
    public static double night(double sunHeight) {
        double k = clamp((.1 - sunHeight) / .2, 0, 1);
        return k * k * (3 - 2 * k);
    }

    /**
     * The ambient for a biome's base temperature and downfall at height {@code y}, a rain level (0 to 1) and a sun height
     * (cosine of the sun angle, 1 at noon, −1 at midnight).
     */
    public static Ambient of(double baseTemperature, double downfall, int y, double rain, double sunHeight) {
        double t = celsius(heightAdjusted(baseTemperature, y)) - RAIN_COOLING * clamp(rain, 0, 1) - NIGHT_COOLING * night(sunHeight);
        return new Ambient(clamp(t, MIN_CELSIUS, MAX_CELSIUS), humidity(downfall, rain));
    }

    /** The ambient at {@code pos} in {@code level}: its biome, the rain there (none in a biome without precipitation) and the sun. */
    public static Ambient at(Level level, BlockPos pos, float partialTick) {
        var biome = level.getBiome(pos).value();
        var climate = biome.getModifiedClimateSettings();
        double rain = biome.hasPrecipitation() ? level.getRainLevel(partialTick) : 0;
        double sunHeight = Math.cos(level.getSunAngle(partialTick));
        return of(biome.getBaseTemperature(), climate.downfall(), pos.getY(), rain, sunHeight);
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
