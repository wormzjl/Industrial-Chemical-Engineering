package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PairInteractions;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.TranslatedCubic;
import java.util.List;
import java.util.Objects;

/**
 * Constant volume-translated PR78 with analytic fixed-composition volumetric and caloric derivatives: the network's
 * adapter of the kernel's {@link TranslatedCubic} (the residual cubic with its translation and volumetric block, which
 * this class inherits with its {@code Workspace}, {@code Values} and {@code Phase}), adding what the kernel does not
 * model, the ideal-gas caloric functions ({@link #idealGasTerms}). Two ideal-gas sources, one per model: the coefficient
 * path (every constructor taking heat-capacity coefficients) evaluates the {@code shifted_polynomial_5} fits, past a
 * fit's range holding the heat capacity at the end value and integrating it; the spine variant
 * ({@link #TranslatedPengRobinson(List, PairInteractions, List, double[], double[])}) takes one {@link IdealGasFunction}
 * per component and a datum offset per component subtracted from the function's enthalpy (the network passes the
 * formation enthalpy, so its energies stay on the sensible datum, decision D10).
 *
 * <p>Loading this class installs the network's counter of temperature preparations
 * ({@link SolverDiagnostics#temperatureTermsCalls}, counted while {@link SolverDiagnostics#ENABLED}) on the kernel, so
 * the phase engine's preparations count as they did when it evaluated this class.</p>
 */
public final class TranslatedPengRobinson extends TranslatedCubic {
    private static final double REFERENCE_T = 298.15;
    static {
        TranslatedCubic.countPreparationsWith(() -> SolverDiagnostics.count(SolverDiagnostics.temperatureTermsCalls));
    }
    private final double[][] heatCapacityCoefficients;
    /** The spine variant's ideal-gas functions, one per component; {@code null} on the coefficient path. */
    private final IdealGasFunction[] idealGas;
    /** Subtracted from each spine enthalpy (the network's formation-enthalpy offsets); {@code null} on the coefficient path. */
    private final double[] datumOffsets;
    /**
     * The coefficient path's fit ranges, K: past {@code [fitMinimum[i], fitMaximum[i]]} component {@code i}'s heat
     * capacity is held at the end value and its enthalpy integrates it. Infinite (no continuation) unless given.
     */
    private final double[] fitMinimum,fitMaximum;

    /** Cp coefficients are powers of (T - 298.15 K); each component has exactly six coefficients. */
    public TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions,
                                 double[][] heatCapacityCoefficients, double[] translations) {
        this(components,interactions,heatCapacityCoefficients,translations,null,null);
    }
    /**
     * As {@link #TranslatedPengRobinson(List, double[][], double[][], double[])}, with each component's fit range
     * {@code [fitMinimum[i], fitMaximum[i]]} (K; both {@code null} for none): past it the heat capacity is held at the end
     * value and the enthalpy integrates it, so both are continuous at the ends.
     */
    public TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions,
                                 double[][] heatCapacityCoefficients, double[] translations, double[] fitMinimum, double[] fitMaximum) {
        this(components,interactions,null,heatCapacityCoefficients,translations,null,null,fitMinimum,fitMaximum);
    }

    /**
     * The full model on a kernel built elsewhere (W3b: the network's derived kernel, {@code DerivedPairs}): one ideal-gas
     * function per component, its enthalpy less {@code datumOffsets} (a spine package's formation enthalpies, zero on the
     * coefficient path's sensible datum). Both {@code null}: the residual model, as {@link TranslatedCubic#residualOnly}.
     */
    public TranslatedPengRobinson(List<ThermoComponent> components, PengRobinsonKernel kernel,
                                  List<? extends IdealGasFunction> idealGas, double[] datumOffsets, double[] translations) {
        super(checkedFunctions(components,idealGas,datumOffsets),kernel,translations,idealGas == null);
        this.idealGas = idealGas == null ? null : idealGas.toArray(new IdealGasFunction[0]);
        this.datumOffsets = datumOffsets == null ? null : datumOffsets.clone();
        int count = components.size();
        this.fitMinimum = new double[count];
        this.fitMaximum = new double[count];
        this.heatCapacityCoefficients = new double[count][6];
        for (int i = 0; i < count; i++) {
            fitMinimum[i] = Double.NEGATIVE_INFINITY;
            fitMaximum[i] = Double.POSITIVE_INFINITY;
        }
    }

    /**
     * The spine variant: the ideal-gas heat capacity and enthalpy of component {@code i} are
     * {@code idealGas.get(i).heatCapacity(T)} and {@code idealGas.get(i).enthalpy(T) - datumOffsets[i]}; the residual
     * parts, the translation and the volumetric block are the coefficient path's expressions on the same kernel
     * (the kernel on {@code interactions}).
     *
     * @param datumOffsets subtracted from each function's enthalpy, J/mol (the formation enthalpy for the network's
     *        sensible datum; zeros keep the functions' own formation datum)
     */
    public TranslatedPengRobinson(List<ThermoComponent> components, PairInteractions interactions,
                                  List<? extends IdealGasFunction> idealGas, double[] datumOffsets, double[] translations) {
        this(components,null,Objects.requireNonNull(interactions,"interactions"),new double[components.size()][6],
                translations,Objects.requireNonNull(idealGas,"idealGas").toArray(new IdealGasFunction[0]),
                Objects.requireNonNull(datumOffsets,"datumOffsets"),null,null);
    }

    /**
     * Exactly one of {@code interactions} (the constant matrix, {@code SPARSE_PAIRS}) and {@code pairModel} is set.
     * {@code idealGas} and {@code datumOffsets} are both set (the spine variant) or both {@code null}; so are the fit
     * ranges, which only the coefficient path takes. The ideal-gas inputs are checked first ({@link #checked}), then the
     * kernel's basis.
     */
    private TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions, PairInteractions pairModel,
                                   double[][] heatCapacityCoefficients, double[] translations,
                                   IdealGasFunction[] idealGas, double[] datumOffsets, double[] fitMinimum, double[] fitMaximum) {
        super(checked(components,heatCapacityCoefficients,idealGas,datumOffsets,fitMinimum,fitMaximum),
                interactions,pairModel,translations,false);
        int count = components.size();
        this.fitMinimum = new double[count];
        this.fitMaximum = new double[count];
        for (int i = 0; i < count; i++) {
            this.fitMinimum[i] = fitMinimum == null ? Double.NEGATIVE_INFINITY : fitMinimum[i];
            this.fitMaximum[i] = fitMaximum == null ? Double.POSITIVE_INFINITY : fitMaximum[i];
        }
        this.idealGas = idealGas == null ? null : idealGas.clone();
        this.datumOffsets = datumOffsets == null ? null : datumOffsets.clone();
        this.heatCapacityCoefficients = new double[count][];
        for (int i = 0; i < count; i++) this.heatCapacityCoefficients[i] = heatCapacityCoefficients[i].clone();
    }

    /** The kernel-taking constructor's checks of its ideal-gas inputs; returns {@code components}. */
    private static List<ThermoComponent> checkedFunctions(List<ThermoComponent> components,
                                                          List<? extends IdealGasFunction> idealGas, double[] datumOffsets) {
        if ((idealGas == null) != (datumOffsets == null)) throw new IllegalArgumentException("Inconsistent ideal-gas functions");
        if (idealGas != null && (idealGas.size() != components.size() || datumOffsets.length != components.size()))
            throw new IllegalArgumentException("One ideal-gas function and one datum offset per component");
        return components;
    }

    /**
     * The ideal-gas inputs of the coefficient path and the spine variant, checked before the kernel's basis: the
     * functions and their offsets, the fit ranges, then the heat-capacity coefficients (one row of six finite numbers per
     * component). Returns {@code components}.
     */
    private static List<ThermoComponent> checked(List<ThermoComponent> components, double[][] heatCapacityCoefficients,
                                                 IdealGasFunction[] idealGas, double[] datumOffsets,
                                                 double[] fitMinimum, double[] fitMaximum) {
        if ((idealGas == null) != (datumOffsets == null))
            throw new IllegalArgumentException("Inconsistent ideal-gas functions");
        if (idealGas != null) {
            if (idealGas.length != components.size() || datumOffsets.length != components.size())
                throw new IllegalArgumentException("One ideal-gas function and one datum offset per component");
            for (IdealGasFunction function : idealGas) Objects.requireNonNull(function, "idealGas");
            for (double offset : datumOffsets) finite(offset);
        }
        if ((fitMinimum == null) != (fitMaximum == null) || fitMinimum != null
                && (idealGas != null || fitMinimum.length != components.size() || fitMaximum.length != components.size()))
            throw new IllegalArgumentException("Inconsistent ideal-gas fit ranges");
        for (int i = 0; i < components.size(); i++) {
            double minimum = fitMinimum == null ? Double.NEGATIVE_INFINITY : fitMinimum[i];
            double maximum = fitMaximum == null ? Double.POSITIVE_INFINITY : fitMaximum[i];
            if (!(minimum < maximum) || maximum <= 0)
                throw new IllegalArgumentException("Invalid ideal-gas fit range " + minimum + ".." + maximum + " K");
        }
        if (heatCapacityCoefficients.length != components.size()) throw new IllegalArgumentException("Inconsistent property basis");
        for (double[] row : heatCapacityCoefficients) {
            if (row.length != 6) throw new IllegalArgumentException("Invalid property matrix dimensions");
            for (double value : row) finite(value);
        }
        return components;
    }

    /** Component {@code i}'s ideal-gas enthalpy at {@code temperature}, J/mol, on this model's datum (the spine variant). */
    public double idealGasEnthalpy(int i, double temperature) {
        if (idealGas == null) throw new IllegalStateException("No ideal-gas functions on the coefficient path");
        return idealGas[i].enthalpy(temperature) - datumOffsets[i];
    }

    /** Component {@code i}'s ideal-gas heat capacity at {@code temperature}, J/(mol K) (the spine variant). */
    public double idealGasHeatCapacity(int i, double temperature) {
        if (idealGas == null) throw new IllegalStateException("No ideal-gas functions on the coefficient path");
        return idealGas[i].heatCapacity(temperature);
    }

    /**
     * The spine variant: each function, which continues past its own data. The coefficient path: each fit at the
     * temperature held inside its range, the enthalpy continued with the held heat capacity past it.
     */
    @Override
    protected void idealGasTerms(double temperature, double[] heatCapacity, double[] enthalpy) {
        int count=heatCapacity.length;
        if(idealGas!=null)for(int i=0;i<count;i++) {
            heatCapacity[i]=idealGas[i].heatCapacity(temperature);
            enthalpy[i]=idealGas[i].enthalpy(temperature)-datumOffsets[i];
        }
        else for(int i=0;i<count;i++) {
            double fit=temperature<fitMinimum[i]?fitMinimum[i]:temperature>fitMaximum[i]?fitMaximum[i]:temperature;
            double delta=fit-REFERENCE_T,cp=0,h=0;
            for(int term=5;term>=0;term--){cp=cp*delta+heatCapacityCoefficients[i][term];h=h*delta+heatCapacityCoefficients[i][term]/(term+1);}
            heatCapacity[i]=cp;enthalpy[i]=h*delta;
            if(fit!=temperature)enthalpy[i]+=cp*(temperature-fit);
        }
    }

    /**
     * The lower end of a component's ideal-gas data, K: its spine function's, or its fit's range on the coefficient path
     * (minus infinity where none was given). The model continues past it; a notice reports it.
     */
    public double idealGasMinimumTemperature(int component) {
        return idealGas != null ? idealGas[component].minimumTemperature() : fitMinimum[component];
    }

    /** The upper end of a component's ideal-gas data, K, as {@link #idealGasMinimumTemperature}. */
    public double idealGasMaximumTemperature(int component) {
        return idealGas != null ? idealGas[component].maximumTemperature() : fitMaximum[component];
    }
}
