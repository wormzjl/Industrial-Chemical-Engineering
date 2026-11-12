package com.wormzjl.icheme.science.thermo.phase;

import java.util.Objects;

/**
 * First derivatives of one phase at fixed composition, with the {@link PhaseState} they were taken at (the same root).
 *
 * <p>Caller-owned and refilled per evaluation, like {@link PhaseState}. The fluid family fills every block; a pure
 * crystal fills all but the composition block, which a one-component phase has no use for (owner decision O5: no
 * capability gating, a block that was not filled reads as left).</p>
 *
 * <p>Conventions: {@code d ln phi_i / d n_j} at the evaluated composition normalised to one mole (the kernel's), at
 * constant T and P; {@code d ln phi_i / dT} at constant P and composition, {@code d ln phi_i / dP} at constant T and
 * composition; {@code dv/dT}, {@code dv/dP}, {@code dh/dT} (the whole {@code c_p}, ideal-gas part included) and
 * {@code dh/dP} of the molar quantities at constant composition.</p>
 */
public final class PhaseDerivatives {
    private final PhaseState state;
    private final double[][] logFugacityComposition;
    private final double[] logFugacityTemperature;
    private final double[] logFugacityPressure;
    private String family = "";
    private double volumeTemperature;
    private double volumePressure;
    private double enthalpyTemperature;
    private double enthalpyPressure;
    private double shareEnthalpyT, shareEnthalpyP, shareEntropyT, shareEntropyP, shareVolumeT, shareVolumeP;

    public PhaseDerivatives(int componentCount) {
        state = new PhaseState(componentCount);
        logFugacityComposition = new double[componentCount][componentCount];
        logFugacityTemperature = new double[componentCount];
        logFugacityPressure = new double[componentCount];
    }

    /** The phase these derivatives describe, evaluated on the same root. */
    public PhaseState state() { return state; }
    public int componentCount() { return logFugacityTemperature.length; }
    public String family() { return family; }

    public double logFugacityCompositionDerivative(int component, int respectTo) {
        return logFugacityComposition[component][respectTo];
    }
    public double[] logFugacityTemperatureDerivativeView() { return logFugacityTemperature; }
    public double[] logFugacityPressureDerivativeView() { return logFugacityPressure; }
    /** m3/(mol K). */
    public double volumeTemperatureDerivative() { return volumeTemperature; }
    /** m3/(mol Pa). */
    public double volumePressureDerivative() { return volumePressure; }
    /** {@code c_p}, J/(mol K), ideal-gas part included. */
    public double enthalpyTemperatureDerivative() { return enthalpyTemperature; }
    /** m3/mol ({@code J/(mol Pa)}). */
    public double enthalpyPressureDerivative() { return enthalpyPressure; }

    /** {@code d/dT} of water's share of the molar enthalpy (D2), at fixed P and composition; zero without one. */
    public double shareEnthalpyTemperatureDerivative() { return shareEnthalpyT; }
    public double shareEnthalpyPressureDerivative() { return shareEnthalpyP; }
    public double shareEntropyTemperatureDerivative() { return shareEntropyT; }
    public double shareEntropyPressureDerivative() { return shareEntropyP; }
    public double shareVolumeTemperatureDerivative() { return shareVolumeT; }
    public double shareVolumePressureDerivative() { return shareVolumeP; }
    /** The balance book's {@code c_p}: the cubic's plus the share's, J/(mol K). */
    public double balanceHeatCapacity() { return enthalpyTemperature + shareEnthalpyT; }
    /** The balance book's {@code dh/dP}. */
    public double balanceEnthalpyPressureDerivative() { return enthalpyPressure + shareEnthalpyP; }
    /** The balance book's {@code dv/dT}. */
    public double balanceVolumeTemperatureDerivative() { return volumeTemperature + shareVolumeT; }
    /** The balance book's {@code dv/dP}. */
    public double balanceVolumePressureDerivative() { return volumePressure + shareVolumeP; }

    // Filling, for an evaluator. Nothing here allocates.

    /** Names the family filling this, and clears the share's derivatives. */
    public void begin(String family) {
        this.family = Objects.requireNonNull(family, "family");
        shareEnthalpyT = shareEnthalpyP = shareEntropyT = shareEntropyP = shareVolumeT = shareVolumeP = 0.0;
    }
    /** The share's derivatives (D2), after {@link #begin}. */
    public void setShare(double enthalpyT, double enthalpyP, double entropyT, double entropyP, double volumeT, double volumeP) {
        shareEnthalpyT = enthalpyT;
        shareEnthalpyP = enthalpyP;
        shareEntropyT = entropyT;
        shareEntropyP = entropyP;
        shareVolumeT = volumeT;
        shareVolumeP = volumeP;
    }
    /** Writable row buffer for {@code d ln phi_i / d n_j}. */
    public double[] logFugacityCompositionRowBuffer(int component) { return logFugacityComposition[component]; }
    public double[] logFugacityTemperatureBuffer() { return logFugacityTemperature; }
    public double[] logFugacityPressureBuffer() { return logFugacityPressure; }
    public void setVolumetric(double volumeTemperatureDerivative, double volumePressureDerivative) {
        volumeTemperature = volumeTemperatureDerivative;
        volumePressure = volumePressureDerivative;
    }
    public void setCaloric(double enthalpyTemperatureDerivative, double enthalpyPressureDerivative) {
        enthalpyTemperature = enthalpyTemperatureDerivative;
        enthalpyPressure = enthalpyPressureDerivative;
    }
}
