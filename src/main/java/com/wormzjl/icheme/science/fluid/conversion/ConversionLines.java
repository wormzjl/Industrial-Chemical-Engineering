package com.wormzjl.icheme.science.fluid.conversion;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text of one fluid conversion rule on its JEI page (batch 2026-11-15-fluid-interfaces, W5, D10), with no Minecraft
 * type so a unit test reads it. Forward (the Forge fluid to a stream): the stream's largest {@link #MAX_COMPOUNDS} compounds
 * with their mole per cent, the rest counted, its temperature and pressure, and a gas rule's moles per litre. Reverse (a
 * drawn phase to the fluid): each component's range on its basis, the unnamed compounds' most, the temperature and
 * pressure windows. Temperatures in °C, pressures in kPa, a fraction in per cent.
 */
public final class ConversionLines {
    /** The compounds a forward stream lists; the rest are counted on one line. */
    public static final int MAX_COMPOUNDS = 6;
    static final String PREFIX = "jei.icheme.fluid_conversion.";
    /** The category's title key. */
    public static final String TITLE = PREFIX + "title";

    /**
     * One line: its language key, the compound it names first (its id, or null), then its numbers as text. The page puts
     * the compound's name before the numbers.
     */
    public record Line(String key, String compound, List<String> args) {
        public Line {
            args = List.copyOf(args);
        }

        static Line of(String key, String... args) {
            return new Line(PREFIX + key, null, List.of(args));
        }

        static Line compound(String key, String compound, String... args) {
            return new Line(PREFIX + key, compound, List.of(args));
        }
    }

    private ConversionLines() {
    }

    /** The lines of {@code rule}: its forward block, then its reverse block, each under its heading. */
    public static List<Line> of(ConversionRule rule) {
        var out = new ArrayList<Line>();
        var forward = rule.toIChemE();
        if (forward != null) {
            out.add(Line.of(rule.phase() == ConversionRule.Phase.GAS ? "to_icheme.gas" : "to_icheme.liquid"));
            var largest = forward.composition().entrySet().stream().filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
            for (int i = 0; i < Math.min(MAX_COMPOUNDS, largest.size()); i++)
                out.add(Line.compound("compound", largest.get(i).getKey(), percent(largest.get(i).getValue())));
            if (largest.size() > MAX_COMPOUNDS) out.add(Line.of("more", Integer.toString(largest.size() - MAX_COMPOUNDS)));
            out.add(Line.of("state", celsius(forward.temperature()), kilopascal(forward.pressure())));
            if (!Double.isNaN(forward.molesPerLitre())) out.add(Line.of("moles_per_litre", number(forward.molesPerLitre())));
        }
        var reverse = rule.toForge();
        if (reverse != null) {
            out.add(Line.of(reverse.basis() == ConversionRule.Basis.MASS ? "to_forge.mass" : "to_forge.mole"));
            reverse.components().entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(e ->
                    out.add(Line.compound("range", e.getKey(), percent(e.getValue().min()), percent(e.getValue().max()))));
            out.add(Line.of("others", percent(reverse.othersMax())));
            window(out, "temperature", reverse.temperature(), ConversionLines::celsius);
            window(out, "pressure", reverse.pressure(), ConversionLines::kilopascal);
        }
        return List.copyOf(out);
    }

    private static void window(List<Line> out, String key, ConversionRule.Range range, java.util.function.DoubleFunction<String> unit) {
        boolean low = Double.isFinite(range.min()), high = Double.isFinite(range.max());
        if (low && high) out.add(Line.of(key, unit.apply(range.min()), unit.apply(range.max())));
        else if (low) out.add(Line.of(key + ".min", unit.apply(range.min())));
        else if (high) out.add(Line.of(key + ".max", unit.apply(range.max())));
    }

    /** A fraction as per cent: one decimal from 10 %, two from 0.1 %, else three significant digits. */
    static String percent(double fraction) {
        double p = fraction * 100;
        if (p == 0 || Math.abs(p) >= 10) return String.format(Locale.ROOT, "%.1f", p);
        if (Math.abs(p) >= .1) return String.format(Locale.ROOT, "%.2f", p);
        return String.format(Locale.ROOT, "%.3g", p);
    }

    static String celsius(double kelvin) {
        return String.format(Locale.ROOT, "%.1f", kelvin - 273.15);
    }

    static String kilopascal(double pascal) {
        return String.format(Locale.ROOT, "%.1f", pascal / 1000);
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }
}
