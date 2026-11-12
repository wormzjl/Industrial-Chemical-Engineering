import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A product's boiling range by mass (batch 2026-11-12-tutorial-system, P4): the tutorial recording's rule (BarrelData): a pure
 * compound boils at its normal boiling point, a petroleum cut evenly between its lower and upper edge, and a cut with no upper
 * edge somewhere above its lower one. Pure arithmetic, no game class.
 */
final class Boiling {
    /** One component's mass in a product and the range it boils over (K); upper NaN: open above. */
    record Part(double mass, double lower, double upper) {
    }

    /**
     * A point of the boiling curve: the temperature at which {@code share} of the mass has boiled (K). {@code above} is true where
     * the point falls in a cut open above, and the value is then that cut's lower edge (the point is at least it).
     */
    record Point(double kelvin, boolean above) {
    }

    private Boiling() {
    }

    /** The temperature at which {@code share} (0 to 1) of {@code parts}' mass has boiled. */
    static Point at(List<Part> parts, double share) {
        var sorted = new ArrayList<>(parts.stream().filter(p -> p.mass() > 0).toList());
        if (sorted.isEmpty()) return new Point(Double.NaN, false);
        double total = sorted.stream().mapToDouble(Part::mass).sum(), want = share * total;
        // The curve is piecewise linear in T: walk T upward through every edge and accumulate.
        var edges = new ArrayList<Double>();
        for (var p : sorted) {
            edges.add(p.lower());
            if (!Double.isNaN(p.upper())) edges.add(p.upper());
        }
        edges.sort(Comparator.naturalOrder());
        double before = 0;
        double last = edges.getFirst();
        for (double t : edges) {
            double boiled = below(sorted, t);
            if (boiled >= want - 1e-12 * total) {
                // Between last and t the curve rises linearly from before to boiled (points jump at t itself).
                double atLast = below(sorted, last);
                double slopeShare = boiled - atLast - jump(sorted, t);
                if (slopeShare > 0 && want > atLast) {
                    double rise = Math.min(want - atLast, slopeShare);
                    return new Point(last + (t - last) * rise / slopeShare, false);
                }
                return new Point(t, false);
            }
            before = boiled;
            last = t;
        }
        // The rest boils in cuts open above: the point is at least the highest such cut's lower edge that the share reaches.
        double acc = before;
        for (var p : sorted.stream().filter(p -> Double.isNaN(p.upper())).sorted(Comparator.comparingDouble(Part::lower)).toList()) {
            acc += p.mass();
            if (acc >= want) return new Point(p.lower(), true);
        }
        return new Point(last, true);
    }

    /** The mass boiled at or below {@code kelvin}. */
    static double below(List<Part> parts, double kelvin) {
        double sum = 0;
        for (var p : parts) {
            if (Double.isNaN(p.upper())) continue;
            if (p.upper() <= p.lower()) sum += kelvin >= p.lower() ? p.mass() : 0;
            else sum += p.mass() * Math.clamp((kelvin - p.lower()) / (p.upper() - p.lower()), 0, 1);
        }
        return sum;
    }

    /** The mass of pure compounds that boil exactly at {@code kelvin}. */
    private static double jump(List<Part> parts, double kelvin) {
        double sum = 0;
        for (var p : parts) if (!Double.isNaN(p.upper()) && p.upper() <= p.lower() && p.lower() == kelvin) sum += p.mass();
        return sum;
    }

    /** The gap between a lighter and a heavier product (K): the heavier one's 5 % point less the lighter one's 95 % point. */
    static double gap(List<Part> lighter, List<Part> heavier) {
        return at(heavier, .05).kelvin() - at(lighter, .95).kelvin();
    }
}
