package com.wormzjl.icheme.science.integrity;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The corrosion rates of a wall, mm/y (batch 2026-11-16-fouling-corrosion, plan section 6.2): game-level fits to API RP
 * 571, API RP 581 Annex B, API RP 939-C (modified McConomy), API RP 932-B, de Waard–Milliams 1975, API Std 530, ISO 9223,
 * API RP 941 and EIGA Doc 13. Carbon steel rates, each a pressure term times a temperature term:
 * <ul>
 * <li><b>M1 sulfidation</b>, from 503 K with sulfur oil or H2S: {@code 1.3 exp(−8698 (1/T − 1/643)) (S_e/0.6)^0.4},
 * {@code S_e = clamp(max(S, 60 p_H2S), 0, 5)}. At 0.6 wt%: 0.08 at 260 °C, 0.18 at 288 °C, 1.3 at 370 °C, 2.4 at 400 °C.</li>
 * <li><b>M2 naphthenic acid</b>, oil with acid number at least 0.5, 493 to 673 K: {@code 0.5 min(A, 6) clamp((T − 493)/130, 0, 1)};
 * acid number 3 at 350 °C: 1.5.</li>
 * <li><b>M3 sour water</b>, wet with H2S below 423 K: {@code min(1.5, 0.3 p_H2S^0.5)}; 1 bar: 0.3.</li>
 * <li><b>M4 wet CO2</b>: {@code min(5, 0.3 · 10^(5.8 − 1710/min(T, 353) + 0.67 log10 p_CO2))}; 1 bar at 60 °C: 1.39.</li>
 * <li><b>M5 ammonium bisulfide</b>, wet with NH3 and H2S: {@code min(5, 2 min(p_NH3, p_H2S)^0.5)}.</li>
 * <li><b>M6 acid condensate</b>, wet with SO2: {@code min(5, (p_SO2/0.001)^0.5)}.</li>
 * <li><b>M7 aerated water</b>, wet with O2: {@code 0.25 min(2, p_O2/0.21) 2^((min(T, 353) − 298)/30)}; air-saturated at
 * 35 °C: 0.32.</li>
 * </ul>
 * The material's own laws, no factor: <b>M8 overheating</b> {@code 0.25 · 2^((T − T_limit)/25)} above
 * {@link Metallurgy#limit}; <b>M10 hydrogen attack</b> {@code 0.5 · 2^((T − T_H)/25)} above
 * {@link Metallurgy#hydrogenLimit} (carbon steel at 30 bar: 0.83 at 250 °C, 3.3 at 300 °C; at 100 bar and 250 °C 1.6);
 * <b>M11 oxygen fire</b> {@code 50 p_O2 / p_limit} above {@link Metallurgy#oxygenLimit}. M10 and M11 are cracking and
 * ignition booked as an equivalent wall loss. The inside rate is the largest (the governing mechanism, as API 581 takes
 * it); <b>M9 corrosive air</b> {@code 0.5 min(I, 5)} adds from outside. Every rate is finite, at most {@link #MAX_RATE},
 * and 0 for NaN or negative inputs. Pure; no state.
 */
public final class CorrosionLaw {
    private CorrosionLaw() {}

    /** The largest rate any law gives, mm/y: a 3 mm wall in 216 process seconds at the default 200 h year. Keeps every rate finite. */
    public static final double MAX_RATE = 1e4;
    /** M1: its onset, K; its reference temperature, K, and rate, mm/y, at 0.6 wt%; its activation, K; the sulfur cap, wt%; H2S as sulfur, wt% per bar. */
    static final double SULFIDATION_ONSET = 503, SULFIDATION_REFERENCE = 643, SULFIDATION_RATE = 1.3, SULFIDATION_ACTIVATION = 8698,
            SULFUR_CAP = 5, SULFUR_REFERENCE = .6, H2S_AS_SULFUR = 60;
    /** M2: the acid number onset and cap; the temperature window, K. */
    static final double ACID_ONSET = .5, ACID_CAP = 6, ACID_FROM = 493, ACID_RAMP = 130, ACID_TO = 673;
    /** M3: the top temperature, K. */
    static final double SOUR_WATER_TOP = 423;
    /** M4 and M7: the temperature cap, K. */
    static final double WATER_TEMPERATURE_CAP = 353;
    /** M7: the partial pressure of oxygen in air, bar. */
    static final double AIR_OXYGEN = .21;
    /** M9: the corrosive index from which the outside corrodes, and its cap. */
    public static final double AIR_ONSET = .01, AIR_CAP = 5;

    /** A wall's inside rate: the governing mechanism (null when nothing acts) and its rate, mm/y. */
    public record Inside(Mechanism mechanism, double rate) {
        public static final Inside NONE = new Inside(null, 0);
    }

    /** The carbon steel rate of {@code mechanism} M1 to M7 on {@code d}, mm/y; 0 for any other mechanism. */
    public static double rate(Mechanism mechanism, CorrosionDigest d) {
        double t = d.temperature();
        if (!(t > 0) || !Double.isFinite(t)) return 0;
        double r = switch (mechanism) {
            case SULFIDATION -> {
                double s = Math.max(d.oil() ? positive(d.sulfur()) : 0, H2S_AS_SULFUR * positive(d.h2s()));
                if (!(t >= SULFIDATION_ONSET) || !(s > 0)) yield 0;
                s = Math.min(s, SULFUR_CAP);
                yield SULFIDATION_RATE * DeterministicMath.exp(-SULFIDATION_ACTIVATION * (1 / t - 1 / SULFIDATION_REFERENCE))
                        * DeterministicMath.pow(s / SULFUR_REFERENCE, .4);
            }
            case NAPHTHENIC_ACID -> {
                double a = positive(d.acid());
                if (!d.oil() || !(a >= ACID_ONSET) || t < ACID_FROM || t > ACID_TO) yield 0;
                yield .5 * Math.min(a, ACID_CAP) * Math.min(1, (t - ACID_FROM) / ACID_RAMP);
            }
            case SOUR_WATER -> {
                double p = positive(d.h2s());
                if (!d.wet() || !(p > 0) || !(t < SOUR_WATER_TOP)) yield 0;
                yield Math.min(1.5, .3 * Math.sqrt(p));
            }
            case WET_CO2 -> {
                double p = positive(d.co2());
                if (!d.wet() || !(p > 0)) yield 0;
                double exponent = 5.8 - 1710 / Math.min(t, WATER_TEMPERATURE_CAP) + .67 * DeterministicMath.log10(Math.min(p, MAX_RATE));
                yield Math.min(5, .3 * DeterministicMath.pow(10, exponent));
            }
            case AMMONIUM_BISULFIDE -> {
                double p = Math.min(positive(d.nh3()), positive(d.h2s()));
                if (!d.wet() || !(p > 0)) yield 0;
                yield Math.min(5, 2 * Math.sqrt(p));
            }
            case ACID_CONDENSATE -> {
                double p = positive(d.so2());
                if (!d.wet() || !(p > 0)) yield 0;
                yield Math.min(5, Math.sqrt(p / .001));
            }
            case AERATED_WATER -> {
                double p = positive(d.o2());
                if (!d.wet() || !(p > 0)) yield 0;
                yield .25 * Math.min(2, p / AIR_OXYGEN) * DeterministicMath.pow(2, (Math.min(t, WATER_TEMPERATURE_CAP) - 298) / 30);
            }
            default -> 0;
        };
        return bounded(r);
    }

    /** M8 on {@code material} at {@code temperature}, K: {@code 0.25 · 2^((T − T_limit)/25)} above its limit. */
    public static double overheating(Metallurgy material, double temperature) {
        double over = temperature - material.limit();
        return over > 0 ? doubling(.25, over) : 0;
    }

    /** M10 on {@code material}: {@code 0.5 · 2^((T − T_H)/25)} above its hydrogen limit at {@code h2Bar}. */
    public static double hydrogenAttack(Metallurgy material, double temperature, double h2Bar) {
        double over = temperature - material.hydrogenLimit(h2Bar);
        return over > 0 ? doubling(.5, over) : 0;
    }

    /** M11 on {@code material}: {@code 50 p_O2 / p_limit} above its oxygen limit at {@code temperature}. */
    public static double oxygenFire(Metallurgy material, double temperature, double o2Bar) {
        double limit = material.oxygenLimit(temperature);
        return o2Bar > limit ? bounded(50 * o2Bar / limit) : 0;
    }

    /**
     * The inside rate of a {@code material} wall on {@code d}: the largest of M1 to M7 times the material's factor
     * (sulfidation's at the hydrogen flag {@code p_H2} ≥ 7 bar), M8, M10 and M11. Ties go to the earlier mechanism.
     */
    public static Inside inside(Metallurgy material, CorrosionDigest d) {
        double t = d.temperature();
        if (!(t > 0) || !Double.isFinite(t)) return Inside.NONE;
        boolean hydrogen = d.h2() >= Metallurgy.HYDROGEN_ONSET_BAR;
        Mechanism governing = null;
        double best = 0;
        for (var m : Mechanism.values()) {
            double r = switch (m) {
                case OVERHEATING -> overheating(material, t);
                case HYDROGEN_ATTACK -> hydrogenAttack(material, t, d.h2());
                case OXYGEN_FIRE -> oxygenFire(material, t, d.o2());
                case CORROSIVE_AIR, EROSION -> 0;
                default -> rate(m, d) * material.factor(m, hydrogen);
            };
            if (r > best) {
                best = r;
                governing = m;
            }
        }
        return governing == null ? Inside.NONE : new Inside(governing, best);
    }

    /** M9: a {@code material} wall's outside rate in a cube of corrosive index {@code index}: {@code 0.5 min(I, 5)} times its factor from 0.01. */
    public static double outside(Metallurgy material, double index) {
        if (!(index >= AIR_ONSET)) return 0;
        return .5 * Math.min(index, AIR_CAP) * material.factor(Mechanism.CORROSIVE_AIR, false);
    }

    private static double doubling(double base, double over) {
        // 2^((T - limit)/25) is above the cap from about 400 K over the limit: cap the exponent, not the result.
        return bounded(base * DeterministicMath.pow(2, Math.min(over / 25, 20)));
    }

    private static double positive(double x) {
        return x > 0 ? x : 0;
    }

    private static double bounded(double r) {
        return r > 0 ? Math.min(r, MAX_RATE) : 0;
    }
}
