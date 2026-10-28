package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.column.v3.V3ColumnDutyLedger;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A running column's split table (batch 2026-10-28-column-operation, plan section 5): from the last accepted V3 solve,
 * for each product the share of each component taken that leaves in it (its recovery), the product's temperature and
 * pressure; the duties per mole of feed taken; and the reference state the table was solved at. State, not a fact: the
 * engine rebuilds it by a solve.
 *
 * <p>Recoveries are on the fluid network's axis (the package's components, water last) and every component's row sums to
 * one exactly, so a cycle conserves each component by construction, whatever the feed. A component the solve had no feed
 * of takes the row of the solved component nearest in normal boiling point (plan section 11), except water, which with
 * no solved water condenses with the distillate (a dry column has no free-water outlet of its own).
 *
 * @param axis              the network's component ids, water last
 * @param products          one per product port with a stream in the solve
 * @param reboilerPerFeed   J per mole of feed taken (the reboiler duty over the solved feed rate)
 * @param pumparoundPerFeed J per mole of feed taken, per pumparound, negative for a cooler
 * @param condenserPerFeed  J per mole of feed taken, the solved condenser duty (column sign: negative, removed)
 * @param reference         the intensive state the table was solved at
 * @param held              components the solve had a feed of; the others borrowed a row
 */
public record SplitTable(List<String> axis, List<Product> products, double reboilerPerFeed, double[] pumparoundPerFeed, double condenserPerFeed,
                         Reference reference, boolean[] held) {
    /** The water's id on the network axis. */
    public static final String WATER = "Water";

    /** A product: its port, its index among draws (0 for the others), its recovery row, temperature (K) and pressure (Pa). */
    public record Product(Port port, int index, double[] recovery, double temperature, double pressure) {
        public Product {
            Objects.requireNonNull(port, "port");
            recovery = recovery.clone();
            if (!(temperature > 0) || !(pressure > 0)) throw new IllegalArgumentException("A product has a temperature and a pressure");
        }

        @Override
        public double[] recovery() {
            return recovery.clone();
        }

        double share(int component) {
            return recovery[component];
        }
    }

    /**
     * The intensive state of the solve: the feed's mole fractions on the axis, its specific enthalpy (J/mol, network basis)
     * and its slope (J/mol K) for the enthalpy band, the steam taken per mole of feed per steam inlet, the throughput
     * relative to the design rate, and the setpoints it solved.
     */
    public record Reference(double[] feedFractions, double feedEnthalpy, double enthalpySlope, double[] steamRatios, double alpha, Setpoints setpoints) {
        public Reference {
            feedFractions = feedFractions.clone();
            steamRatios = steamRatios.clone();
            Objects.requireNonNull(setpoints, "setpoints");
        }

        @Override
        public double[] feedFractions() {
            return feedFractions.clone();
        }

        @Override
        public double[] steamRatios() {
            return steamRatios.clone();
        }
    }

    public SplitTable {
        axis = List.copyOf(axis);
        products = List.copyOf(products);
        pumparoundPerFeed = pumparoundPerFeed.clone();
        held = held.clone();
        Objects.requireNonNull(reference, "reference");
        if (products.isEmpty() || held.length != axis.size()) throw new IllegalArgumentException("A split table has products on its axis");
        for (int i = 0; i < axis.size(); i++) {
            double sum = 0;
            for (var p : products) {
                if (p.recovery.length != axis.size()) throw new IllegalArgumentException("A recovery row per axis component");
                double r = p.recovery[i];
                if (!(r >= 0) || r > 1) throw new IllegalArgumentException("A recovery is 0 to 1");
                sum += r;
            }
            if (Math.abs(sum - 1) > 1e-12) throw new IllegalArgumentException("Component " + axis.get(i) + "'s recoveries sum to " + sum);
        }
    }

    @Override
    public double[] pumparoundPerFeed() {
        return pumparoundPerFeed.clone();
    }

    @Override
    public boolean[] held() {
        return held.clone();
    }

    /** The product moles of {@code taken} (pooled inlet moles on the axis): each component split by its row. */
    public double[][] split(double[] taken) {
        if (taken.length != axis.size()) throw new IllegalArgumentException("Taken moles on the table's axis");
        double[][] out = new double[products.size()][axis.size()];
        for (int i = 0; i < taken.length; i++) {
            double rest = taken[i];
            // The last product with a share takes the remainder, so the split adds up to the taken moles exactly.
            int last = -1;
            for (int k = 0; k < products.size(); k++) if (products.get(k).share(i) > 0) last = k;
            for (int k = 0; k < products.size(); k++) {
                if (k == last) {
                    out[k][i] = Math.max(0, rest);
                    break;
                }
                double n = taken[i] * products.get(k).share(i);
                out[k][i] = n;
                rest -= n;
            }
        }
        return out;
    }

    /**
     * The table of an accepted solve.
     *
     * @param axis          the network's component ids, water last
     * @param boilingPoints the axis components' normal boiling points, K, for the rows of components the solve had no feed of
     * @param input         the solved input (its feed, steam and duties)
     * @param result        the accepted result
     * @param reference     the intensive state the input stood for
     */
    public static SplitTable of(List<String> axis, double[] boilingPoints, V3ColumnInput input, V3ColumnResult result, Reference reference) {
        int m = axis.size();
        int water = axis.indexOf(WATER);
        var streams = result.streams();
        var rows = new ArrayList<double[]>();
        var products = new ArrayList<Product>();
        for (var s : streams) {
            var port = port(s.streamId(), input);
            double[] flows = new double[m];
            for (var f : s.moleFractions()) {
                int i = axis.indexOf(f.componentId());
                if (i >= 0) flows[i] += s.molarFlowMolPerSecond() * f.moleFraction();
            }
            // Two streams to one port (none today) add up.
            int existing = -1;
            for (int k = 0; k < products.size(); k++) if (products.get(k).port() == port.port() && products.get(k).index() == port.index()) existing = k;
            if (existing >= 0) {
                for (int i = 0; i < m; i++) rows.get(existing)[i] += flows[i];
                continue;
            }
            rows.add(flows);
            products.add(new Product(port.port(), port.index(), new double[m], s.temperatureKelvin(), s.pressurePascal()));
        }
        double[] total = new double[m];
        for (double[] row : rows) for (int i = 0; i < m; i++) total[i] += row[i];
        boolean[] held = new boolean[m];
        double largest = Arrays.stream(total).max().orElse(0);
        for (int i = 0; i < m; i++) held[i] = total[i] > largest * 1e-14;
        double[][] recovery = new double[products.size()][m];
        for (int i = 0; i < m; i++) {
            if (held[i]) {
                for (int k = 0; k < products.size(); k++) recovery[k][i] = rows.get(k)[i] / total[i];
                continue;
            }
            int distillate = indexOf(products, Port.DISTILLATE);
            if (i == water && distillate >= 0) {
                recovery[distillate][i] = 1;
                continue;
            }
            int nearest = -1;
            for (int j = 0; j < m; j++)
                if (held[j] && j != water && (nearest < 0 || Math.abs(boilingPoints[j] - boilingPoints[i]) < Math.abs(boilingPoints[nearest] - boilingPoints[i]))) nearest = j;
            if (nearest < 0) throw new IllegalArgumentException("The solve holds no hydrocarbon");
            for (int k = 0; k < products.size(); k++) recovery[k][i] = recovery[k][nearest];
        }
        // Each row to a sum of one exactly: the largest share takes the rounding.
        for (int i = 0; i < m; i++) {
            int big = 0;
            double sum = 0;
            for (int k = 0; k < products.size(); k++) {
                sum += recovery[k][i];
                if (recovery[k][i] > recovery[big][i]) big = k;
            }
            recovery[big][i] += 1 - sum;
            if (recovery[big][i] > 1) recovery[big][i] = 1;
        }
        var finished = new ArrayList<Product>();
        for (int k = 0; k < products.size(); k++) {
            var p = products.get(k);
            finished.add(new Product(p.port(), p.index(), recovery[k], p.temperature(), p.pressure()));
        }
        double feed = Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        V3ColumnDutyLedger ledger = result.dutyLedger().orElseThrow(() -> new IllegalArgumentException("An accepted result has its duties"));
        double[] pa = new double[input.pumparounds().size()];
        for (int j = 0; j < pa.length; j++) pa[j] = input.pumparounds().get(j).dutyWatts() / feed;
        return new SplitTable(axis, finished, ledger.reboilerWatts() / feed, pa, ledger.condenserWatts() / feed, reference, held);
    }

    /** A product port and its index among the draws. */
    public record PortIndex(Port port, int index) {
    }

    /** The product port of a V3 stream id. */
    public static PortIndex port(String streamId, V3ColumnInput input) {
        return switch (streamId) {
            case "overhead_vapor" -> new PortIndex(Port.OFF_GAS, 0);
            case "distillate_liquid" -> new PortIndex(Port.DISTILLATE, 0);
            case "free_water" -> new PortIndex(Port.SOUR_WATER, 0);
            case "bottoms_liquid" -> new PortIndex(Port.BOTTOMS, 0);
            default -> {
                if (!streamId.startsWith("side_liquid_tray_")) throw new IllegalArgumentException("Unknown column stream " + streamId);
                int tray = Integer.parseInt(streamId.substring("side_liquid_tray_".length()));
                List<V3SideDrawSpec> draws = input.sideDraws();
                for (int j = 0; j < draws.size(); j++) if (draws.get(j).trayNumber() == tray) yield new PortIndex(Port.DRAW, j);
                throw new IllegalArgumentException("No side draw on tray " + tray);
            }
        };
    }

    /** The product on {@code port} (and draw {@code index}), or -1. */
    public int product(Port port, int index) {
        for (int k = 0; k < products.size(); k++) if (products.get(k).port() == port && products.get(k).index() == index) return k;
        return -1;
    }

    private static int indexOf(List<Product> products, Port port) {
        for (int k = 0; k < products.size(); k++) if (products.get(k).port() == port) return k;
        return -1;
    }
}
