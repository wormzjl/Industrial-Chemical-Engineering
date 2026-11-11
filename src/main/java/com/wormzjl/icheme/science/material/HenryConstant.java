package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.List;
import java.util.Objects;

/**
 * A gas's Henry constant in water, a dataset's optional {@code henry_in_water} sub-record (batch
 * 2026-11-11-aqueous-solvents, plan M3/M4, W2a): the IAPWS G7-04 form (Fernandez-Prini, Alvarez and Harvey 2003)
 * <pre>
 *   ln(k_H / p*) = A / T_r + B tau^0.355 / T_r + C T_r^-0.41 exp(tau),   T_r = T / 647.096 K,   tau = 1 - T_r
 * </pre>
 * <p>with p* the saturation pressure of water, here the water model's Wagner-Pruss line
 * ({@link V3WaterProperties#wagnerPruss}); k_H is on the mole-fraction basis (x_gas = f_gas / k_H at infinite dilution).
 * Record: {@code {"type": "iapws_g7_04", "coefficients": [A, B, C], "source", "revision"}}. The derivation of a gas-water
 * pair (W2b) reads it; until then it is data only, with its own revision and in no identity, as the other sub-records.</p>
 */
public record HenryConstant(double a, double b, double c, String source, String revision) {
    /** The record's {@code type}. */
    public static final String TYPE = "iapws_g7_04";
    /** The critical temperature of water that IAPWS G7-04 reduces with, K. */
    public static final double CRITICAL_TEMPERATURE = 647.096;

    public HenryConstant {
        Objects.requireNonNull(source); Objects.requireNonNull(revision);
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)) throw new IllegalArgumentException("coefficients: nonfinite");
    }

    /** ln(k_H / p*) at {@code temperature} K, from 0 K exclusive to the critical temperature. */
    public double lnRatio(double temperature) {
        if (!(temperature > 0) || !(temperature <= CRITICAL_TEMPERATURE))
            throw new IllegalArgumentException("Henry constant: temperature must lie in (0, " + CRITICAL_TEMPERATURE + "] K, got " + temperature);
        double tr = temperature / CRITICAL_TEMPERATURE, tau = 1.0 - tr;
        return a / tr + b * DeterministicMath.pow(tau, 0.355) / tr + c * DeterministicMath.pow(tr, -0.41) * DeterministicMath.exp(tau);
    }

    /** k_H in Pa (mole-fraction basis) at {@code temperature} K, with {@code water}'s saturation pressure. */
    public double pascal(MaterialCatalog.Water water, double temperature) {
        return V3WaterProperties.wagnerPruss(water, temperature) * DeterministicMath.exp(lnRatio(temperature));
    }

    /** Reads a {@code henry_in_water} sub-record; a refusal names the field. */
    static HenryConstant read(JsonObject o) {
        try {
            String type = JsonFields.text(o, "type");
            if (!type.equals(TYPE)) throw new IllegalArgumentException("type: expected " + TYPE + ", got " + type);
            List<Double> k = JsonFields.numbers(o, "coefficients");
            if (k.size() != 3) throw new IllegalArgumentException("coefficients: expected 3 numbers A, B, C, got " + k.size());
            return new HenryConstant(k.get(0), k.get(1), k.get(2), JsonFields.text(o, "source"), JsonFields.revision(o, "revision"));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("henry_in_water." + invalid.getMessage(), invalid); }
    }
}
