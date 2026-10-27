package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.AssemblyReport.PortReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.Blueprint.*;
import com.wormzjl.icheme.science.assembly.PortGraph.*;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.PortMarkings;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

import static com.wormzjl.icheme.science.assembly.AssemblyReport.NONE;

/**
 * Binds an assembly's slots to what its pipes reach (batch 2026-10-26-multi-machine-framework, plan section 6): from the
 * master, the links in blueprint order; a unit or fitting of a slot's form met where its step expects it fills the slot,
 * and the link goes on from the unit's other port. A unit is bound as soon as one of its ports is reached, so a partly
 * connected unit counts as found and its other ports keep their colours. Deterministic: the same graph gives the same
 * report. Minecraft-free.
 *
 * <p>Faults are kept on the slot they concern, the worst one per slot: a part met in the wrong place (yellow) is still
 * bound and the walk goes on through it; a line that stops (red) leaves the steps after it pending. A pending step met
 * later on another line is bound in the wrong order; one never met is missing.
 */
public final class AssemblyMatcher {
    /** Pipe blocks beyond a boundary valve that count as outside (plan section 5.2). */
    public static final int EXTERNAL_REACH = 16;

    private AssemblyMatcher() {
    }

    /**
     * The report of {@code blueprint} over {@code graph}, from {@code master}; {@code taken} holds the origins of units
     * that other assemblies hold, which this one reports as shared instead of binding.
     */
    public static AssemblyReport match(Blueprint blueprint, PortGraph graph, Unit master, Set<Long> taken) {
        return new Matcher(blueprint, graph, master, taken).run();
    }

    /** What fills a slot: a unit (entered at {@code entered}) or a fitting at {@code position}, by {@code form}. */
    private record Binding(@Nullable Unit unit, long position, @Nullable Role entered, @Nullable Form form) {
    }

    private record Fault(State state, long at, String detail) {
    }

    private record Key(long unit, Role role, int ordinal) {
        static Key of(Port port) {
            return new Key(port.unit().origin(), port.role(), port.ordinal());
        }
    }

    /** A step a met element fills, by which form, in which state (the designed form, a family form, or out of order). */
    private record Hit(Step step, Form form, State state) {
    }

    private static final class Matcher {
        private final Blueprint blueprint;
        private final PortGraph graph;
        private final Unit master;
        private final Set<Long> taken;
        private final Map<Slot, Binding> bound = new LinkedHashMap<>();
        private final Map<Slot, Fault> faults = new HashMap<>();
        private final Set<Key> used = new HashSet<>(), required = new HashSet<>();
        private final Set<Long> footprint = new LinkedHashSet<>();
        /** Steps passed over: met later on another line, they are bound in the wrong order. */
        private final List<Step> pending = new ArrayList<>();

        Matcher(Blueprint blueprint, PortGraph graph, Unit master, Set<Long> taken) {
            this.blueprint = blueprint;
            this.graph = graph;
            this.master = master;
            this.taken = taken;
        }

        AssemblyReport run() {
            bound.put(blueprint.master(), new Binding(master, master.origin(), null, null));
            for (var link : blueprint.links()) follow(link);
            return report();
        }

        private void follow(Link link) {
            var start = link.start();
            var from = bound.get(start.slot());
            Role role = null;
            int ordinal = start.ordinal();
            if (from != null && from.unit() != null)
                role = start.afterEntry() ? from.entered() == null ? null : PortMarkings.partner(from.unit().kind(), from.entered()).orElse(null) : start.role();
            if (role == null || ordinal >= from.unit().count(role)) {
                // The line's start is not there: its steps wait for another line, or are missing.
                pending.addAll(link.steps());
                return;
            }
            var port = new Port(from.unit(), role, ordinal);
            required.add(Key.of(port));
            var run = graph.run(port);
            if (!(run.end() instanceof Unconnected)) used.add(Key.of(port));
            walk(new ArrayList<>(link.steps()), link.finish(), link.owner(), run, link.upstream());
        }

        /** Follows {@code run} and the runs after it while {@code rest} has steps, then checks {@code finish}. */
        private void walk(List<Step> rest, Finish finish, Slot owner, Run run, boolean upstream) {
            long lastValve = -1;
            while (true) {
                footprint.addAll(run.positions());
                for (var e : run.elements()) {
                    // Fittings beyond the boundary valve are outside the assembly; a flowmeter no step asks for is passed over.
                    if (e.kind() == Fitting.OTHER || rest.isEmpty() && finish instanceof Outside) continue;
                    if (e.kind() == Fitting.FLOWMETER && rest.stream().noneMatch(s -> form(s, form -> form instanceof FittingForm f && f.kind() == Fitting.FLOWMETER) != null)) continue;
                    var hit = take(rest, form -> form instanceof FittingForm f && f.kind() == e.kind(), false);
                    if (hit == null) {
                        fault(rest.isEmpty() ? owner : rest.getFirst().slot(), State.EXTRA_VALVE, e.position(), e.kind().name());
                        continue;
                    }
                    bind(hit.step().slot(), new Binding(null, e.position(), null, hit.form()));
                    var state = hit.state();
                    String detail = state == State.WRONG_KIND ? e.kind().name() : "";
                    // The arrow follows the stream: along the walk downstream, back along it upstream. A flowmeter reads either way.
                    if (e.kind() != Fitting.FLOWMETER && (upstream ? -e.direction() : e.direction()) < 0) {
                        state = worse(state, e.kind() == Fitting.CONTROL_VALVE ? State.VALVE_REVERSED : State.WRONG_PORT);
                        detail = "backwards";
                    }
                    if (state != State.OK) fault(hit.step().slot(), state, e.position(), detail);
                    lastValve = e.pipesBefore();
                }
                switch (run.end()) {
                    case AtPort at -> {
                        var u = at.port().unit();
                        if (member(u)) used.add(Key.of(at.port()));
                        if (rest.isEmpty()) {
                            finish(finish, owner, at.port());
                            return;
                        }
                        if (member(u)) {
                            stop(rest, State.MISSING, NONE, "returns:" + at.port().role().name());
                            return;
                        }
                        var hit = take(rest, form -> accepts(form, u), true);
                        if (hit == null) {
                            var first = rest.getFirst();
                            stop(rest, first.form() instanceof UnitForm ? State.WRONG_KIND : State.MISSING, u.origin(), describe(u));
                            return;
                        }
                        var slot = hit.step().slot();
                        if (taken.contains(u.origin())) {
                            fault(slot, State.SHARED, u.origin(), "");
                            stop(rest, State.MISSING, NONE, "");
                            return;
                        }
                        var form = (UnitForm) hit.form();
                        var state = hit.state();
                        String detail = state == State.WRONG_KIND ? describe(u) : "";
                        Role entered = at.port().role(), next = form.out();
                        if (entered != form.in()) {
                            state = worse(state, State.WRONG_PORT);
                            detail = PortMarkings.flow(u.kind(), entered) == PortMarkings.Flow.OUT ? "backwards" : entered.name();
                            next = form.out() == null ? null : PortMarkings.partner(u.kind(), entered).orElse(null);
                        }
                        bind(slot, new Binding(u, u.origin(), entered, form));
                        used.add(Key.of(at.port()));
                        if (state != State.OK) fault(slot, state, NONE, detail);
                        if (form.out() == null) {
                            pending.addAll(rest);
                            return;
                        }
                        if (next == null || u.count(next) == 0) {
                            stop(rest, State.MISSING, NONE, "");
                            return;
                        }
                        var out = new Port(u, next, 0);
                        required.add(Key.of(out));
                        run = graph.run(out);
                        if (!(run.end() instanceof Unconnected)) used.add(Key.of(out));
                        lastValve = -1;
                    }
                    case Junction junction -> {
                        footprint.add(junction.position());
                        if (rest.isEmpty()) {
                            // A branch beyond the boundary valve is outside; a branch on the way to a port is not.
                            if (!(finish instanceof Outside)) fault(owner, State.BRANCHED, junction.position(), "");
                            return;
                        }
                        if (rest.getFirst().form() instanceof TeeForm) tee(rest.removeFirst(), junction, run);
                        else stop(rest, State.BRANCHED, junction.position(), "");
                        return;
                    }
                    case Terminal terminal -> {
                        if (!rest.isEmpty()) stop(rest, State.MISSING, terminal.position(), "terminal");
                        else if (!(finish instanceof Outside)) fault(owner, State.MISSING, terminal.position(), "terminal");
                        return;
                    }
                    case OpenEnd open -> {
                        if (!rest.isEmpty()) stop(rest, State.OPEN_END, open.position(), "");
                        else if (!(finish instanceof Outside) || lastValve < 0 || run.pipes() - lastValve < EXTERNAL_REACH)
                            fault(owner, State.OPEN_END, open.position(), "");
                        return;
                    }
                    case NotLoaded unloaded -> {
                        if (rest.isEmpty()) fault(owner, State.NOT_LOADED, unloaded.position(), "");
                        else for (var step : rest) fault(step.slot(), State.NOT_LOADED, unloaded.position(), "");
                        pending.addAll(rest);
                        return;
                    }
                    case Unconnected ignored -> {
                        if (!rest.isEmpty()) stop(rest, State.MISSING, NONE, "");
                        else fault(owner, State.OPEN_END, NONE, "unconnected");
                        return;
                    }
                }
            }
        }

        /** A line that ends with nothing it may still meet: its end on {@link #finish}'s port. */
        private void finish(Finish finish, Slot owner, Port port) {
            switch (finish) {
                case ToPort target -> {
                    var spec = target.port();
                    var holder = bound.get(spec.slot());
                    boolean there = holder != null && holder.unit() != null && holder.unit().origin() == port.unit().origin() && port.role() == spec.role()
                            && port.ordinal() == spec.ordinal();
                    if (there) used.add(Key.of(port));
                    else fault(owner, State.WRONG_PORT, NONE, port.role().name());
                }
                // Another structure beyond the boundary valve is outside; this assembly's own units are not.
                case Outside ignored -> {
                    if (member(port.unit())) fault(owner, State.WRONG_PORT, NONE, "returns:" + port.role().name());
                }
                case AtUnit ignored -> {
                }
            }
        }

        /**
         * A tee: the branches leaving it matched to the tee's branches, each pair by how well the branch's end and fittings
         * fit (the best assignment, the first on a tie), then followed; a branch more than the tee has is a fault.
         */
        private void tee(Step step, Junction junction, Run arrived) {
            var form = (TeeForm) step.form();
            bind(step.slot(), new Binding(null, junction.position(), null, form));
            var runs = graph.branches(junction, arrived);
            if (runs.size() > form.branches().size()) fault(step.slot(), State.BRANCHED, junction.position(), Integer.toString(runs.size()));
            int n = form.branches().size();
            int[] best = null;
            int bestScore = Integer.MIN_VALUE;
            for (var order : permutations(n)) {
                int score = 0;
                for (int b = 0; b < n; b++) if (order[b] < runs.size()) score += score(form.branches().get(b), runs.get(order[b]));
                if (score > bestScore) {
                    bestScore = score;
                    best = order;
                }
            }
            for (int b = 0; b < n; b++) {
                var branch = form.branches().get(b);
                if (best[b] < runs.size()) walk(new ArrayList<>(branch.steps()), branch.finish(), branch.owner(), runs.get(best[b]), false);
                else stop(new ArrayList<>(branch.steps()), State.MISSING, junction.position(), "");
            }
        }

        /**
         * How well {@code run} fits {@code branch}: a valve on it; its end at the branch's port (4); a branch outside not
         * returning to the assembly (2). A branch that splits again on its way (a bypass, batch 2026-10-27-heat-exchanger-design,
         * W8) fits a run that ends at a junction (2), and one with a valve step a run with a valve (1): the feed and the
         * bypass leaving an inlet tee both end outside the tee's own walk.
         */
        private int score(Branch branch, Run run) {
            boolean valve = run.elements().stream().anyMatch(e -> e.kind() == Fitting.CONTROL_VALVE);
            int score = valve ? 1 : 0;
            if (branch.steps().stream().anyMatch(s -> s.form() instanceof TeeForm)) {
                if (run.end() instanceof Junction) score += 2;
                if (valve && branch.steps().stream().anyMatch(s -> s.form() instanceof FittingForm f && f.kind() == Fitting.CONTROL_VALVE)) score += 1;
            }
            if (branch.finish() instanceof ToPort target) {
                var holder = bound.get(target.port().slot());
                if (run.end() instanceof PortGraph.AtPort at && holder != null && holder.unit() != null && at.port().unit().origin() == holder.unit().origin()
                        && at.port().role() == target.port().role()) score += 4;
            } else if (branch.finish() instanceof Outside && !(run.end() instanceof PortGraph.AtPort at && member(at.port().unit()))) score += 2;
            return score;
        }

        private static List<int[]> permutations(int n) {
            var out = new ArrayList<int[]>();
            permute(new int[n], new boolean[n], 0, out);
            return out;
        }

        private static void permute(int[] order, boolean[] taken, int at, List<int[]> out) {
            if (at == order.length) {
                out.add(order.clone());
                return;
            }
            for (int i = 0; i < order.length; i++) {
                if (taken[i]) continue;
                taken[i] = true;
                order[at] = i;
                permute(order, taken, at + 1, out);
                taken[i] = false;
            }
        }

        /**
         * The step a met element fills, taken out of {@code rest}: the next step (or the next two when they come in either
         * order); else a later step, the ones before it then pending; else, for a unit ({@code orPending}), a step pending
         * from earlier, in the wrong order. A valve is never another line's valve out of order: it is an extra one.
         */
        private @Nullable Hit take(List<Step> rest, Predicate<Form> accepts, boolean orPending) {
            int current = rest.isEmpty() ? 0 : rest.getFirst().anyOrder() && rest.size() > 1 ? 2 : 1;
            for (int k = 0; k < rest.size(); k++) {
                var step = rest.get(k);
                var form = form(step, accepts);
                if (form == null) continue;
                if (k >= current) {
                    pending.addAll(rest.subList(0, k));
                    rest.subList(0, k).clear();
                }
                rest.remove(step);
                return new Hit(step, form, form == step.form() ? State.OK : State.WRONG_KIND);
            }
            if (!orPending) return null;
            for (var step : pending) {
                var form = form(step, accepts);
                if (form == null || bound.containsKey(step.slot())) continue;
                pending.remove(step);
                return new Hit(step, form, State.WRONG_ORDER);
            }
            return null;
        }

        private static @Nullable Form form(Step step, Predicate<Form> accepts) {
            if (accepts.test(step.form())) return step.form();
            for (var other : step.family()) if (accepts.test(other)) return other;
            return null;
        }

        private static boolean accepts(Form form, Unit unit) {
            return form instanceof UnitForm f && f.kind() == unit.kind() && (!f.lyingWithBoot() || unit.lying() && unit.boot());
        }

        /** The line stops: its next step takes the fault, the steps after it wait for another line. */
        private void stop(List<Step> rest, State state, long at, String detail) {
            if (rest.isEmpty()) return;
            fault(rest.getFirst().slot(), state, at, detail);
            pending.addAll(rest);
        }

        /** Every port the blueprint names on a filled slot is needed, whether or not a walk reached it. */
        private void requireNamed() {
            for (var link : blueprint.links()) {
                if (!link.start().afterEntry()) require(new PortSpec(link.start().slot(), link.start().role(), link.start().ordinal()));
                require(link.finish());
                for (var step : link.steps())
                    if (step.form() instanceof TeeForm tee) for (var branch : tee.branches()) require(branch.finish());
            }
        }

        private void require(Finish finish) {
            if (finish instanceof ToPort target) require(target.port());
        }

        private void require(PortSpec spec) {
            var holder = bound.get(spec.slot());
            if (holder != null && holder.unit() != null) required.add(new Key(holder.unit().origin(), spec.role(), spec.ordinal()));
        }

        private boolean member(Unit unit) {
            for (var binding : bound.values()) if (binding.unit() != null && binding.unit().origin() == unit.origin()) return true;
            return false;
        }

        /**
         * Binds a slot. A part found clears the red fault a line that stopped before it left on its slot; a yellow fault met
         * on the way to it (an extra valve) stays.
         */
        private void bind(Slot slot, Binding binding) {
            bound.put(slot, binding);
            var known = faults.get(slot);
            if (known != null && known.state().colour == AssemblyReport.Colour.RED) faults.remove(slot);
            footprint.add(binding.position());
        }

        private void fault(Slot slot, State state, long at, String detail) {
            var known = faults.get(slot);
            if (known == null || state.ordinal() > known.state().ordinal()) faults.put(slot, new Fault(state, at, detail));
        }

        private static State worse(State a, State b) {
            return a.ordinal() >= b.ordinal() ? a : b;
        }

        /** A unit's kind for a fault's text, a drum's lie and boot with it. */
        private static String describe(Unit unit) {
            if (unit.kind() != Kind.DRUM) return unit.kind().name();
            return unit.lying() ? unit.boot() ? "DRUM" : "DRUM_NO_BOOT" : "DRUM_STANDING";
        }

        private AssemblyReport report() {
            requireNamed();
            var slots = new ArrayList<SlotReport>();
            for (var slot : blueprint.slots()) {
                var binding = bound.get(slot);
                var fault = faults.get(slot);
                var state = fault != null ? fault.state() : binding != null ? State.OK : State.MISSING;
                slots.add(new SlotReport(slot, state, binding != null ? binding.position() : NONE, fault != null ? fault.at() : NONE,
                        fault != null ? fault.detail() : ""));
            }
            var ports = new ArrayList<PortReport>();
            var members = new ArrayList<Long>();
            for (var binding : bound.values()) {
                var unit = binding.unit();
                if (unit == null || members.contains(unit.origin())) continue;
                members.add(unit.origin());
                var utilities = binding.form() instanceof UnitForm f ? f.utilities() : Set.<Role>of();
                var seen = new EnumMap<Role, Integer>(Role.class);
                for (var role : unit.nozzles()) {
                    int ordinal = seen.merge(role, 1, Integer::sum) - 1;
                    var key = new Key(unit.origin(), role, ordinal);
                    PortState state;
                    if (used.contains(key)) state = PortState.CONNECTED;
                    else if (required.contains(key)) state = PortState.NEEDED;
                    else if (utilities.contains(role)) state = PortState.UTILITY;
                    else state = PortState.UNUSED;
                    ports.add(new PortReport(unit.origin(), role, ordinal, state));
                }
            }
            footprint.remove(NONE);
            return new AssemblyReport(slots, ports, footprint, members);
        }
    }
}
