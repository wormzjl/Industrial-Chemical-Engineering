package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import java.util.*;

/** Sparse native cross-section graph. Edges stay on carrier facets; joints weld within 22 mm. */
final class SurfaceSection {
    private record Segment(double[] a, double[] b) {}

    private record Visit(int node, double cost) {}

    private final List<double[]> points = new ArrayList<>();
    private final List<Map<Integer, Double>> edges = new ArrayList<>();
    private final Map<String, Integer> indices = new HashMap<>();

    private int node(double[] p) {
        String k =
                Math.round(p[0] * 1e7)
                        + ":"
                        + Math.round(p[1] * 1e7)
                        + ":"
                        + Math.round(p[2] * 1e7);
        var old = indices.get(k);
        if (old != null) return old;
        int id = points.size();
        indices.put(k, id);
        points.add(p);
        edges.add(new HashMap<>());
        return id;
    }

    private void link(double[] a, double[] b) {
        int i = node(a), j = node(b);
        if (i == j) return;
        double length = Math.sqrt(dot(sub(a, b), sub(a, b)));
        edges.get(i).merge(j, length, Math::min);
        edges.get(j).merge(i, length, Math::min);
    }

    static List<double[]> route(List<Polygon> polys, double[] start, double[] end, int axis) {
        if (Math.abs(start[axis] - end[axis]) > 1e-6)
            throw new IllegalArgumentException("Different section stations");
        var segments = new ArrayList<Segment>();
        double station = end[axis];
        for (var poly : polys) {
            var v = poly.positions();
            for (int t = 1; t + 1 < poly.size(); t++) {
                var p =
                        new double[][] {
                            Arrays.copyOfRange(v, 0, 3),
                            Arrays.copyOfRange(v, 3 * t, 3 * t + 3),
                            Arrays.copyOfRange(v, 3 * t + 3, 3 * t + 6)
                        };
                var hits = new ArrayList<double[]>();
                for (int i = 0; i < 3; i++) {
                    var a = p[i];
                    var b = p[(i + 1) % 3];
                    double da = a[axis] - station, db = b[axis] - station;
                    if (Math.abs(da) < 1e-8) hits.add(a);
                    if (da * db < -1e-16) hits.add(plus(a, scale(sub(b, a), -da / (db - da))));
                }
                for (int i = 0; i < hits.size(); i++)
                    for (int j = 0; j < i; j++)
                        if (distance(hits.get(i), hits.get(j)) > 1e-8)
                            segments.add(new Segment(hits.get(i), hits.get(j)));
            }
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("Empty native wiring section");
        var cuts = new ArrayList<TreeSet<Double>>();
        for (var ignored : segments) cuts.add(new TreeSet<>(List.of(0., 1.)));
        var welds = new ArrayList<Segment>();
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        for (int i = 0; i < segments.size(); i++) {
            var a = segments.get(i);
            var r = sub(a.b, a.a);
            for (int j = 0; j < i; j++) {
                var b = segments.get(j);
                var s = sub(b.b, b.a);
                var offset = sub(b.a, a.a);
                double den = r[u] * s[w] - r[w] * s[u];
                if (Math.abs(den) > 1e-12) {
                    double t = (offset[u] * s[w] - offset[w] * s[u]) / den,
                            q = (offset[u] * r[w] - offset[w] * r[u]) / den;
                    if (t >= -1e-7 && t <= 1 + 1e-7 && q >= -1e-7 && q <= 1 + 1e-7) {
                        cuts.get(i).add(Math.clamp(t, 0, 1));
                        cuts.get(j).add(Math.clamp(q, 0, 1));
                    }
                }
                for (int direction = 0; direction < 2; direction++) {
                    int left = direction == 0 ? i : j, right = direction == 0 ? j : i;
                    var l = segments.get(left);
                    var z = segments.get(right);
                    for (var p : List.of(l.a, l.b)) {
                        double t = amount(p, z.a, z.b);
                        var q = plus(z.a, scale(sub(z.b, z.a), t));
                        double gap = distance(p, q);
                        if (gap <= .022) {
                            cuts.get(right).add(t);
                            if (gap > 1e-8) welds.add(new Segment(p, q));
                        }
                    }
                }
            }
        }
        var graph = new SurfaceSection();
        int[] ends = new int[2];
        int which = 0;
        for (var p : List.of(start, end)) {
            double best = Double.POSITIVE_INFINITY;
            int chosen = -1;
            double amount = 0;
            double[] q = null;
            for (int i = 0; i < segments.size(); i++) {
                var z = segments.get(i);
                double t = amount(p, z.a, z.b);
                var hit = plus(z.a, scale(sub(z.b, z.a), t));
                double gap = distance(p, hit);
                if (gap < best) {
                    best = gap;
                    chosen = i;
                    amount = t;
                    q = hit;
                }
            }
            if (best > .06)
                throw new IllegalArgumentException("Wiring section misses entry by " + best);
            cuts.get(chosen).add(amount);
            ends[which++] = graph.node(q);
        }
        for (int i = 0; i < segments.size(); i++) {
            var z = segments.get(i);
            double[] last = null;
            for (double t : cuts.get(i)) {
                var p = plus(z.a, scale(sub(z.b, z.a), t));
                if (last != null) graph.link(last, p);
                last = p;
            }
        }
        for (var z : welds) graph.link(z.a, z.b);
        double[] costs = new double[graph.points.size()];
        Arrays.fill(costs, Double.POSITIVE_INFINITY);
        costs[ends[0]] = 0;
        int[] parent = new int[costs.length];
        Arrays.fill(parent, -1);
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost));
        queue.add(new Visit(ends[0], 0));
        while (!queue.isEmpty()) {
            var at = queue.remove();
            if (at.cost != costs[at.node]) continue;
            if (at.node == ends[1]) break;
            for (var next : graph.edges.get(at.node).entrySet()) {
                double trial = at.cost + next.getValue();
                if (trial < costs[next.getKey()]) {
                    costs[next.getKey()] = trial;
                    parent[next.getKey()] = at.node;
                    queue.add(new Visit(next.getKey(), trial));
                }
            }
        }
        if (!Double.isFinite(costs[ends[1]])) {
            var other = new HashSet<Integer>();
            var todo = new ArrayDeque<Integer>();
            other.add(ends[1]);
            todo.add(ends[1]);
            while (!todo.isEmpty())
                for (int q : graph.edges.get(todo.remove()).keySet()) if (other.add(q)) todo.add(q);
            double gap = Double.POSITIVE_INFINITY;
            double[] nearA = null, nearB = null;
            for (int i = 0; i < costs.length; i++)
                if (Double.isFinite(costs[i]))
                    for (int j : other) {
                        double d = distance(graph.points.get(i), graph.points.get(j));
                        if (d < gap) {
                            gap = d;
                            nearA = graph.points.get(i);
                            nearB = graph.points.get(j);
                        }
                    }
            throw new IllegalArgumentException(
                    "Disconnected native wiring section axis="
                            + axis
                            + " gap="
                            + gap
                            + " at "
                            + Arrays.toString(nearA)
                            + " / "
                            + Arrays.toString(nearB)
                            + " endpoints="
                            + Arrays.toString(start)
                            + " / "
                            + Arrays.toString(end));
        }
        var result = new ArrayList<double[]>();
        for (int p = ends[1]; p >= 0; p = parent[p]) {
            result.add(graph.points.get(p));
            if (p == ends[0]) break;
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    static double amount(double[] p, double[] a, double[] b) {
        var d = sub(b, a);
        return Math.clamp(dot(sub(p, a), d) / Math.max(1e-30, dot(d, d)), 0, 1);
    }

    static double distance(double[] a, double[] b) {
        var d = sub(a, b);
        return Math.sqrt(dot(d, d));
    }
}
