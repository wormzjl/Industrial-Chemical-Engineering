import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3SolveStrategy;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The design's sample flowsheet (batch 2026-11-02-process-simulator) solved by hand on the shipped science: the owner's example
 * of 2026-11-02. Crude from 25 C through a pump, the tube sides of the column block's three pumparound coolers, the residue
 * exchanger, a fired heater that holds its outlet and a valve into the default crude column; the residue back through the
 * exchanger and an air cooler; cooling water through the condenser's tube side. The pumparound duties are the door's, so the
 * crude's pass through the block is no loop; one loop stays, torn at the column's bottoms. It prints what the design sheets
 * show. Run as {@code ClientRunProbe}.
 */
public final class SampleFlowsheetProbe {
    static FluidThermodynamics thermo;
    static MaterialCatalog catalog;
    static String network;
    static List<String> axis;
    static final double[] DENSITY = new double[32];
    static final double[] WEIGHT = new double[32];
    static final LinkedHashMap<String, FluidThermodynamics.State> STREAMS = new LinkedHashMap<>();

    static FluidThermodynamics.State tp(double t, double p, double[] n) {
        return MaterialRuntime.with(catalog, network, () -> thermo.flashTP(t, p, n, () -> {}));
    }

    static FluidThermodynamics.State ph(double guess, double p, double h, double[] n) {
        var s = MaterialRuntime.with(catalog, network, () -> thermo.equilibriumPH(guess, p, h, n, () -> {}));
        if (s == null) throw new IllegalStateException("PH flash failed at " + p);
        return s;
    }

    static double[] amounts(FluidThermodynamics.State s) {
        int n = s.componentCount();
        double[] a = new double[n];
        for (int i = 0; i + 1 < n; i++) a[i] = s.liquidView()[i] + s.vaporView()[i];
        a[n - 1] = s.waterLiquid() + s.waterVapor();
        return a;
    }

    /** A product of the column as a stream: its published T, P and composition on the network axis. */
    static FluidThermodynamics.State product(V3ColumnResult r, String name) {
        var s = r.streams().stream().filter(x -> x.displayName().startsWith(name)).findFirst().orElseThrow();
        double[] n = new double[axis.size()];
        for (var c : s.moleFractions()) n[axis.indexOf(c.componentId())] += c.moleFraction() * s.molarFlowMolPerSecond();
        return tp(s.temperatureKelvin(), s.pressurePascal(), n);
    }

    public static void main(String[] args) {
        long start = System.nanoTime();
        catalog = MaterialCatalog.bundled();
        var base = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        network = catalog.presets().network().packageId();
        thermo = MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
        axis = thermo.components();
        var options = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);
        var model = V3NeuralRegistry.bundled().bind(catalog, base);
        double screen = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        var pack = catalog.requirePackage(network);
        double[] overall = new double[axis.size()];
        var ids = base.componentBasis().componentIds();
        for (int i = 0; i < ids.size(); i++) overall[axis.indexOf(ids.get(i))] = base.feedComponentMolarFlowsMolPerSecond()[i];

        var crude = tp(298.15, 8e5, overall);
        double pumped = 14e5, rho = crude.mass() / crude.volume(), work = crude.mass() * (pumped - 8e5) / rho;
        var s1 = ph(298.5, pumped, crude.enthalpy() + work, overall);
        double[] steamIn = new double[axis.size()];
        steamIn[axis.indexOf("Water")] = base.steamFeeds().getFirst().molarFlowMolPerSecond();
        var steam = tp(base.steamFeeds().getFirst().temperatureKelvin(), 3.5e5, steamIn);
        // The pumparound coolers' tube sides: each door duty into the crude, 0.3 bar a side. No loop: the column does not read them.
        double[] pa = base.pumparounds().stream().mapToDouble(p -> -p.dutyWatts()).toArray();
        var s2 = ph(s1.temperature() + 40, s1.pressure() - 0.3e5, s1.enthalpy() + pa[0], overall);
        var s3 = ph(s2.temperature() + 50, s2.pressure() - 0.3e5, s2.enthalpy() + pa[1], overall);
        var s4 = ph(s3.temperature() + 30, s3.pressure() - 0.3e5, s3.enthalpy() + pa[2], overall);

        FluidThermodynamics.State tear = null, s5 = null, s6 = null, s7 = null, s10 = null;
        V3ColumnResult column = null;
        V3NeuralSeed warm = null;
        double q101 = 0, qHeater = 0;
        int passes = 0;
        long[] passTime = new long[32];
        for (int pass = 1; pass <= 30; pass++) {
            long t0 = System.nanoTime();
            passes = pass;
            // E-101: the shell side's outlet set at 200 C, the duty into the tube side; no heat on the first pass, the tear not known yet.
            q101 = 0;
            if (tear != null) {
                var hot = amounts(tear);
                s10 = tp(473.15, tear.pressure() - 0.3e5, hot);
                q101 = tear.enthalpy() - s10.enthalpy();
            }
            s5 = ph(s4.temperature() + q101 / 3e5, s4.pressure() - 0.5e5, s4.enthalpy() + q101, overall);
            // H-101: the coil's outlet set at 365 C, 5 bar.
            s6 = tp(638.15, 5e5, overall);
            qHeater = s6.enthalpy() - s5.enthalpy();
            // V-101, isenthalpic to the feed tray's pressure.
            s7 = ph(s6.temperature() - 6, 2.72e5, s6.enthalpy(), overall);
            double feedT = s7.temperature();
            var in = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), feedT, base.stageCount(),
                    base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), base.sideDraws(), base.steamFeeds(), base.pumparounds(), base.columnDiameterMetres());
            final var seed = warm;
            V3ColumnOutcome outcome = MaterialRuntime.with(catalog, base.packageId(), () -> seed == null ? V3ColumnCalculator.calculate(in, () -> {}, 0, 0, options, model, screen)
                    : V3ColumnCalculator.calculateWithStrategy(in, () -> {}, V3SolveStrategy.AGGRESSIVE, seed, screen));
            if (!(outcome instanceof V3ColumnOutcome.Success success)) throw new IllegalStateException(((V3ColumnOutcome.Failure) outcome).summary());
            column = success.result();
            warm = column.warmSeed().orElse(warm);
            var next = product(column, "Bottoms");
            double change = tear == null ? 1 : Math.abs(next.enthalpy() - tear.enthalpy()) / Math.abs(next.enthalpy());
            passTime[pass] = System.nanoTime() - t0;
            System.out.printf(Locale.ROOT, "pass %d: E-101 %.2f MW, H-101 %.2f MW, feed %.2f C, bottoms %.2f C, tear change %.2e, %.0f ms%n", pass, q101 / 1e6, qHeater / 1e6, feedT - 273.15,
                    next.temperature() - 273.15, change, passTime[pass] / 1e6);
            boolean done = tear != null && change < 1e-4;
            tear = next;
            if (done) break;
        }
        var s8 = product(column, "Side draw (tray 19)");
        var dieselOut = ph(s8.temperature(), 6e5, s8.enthalpy() + s8.mass() * (6e5 - s8.pressure()) / (s8.mass() / s8.volume()), amounts(s8));
        var residue = tp(343.15, s10.pressure() - 0.2e5, amounts(s10));
        double qE102 = residue.enthalpy() - s10.enthalpy();
        // The condenser's tube side: 190000 kmol/h of cooling water takes the column's condenser duty.
        double condenser = column.dutyLedger().orElseThrow().condenserWatts();
        double[] water = new double[axis.size()];
        water[axis.indexOf("Water")] = 190000 / 3.6;
        var cws = tp(303.15, 4e5, water);
        var cwr = ph(313.15, 3.7e5, cws.enthalpy() - condenser, water);
        STREAMS.put("CRUDE", crude);
        STREAMS.put("STEAM", steam);
        STREAMS.put("CWS", cws);
        STREAMS.put("S-1", s1);
        STREAMS.put("S-2", s2);
        STREAMS.put("S-3", s3);
        STREAMS.put("S-4", s4);
        STREAMS.put("S-5", s5);
        STREAMS.put("S-6", s6);
        STREAMS.put("S-7", s7);
        STREAMS.put("OFFGAS", product(column, "Overhead"));
        STREAMS.put("NAPHTHA", product(column, "Liquid distillate"));
        STREAMS.put("WATER", product(column, "Free water"));
        STREAMS.put("KERO", product(column, "Side draw (tray 11)"));
        STREAMS.put("S-8", s8);
        STREAMS.put("DIESEL", dieselOut);
        STREAMS.put("AGO", product(column, "Side draw (tray 29)"));
        STREAMS.put("S-9", tear);
        STREAMS.put("S-10", s10);
        STREAMS.put("RESIDUE", residue);
        STREAMS.put("CWR", cwr);
        long total = System.nanoTime() - start;
        var scratch = new MixtureViscosity.Workspace();
        System.out.println("stream   T,C   P,bar  vap%  kmol/h   t/h   Lm3/h  rho  mu,mPas  MW  h,kJ/kg");
        for (var e : STREAMS.entrySet()) {
            var s = e.getValue();
            double[] n = amounts(s);
            double all = Arrays.stream(n).sum(), vap = Arrays.stream(s.vaporView()).sum() + s.waterVapor();
            double volume = 0;
            for (int i = 0; i + 1 < n.length; i++) volume += n[i] * pack.properties().get(i).molecularWeight() / pack.properties().get(i).density();
            volume += s.waterLiquid() * 0.018015 / 998 + s.waterVapor() * 0.018015 / 998;
            double oil = Arrays.stream(s.liquidView()).sum();
            double mu = oil > 0 ? thermo.viscosity.liquid(s.temperature(), s.liquidView()).pascalSeconds() : s.waterLiquid() > 0 ? thermo.viscosity.waterLiquid(s.temperature()) : 0;
            double gas = vap > 0 ? thermo.vaporViscosity(s, scratch, null) : 0;
            System.out.printf(Locale.ROOT, "%-8s %6.1f %6.2f %5.1f %7.1f %7.2f %6.1f %6.1f %6.3f %6.4f %6.1f %8.1f%n", e.getKey(), s.temperature() - 273.15, s.pressure() / 1e5, 100 * vap / all, all * 3.6,
                    s.mass() * 3.6, volume * 3600, s.mass() / s.volume(), mu * 1e3, gas * 1e3, s.mass() / all * 1000, s.enthalpy() / s.mass() / 1000);
        }
        var ledger = column.dutyLedger().orElseThrow();
        double duties = ledger.condenserWatts() + ledger.reboilerWatts() + ledger.stageHeatTotalWatts();
        double massIn = crude.mass() + steam.mass() + cws.mass(), massOut = 0, hOut = 0;
        for (var name : List.of("OFFGAS", "NAPHTHA", "WATER", "KERO", "DIESEL", "AGO", "RESIDUE", "CWR")) {
            massOut += STREAMS.get(name).mass();
            hOut += STREAMS.get(name).enthalpy();
        }
        double pumpP102 = dieselOut.enthalpy() - s8.enthalpy();
        double hIn = crude.enthalpy() + steam.enthalpy() + cws.enthalpy() + work + pumpP102 + qHeater + qE102;
        System.out.printf(Locale.ROOT, "units: P-101 %.1f kW, E-101 %.2f MW, H-101 %.2f MW, column condenser %.2f MW, PA %.2f MW, P-102 %.1f kW, E-102 %.2f MW%n", work / 1e3, q101 / 1e6, qHeater / 1e6,
                ledger.condenserWatts() / 1e6, ledger.stageHeatTotalWatts() / 1e6, pumpP102 / 1e3, qE102 / 1e6);
        double near = s10.temperature() - s4.temperature(), far = tear.temperature() - s5.temperature(), lmtd = (far - near) / Math.log(far / near);
        System.out.printf(Locale.ROOT, "E-101: approach %.1f K, LMTD %.1f K, UA %.1f kW/K%n", Math.min(near, far), lmtd, q101 / lmtd / 1e3);
        System.out.printf(Locale.ROOT, "closure: mass in %.3f, out %.3f t/h (%.1e %%); heat in %.3f, out %.3f MW, gap %.3f MW (%.3f %% of %.1f MW of duty)%n", massIn * 3.6, massOut * 3.6, 100 * Math.abs(massIn - massOut) / massIn,
                hIn / 1e6, hOut / 1e6, (hIn - hOut) / 1e6, 100 * Math.abs(hIn - hOut) / (Math.abs(duties) + Math.abs(qHeater) + Math.abs(qE102)), (Math.abs(duties) + Math.abs(qHeater) + Math.abs(qE102)) / 1e6);
        System.out.printf(Locale.ROOT, "passes %d, whole run %.0f ms from a cold JVM, one thread%n", passes, total / 1e6);
        System.gc();
        var heap = Runtime.getRuntime();
        System.out.printf(Locale.ROOT, "heap held after the run: %.0f MB of %.0f MB%n", (heap.totalMemory() - heap.freeMemory()) / 1e6, heap.maxMemory() / 1e6);
        System.out.printf(Locale.ROOT, "column: %d trays, feed tray %d, top %.3f bar, drop %.0f Pa/tray, diameter %.2f m%n", base.stageCount(), base.feedStageNumber(), base.topPressurePascal() / 1e5,
                base.stagePressureDropPascal(), base.columnDiameterMetres());
        System.out.println("specs " + base.specifications());
        System.out.println("draws " + base.sideDraws());
        System.out.println("steam " + base.steamFeeds());
        System.out.println("pumparounds " + base.pumparounds());
        System.out.println("hydraulics " + column.trayHydraulics());
        var profile = new StringBuilder("profile T,C:");
        for (var node : column.inspection().orElseThrow().nodes()) profile.append(String.format(Locale.ROOT, " %.1f", node.temperatureKelvin() - 273.15));
        System.out.println(profile);
        for (var name : List.of("S-6", "S-7")) {
            var s = STREAMS.get(name);
            double gasMoles = Arrays.stream(s.vaporView()).sum() + s.waterVapor(), oilMoles = Arrays.stream(s.liquidView()).sum() + s.waterLiquid();
            System.out.printf(Locale.ROOT, "%s phases: gas %.1f kmol/h %.2f t/h, oil %.1f kmol/h %.2f t/h%n", name, gasMoles * 3.6, thermo.vaporMass(s) * 3.6, oilMoles * 3.6, (s.mass() - thermo.vaporMass(s)) * 3.6);
        }
        System.out.println("composition, mole %: " + List.of("CRUDE", "S-7", "OFFGAS", "NAPHTHA", "KERO", "RESIDUE"));
        for (int i = 0; i < axis.size(); i++) {
            var row = new ArrayList<String>();
            for (var name : List.of("CRUDE", "S-7", "OFFGAS", "NAPHTHA", "KERO", "RESIDUE")) {
                double[] n = amounts(STREAMS.get(name));
                row.add(String.format(Locale.ROOT, "%.2f", 100 * n[i] / Arrays.stream(n).sum()));
            }
            System.out.println(axis.get(i) + " " + (i + 1 < axis.size() ? String.format(Locale.ROOT, "mw=%.1f nbp=%.1f", pack.properties().get(i).molecularWeight() * 1000, pack.properties().get(i).normalBoilingPoint() - 273.15) : "") + " " + row);
        }
    }
}
