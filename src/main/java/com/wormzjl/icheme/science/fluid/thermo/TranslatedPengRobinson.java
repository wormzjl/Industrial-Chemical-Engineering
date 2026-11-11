package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PairInteractions;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import com.wormzjl.icheme.science.thermo.phase.PhaseIdentification;
import java.util.List;
import java.util.Objects;

/**
 * Constant volume-translated PR78 with analytic fixed-composition volumetric and caloric derivatives. The equation of
 * state itself is the shared {@link PengRobinsonKernel} on its pair list (the
 * network's package has 11 nonzero binary interactions among 21 components); what stays here is what the kernel does
 * not model: the ideal-gas caloric functions, the constant volume translation, and the volumetric block (dv/dT, dv/dP,
 * d2v/dT2, cp, dh/dP, the isothermal compressibility and the partial molar volumes), explicit algebra in
 * {@code (v, a, b, da/dT, d2a/dT2, S_i)}. Two ideal-gas sources, one per model: the coefficient path (every constructor
 * taking heat-capacity coefficients) evaluates the {@code shifted_polynomial_5} fits, past a fit's range holding the
 * heat capacity at the end value and integrating it; the spine variant
 * ({@link #TranslatedPengRobinson(List, PairInteractions, List, double[], double[])}) takes one {@link IdealGasFunction}
 * per component and a datum offset per component subtracted from the function's enthalpy (the network passes the
 * formation enthalpy, so its energies stay on the sensible datum, decision D10).
 */
public final class TranslatedPengRobinson {
    private static final double R = FluidThermodynamics.R;
    private static final double SQRT_TWO = Math.sqrt(2);
    private static final double REFERENCE_T = 298.15;
    private final List<ThermoComponent> components;
    private final double[][] heatCapacityCoefficients;
    private final double[] translations;
    private final PengRobinsonKernel kernel;
    /** See {@link #residualOnly}: no ideal-gas part, so the heat capacity is the residual one and may be any sign. */
    private final boolean residualOnly;
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
        this(components,interactions,null,heatCapacityCoefficients,translations,false,null,null,fitMinimum,fitMaximum);
    }

    /**
     * The same equation of state without an ideal-gas part, for an evaluator that adds its own ideal-gas functions
     * (the phase engine's {@code science.thermo.phase.CubicPhaseEvaluator}). Every heat-capacity coefficient is zero,
     * so the ideal-gas enthalpy and heat capacity are exact zeros: {@link Values#molarEnthalpy()} is then
     * {@code h^R + P c} bit for bit and {@link Values#heatCapacity()} is the residual {@code c_p}, whose sign is not
     * checked. Volume, fugacity coefficients and every volumetric derivative are the ones the full model computes.
     */
    public static TranslatedPengRobinson residualOnly(List<ThermoComponent> components, double[][] interactions,
                                                      double[] translations) {
        return new TranslatedPengRobinson(components,interactions,null,new double[components.size()][6],translations,true,null,null,null,null);
    }

    /**
     * {@link #residualOnly(List, double[][], double[])} on a pair-interaction model: the kernel reads
     * the model's pairs, so a group-contribution (E-PPR78) pair's
     * {@code k_ij(T)} and its temperature derivatives enter every value. On a model of constant pairs it is the matrix
     * factory's model bit for bit.
     */
    public static TranslatedPengRobinson residualOnly(List<ThermoComponent> components, PairInteractions interactions,
                                                      double[] translations) {
        return new TranslatedPengRobinson(components,null,Objects.requireNonNull(interactions,"interactions"),
                new double[components.size()][6],translations,true,null,null,null,null);
    }

    /**
     * {@link #residualOnly(List, double[][], double[])} on a prebuilt kernel (batch 2026-11-11-aqueous-solvents, W3a): a
     * list whose pairs are derived ({@code science.material.DerivedPairs}, the Huron-Vidal rule and per-compound alphas)
     * hands its own kernel over. The kernel's components must be {@code components} in order (critical constants and
     * acentric factors equal); the translation and the volumetric block are this class's expressions on it.
     */
    public static TranslatedPengRobinson residualOnly(List<ThermoComponent> components, PengRobinsonKernel kernel,
                                                      double[] translations) {
        return new TranslatedPengRobinson(components,Objects.requireNonNull(kernel,"kernel"),translations);
    }

    /** The kernel-taking residual-only form: no ideal-gas part, no fit ranges. */
    private TranslatedPengRobinson(List<ThermoComponent> components, PengRobinsonKernel kernel, double[] translations) {
        this(components, kernel, null, null, translations);
    }

    /**
     * The full model on a kernel built elsewhere (W3b: the network's derived kernel, {@code DerivedPairs}): one ideal-gas
     * function per component, its enthalpy less {@code datumOffsets} (a spine package's formation enthalpies, zero on the
     * coefficient path's sensible datum).
     */
    public TranslatedPengRobinson(List<ThermoComponent> components, PengRobinsonKernel kernel,
                                  List<? extends IdealGasFunction> idealGas, double[] datumOffsets, double[] translations) {
        this.residualOnly = idealGas == null;
        if ((idealGas == null) != (datumOffsets == null)) throw new IllegalArgumentException("Inconsistent ideal-gas functions");
        if (idealGas != null && (idealGas.size() != components.size() || datumOffsets.length != components.size()))
            throw new IllegalArgumentException("One ideal-gas function and one datum offset per component");
        this.idealGas = idealGas == null ? null : idealGas.toArray(new IdealGasFunction[0]);
        this.datumOffsets = datumOffsets == null ? null : datumOffsets.clone();
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || kernel.componentCount() != count || translations.length != count)
            throw new IllegalArgumentException("Inconsistent property basis");
        this.fitMinimum = new double[count];
        this.fitMaximum = new double[count];
        this.heatCapacityCoefficients = new double[count][6];
        this.translations = translations.clone();
        for (int i = 0; i < count; i++) {
            fitMinimum[i] = Double.NEGATIVE_INFINITY;
            fitMaximum[i] = Double.POSITIVE_INFINITY;
            finite(this.translations[i]);
        }
        this.kernel = kernel;
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
                translations,false,Objects.requireNonNull(idealGas,"idealGas").toArray(new IdealGasFunction[0]),
                Objects.requireNonNull(datumOffsets,"datumOffsets"),null,null);
    }

    /** Exactly one of {@code interactions} (the constant matrix, {@code SPARSE_PAIRS}) and {@code pairModel} is set.
     * {@code idealGas} and {@code datumOffsets} are both set (the spine variant) or both {@code null}; so are the fit
     * ranges, which only the coefficient path takes. */
    private TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions, PairInteractions pairModel,
                                   double[][] heatCapacityCoefficients, double[] translations, boolean residualOnly,
                                   IdealGasFunction[] idealGas, double[] datumOffsets, double[] fitMinimum, double[] fitMaximum) {
        this.residualOnly = residualOnly;
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
        this.fitMinimum = new double[components.size()];
        this.fitMaximum = new double[components.size()];
        for (int i = 0; i < components.size(); i++) {
            this.fitMinimum[i] = fitMinimum == null ? Double.NEGATIVE_INFINITY : fitMinimum[i];
            this.fitMaximum[i] = fitMaximum == null ? Double.POSITIVE_INFINITY : fitMaximum[i];
            if (!(this.fitMinimum[i] < this.fitMaximum[i]) || this.fitMaximum[i] <= 0)
                throw new IllegalArgumentException("Invalid ideal-gas fit range " + this.fitMinimum[i] + ".." + this.fitMaximum[i] + " K");
        }
        this.idealGas = idealGas == null ? null : idealGas.clone();
        this.datumOffsets = datumOffsets == null ? null : datumOffsets.clone();
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || (pairModel == null ? interactions.length != count : pairModel.componentCount() != count)
                || heatCapacityCoefficients.length != count
                || translations.length != count) throw new IllegalArgumentException("Inconsistent property basis");
        this.heatCapacityCoefficients = new double[count][];
        this.translations = translations.clone();
        double[] criticalTemperatures=new double[count],criticalPressures=new double[count],acentricFactors=new double[count];
        for (int i = 0; i < count; i++) {
            if ((pairModel == null && interactions[i].length != count) || heatCapacityCoefficients[i].length != 6) {
                throw new IllegalArgumentException("Invalid property matrix dimensions");
            }
            this.heatCapacityCoefficients[i] = heatCapacityCoefficients[i].clone();
            if (pairModel == null) for (double value : interactions[i]) finite(value);
            for (double value : this.heatCapacityCoefficients[i]) finite(value);
            finite(this.translations[i]);
            var component=components.get(i);
            criticalTemperatures[i]=component.criticalTemperatureKelvin();
            criticalPressures[i]=component.criticalPressurePascal();
            acentricFactors[i]=component.acentricFactor();
        }
        if (pairModel == null) for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) {
            if (interactions[i][j] != interactions[j][i]) throw new IllegalArgumentException("Asymmetric interactions");
        }
        // The kernel's own domain is left open: this class validates every state itself, with the messages it
        // always produced, and a property package's validity range is the caller's business, not the EOS's.
        kernel=pairModel == null
                ? new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,interactions,
                        Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE)
                : new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,pairModel,
                        Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE);
    }

    /** The equation of state this model translates; the phase-stability test runs on it (the translation cancels). */
    public PengRobinsonKernel kernel() { return kernel; }

    public Phase evaluate(double temperature, double pressure, double[] amounts, PengRobinsonKernel.Root root) {
        return evaluate(temperature,pressure,amounts,root,prepare(temperature));
    }

    /**
     * One exact temperature's prepared state, and the scratch every evaluation at it reuses: the kernel's 3n
     * pure-component vectors, the composition-dependent sums formed per evaluation in O(n + pairs). Mutable scratch, so
     * it belongs to one solving thread at a time; the caller that holds it (a node's {@link FluidThermodynamics.Prepared})
     * already has that confinement.
     */
    public static final class Workspace {
        private final TranslatedPengRobinson owner;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation evaluation;
        private final Values values;
        private final double[] heatCapacity,enthalpy;
        private final double[] rootDt,rootDt2,crossDt;
        private double temperature=Double.NaN;
        private Workspace(TranslatedPengRobinson owner) {
            this.owner=owner;int count=owner.components.size();
            mixture=owner.kernel.newWorkspace();evaluation=owner.kernel.newEvaluation();values=new Values(count);
            heatCapacity=new double[count];enthalpy=new double[count];
            rootDt=new double[count];rootDt2=new double[count];crossDt=new double[count];
        }
        public double temperature(){return temperature;}
        /** Component {@code i}'s ideal-gas heat capacity at the prepared temperature, J/(mol K). */
        public double idealGasHeatCapacity(int i){return heatCapacity[i];}
    }

    /**
     * The same numbers a {@link Phase} carries, in a buffer the caller keeps instead of a record it allocates.
     * {@link #evaluate} fills the workspace's own and copies out of it; {@link #evaluateValues} returns that buffer
     * itself.
     */
    public static final class Values {
        private final double[] logFugacityCoefficients,partialMolarVolumes;
        private double molarVolume,molarEnthalpy,molarInternalEnergy,volumeTemperatureDerivative,
                volumePressureDerivative,heatCapacity,enthalpyPressureDerivative,isothermalCompressibility,
                volumeSecondTemperatureDerivative,compressibility,idealGasHeatCapacity;
        /** The phase identification inputs of the evaluated root: its untranslated volume and the mixture a, b, da/dT. */
        private double temperature,untranslatedVolume,attraction,coVolume,attractionTemperatureDerivative;
        private int physicalRootCount;
        private Values(int count) {
            logFugacityCoefficients=new double[count];partialMolarVolumes=new double[count];
        }
        public double molarVolume(){return molarVolume;}
        public double molarEnthalpy(){return molarEnthalpy;}
        public double molarInternalEnergy(){return molarInternalEnergy;}
        public double volumeTemperatureDerivative(){return volumeTemperatureDerivative;}
        public double volumePressureDerivative(){return volumePressureDerivative;}
        public double heatCapacity(){return heatCapacity;}
        public double enthalpyPressureDerivative(){return enthalpyPressureDerivative;}
        public double isothermalCompressibility(){return isothermalCompressibility;}
        public double volumeSecondTemperatureDerivative(){return volumeSecondTemperatureDerivative;}
        public double compressibility(){return compressibility;}
        /** The ideal-gas half of {@link #heatCapacity()}; the residual half is the kernel's dH^R/dT. */
        public double idealGasHeatCapacity(){return idealGasHeatCapacity;}
        /**
         * The phase identification parameter of the evaluated root (Venkatarathnam and Oellrich 2011) on the untranslated
         * cubic, {@link PhaseIdentification#parameter}: the root is liquid-like when it is above one and above
         * {@link #compressibility()} ({@link PhaseIdentification#liquidLike(double, double)}). It is the network's one
         * phase-identification rule (replacing the retired root heuristic), computed on demand from the root's own
         * volume and mixture parameters, so an evaluation that does not ask for it pays nothing.
         */
        public double phaseIdentificationParameter() {
            return PhaseIdentification.parameter(temperature,untranslatedVolume,attraction,coVolume,attractionTemperatureDerivative);
        }
        /** Physical roots of the cubic at this state and composition: 1, or 3 where both roots exist. */
        public int physicalRootCount(){return physicalRootCount;}
        /** The caller must not mutate these; they are refilled by the next evaluation. */
        public double[] logFugacityCoefficientsView(){return logFugacityCoefficients;}
        public double[] partialMolarVolumesView(){return partialMolarVolumes;}
        /** The root's untranslated molar volume, m3/mol, and the mixture's co-volume (water's share reads their ratio). */
        public double untranslatedVolume(){return untranslatedVolume;}
        public double coVolume(){return coVolume;}
        private Phase phase() {
            return new Phase(molarVolume,molarEnthalpy,molarInternalEnergy,volumeTemperatureDerivative,
                    volumePressureDerivative,heatCapacity,enthalpyPressureDerivative,isothermalCompressibility,
                    logFugacityCoefficients.clone(),volumeSecondTemperatureDerivative,
                    partialMolarVolumes.clone(),phaseIdentificationParameter());
        }
    }

    public Workspace newWorkspace() { return new Workspace(this); }

    /** A workspace prepared at one temperature; a solve reuses it across every composition and pressure trial. */
    public Workspace prepare(double temperature) {
        Workspace workspace=new Workspace(this);
        prepare(temperature,workspace);
        return workspace;
    }
    public void prepare(double temperature,Workspace workspace) {
        SolverDiagnostics.count(SolverDiagnostics.temperatureTermsCalls);
        if(workspace.owner!=this)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid coefficient temperature");
        int count=components.size();
        // The spine variant: each function, which continues past its own data. The coefficient path: each fit at the
        // temperature held inside its range, the enthalpy continued with the held heat capacity past it.
        if(idealGas!=null)for(int i=0;i<count;i++) {
            workspace.heatCapacity[i]=idealGas[i].heatCapacity(temperature);
            workspace.enthalpy[i]=idealGas[i].enthalpy(temperature)-datumOffsets[i];
        }
        else for(int i=0;i<count;i++) {
            double fit=temperature<fitMinimum[i]?fitMinimum[i]:temperature>fitMaximum[i]?fitMaximum[i]:temperature;
            double delta=fit-REFERENCE_T,cp=0,h=0;
            for(int term=5;term>=0;term--){cp=cp*delta+heatCapacityCoefficients[i][term];h=h*delta+heatCapacityCoefficients[i][term]/(term+1);}
            workspace.heatCapacity[i]=cp;workspace.enthalpy[i]=h*delta;
            if(fit!=temperature)workspace.enthalpy[i]+=cp*(temperature-fit);
        }
        kernel.prepareTemperature(temperature,workspace.mixture);
        // sqrt(a_i) vanishes exactly where the PR alpha term does, which is where every d/dT below divides
        // by zero. The same state the dense form refused as a degenerate alpha derivative.
        double[] roots=workspace.mixture.attractionRootsView();
        for(int i=0;i<count;i++)if(roots[i]==0)throw new IllegalArgumentException("Degenerate PR alpha derivative");
        // The pure-component root derivatives depend on temperature alone, exactly like the dense form's
        // three matrices did; only the composition-weighted sums below are per evaluation.
        kernel.prepareRootDerivatives(temperature,workspace.mixture,workspace.rootDt,workspace.rootDt2);
        workspace.temperature=temperature;
    }

    /** {@link #evaluate} without the record and its two
     * array copies; the returned buffer is the workspace's own and is refilled by the next evaluation. */
    public Values evaluateValues(double temperature,double pressure,double[] amounts,PengRobinsonKernel.Root root,Workspace workspace) {
        requireState(temperature,pressure,amounts,workspace);
        kernel.evaluate(temperature,pressure,amounts,root,workspace.mixture,workspace.evaluation);
        fill(temperature,pressure,workspace,workspace.evaluation,workspace.values);
        return workspace.values;
    }
    public Phase evaluate(double temperature,double pressure,double[] amounts,PengRobinsonKernel.Root root,Workspace workspace) {
        requireState(temperature,pressure,amounts,workspace);
        kernel.evaluate(temperature,pressure,amounts,root,workspace.mixture,workspace.evaluation);
        fill(temperature,pressure,workspace,workspace.evaluation,workspace.values);
        return workspace.values.phase();
    }

    private void requireState(double temperature,double pressure,double[] amounts,Workspace workspace) {
        if (!Double.isFinite(temperature) || temperature <= 0 || !Double.isFinite(pressure) || pressure <= 0) {
            throw new IllegalArgumentException("Positive finite temperature and pressure required");
        }
        if(workspace.owner!=this||temperature!=workspace.temperature)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(amounts.length!=components.size())throw new IllegalArgumentException("Composition basis mismatch");
    }

    /** The translation, the caloric terms and the volumetric block, on the mixture the kernel just evaluated. */
    private void fill(double temperature,double pressure,Workspace workspace,
                      PengRobinsonKernel.Evaluation evaluation,Values out) {
        double[] x=workspace.mixture.compositionView(),attractionRows=workspace.mixture.attractionRowsView();
        double a=evaluation.aMix(),b=evaluation.bMix(),da=evaluation.daMixDt();
        double dda=kernel.mixtureSecondTemperatureDerivative(workspace.mixture,workspace.rootDt,workspace.rootDt2,workspace.crossDt);
        double shift = 0, idealH = 0, idealCp = 0;
        for (int i = 0; i < x.length; i++) {
            shift += x[i] * translations[i];
            idealCp+=x[i]*workspace.heatCapacity[i];idealH+=x[i]*workspace.enthalpy[i];
        }
        double rt=R*temperature;
        double z=evaluation.compressibility(),v=z*rt/pressure;
        double denominator = v*v + 2*b*v - b*b;
        double dpdv = -R*temperature / ((v-b)*(v-b)) + 2*a*(v+b) / (denominator*denominator);
        double dpdt = R/(v-b) - da/denominator;
        if (!(dpdv < 0)) throw new IllegalArgumentException("Mechanically unstable PR root");
        double dvdp = 1/dpdv, dvdt = -dpdt/dpdv;
        double dpdvv = 2*R*temperature/((v-b)*(v-b)*(v-b)) + 2*a/(denominator*denominator)
                - 8*a*(v+b)*(v+b)/(denominator*denominator*denominator);
        double dpdtv = -R/((v-b)*(v-b)) + 2*da*(v+b)/(denominator*denominator);
        double dvdtt = -(-dda/denominator + 2*dpdtv*dvdt + dpdvv*dvdt*dvdt)/dpdv;
        double physicalV = v + shift;
        double log = DeterministicMath.log((v+(1+SQRT_TWO)*b)/(v+(1-SQRT_TWO)*b));
        double h=idealH+evaluation.residualEnthalpyJoulesPerMol()+pressure*shift;
        double dlogdv = 1/(v+(1+SQRT_TWO)*b) - 1/(v+(1-SQRT_TWO)*b);
        double cp = idealCp + pressure*dvdt - R + temperature*dda/(2*SQRT_TWO*b)*log
                + (temperature*da-a)/(2*SQRT_TWO*b)*dlogdv*dvdt;
        double dhdp = physicalV - temperature*dvdt;
        double[] logPhi = out.logFugacityCoefficients;
        double[] partialVolumes = out.partialMolarVolumes;
        double[] mixtureLogPhi=evaluation.logFugacityCoefficientsView();
        for (int i=0;i<x.length;i++) {
            logPhi[i]=mixtureLogPhi[i]+pressure*translations[i]/rt;
            double db=coVolume(i)-b;
            double pressureCompositionDerivative=-2*(attractionRows[i]-a)/denominator
                    +db*(R*temperature/((v-b)*(v-b))+2*a*(v-b)/(denominator*denominator));
            partialVolumes[i]=v-pressureCompositionDerivative/dpdv+translations[i];
            if(!Double.isFinite(logPhi[i])||!Double.isFinite(partialVolumes[i]))throw new IllegalArgumentException("Nonfinite chemical-potential derivative");
        }
        if (!(physicalV > 0) || !(residualOnly || cp > 0) || !Double.isFinite(h) || !Double.isFinite(cp)
                || !Double.isFinite(dvdp) || !Double.isFinite(dvdt) || !Double.isFinite(dhdp)) {
            throw new IllegalArgumentException("Invalid translated phase properties");
        }
        out.molarVolume=physicalV;out.molarEnthalpy=h;out.molarInternalEnergy=h-pressure*physicalV;
        out.volumeTemperatureDerivative=dvdt;out.volumePressureDerivative=dvdp;out.heatCapacity=cp;
        out.enthalpyPressureDerivative=dhdp;out.isothermalCompressibility=-dvdp/physicalV;
        out.volumeSecondTemperatureDerivative=dvdtt;out.compressibility=z;out.idealGasHeatCapacity=idealCp;
        out.temperature=temperature;out.untranslatedVolume=v;out.attraction=a;out.coVolume=b;out.attractionTemperatureDerivative=da;
        out.physicalRootCount=evaluation.physicalRootCount();
    }

    private double coVolume(int component) { return kernel.coVolume(component); }

    /** The constant volume translation of a component, m3/mol ({@code v = v_PR + c}). */
    public double translation(int component) { return translations[component]; }

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

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite property coefficient");
    }

    /** The arrays belong to the record from construction on; {@link #evaluate} builds them for it. */
    public record Phase(double molarVolume, double molarEnthalpy, double molarInternalEnergy,
                        double volumeTemperatureDerivative, double volumePressureDerivative,
                        double heatCapacity, double enthalpyPressureDerivative,
                        double isothermalCompressibility, double[] logFugacityCoefficients,
                        double volumeSecondTemperatureDerivative,double[] partialMolarVolumes,double phaseIdentificationParameter) {
        @Override public double[] logFugacityCoefficients() { return logFugacityCoefficients.clone(); }
        @Override public double[] partialMolarVolumes() { return partialMolarVolumes.clone(); }
    }
}
