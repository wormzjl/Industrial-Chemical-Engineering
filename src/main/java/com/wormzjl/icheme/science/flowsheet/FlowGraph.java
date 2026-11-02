package com.wormzjl.icheme.science.flowsheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The order of a run (batch 2026-11-02-process-simulator, plan section 3.1): the graph of calculations split into strongly
 * connected parts, solved in order, and in each loop the fewest streams torn. The same sheet always gives the same plan.
 */
final class FlowGraph {
    /** Combinations of streams a loop may try before the tear falls to the greedy rule. */
    private static final int TRIALS = 200_000;

    private final List<Calculation> calculations;
    private final List<List<Edge>> out;

    /** A wire from one calculation to another; only a stream may be torn. */
    private record Edge(int from, int to, String wire, boolean stream) {
    }

    /** One step of a run: a single calculation, or a loop's calculations in pass order with its tears. */
    record Group(List<Calculation> order, List<String> tears) {
        Group {
            order = List.copyOf(order);
            tears = List.copyOf(tears);
        }
    }

    private FlowGraph(List<Calculation> calculations) {
        this.calculations = calculations;
        out = new ArrayList<>();
        var writer = new HashMap<String, Integer>();
        for (int i = 0; i < calculations.size(); i++) {
            out.add(new ArrayList<>());
            var c = calculations.get(i);
            for (var wire : c.writes()) if (writer.put(wire, i) != null) throw new IllegalArgumentException("Two calculations write " + wire);
            for (var wire : c.gives()) if (writer.put(wire, i) != null) throw new IllegalArgumentException("Two calculations give " + wire);
        }
        for (int i = 0; i < calculations.size(); i++) {
            var c = calculations.get(i);
            connect(c.reads(), writer, i, true);
            connect(c.needs(), writer, i, false);
        }
    }

    private void connect(List<String> wires, Map<String, Integer> writer, int to, boolean stream) {
        for (var wire : wires) {
            var from = writer.get(wire);
            if (from != null) out.get(from).add(new Edge(from, to, wire, stream));
        }
    }

    /** The steps of a run over {@code calculations}, each after everything it reads, but for its tears. */
    static List<Group> plan(List<Calculation> calculations) {
        var graph = new FlowGraph(List.copyOf(calculations));
        var groups = new ArrayList<Group>();
        for (var part : graph.parts()) {
            var nodes = part.stream().sorted().toList();
            boolean selfLoop = nodes.size() == 1 && graph.out.get(nodes.getFirst()).stream().anyMatch(e -> e.to() == nodes.getFirst());
            if (nodes.size() == 1 && !selfLoop) groups.add(new Group(List.of(graph.calculations.get(nodes.getFirst())), List.of()));
            else groups.add(graph.loop(nodes));
        }
        return groups;
    }

    /** Tarjan's strongly connected parts. It closes a part after every part it feeds, so the list reversed is the run's order. */
    private List<List<Integer>> parts() {
        int n = calculations.size();
        int[] index = new int[n], low = new int[n];
        Arrays.fill(index, -1);
        boolean[] onStack = new boolean[n];
        var stack = new ArrayList<Integer>();
        var parts = new ArrayList<List<Integer>>();
        int[] counter = {0};
        for (int i = n - 1; i >= 0; i--) if (index[i] < 0) visit(i, index, low, onStack, stack, parts, counter);
        return parts.reversed();
    }

    private void visit(int v, int[] index, int[] low, boolean[] onStack, List<Integer> stack, List<List<Integer>> parts, int[] counter) {
        index[v] = low[v] = counter[0]++;
        stack.add(v);
        onStack[v] = true;
        for (var edge : out.get(v)) {
            int w = edge.to();
            if (index[w] < 0) {
                visit(w, index, low, onStack, stack, parts, counter);
                low[v] = Math.min(low[v], low[w]);
            } else if (onStack[w]) low[v] = Math.min(low[v], index[w]);
        }
        if (low[v] == index[v]) {
            var part = new ArrayList<Integer>();
            int w;
            do {
                w = stack.removeLast();
                onStack[w] = false;
                part.add(w);
            } while (w != v);
            parts.add(part);
        }
    }

    private Group loop(List<Integer> nodes) {
        var inside = new BitSet();
        nodes.forEach(inside::set);
        var edges = new ArrayList<Edge>();
        for (int node : nodes) for (var edge : out.get(node)) if (inside.get(edge.to())) edges.add(edge);
        var candidates = edges.stream().filter(Edge::stream).toList();
        var tears = tears(nodes, edges, candidates);
        var order = order(nodes, edges, tears).stream().map(calculations::get).toList();
        return new Group(order, tears.stream().map(Edge::wire).toList());
    }

    /** The fewest streams whose removal leaves no cycle; among equals, the most that leave a block's own solve, then sheet order. */
    private List<Edge> tears(List<Integer> nodes, List<Edge> edges, List<Edge> candidates) {
        int trials = 0;
        for (int size = 1; size <= candidates.size(); size++) {
            List<Edge> best = null;
            int bestScore = Integer.MAX_VALUE;
            int[] choice = new int[size];
            for (int i = 0; i < size; i++) choice[i] = i;
            while (true) {
                if (++trials > TRIALS) return greedy(nodes, edges, candidates);
                var trial = new ArrayList<Edge>();
                for (int i : choice) trial.add(candidates.get(i));
                if (order(nodes, edges, trial).size() == nodes.size()) {
                    int score = 0;
                    for (var tear : trial) if (calculations.get(tear.from()).kind() != Calculation.Kind.BLOCK) score++;
                    if (score < bestScore) {
                        best = trial;
                        bestScore = score;
                    }
                }
                if (!next(choice, candidates.size())) break;
            }
            if (best != null) return best;
        }
        throw new IllegalArgumentException("A loop of links alone: " + nodes.stream().map(i -> calculations.get(i).id()).toList());
    }

    /** The combination after {@code choice}, in sheet order; false after the last. */
    private static boolean next(int[] choice, int count) {
        int i = choice.length - 1;
        while (i >= 0 && choice[i] == count - choice.length + i) i--;
        if (i < 0) return false;
        choice[i]++;
        for (int k = i + 1; k < choice.length; k++) choice[k] = choice[k - 1] + 1;
        return true;
    }

    /** A loop too large to search: tear the stream that leaves the smallest cycle, again and again, then give back what proved not needed. */
    private List<Edge> greedy(List<Integer> nodes, List<Edge> edges, List<Edge> candidates) {
        var tears = new LinkedHashSet<Edge>();
        while (order(nodes, edges, List.copyOf(tears)).size() < nodes.size()) {
            Edge best = null;
            int smallest = Integer.MAX_VALUE;
            for (var candidate : candidates) {
                if (tears.contains(candidate)) continue;
                var trial = new ArrayList<>(tears);
                trial.add(candidate);
                int left = nodes.size() - order(nodes, edges, trial).size();
                if (left < smallest) {
                    best = candidate;
                    smallest = left;
                }
            }
            if (best == null) throw new IllegalArgumentException("A loop of links alone: " + nodes.stream().map(i -> calculations.get(i).id()).toList());
            tears.add(best);
        }
        for (var tear : List.copyOf(tears)) {
            tears.remove(tear);
            if (order(nodes, edges, List.copyOf(tears)).size() < nodes.size()) tears.add(tear);
        }
        return List.copyOf(tears);
    }

    /** Kahn's order over {@code nodes} without {@code tears}, the earliest in sheet order first. A cycle leaves its nodes out. */
    private static List<Integer> order(List<Integer> nodes, List<Edge> edges, List<Edge> tears) {
        var into = new HashMap<Integer, Integer>();
        var after = new HashMap<Integer, List<Integer>>();
        for (int node : nodes) into.put(node, 0);
        for (var edge : edges) {
            if (tears.contains(edge)) continue;
            into.merge(edge.to(), 1, Integer::sum);
            after.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
        }
        var ready = new PriorityQueue<Integer>();
        for (int node : nodes) if (into.get(node) == 0) ready.add(node);
        var order = new ArrayList<Integer>();
        while (!ready.isEmpty()) {
            int node = ready.poll();
            order.add(node);
            for (int next : after.getOrDefault(node, List.of())) if (into.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        return order;
    }
}
