package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.water.DiluteSteamViscosity;
import com.wormzjl.icheme.science.thermo.water.Iapws97Region1;

/**
 * IF97 Region 1 in SI units, with its original datum. Hybrid-reference offsets are applied by the caller.
 *
 * <p>The fluid network's liquid water at the state pressure, at any positive pressure: the stable liquid of the
 * region's range of validity, {@code ps(T) <= p <= 100 MPa} (IAPWS R7-97(2012), section 5.1), and its continuation
 * past it (owner rule of 2026-10-16, plan RANGE_EXTRAPOLATION_PLAN section 3). Below saturation it is the metastable
 * superheated liquid, which the release covers qualitatively ("In addition to the properties in the stable
 * single-phase liquid region, Eq. (7) also yields reasonable values in the metastable superheated-liquid region close
 * to the saturated liquid line", section 5.1); above 100 MPa the region's own sums. Outside the temperatures from the
 * water model's triple point to the region's 623.15 K boundary every property is held at the nearer end and the
 * volume and enthalpy continue with the held slopes {@code dv/dT} and {@code c_p}, so both are continuous with their
 * slopes: the region's own sums pass their check down to about 100 K but diverge below about 230 K (c_p 911 J/(mol K)
 * and 664 kg/m3 at 200 K). The data ranges are what a notice reports. A result that fails the sums' own check
 * ({@code v > 0}, {@code c_p > 0}, {@code dv/dp < 0}) stays a refusal, a rejected trial.
 *
 * <p>The table, the sums and the held continuation are the water module's ({@link Iapws97Region1#liquid}, F2 of batch
 * 2026-11-13-codebase-review); this class keeps the network's API and delegates.</p>
 */
public final class WaterRegion1 {
    /**
     * The declared bound of the metastable liquid's data: the release's "close to the saturated liquid line" is its only
     * statement of validity, and liquid water down to {@code ps(T) - 2 MPa} is what the retired reference-pressure path
     * covered (it evaluated Region 1 at 2 MPa for every state, so every envelope pressure wherever {@code ps(T) <= 2 MPa},
     * T up to 485.5 K). Below it the liquid continues on the region's own sums; a notice reports it.
     */
    public static final double METASTABLE_MARGIN_PASCAL = 2e6;

    private WaterRegion1() {}

    public static State evaluate(double temperatureKelvin,double pressurePascal) {
        return evaluate(MaterialRuntime.water(),temperatureKelvin,pressurePascal);
    }

    /** Liquid water at {@code (T, p)} as a record; {@code water} supplies the triple point the low end holds at. */
    public static State evaluate(MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal) {
        var workspace=new Workspace();
        evaluateLiquid(water,temperatureKelvin,pressurePascal,workspace);
        return workspace.state();
    }
    /** IF97 Region 1's own upper temperature boundary: a boundary of the formulation, where its properties are held. */
    public static final double REGION_1_MAXIMUM_TEMPERATURE=Iapws97Region1.MAXIMUM_TEMPERATURE,REGION_1_MAXIMUM_PRESSURE=Iapws97Region1.MAXIMUM_PRESSURE;

    /**
     * Liquid water at the state pressure, into {@code workspace}, without allocating: Region 1 at the temperature held
     * inside {@code [triple point, 623.15 K]}, continued past it (class comment). A non-finite or non-positive state
     * is refused.
     */
    public static void evaluateLiquid(MaterialCatalog.Water water,double temperatureKelvin,double pressurePascal,Workspace workspace) {
        Iapws97Region1.liquid(water.triplePoint(),temperatureKelvin,pressurePascal,workspace.liquid);
    }

    /**
     * Caller-owned Region 1 scratch and result: the module's power tables and the last evaluation's properties. Mutable,
     * so - like the Peng-Robinson workspace a node's prepared temperature holds - it belongs to one solving thread.
     */
    public static final class Workspace {
        private final Iapws97Region1.Liquid liquid=new Iapws97Region1.Liquid();
        public Workspace() {}
        public double specificVolume(){return liquid.specificVolume();}
        public double specificEnthalpy(){return liquid.specificEnthalpy();}
        /** c_p, J/(kg K): the region's at the evaluated temperature, the held end value past its range. */
        public double specificHeatCapacity(){return liquid.specificHeatCapacity();}
        public double volumePressureDerivative(){return liquid.volumePressureDerivative();}
        /** A copy of the last evaluation as the immutable record. */
        public State state() {
            return new State(liquid.specificVolume(),liquid.specificEnthalpy(),liquid.specificInternalEnergy(),liquid.specificHeatCapacity(),
                    liquid.volumeTemperatureDerivative(),liquid.volumePressureDerivative(),liquid.enthalpyPressureDerivative(),
                    liquid.volumeSecondTemperatureDerivative());
        }
    }

    /** The dilute-gas term's data floor, K ({@link DiluteSteamViscosity#LOW}). */
    public static final double VISCOSITY_LOW = DiluteSteamViscosity.LOW;

    /** IAPWS 2008 dilute-gas viscosity term (Pa.s): {@link DiluteSteamViscosity#viscosity}. */
    public static double diluteVaporViscosity(double temperatureKelvin) {
        return DiluteSteamViscosity.viscosity(temperatureKelvin);
    }

    public record State(double specificVolume,double specificEnthalpy,double specificInternalEnergy,
                        double specificHeatCapacity,double volumeTemperatureDerivative,
                        double volumePressureDerivative,double enthalpyPressureDerivative,
                        double volumeSecondTemperatureDerivative) {}
}
