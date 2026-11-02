package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The one clipboard (batch 2026-11-02-process-simulator, plan section 3.8): client memory, shared by every screen, so a tank's page copies and a feed's door pastes. Copying reads what a
 * screen already holds and sends nothing, and pasting changes a sheet alone. It holds a composition, a temperature and a pressure, not solids. A paste maps by component id, since a tank
 * and a sheet may stand on different packages, and names what it could not place. Minecraft-free.
 */
public final class SheetClipboard {
    /** What it holds: words for where it came from, parts by component id in any unit, temperature (K) and pressure (Pa). */
    public record Held(String source, Map<String, Double> amounts, double temperature, double pressure) {
        public Held {
            amounts = Map.copyOf(new LinkedHashMap<>(amounts));
        }
    }

    /** A paste: the feed it makes, and the ids it could not place. */
    public record Pasted(Flowsheet.Feed feed, int placed, List<String> left) {
    }

    private static final double FLOW_WHEN_NONE = 100 * 1000 / 3600.0;
    private static @Nullable Held held;

    private SheetClipboard() {
    }

    public static @Nullable Held held() {
        return held;
    }

    public static void copy(@Nullable Held next) {
        held = next;
    }

    /** What a vessel's snapshot shows, its phases summed: {@code phases} are mol by phase and component, in {@code components}' order. */
    public static Held vessel(String source, List<String> components, double[][] phases, double temperature, double pressure) {
        var amounts = new LinkedHashMap<String, Double>();
        for (int i = 0; i < components.size(); i++) {
            double total = 0;
            for (double[] phase : phases) total += i < phase.length ? phase[i] : 0;
            if (total > 0) amounts.put(components.get(i), total);
        }
        return new Held(source, amounts, temperature, pressure);
    }

    /** A solved stream, on its sheet's {@code axis}. */
    public static Held stream(String source, FlowStream stream, List<String> axis) {
        var amounts = new LinkedHashMap<String, Double>();
        double[] parts = stream.amountsView();
        for (int i = 0; i < parts.length && i < axis.size(); i++) if (parts[i] > 0) amounts.put(axis.get(i), parts[i]);
        return new Held(source, amounts, stream.temperature(), stream.pressure());
    }

    /** A feed as set on the sheet. */
    public static Held feed(String source, Flowsheet.Feed feed) {
        return new Held(source, feed.amounts(), feed.temperature(), feed.pressure());
    }

    /**
     * {@code held} laid on {@code feed}: the feed keeps its flow as a part's edit does, or takes 100 kmol/h if it has none; {@code conditions} takes T and P too. A component the
     * sheet's {@code axis} does not hold stays out, and is named: the rest take the whole flow between them.
     */
    public static Pasted paste(Held held, Flowsheet.Feed feed, List<String> axis, boolean conditions) {
        double placed = 0;
        var left = new ArrayList<String>();
        for (var part : held.amounts().entrySet()) {
            if (axis.contains(part.getKey())) placed += part.getValue();
            else left.add(part.getKey());
        }
        left.sort(null);
        double total = feed.amounts().values().stream().mapToDouble(Double::doubleValue).sum();
        if (total == 0) total = FLOW_WHEN_NONE;
        var amounts = new LinkedHashMap<String, Double>();
        int count = 0;
        if (placed > 0) for (var component : axis) {
            var part = held.amounts().get(component);
            if (part == null) continue;
            amounts.put(component, part / placed * total);
            count++;
        }
        var next = placed > 0 ? new Flowsheet.Feed(conditions ? held.temperature() : feed.temperature(), conditions ? held.pressure() : feed.pressure(), amounts, feed.solids(), feed.unknown()) : feed;
        return new Pasted(next, count, left);
    }
}
