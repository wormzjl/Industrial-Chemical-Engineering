package com.wormzjl.icheme.science.integrity;

/**
 * The corrosion standard of one compound list (batch 2026-11-16-fouling-corrosion, plan section 6.1), built once per
 * list as {@code HazardStandard} is: per compound its molar mass, its sulfur (wt%), acid number and fouling number, and
 * the indices of hydrogen sulfide, carbon dioxide, sulfur dioxide, ammonia, oxygen, hydrogen and water, each found by its
 * exact formula (C, H, N, O, S atoms per molecule, the catalog's order) as {@code Releases.so2Reference} finds SO2. A
 * compound whose formula holds sulfur takes its sulfur from the formula ({@code 32.06 g/mol × S / M}); one without (a
 * crude cut, whose formula is CHx) takes its {@code corrosion} sub-record's. A list without such records reads sulfur 0.
 * An island's own list has other indices than the world's: build a standard per list, never share indices across lists.
 * Immutable; no Minecraft types.
 */
public final class CorrosionStandard {
    /** The molar mass of sulfur, kg/mol. */
    static final double SULFUR_MOLAR_MASS = 32.06e-3;
    /** The share of the total moles above which a liquid counts as present. */
    public static final double TRACE = 1e-9;
    /** Pa per bar. */
    static final double BAR = 1e5;
    private static final int C = 0, H = 1, N = 2, O = 3, S = 4;

    private final double[] molarMass, sulfur, acid, fouling;
    private final int h2s, co2, so2, nh3, o2, h2, water;

    /**
     * All arrays have one length, one entry per compound, in the list's order.
     *
     * @param molarMasses kg/mol, positive and finite
     * @param formulas    atoms of C, H, N, O and S per molecule (five entries each; all 0 for a record without a formula)
     * @param sulfurMassPercent each compound's {@code corrosion} sulfur, wt% (0 where it has no sub-record); a formula with sulfur overrides it
     * @param acidNumbers each compound's acid number, mg KOH/g (0 where none)
     * @param foulingNumbers each compound's fouling number, m²K/W (0 where none)
     */
    public CorrosionStandard(double[] molarMasses, double[][] formulas, double[] sulfurMassPercent, double[] acidNumbers,
                             double[] foulingNumbers) {
        int n = molarMasses.length;
        if (formulas.length != n || sulfurMassPercent.length != n || acidNumbers.length != n || foulingNumbers.length != n)
            throw new IllegalArgumentException("corrosion standard: every array needs " + n + " entries");
        molarMass = molarMasses.clone();
        sulfur = new double[n];
        acid = values(acidNumbers, "acid number");
        fouling = values(foulingNumbers, "fouling number");
        var given = values(sulfurMassPercent, "sulfur");
        int ih2s = -1, ico2 = -1, iso2 = -1, inh3 = -1, io2 = -1, ih2 = -1, iwater = -1;
        for (int i = 0; i < n; i++) {
            if (!(molarMass[i] > 0) || !Double.isFinite(molarMass[i]))
                throw new IllegalArgumentException("corrosion standard: compound " + i + " needs a positive molar mass");
            var f = formulas[i];
            if (f == null || f.length != 5) throw new IllegalArgumentException("corrosion standard: compound " + i + " needs five formula atoms");
            sulfur[i] = f[S] > 0 ? Math.min(100, 100 * SULFUR_MOLAR_MASS * f[S] / molarMass[i]) : given[i];
            if (ih2s < 0 && is(f, 0, 2, 0, 0, 1)) ih2s = i;
            if (ico2 < 0 && is(f, 1, 0, 0, 2, 0)) ico2 = i;
            if (iso2 < 0 && is(f, 0, 0, 0, 2, 1)) iso2 = i;
            if (inh3 < 0 && is(f, 0, 3, 1, 0, 0)) inh3 = i;
            if (io2 < 0 && is(f, 0, 0, 0, 2, 0)) io2 = i;
            if (ih2 < 0 && is(f, 0, 2, 0, 0, 0)) ih2 = i;
            if (iwater < 0 && is(f, 0, 2, 0, 1, 0)) iwater = i;
        }
        h2s = ih2s; co2 = ico2; so2 = iso2; nh3 = inh3; o2 = io2; h2 = ih2; water = iwater;
    }

    private static boolean is(double[] f, double c, double h, double n, double o, double s) {
        return f[C] == c && f[H] == h && f[N] == n && f[O] == o && f[S] == s;
    }

    private static double[] values(double[] values, String label) {
        var out = values.clone();
        for (int i = 0; i < out.length; i++)
            if (!(out[i] >= 0) || !Double.isFinite(out[i]))
                throw new IllegalArgumentException("corrosion standard: " + label + " of compound " + i + " must be finite and not negative");
        return out;
    }

    /** The number of compounds. */
    public int size() { return molarMass.length; }

    /** The index of the water found by its formula, -1 for none. */
    public int water() { return water; }

    /** Compound {@code i}'s sulfur, wt%: from its formula when that holds sulfur, else from its sub-record. */
    public double sulfur(int i) { return sulfur[i]; }

    /**
     * The digest of a state (plan section 6.1): {@code p_i = y_i P} in bar on the overall {@code moles}; {@code oil} and
     * {@code wet} when {@code oilMoles} and {@code aqueousMoles} (either may be null: none) sum above {@link #TRACE} of
     * the total; the sulfur, acid and fouling numbers mass-weighted over the oil liquid. Negative, NaN and infinite
     * amounts count as 0; a non-finite temperature or pressure reads 0; an empty state is {@link CorrosionDigest#EMPTY}.
     * Allocates the record only.
     */
    public CorrosionDigest digest(double[] moles, double temperature, double pressurePa, double[] oilMoles, double[] aqueousMoles) {
        int n = size();
        if (moles.length != n || oilMoles != null && oilMoles.length != n || aqueousMoles != null && aqueousMoles.length != n)
            throw new IllegalArgumentException("corrosion standard: amounts on another list than its " + n + " compounds");
        double total = 0, oil = 0, wet = 0, mass = 0, sulfurMass = 0, acidMass = 0, foulingMass = 0;
        for (int i = 0; i < n; i++) {
            total += amount(moles[i]);
            if (aqueousMoles != null) wet += amount(aqueousMoles[i]);
            if (oilMoles != null) {
                double x = amount(oilMoles[i]);
                oil += x;
                double m = x * molarMass[i];
                mass += m;
                sulfurMass += m * sulfur[i];
                acidMass += m * acid[i];
                foulingMass += m * fouling[i];
            }
        }
        if (!(total > 0) || !Double.isFinite(total)) return CorrosionDigest.EMPTY;
        double t = Double.isFinite(temperature) && temperature > 0 ? temperature : 0;
        double p = Double.isFinite(pressurePa) && pressurePa > 0 ? pressurePa / BAR : 0;
        boolean hasOil = oil > TRACE * total && mass > 0 && Double.isFinite(mass);
        return new CorrosionDigest(t, wet > TRACE * total, hasOil,
                hasOil ? sulfurMass / mass : 0, hasOil ? acidMass / mass : 0,
                partial(moles, h2s, total, p), partial(moles, co2, total, p), partial(moles, so2, total, p),
                partial(moles, nh3, total, p), partial(moles, o2, total, p), partial(moles, h2, total, p),
                hasOil ? foulingMass / mass : 0);
    }

    private static double amount(double x) {
        return x > 0 && x < Double.POSITIVE_INFINITY ? x : 0;
    }

    private static double partial(double[] moles, int i, double total, double pressureBar) {
        return i < 0 ? 0 : amount(moles[i]) / total * pressureBar;
    }
}
