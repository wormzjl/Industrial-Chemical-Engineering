package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static com.wormzjl.icheme.science.material.JsonFields.number;
import static com.wormzjl.icheme.science.material.JsonFields.strings;
import static com.wormzjl.icheme.science.material.JsonFields.text;

/**
 * A package's liquid-mixture pair term, the correction to sum x_i ln(mu_i): delta = G ((sum over C of x_i)^2 - sum over C
 * of x_i^2), C the participating compounds, x the mole fraction over every liquid compound, so a nonparticipant still
 * dilutes them. It vanishes for a pure compound and is factored to O(n). Owner decision E12 (batch
 * 2026-11-04-open-thermo-data): one open coefficient fitted to whole-crude viscosities. It carries no elemental or
 * reactive state.
 *
 * @param coefficient  G
 * @param participants the indices of the participating compounds on the package's axis, ascending
 * @param components   the size of that axis
 */
public record LiquidMixtureCorrection(double coefficient, List<Integer> participants, int components) {
    public LiquidMixtureCorrection {
        participants = List.copyOf(participants);
        if (!Double.isFinite(coefficient) || components < 0) throw new IllegalArgumentException("liquid_mixture: invalid coefficient");
        for (int k = 0; k < participants.size(); k++)
            if (participants.get(k) < 0 || participants.get(k) >= components || k > 0 && participants.get(k) <= participants.get(k - 1))
                throw new IllegalArgumentException("liquid_mixture: invalid participant index");
    }

    /**
     * A method's term ({@code liquid_mixture}, type {@code cut_pair_v1}): its coefficient, the compounds that take part, by
     * component id, and its own revision, which the method's identity names. A method without one has none (delta = 0).
     */
    record Rule(double coefficient, Set<String> components, String revision) {
        static final Rule NONE = new Rule(0, Set.of(), null);
        Rule { components = Set.copyOf(components); }
        static Rule read(JsonObject method) {
            if (!method.has("liquid_mixture")) return NONE;
            var o = method.getAsJsonObject("liquid_mixture");
            if (!text(o, "type").equals("cut_pair_v1")) throw new IllegalArgumentException("liquid_mixture.type: unsupported correction");
            double coefficient = number(o, "coefficient");
            if (!Double.isFinite(coefficient)) throw new IllegalArgumentException("liquid_mixture.coefficient: expected a finite number");
            var ids = strings(o, "components", "liquid_mixture.components");
            var set = new LinkedHashSet<>(ids);
            if (set.isEmpty() || set.size() != ids.size()) throw new IllegalArgumentException("liquid_mixture.components: expected distinct compound ids");
            text(o, "source");
            return new Rule(coefficient, set, JsonFields.token(text(o, "revision"), "liquid_mixture.revision"));
        }
        /** The term on a package's axis: the listed compounds it holds take part. */
        LiquidMixtureCorrection on(List<String> axis) {
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < axis.size(); i++) if (components.contains(axis.get(i))) indices.add(i);
            return new LiquidMixtureCorrection(coefficient, indices, axis.size());
        }
    }

    public double logCorrection(double[] amounts) {
        if (amounts.length != components) throw new IllegalArgumentException("Invalid liquid correction basis");
        double total = 0;
        for (double n : amounts) {
            if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException("Invalid liquid correction amount");
            total += n;
        }
        if (!(total > 0) || !Double.isFinite(total)) throw new IllegalArgumentException("Empty liquid correction mixture");
        double x = 0, x2 = 0;
        for (int i : participants) {
            double v = amounts[i] / total;
            x += v; x2 += v * v;
        }
        return coefficient * (x * x - x2);
    }
}
