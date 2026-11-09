package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * PROBE (P1, batch 2026-11-04-open-thermo-data; scratch only, not a gate): the free-water UV round trip at 460 K and
 * 0.5 MPa of FluidTpEquilibriumPhUvTest, both water ratios, replaying the test's workspace history.
 */
class PhuvRoundTripProbe {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    @Test
    void probe() {
        var service = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(FluidTestSupport.networkModel()));
        var contract = service.contract();
        var workspace = service.newWorkspace();
        List<String> species = contract.components();
        int water = contract.waterIndex();
        StringBuilder out = new StringBuilder();
        for (double ratio : new double[] {0.01, 1.0}) {
            for (double t : new double[] {340.0, 460.0, 580.0}) {
                for (double p : new double[] {0.5e6, 1.4e6}) {
                    double[] amounts = PhaseTestSupport.crudeWithNitrogen(contract);
                    amounts[water] = ratio * PhaseTestSupport.sum(amounts);
                    var tp0 = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
                    if (tp0.coverage().grade() == EquilibriumResult.CoverageGrade.EXTRAPOLATED) continue;
                    boolean focus = t == 460.0 && p == 0.5e6;
                    // The test's sequence: TP, PH, UV on the shared workspace, then four stiffness TP calls.
                    var tp = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
                    double u = tp.internalEnergy(), v = tp.volume(), n = tp.totalAmount();
                    service.ph(EquilibriumRequest.ph(p, tp.enthalpy(), species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
                    var uv = service.uv(EquilibriumRequest.uv(u, v, species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
                    double kTest = stiffness(service, workspace, species, amounts, t, p, 1.0e-4);
                    double dT = uv.phases().get(0).temperature() / t - 1.0;
                    double dP = uv.phases().get(0).pressure() / p - 1.0;
                    String line = f("ratio %s %s K %s Pa: UV %s calls %d kernel %d, dT/T %.3e dP/P %.3e, test stiffness %.3f, allowed %.3e, %s",
                            ratio, t, p, uv.status(), uv.diagnostics().outerIterations(), uv.diagnostics().kernelEvaluations(), dT, dP, kTest, kTest * 1.0e-9,
                            Math.abs(dP) <= kTest * 1.0e-9 ? "pass" : "FAIL");
                    if (!focus) { out.append(line).append('\n'); continue; }
                    out.append("== ").append(line).append('\n');
                    out.append("  TP: ").append(tp.classification()).append(f(", U %.10e J, V %.10e m3, N %.6f", u, v, n)).append('\n');
                    for (PhaseAmounts ph : tp.phases()) {
                        out.append(f("    phase %s: %.6f mol (%.4f of N), water %.6f%n", ph.kind(), ph.total(), ph.total() / n, ph.amount(water)));
                    }
                    out.append("    freeWater ").append(tp.freeWater()).append('\n');
                    out.append(f("  UV answer: %s, phases %d, specRes %.3e, outer %d%n", uv.classification(), uv.phases().size(),
                            uv.diagnostics().specificationResidual(), uv.diagnostics().outerIterations()));
                    // Independent derivatives of the TP answers on a fresh workspace (central, two step sizes).
                    var fresh = service.newWorkspace();
                    for (double h : new double[] {1.0e-4, 1.0e-6}) {
                        double[] j = jacobian(service, fresh, species, amounts, t, p, u, v, n, h);
                        double kIso = -j[2] / j[3];
                        double det = j[0] * j[3] - j[1] * j[2];
                        // The answer's error from its residuals: x - x* = J^-1 F at the UV answer.
                        double tu = uv.phases().get(0).temperature(), pu = uv.phases().get(0).pressure();
                        var at = service.tp(EquilibriumRequest.tp(tu, pu, species, amounts, PhaseCompetition.FLUID_ONLY), fresh);
                        double f0 = (at.internalEnergy() - u) / (n * R * tu), f1 = Math.log(at.volume() / v);
                        double e0 = (j[3] * f0 - j[1] * f1) / det, e1 = (j[0] * f1 - j[2] * f0) / det;
                        out.append(f("  J(h=%.0e) [dF0/dlnT %.6e dF0/dlnP %.6e; dF1/dlnT %.6e dF1/dlnP %.6e], isochoric k %.4f, "
                                        + "F at UV answer (fresh TP) %.3e %.3e -> J^-1F %.3e %.3e%n",
                                h, j[0], j[1], j[2], j[3], kIso, f0, f1, e0, e1));
                    }
                    // Noise floor: the same (t, p) on the shared and a fresh workspace, and nearby points.
                    service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
                    service.tp(EquilibriumRequest.tp(uv.phases().get(0).temperature(), uv.phases().get(0).pressure(), species, amounts, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
                    var again = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), fresh);
                    var other = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
                    out.append(f("  TP repeat noise: same-fresh-after-J dU/NRT %.3e dlnV %.3e; fresh dU/NRT %.3e dlnV %.3e%n",
                            (again.internalEnergy() - u) / (n * R * t), Math.log(again.volume() / v),
                            (other.internalEnergy() - u) / (n * R * t), Math.log(other.volume() / v)));
                    for (double e : new double[] {1.0e-11, 1.0e-10, 1.0e-9, 1.0e-8}) {
                        var a = service.tp(EquilibriumRequest.tp(t, p * Math.exp(e), species, amounts, PhaseCompetition.FLUID_ONLY), fresh);
                        var b = service.tp(EquilibriumRequest.tp(t * Math.exp(e), p, species, amounts, PhaseCompetition.FLUID_ONLY), fresh);
                        out.append(f("  lnP+%.0e: F %.3e %.3e;  lnT+%.0e: F %.3e %.3e%n", e, (a.internalEnergy() - u) / (n * R * t),
                                Math.log(a.volume() / v), e, (b.internalEnergy() - u) / (n * R * t * Math.exp(e)), Math.log(b.volume() / v)));
                    }
                }
            }
        }
        // P2: one TP each on a fresh workspace, trace water to as much as crude: kernel evaluations and the closure residual.
        for (double t : new double[] {460.0, 580.0}) {
            for (double p : new double[] {0.5e6, 1.4e6}) {
                for (double ratio : new double[] {1.0e-5, 1.0e-4, 1.0e-3, 1.0e-2, 0.1, 0.3, 1.0}) {
                    double[] amounts = PhaseTestSupport.crudeWithNitrogen(contract);
                    amounts[water] = ratio * PhaseTestSupport.sum(amounts);
                    var tp = service.tp(EquilibriumRequest.tp(t, p, species, amounts, PhaseCompetition.FLUID_ONLY), service.newWorkspace());
                    var fw = tp.freeWater();
                    out.append(f("TRACE ratio %.0e %s K %s Pa: kernel %d, liquid water %s, r/P %.3e%n", ratio, t, p,
                            tp.diagnostics().kernelEvaluations(), fw == null ? "-" : fw.liquidWater(),
                            fw == null ? Double.NaN : (fw.hydrocarbonPressure() + fw.waterPartialPressure() - p) / p));
                }
            }
        }
        System.out.println("PHUV PROBE\n" + out);
    }

    private static double[] jacobian(FluidTpEquilibrium service, FluidTpEquilibrium.Workspace ws, List<String> species, double[] amounts,
                                     double t, double p, double u, double v, double n, double h) {
        double[] r = new double[4];
        double[][] s = {{t * Math.exp(h), p}, {t * Math.exp(-h), p}, {t, p * Math.exp(h)}, {t, p * Math.exp(-h)}};
        double[] f0 = new double[4], f1 = new double[4];
        for (int k = 0; k < 4; k++) {
            var x = service.tp(EquilibriumRequest.tp(s[k][0], s[k][1], species, amounts, PhaseCompetition.FLUID_ONLY), ws);
            f0[k] = (x.internalEnergy() - u) / (n * R * s[k][0]);
            f1[k] = Math.log(x.volume() / v);
        }
        r[0] = (f0[0] - f0[1]) / (2 * h);
        r[2] = (f1[0] - f1[1]) / (2 * h);
        r[1] = (f0[2] - f0[3]) / (2 * h);
        r[3] = (f1[2] - f1[3]) / (2 * h);
        return r;
    }

    private static double stiffness(FluidTpEquilibrium service, FluidTpEquilibrium.Workspace workspace, List<String> species,
                                    double[] amounts, double t, double p, double rel) {
        double dt = rel * t, dp = rel * p;
        double[] v = new double[4];
        double[][] states = {{t + dt, p}, {t - dt, p}, {t, p + dp}, {t, p - dp}};
        for (int k = 0; k < 4; k++) {
            var r = service.tp(EquilibriumRequest.tp(states[k][0], states[k][1], species, amounts, PhaseCompetition.FLUID_ONLY), workspace);
            if (!r.converged()) return 1.0;
            v[k] = r.volume();
        }
        double stiffness = -t / p * ((v[0] - v[1]) / (2.0 * dt)) / ((v[2] - v[3]) / (2.0 * dp));
        return Double.isFinite(stiffness) ? Math.max(1.0, stiffness) : 1.0;
    }
}
