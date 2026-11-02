package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A column design's operating point (batch 2026-10-28-column-operation, plan section 12, decision D7 of the 2026-10-26
 * plan): the values the column calculator solved the design at, which a built column starts from. The feed (package,
 * components by id, molar flows, temperature), the top pressure and the tray drop, the three specifications, and the
 * steam, draw and pumparound values in the order of the trays' own lists ({@link ColumnTrays#steam()}, {@link
 * ColumnTrays#draws()}, {@link ColumnTrays#pumparounds()}). An equipment fact: saved by name beside the trays, append-only;
 * a column built before the field has none and cannot run, as before.
 *
 * @param feed          the feed's component molar flows, mol/s, on {@code components}
 * @param stageDrop     the prescribed tray pressure drop, Pa (the calculator's; with a diameter the hydraulics replace it)
 * @param reboilerDuty  W, positive into the column
 */
public record ColumnOperatingPoint(String packageId, String assayId, List<String> components, List<Double> feed, double feedTemperature,
                                   double topPressure, double stageDrop, double condenserTemperature, double refluxRatio, double reboilerDuty,
                                   List<Steam> steam, List<Double> draws, List<Pumparound> pumparounds) {
    /** Longest package, assay and component id the point keeps. */
    public static final int MAX_ID = 128;

    /** A steam feed's rate, mol/s, and temperature, K. */
    public record Steam(double flow, double temperature) {
        public Steam {
            if (!(flow > 0) || !Double.isFinite(flow) || !(temperature > 0) || !Double.isFinite(temperature))
                throw new IllegalArgumentException("A steam feed has a rate and a temperature above 0");
        }
    }

    /** A pumparound's duty, W (negative: cooling), and where it is placed in its zone. */
    public record Pumparound(double duty, V3PumparoundSpec.Split split) {
        public Pumparound {
            Objects.requireNonNull(split, "split");
            if (!Double.isFinite(duty) || duty == 0) throw new IllegalArgumentException("A pumparound has a finite duty other than 0");
        }
    }

    public ColumnOperatingPoint {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(assayId, "assayId");
        components = List.copyOf(components);
        feed = List.copyOf(feed);
        steam = List.copyOf(steam);
        draws = List.copyOf(draws);
        pumparounds = List.copyOf(pumparounds);
        if (packageId.isEmpty() || packageId.length() > MAX_ID || assayId.length() > MAX_ID)
            throw new IllegalArgumentException("A package id of 1 to " + MAX_ID + " characters");
        if (components.isEmpty() || components.size() > V3ComponentBasis.MAX_COMPONENTS || components.size() != feed.size())
            throw new IllegalArgumentException("One feed rate per component, 1 to " + V3ComponentBasis.MAX_COMPONENTS);
        if (new java.util.HashSet<>(components).size() != components.size()) throw new IllegalArgumentException("A component named twice");
        double total = 0;
        for (var id : components) if (id.isEmpty() || id.length() > MAX_ID) throw new IllegalArgumentException("A component id of 1 to " + MAX_ID + " characters");
        for (double f : feed) {
            if (!(f >= 0) || !Double.isFinite(f)) throw new IllegalArgumentException("A feed rate is finite and not negative");
            total += f;
        }
        if (!(total > 0)) throw new IllegalArgumentException("The feed has a rate above 0");
        for (double d : draws) if (!(d > 0) || !Double.isFinite(d)) throw new IllegalArgumentException("A side draw has a rate above 0");
        for (double v : new double[] {feedTemperature, topPressure, condenserTemperature})
            if (!(v > 0) || !Double.isFinite(v)) throw new IllegalArgumentException("The feed temperature, top pressure and condenser temperature are above 0");
        if (!(stageDrop >= 0) || !Double.isFinite(stageDrop) || !(refluxRatio >= 0) || !Double.isFinite(refluxRatio) || !Double.isFinite(reboilerDuty))
            throw new IllegalArgumentException("A tray drop and a reflux ratio of 0 or more and a finite reboiler duty");
    }

    /** The operating point of a calculator input. */
    public static ColumnOperatingPoint of(V3ColumnInput input) {
        var feed = new ArrayList<Double>();
        for (double f : input.feedComponentMolarFlowsMolPerSecond()) feed.add(f);
        return new ColumnOperatingPoint(input.packageId(), input.assayId(), input.componentBasis().componentIds(), feed, input.feedTemperatureKelvin(),
                input.topPressurePascal(), input.stagePressureDropPascal(), spec(input, V3ColumnSpecification.CondenserOutletTemperature.class),
                spec(input, V3ColumnSpecification.OrganicRefluxRatio.class), spec(input, V3ColumnSpecification.ReboilerDuty.class),
                input.steamFeeds().stream().map(s -> new Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin())).toList(),
                input.sideDraws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList(),
                input.pumparounds().stream().map(p -> new Pumparound(p.dutyWatts(), p.split())).toList());
    }

    /** Whether this point fits the lists of {@code trays}: one steam, draw and pumparound value per entry. */
    public boolean fits(ColumnTrays trays) {
        return steam.size() == trays.steam().size() && draws.size() == trays.draws().size() && pumparounds.size() == trays.pumparounds().size()
                && trays.feed() <= trays.trays() + 1;
    }

    /** The calculator input of this point on {@code trays} at {@code diameter}, m. */
    public V3ColumnInput input(ColumnTrays trays, double diameter) {
        if (!fits(trays)) throw new IllegalArgumentException("The operating point does not fit the trays");
        var steamFeeds = new ArrayList<V3SteamFeedSpec>();
        for (int i = 0; i < steam.size(); i++) steamFeeds.add(new V3SteamFeedSpec(trays.steam().get(i), steam.get(i).flow(), steam.get(i).temperature()));
        var sideDraws = new ArrayList<V3SideDrawSpec>();
        for (int i = 0; i < draws.size(); i++) sideDraws.add(new V3SideDrawSpec(trays.draws().get(i), draws.get(i)));
        var pas = new ArrayList<V3PumparoundSpec>();
        for (int i = 0; i < pumparounds.size(); i++)
            pas.add(new V3PumparoundSpec(trays.pumparounds().get(i).ret(), trays.pumparounds().get(i).draw(), pumparounds.get(i).duty(), pumparounds.get(i).split()));
        double[] flows = feed.stream().mapToDouble(Double::doubleValue).toArray();
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, packageId, assayId, new V3ComponentBasis(components), flows, feedTemperature, trays.trays(), trays.feed(),
                topPressure, stageDrop, List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                new V3ColumnSpecification.OrganicRefluxRatio(refluxRatio), new V3ColumnSpecification.ReboilerDuty(reboilerDuty)),
                sideDraws, steamFeeds, pas, diameter);
    }

    /** The feed's total molar rate, mol/s. */
    public double feedRate() {
        return feed.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double spec(V3ColumnInput input, Class<? extends V3ColumnSpecification> type) {
        for (var s : input.specifications()) {
            if (!type.isInstance(s)) continue;
            if (s instanceof V3ColumnSpecification.CondenserOutletTemperature t) return t.kelvin();
            if (s instanceof V3ColumnSpecification.OrganicRefluxRatio r) return r.ratio();
            if (s instanceof V3ColumnSpecification.ReboilerDuty q) return q.watts();
        }
        throw new IllegalArgumentException("The input has no " + type.getSimpleName());
    }
}
