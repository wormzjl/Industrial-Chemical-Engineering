package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.PhaseDerivatives;
import com.wormzjl.icheme.science.thermo.phase.ThermoIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P3 WP5 gate, ported at M2 of the unified thermo port: a package without a reference spine keeps its coefficient path
 * bit for bit. The spine path (ideal gas from the spine records, E-PPR78 pairs, Tr = 0.8 anchors, the thermodynamic
 * identity as the revision) is taken only for a package that declares a {@code spine}; the bundled network package does
 * not.
 *
 * <p>The digest hashes the raw bits of every number the network reads from its hydrocarbon model on a fixed probe (the
 * {@link HydrocarbonModel#phase} record on both roots, the translated equation of state's values, the derivatives of the
 * engine's evaluator of the package, the free-water {@link FluidThermodynamics#flashTP} state with its acoustic velocity
 * bound, the pump reference density), and the text of every refusal the probe meets. The value is JDK-specific and is
 * pinned on the toolchain's JDK 21.0.11; a change of the legacy formulation on purpose re-pins it with its reason.</p>
 */
class LegacyNetworkPathPinTest {
    static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    /**
     * SHA-256 of {@link #digest}, pinned at M2 (2026-10-16) by this test on the toolchain's JDK 21.0.11 under Gradle. The
     * branch's last value ({@code 778b87e5ff0613589a0010d39890640bebdafad2cacc9c2799887eec582d9fc6}, P3 WP7c) does not
     * carry over, by design: main's {@code DeterministicMath} replaces {@code java.lang.Math} (last-bit differences), the
     * derivative part is the engine evaluator's {@link PhaseDerivatives} instead of the branch's translated derivative
     * object (OD-P2: not on main), main's phase record carries no liquid-root-absent flag, and the engine's flash solves
     * Rachford-Rice in the minor phase's fraction (D24).
     *
     * <p>Re-pinned for the owner rule of 2026-10-16 (every data range continued; M2's value
     * {@code dc9a89199a46e4f1662925d27e18ffd1bd037acb2757e455c92950bd17d99e02}). Point by point, every probe state inside
     * the data ranges keeps its bits (293.15, 350, 450 and 700 K); what moved is what the old rule refused: below the
     * fits' 293.15 K lower ends (77, 110, 150 K) the phases are answered with each ideal-gas heat capacity held at its
     * end, where the translated equation of state's values had run the polynomial past it; a wet flash below the water
     * model's triple point is the ice hold instead of a range refusal; and the 450 K, 2 MPa wet mixture past the steam's
     * qualified partial pressure is carried.</p>
     *
     * <p>Re-pinned for the free water's ice (WP4 of the range-extrapolation plan; the value before,
     * {@code 1e75c5c55a42a8d064eadee7a59d9130b49033886b3945e589bc83dffa27ee8f}). Point by point
     * ({@code tools/unified-thermo-port-extrapolation/probes/LegacyPinPointProbe.java} at both trees) only the 27 wet
     * flashes at 77, 110 and 150 K moved, each the ice hold before: 26 are answered, the water ice beside the
     * hydrocarbons, and the twelve-component mixture at 110 K and 1 bar is the three-phase hold its hydrocarbons meet dry.</p>
     *
     * <p>Re-pinned for owner decision D37, simplification S4 (nitrogen's low-temperature heat-capacity segment deleted,
     * its fit held below its 273.16 K lower end; the value before, {@code 6e9fbe83094f3a9fa9aeee3460f89f22b0e74931e035e3adca39c9f196e0b7e4}).
     * Point by point (the same probe at both trees, {@code tools/unified-thermo-port-m5/simplifications/probe}) every
     * point at 293.15 K and above keeps its bits; the 188 points at 77, 110 and 150 K moved, each through nitrogen's
     * ideal-gas part alone (pure nitrogen at 77 K: h -0.843 J/mol, 1.3e-4 of h; no volume or phase amount moved).</p>
     *
     * <p>Re-pinned for the mod rename to IChemE (the value before, {@code 98a1af1147a2fc7e952a83a62d274ad68a1c875ce7c3c7bd80ce757b904a8d78}):
     * the refusals hash their exception class and message, which name the package {@code com.wormzjl.icheme} and the
     * {@code icheme:} ids. With those names mapped back to {@code createcheme} the digest is the value before, bit for bit.</p>
     *
     * <p>Re-pinned for the second-virial steam ({@code separate-free-water-v3}, 2026-10-28; the value before,
     * {@code 37c1b7020d789e0d44041f8cc0a9b24ac8c694c63ef59e36214f08ccdccab1ee}): the wet flashes whose state holds steam
     * move, their gas volume, partial pressures, enthalpy and acoustic bound now those of {@code R T / p + B} steam; every
     * changed line runs only for a state or a phase holding water, so the dry probe states run no changed code.</p>
     */
    static final String DIGEST = "02fec125e6b5a3502bf79a257554be4b3588352edd914c4bb66b59c0895902b0";
    /**
     * The revision string: the formulation tag {@code direct-liquid-v1:tp-engine-v1:range-continuation-v1} (the phases
     * decided by the equilibrium engine, every data range continued since the owner rule of 2026-10-16) and the fluid
     * thermodynamic fingerprint of the widened {@code fluid_domain} (nitrogen, methane and the envelope to 10 MPa, P3 WP7
     * data) replace main 0.8.0's: a fresh world. Since WP4 it carries the free water's formulation and revision
     * ({@code separate-free-water-v2:water-iapws-shomate-r2}: its ice; v3 since 2026-10-28, the virial steam), as a spine package's identity does, and the
     * fingerprint hashes the ice's science. Since D37 S4 the {@code cp-segments-v1} tag is gone with the segments, and the
     * fingerprint is the nitrogen record r3's. Since 2026-10-26 nothing is hashed and each record declares its own
     * revision (owner decisions): the fingerprint (last {@code 7b222959...}) is every record's declared revision,
     * {@link MaterialCatalog#fluidRevision}: the column's identity, then each viscosity by its own revision, the ice and
     * the fluid reference records. The nitrogen record's r3 is visible in it.
     */
    static final String REVISION = String.join(";",
            "icheme:tjl20_methane_nitrogen@crude-regrouped-r1",
            "properties=" + String.join(",",
                    "icheme:tjl20_methane@tjl20-methane-nist-r1",
                    "icheme:tjl19_ethane@tjl19-dwsim-10.2.3-r1",
                    "icheme:tjl19_propane@tjl19-dwsim-10.2.3-r1",
                    "icheme:tjl19_isobutane@tjl19-dwsim-10.2.3-r1",
                    "icheme:tjl19_n_butane@tjl19-dwsim-10.2.3-r1",
                    "icheme:tjl19_isopentane@tjl19-dwsim-10.2.3-r1",
                    "icheme:tjl19_n_pentane@tjl19-dwsim-10.2.3-r1",
                    "icheme:crude_pc01@crude-regrouped-r1",
                    "icheme:crude_pc02@crude-regrouped-r1",
                    "icheme:crude_pc03@crude-regrouped-r1",
                    "icheme:crude_pc04@crude-regrouped-r1",
                    "icheme:crude_pc05@crude-regrouped-r1",
                    "icheme:crude_pc06@crude-regrouped-r1",
                    "icheme:crude_pc07@crude-regrouped-r1",
                    "icheme:crude_pc08@crude-regrouped-r1",
                    "icheme:crude_pc09@crude-regrouped-r1",
                    "icheme:crude_pc10@crude-regrouped-r1",
                    "icheme:crude_pc11@crude-regrouped-r1",
                    "icheme:crude_pc12@crude-regrouped-r1",
                    "icheme:fluid_nitrogen@fluid-nitrogen-nist-r3"),
            "interactions=icheme:tjl20@tjl20-frozen-kij-r1",
            "water=icheme:water@water-iapws-shomate-r2",
            "viscosity=" + String.join(",",
                    viscosities("icheme:tjl20_methane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:tjl19_ethane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:tjl19_propane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:tjl19_isobutane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:tjl19_n_butane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:tjl19_isopentane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:tjl19_n_pentane", "dwsim-viscosity-api-r1", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc01", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc02", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc03", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc04", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc05", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc06", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc07", "crude-regrouped-r1-dalia", "dwsim-viscosity-api-ambient-r2"),
                    viscosities("icheme:crude_pc08", "crude-regrouped-r1-dalia", "crude-regrouped-r1-vapor"),
                    viscosities("icheme:crude_pc09", "crude-regrouped-r1-dalia", "crude-regrouped-r1-vapor"),
                    viscosities("icheme:crude_pc10", "crude-regrouped-r1-dalia", "crude-regrouped-r1-vapor"),
                    viscosities("icheme:crude_pc11", "crude-regrouped-r1-dalia", "crude-regrouped-r1-vapor"),
                    viscosities("icheme:crude_pc12", "crude-regrouped-r1-dalia", "crude-regrouped-r1-vapor"),
                    viscosities("icheme:fluid_nitrogen", "nitrogen-nist-viscosity-r2", "nitrogen-nist-viscosity-r2"),
                    "icheme:water/liquid@iapws-sr6-2011-viscosity-r1"),
            "ice=icheme:crystal_ice_ih@crystal-ice-ih-r1",
            "fluid-data=conditional_solute_log_tables@dwsim-conditional-solute-ambient-v2,liquid_volume_reference_points@nist-liquid-calibration-20260915")
            + ":direct-liquid-v1:tp-engine-v1:range-continuation-v1"
            + ":separate-free-water-v3:water-iapws-shomate-r2:fluid-domain-data-v1:catalog-volume-reference-v1";

    /** A property record's two viscosities in the fluid identity: {@code id/liquid@revision,id/vapor@revision}. */
    private static String viscosities(String id, String liquid, String vapor) {
        return id + "/liquid@" + liquid + "," + id + "/vapor@" + vapor;
    }

    @Test
    void theBundledNetworkPackageKeepsItsCoefficientPathBitForBit() {
        var catalog = MaterialCatalog.bundled();
        var model = FluidTestSupport.model(catalog, NETWORK);
        String revision = model.hydrocarbon.revision();
        System.out.println("legacy network revision: " + revision);
        String digest = digest(catalog, model);
        System.out.println("legacy network digest: " + digest);
        // The revision keeps WP4's layout: the package data's declared revisions and the formulation tags, with the
        // free water's formulation and revision since the free water's ice (WP4 of the range-extrapolation plan).
        assertEquals(catalog.fluidRevision(NETWORK) + ":" + HydrocarbonModel.FORMULATION + ":" + ThermoIdentity.SEPARATE_FREE_WATER_FORMULATION
                + ":" + catalog.requirePackage(NETWORK).water().revision() + ":fluid-domain-data-v1:catalog-volume-reference-v1", revision);
        assertEquals(REVISION, revision);
        assertEquals(DIGEST, digest);
        assertTrue(catalog.requirePackage(NETWORK).spine().isEmpty(), "the bundled network package declares no spine");
    }

    /**
     * The engine's evaluator of a package without a spine, as {@link NetworkPhaseEngine} builds it: the package's PR78
     * constants and constant interactions, the network model's own translations, and the {@code shifted_polynomial_5}
     * fits on the network's datum over the translated model's fit ranges.
     */
    static CubicPhaseEvaluator evaluator(MaterialCatalog catalog, FluidThermodynamics model) {
        var propertyPackage = catalog.requirePackage(NETWORK);
        var translated = model.hydrocarbon.translated();
        int n = propertyPackage.components().size();
        double[] translations = new double[n];
        List<IdealGasFunction> gas = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            translations[i] = translated.translation(i);
            gas.add(new NetworkPhaseEngine.CoefficientIdealGas(propertyPackage.properties().get(i),
                    translated.idealGasMinimumTemperature(i), translated.idealGasMaximumTemperature(i)));
        }
        return CubicPhaseEvaluator.fromPackage(propertyPackage, propertyPackage.components(), translations, gas);
    }

    /** Every number of the probe, in a fixed order, as SHA-256 hex. */
    static String digest(MaterialCatalog catalog, FluidThermodynamics model) {
        var sink = new Sink();
        var hydrocarbon = model.hydrocarbon;
        var eos = hydrocarbon.translated();
        var evaluator = evaluator(catalog, model);
        var workspace = evaluator.newWorkspace();
        int n = hydrocarbon.componentCount();
        var derivatives = new PhaseDerivatives(n);
        int nitrogen = hydrocarbon.components().indexOf("Nitrogen");
        double[] pure = new double[n];
        pure[nitrogen] = 1;
        double[] mixture = new double[n];
        for (int i = 0; i < n; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
        double[] lean = new double[n];
        lean[nitrogen] = 0.9;
        lean[(nitrogen + 1) % n] = 0.1;
        sink.number(model.pumpReferenceDensity());
        double[] temperatures = {77.0, 110.0, 150.0, 293.15, 350.0, 450.0, 700.0};
        double[] pressures = {1.0e5, 5.0e5, 2.0e6};
        double[][] compositions = {pure, lean, mixture};
        for (double t : temperatures) {
            for (double p : pressures) {
                for (double[] x : compositions) {
                    for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                        try {
                            var phase = hydrocarbon.phase(t, p, x, root);
                            sink.number(phase.molarVolume()).number(phase.molarEnthalpy()).number(phase.molarInternalEnergy())
                                    .number(phase.volumePressureDerivative()).numbers(phase.logFugacityView())
                                    .flag(phase.vaporLike());
                        } catch (RuntimeException refused) {
                            sink.refusal(refused);
                        }
                        try {
                            var v = eos.evaluateValues(t, p, x, root, eos.prepare(t));
                            sink.number(v.molarVolume()).number(v.molarEnthalpy()).number(v.molarInternalEnergy())
                                    .number(v.volumeTemperatureDerivative()).number(v.volumePressureDerivative())
                                    .number(v.heatCapacity()).number(v.enthalpyPressureDerivative())
                                    .number(v.isothermalCompressibility()).number(v.volumeSecondTemperatureDerivative())
                                    .number(v.compressibility()).number(v.idealGasHeatCapacity())
                                    .numbers(v.logFugacityCoefficientsView()).numbers(v.partialMolarVolumesView())
                                    .number(v.phaseIdentificationParameter()).number(v.physicalRootCount());
                        } catch (RuntimeException refused) {
                            sink.refusal(refused);
                        }
                        try {
                            evaluator.derivatives(t, p, x, root, workspace, derivatives);
                            var state = derivatives.state();
                            sink.number(state.molarVolume()).number(state.idealGasEnthalpy()).number(state.residualEnthalpy())
                                    .number(state.idealGasEntropy()).number(state.residualEntropy())
                                    .number(state.molarGibbsEnergy()).numbers(state.logFugacityCoefficientsView())
                                    .number(state.physicalRootCount()).number(state.root().ordinal());
                            for (int i = 0; i < n; i++) {
                                for (int j = 0; j < n; j++) sink.number(derivatives.logFugacityCompositionDerivative(i, j));
                            }
                            sink.numbers(derivatives.logFugacityTemperatureDerivativeView())
                                    .numbers(derivatives.logFugacityPressureDerivativeView())
                                    .number(derivatives.volumeTemperatureDerivative()).number(derivatives.volumePressureDerivative())
                                    .number(derivatives.enthalpyTemperatureDerivative())
                                    .number(derivatives.enthalpyPressureDerivative());
                        } catch (RuntimeException refused) {
                            sink.refusal(refused);
                        }
                    }
                    try {
                        double[] overall = new double[n + 1];
                        System.arraycopy(x, 0, overall, 0, n);
                        overall[n] = 0.1;
                        var state = model.flashTP(t, p, overall, () -> { });
                        sink.number(state.volume()).number(state.enthalpy()).number(state.internalEnergy()).number(state.mass())
                                .numbers(state.liquidView()).numbers(state.vaporView()).number(state.waterLiquid())
                                .number(state.waterVapor()).number(state.hydrocarbonPartialPressure())
                                .number(state.waterPartialPressure()).number(state.liquidVolume()).number(state.waterVolume())
                                .number(state.vaporVolume()).number(state.waterCompressibility()).number(model.velocityLimit(state));
                    } catch (RuntimeException refused) {
                        sink.refusal(refused);
                    }
                }
            }
        }
        return sink.hex();
    }

    private static final class Sink {
        private final MessageDigest digest;
        private final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);

        Sink() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }

        Sink number(double value) {
            buffer.clear();
            buffer.putLong(Double.doubleToRawLongBits(value));
            digest.update(buffer.array());
            return this;
        }

        Sink numbers(double[] values) {
            number(values.length);
            for (double value : values) number(value);
            return this;
        }

        Sink flag(boolean value) { return number(value ? 1 : 0); }

        void refusal(RuntimeException refused) {
            digest.update((refused.getClass().getName() + ": " + refused.getMessage()).getBytes(StandardCharsets.UTF_8));
        }

        String hex() { return HexFormat.of().formatHex(digest.digest()); }
    }
}
