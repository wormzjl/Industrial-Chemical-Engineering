package com.wormzjl.icheme.science.material;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.List;
import java.util.Objects;

/** Immutable pure-phase dynamic-viscosity correlation; parameters and domains are supplied by a data pack. */
public record ViscosityCorrelation(Model model, double minimumTemperatureKelvin, double maximumTemperatureKelvin,
        double minimumPressurePascal, double maximumPressurePascal, double referenceTemperatureKelvin,
        List<Double> coefficients, List<Double> exponents, String revision, String source, boolean estimated,
        List<Double> temperaturesKelvin) {
    public enum Phase { LIQUID, VAPOR }
    public enum Model { ANDRADE, SUTHERLAND, POWER_SUM, LOG_TABLE }

    public ViscosityCorrelation {
        Objects.requireNonNull(model, "model");
        coefficients = List.copyOf(coefficients);
        exponents = List.copyOf(exponents);
        temperaturesKelvin = List.copyOf(temperaturesKelvin);
        requirePositive(minimumTemperatureKelvin, "temperature_min_kelvin");
        requirePositive(maximumTemperatureKelvin, "temperature_max_kelvin");
        requirePositive(minimumPressurePascal, "pressure_min_pascal");
        requirePositive(maximumPressurePascal, "pressure_max_pascal");
        requirePositive(referenceTemperatureKelvin, "reference_temperature_kelvin");
        if (maximumTemperatureKelvin <= minimumTemperatureKelvin || maximumPressurePascal < minimumPressurePascal)
            throw new IllegalArgumentException("viscosity: invalid temperature or pressure interval");
        if (revision == null || revision.isBlank() || revision.length() > 128
                || source == null || source.isBlank() || source.length() > 2048)
            throw new IllegalArgumentException("viscosity: revision and source are required");
        if (coefficients.isEmpty() || coefficients.size() > (model == Model.LOG_TABLE ? 2048 : 16)
                || coefficients.stream().anyMatch(v -> !Double.isFinite(v))
                || exponents.stream().anyMatch(v -> !Double.isFinite(v)))
            throw new IllegalArgumentException("viscosity: coefficients/exponents must be finite and bounded");
        if (model == Model.LOG_TABLE) {
            if (coefficients.size() < 2 || temperaturesKelvin.size() != coefficients.size() || !exponents.isEmpty()
                    || temperaturesKelvin.getFirst() != minimumTemperatureKelvin || temperaturesKelvin.getLast() != maximumTemperatureKelvin)
                throw new IllegalArgumentException("viscosity.log_table: temperature nodes must span the declared domain and match values");
            for (int i = 0; i < coefficients.size(); i++) {
                requirePositive(coefficients.get(i), "viscosity.log_table value");
                requirePositive(temperaturesKelvin.get(i), "viscosity.log_table temperature");
                if (i > 0 && temperaturesKelvin.get(i) <= temperaturesKelvin.get(i - 1))
                    throw new IllegalArgumentException("viscosity.log_table: temperatures must be strictly increasing");
            }
        } else if (!temperaturesKelvin.isEmpty()) {
            throw new IllegalArgumentException("viscosity: temperature nodes are only valid for log_table");
        } else if (model == Model.POWER_SUM) {
            if (exponents.size() != coefficients.size() || coefficients.stream().anyMatch(v -> v <= 0))
                throw new IllegalArgumentException("viscosity.power_sum: positive coefficients and matching exponents required");
        } else {
            if (coefficients.size() != 2 || !exponents.isEmpty() || coefficients.getFirst() <= 0)
                throw new IllegalArgumentException("viscosity: expected two coefficients, positive scale, and no exponents");
            if (model == Model.SUTHERLAND && (coefficients.get(1) < 0
                    || referenceTemperatureKelvin < minimumTemperatureKelvin || referenceTemperatureKelvin > maximumTemperatureKelvin))
                throw new IllegalArgumentException("viscosity.sutherland: S must be nonnegative and reference temperature in range");
        }
        // Positive power sums are convex as functions of ln(T); their maximum is at an endpoint.
        // Andrade and the admitted Sutherland form are monotone. Reject overflow before publication.
        requirePositive(evaluate(model, minimumTemperatureKelvin, referenceTemperatureKelvin, coefficients, exponents, temperaturesKelvin), "viscosity at minimum temperature");
        requirePositive(evaluate(model, maximumTemperatureKelvin, referenceTemperatureKelvin, coefficients, exponents, temperaturesKelvin), "viscosity at maximum temperature");
    }

    /**
     * The viscosity cap, Pa s: a glass. A continuation is capped here in log space, so no table continued to the
     * numerical box's 10 K (the heaviest crude cut holds 6.8e4 Pa s at 293 K) overflows a double.
     */
    public static final double MAXIMUM_VISCOSITY = 1e12;
    private static final double LOG_MAXIMUM_VISCOSITY = DeterministicMath.log(MAXIMUM_VISCOSITY);

    /**
     * Returns Pa s at any positive finite temperature, as the phase the correlation describes. Inside the declared
     * temperature interval it is the correlation itself. Past an end of a {@code log_table} it continues from the end
     * node (owner rule of 2026-10-16, plan RANGE_EXTRAPOLATION_PLAN section 3): a liquid below its table keeps the end
     * segment's slope with ln(mu) linear in 1/T (Andrade's form; value and slope continuous) and above its table holds
     * the end value (continuous, like the table's own nodes); a vapour has ln(mu) linear in ln(T) with the end segment's
     * slope at both ends. A formula (Andrade, Sutherland, power sum) continues itself. Every result is capped at
     * {@link #MAXIMUM_VISCOSITY}. The declared interval is the data range a notice reports; these models have no
     * pressure correction, so the declared pressure interval is data only.
     */
    public double dynamicViscosityPascalSeconds(double temperatureKelvin, Phase phase) {
        Objects.requireNonNull(phase, "phase");
        if (!(temperatureKelvin > 0) || !Double.isFinite(temperatureKelvin))
            throw new IllegalArgumentException("Viscosity temperature must be positive and finite: " + temperatureKelvin);
        double value;
        if (model == Model.LOG_TABLE && temperatureKelvin < minimumTemperatureKelvin) value = continued(temperatureKelvin, 0, 1, phase);
        else if (model == Model.LOG_TABLE && temperatureKelvin > maximumTemperatureKelvin)
            value = continued(temperatureKelvin, coefficients.size() - 1, coefficients.size() - 2, phase);
        else value = evaluate(model, temperatureKelvin, referenceTemperatureKelvin, coefficients, exponents, temperaturesKelvin);
        if (!(value <= MAXIMUM_VISCOSITY)) value = MAXIMUM_VISCOSITY;
        requirePositive(value, "dynamic viscosity");
        return value;
    }

    /** A table past its end node {@code end}, {@code inner} the node next to it: the continuation of the phase. */
    private double continued(double t, int end, int inner, Phase phase) {
        double te = temperaturesKelvin.get(end), endValue = coefficients.get(end);
        if (phase == Phase.LIQUID && t > te) return endValue;
        double endLog = DeterministicMath.log(endValue);
        double slope = (endLog - DeterministicMath.log(coefficients.get(inner))) / (te - temperaturesKelvin.get(inner));
        double log = phase == Phase.LIQUID ? endLog - slope * te * te * (1 / t - 1 / te) : endLog + slope * te * DeterministicMath.log(t / te);
        return log >= LOG_MAXIMUM_VISCOSITY ? MAXIMUM_VISCOSITY : DeterministicMath.exp(log);
    }

    private static double evaluate(Model model, double t, double reference, List<Double> c, List<Double> powers, List<Double> nodes) {
        return switch (model) {
            case ANDRADE -> DeterministicMath.exp(DeterministicMath.log(c.get(0)) + c.get(1) / t);
            case SUTHERLAND -> c.get(0) * DeterministicMath.pow(t / reference, 1.5) * (reference + c.get(1)) / (t + c.get(1));
            case LOG_TABLE -> {
                int index = java.util.Collections.binarySearch(nodes, t);
                if (index >= 0) yield c.get(index);
                int high = -index - 1, low = high - 1;
                double fraction = (t - nodes.get(low)) / (nodes.get(high) - nodes.get(low));
                double lowLog = DeterministicMath.log(c.get(low));
                yield DeterministicMath.exp(lowLog + fraction * (DeterministicMath.log(c.get(high)) - lowLog));
            }
            case POWER_SUM -> {
                double total = 0;
                for (int i = 0; i < c.size(); i++) total += c.get(i) * DeterministicMath.pow(t / reference, powers.get(i));
                yield total;
            }
        };
    }

    private static void requirePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + ": must be finite and positive");
    }
}
