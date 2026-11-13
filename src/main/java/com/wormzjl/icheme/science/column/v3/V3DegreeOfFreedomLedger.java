package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ControlledQuantity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Independently enumerates the V3 unknowns, residuals, controls, and structural-rank evidence.
 *
 * <p>This is intentionally a topology/contract artifact.  It does not assemble production
 * residual values or use a numerical solver.</p>
 */
public final class V3DegreeOfFreedomLedger {
    private final V3ColumnTopology topology;
    private final int componentCount;
    private final V3TruncationSupport truncationSupport;
    private final V3WetTraySet wetTraySet;
    private final List<V3ColumnSpecification> specifications;
    private final List<Unknown> unknowns;
    /** The equations' ids in ledger order, shared by every residual of this ledger and never written. */
    private final EquationId[] equationIds;
    /**
     * Each equation's referenced unknowns as indexes into {@link #unknowns}, in {@link UnknownId} order (the order the
     * matching visits them). The {@link Equation} records are built from these only when {@link #equations} is read.
     */
    private final int[][] references;
    private volatile List<Equation> equations;
    private final List<ContractDiagnostic> diagnostics;
    private final int structuralRank;

    private V3DegreeOfFreedomLedger(
            V3ColumnTopology topology, int componentCount, List<V3ColumnSpecification> specifications,
            List<Unknown> unknowns, EquationId[] equationIds, int[][] references,
            List<ContractDiagnostic> diagnostics, int structuralRank, V3TruncationSupport truncationSupport,
            V3WetTraySet wetTraySet) {
        this.topology = topology;
        this.componentCount = componentCount;
        this.truncationSupport = truncationSupport;
        this.wetTraySet = wetTraySet;
        this.specifications = List.copyOf(specifications);
        this.unknowns = List.copyOf(unknowns);
        this.equationIds = equationIds;
        this.references = references;
        this.diagnostics = List.copyOf(diagnostics);
        this.structuralRank = structuralRank;
    }

    static V3DegreeOfFreedomLedger create(
            V3ColumnTopology topology,
            int componentCount,
            List<V3ColumnSpecification> specifications,
            V3CondenserComponentPhases condenserComponentPhases) {
        return create(topology, componentCount, specifications, condenserComponentPhases,
                V3TruncationSupport.identity(topology, componentCount));
    }

    static V3DegreeOfFreedomLedger create(
            V3ColumnTopology topology,
            int componentCount,
            List<V3ColumnSpecification> specifications,
            V3CondenserComponentPhases condenserComponentPhases,
            V3TruncationSupport truncationSupport) {
        return create(topology, componentCount, specifications, condenserComponentPhases, truncationSupport, List.of());
    }

    static V3DegreeOfFreedomLedger create(
            V3ColumnTopology topology, int componentCount, List<V3ColumnSpecification> specifications,
            V3CondenserComponentPhases condenserComponentPhases, V3TruncationSupport truncationSupport,
            List<V3SideDrawSpec> sideDraws) {
        return create(topology, componentCount, specifications, condenserComponentPhases, truncationSupport,
                sideDraws, V3WetTraySet.dry(Objects.requireNonNull(topology, "topology")));
    }

    static V3DegreeOfFreedomLedger create(
            V3ColumnTopology topology, int componentCount, List<V3ColumnSpecification> specifications,
            V3CondenserComponentPhases condenserComponentPhases, V3TruncationSupport truncationSupport,
            List<V3SideDrawSpec> sideDraws, V3WetTraySet wetTraySet) {
        topology = Objects.requireNonNull(topology, "topology");
        condenserComponentPhases = Objects.requireNonNull(condenserComponentPhases, "condenserComponentPhases");
        truncationSupport = Objects.requireNonNull(truncationSupport, "truncationSupport");
        wetTraySet = Objects.requireNonNull(wetTraySet, "wetTraySet");
        if (!wetTraySet.topology().equals(topology)) {
            throw new IllegalArgumentException("V3 wet-tray set does not match the ledger topology");
        }
        truncationSupport.requireCompatible(topology, componentCount);
        if (componentCount < 1 || componentCount > V3ComponentBasis.MAX_COMPONENTS) {
            throw new IllegalArgumentException("V3 component count is outside the supported contract range");
        }
        specifications = List.copyOf(Objects.requireNonNull(specifications, "specifications"));
        if (specifications.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 specifications cannot contain null");
        }

        List<ContractDiagnostic> diagnostics = specificationDiagnostics(topology, specifications);
        UnknownIndex index = new UnknownIndex(topology.nodeCount(), componentCount);
        List<Unknown> unknowns = enumerateUnknowns(topology, componentCount, condenserComponentPhases,
                truncationSupport, wetTraySet, index);
        List<EquationId> ids = new ArrayList<>();
        List<int[]> rows = new ArrayList<>();
        enumerateEquations(topology, componentCount, index, ids, rows, condenserComponentPhases, truncationSupport,
                sideDraws, wetTraySet);
        EquationId[] equationIds = ids.toArray(EquationId[]::new);
        int[][] references = rows.toArray(int[][]::new);
        // Every reference is an active unknown by construction, so only an empty row can fail the reference check.
        for (int equation = 0; equation < equationIds.length; equation++) {
            if (references[equation].length == 0) {
                diagnostics.add(new ContractDiagnostic("EQUATION_WITHOUT_UNKNOWN",
                        "V3 equation " + equationIds[equation] + " does not reference an active unknown"));
            }
        }
        int structuralRank = maximumBipartiteMatching(references, unknowns.size());
        if (unknowns.size() != equationIds.length) {
            diagnostics.add(new ContractDiagnostic("DOF_COUNT_MISMATCH", "V3 unknown and equation counts differ"));
        }
        if (structuralRank != equationIds.length) {
            diagnostics.add(new ContractDiagnostic("STRUCTURAL_RANK_DEFICIENT",
                    "V3 equation-to-unknown sparsity matching is rank deficient"));
        }
        return new V3DegreeOfFreedomLedger(topology, componentCount, specifications, unknowns, equationIds, references,
                diagnostics, structuralRank, truncationSupport, wetTraySet);
    }

    V3TruncationSupport truncationSupport() {
        return truncationSupport;
    }

    V3WetTraySet wetTraySet() {
        return wetTraySet;
    }

    public List<Unknown> unknowns() {
        return unknowns;
    }

    /**
     * The equations with their referenced unknowns, built on the first read (contract tests and diagnostic text read
     * them; the solve reads {@link #equationIds} and {@link #equationCount}).
     */
    public List<Equation> equations() {
        List<Equation> built = equations;
        if (built == null) {
            List<Equation> list = new ArrayList<>(equationIds.length);
            for (int equation = 0; equation < equationIds.length; equation++) {
                List<UnknownId> referenced = new ArrayList<>(references[equation].length);
                for (int unknown : references[equation]) referenced.add(unknowns.get(unknown).id());
                list.add(new Equation(equationIds[equation], referenced));
            }
            equations = built = List.copyOf(list);
        }
        return built;
    }

    /** The equations' ids in ledger order: the shared array, which a caller never writes. */
    EquationId[] equationIds() {
        return equationIds;
    }

    public List<ContractDiagnostic> diagnostics() {
        return diagnostics;
    }

    public int unknownCount() {
        return unknowns.size();
    }

    public int equationCount() {
        return equationIds.length;
    }

    public int structuralRank() {
        return structuralRank;
    }

    public boolean hasFullStructuralRank() {
        return unknownCount() == equationCount() && structuralRank == equationCount();
    }

    public boolean isValid() {
        return diagnostics.isEmpty() && hasFullStructuralRank();
    }

    /** Stable compact diagnostic intended for tests and a future V3 UI. */
    public String humanReadableDiagnostic() {
        String codes = diagnostics.isEmpty() ? "none"
                : diagnostics.stream().map(ContractDiagnostic::code).distinct().sorted().reduce((a, b) -> a + "," + b)
                .orElseThrow();
        return "topology=" + topology + ", components=" + componentCount + ", unknowns=" + unknownCount()
                + ", equations=" + equationCount() + ", structuralRank=" + structuralRank + ", diagnostics=" + codes;
    }

    private static List<ContractDiagnostic> specificationDiagnostics(
            V3ColumnTopology topology, List<V3ColumnSpecification> specifications) {
        Map<ControlledQuantity, V3ColumnSpecification> controls = new EnumMap<>(ControlledQuantity.class);
        List<ContractDiagnostic> diagnostics = new ArrayList<>();
        for (V3ColumnSpecification specification : specifications) {
            if (controls.putIfAbsent(specification.controlledQuantity(), specification) != null) {
                diagnostics.add(new ContractDiagnostic("DUPLICATE_CONTROL",
                        "V3 controls " + specification.controlledQuantity() + " more than once"));
            }
        }
        for (ControlledQuantity required : EnumSet.allOf(ControlledQuantity.class)) {
            if (!controls.containsKey(required)) {
                diagnostics.add(new ContractDiagnostic("MISSING_CONTROL", "V3 is missing " + required));
            }
        }
        V3ColumnSpecification reflux = controls.get(ControlledQuantity.ORGANIC_REFLUX_RATIO);
        if (topology.condenserPhaseBranch() == V3CondenserPhaseBranch.VAPOR_ONLY
                && reflux instanceof V3ColumnSpecification.OrganicRefluxRatio ratio && ratio.ratio() > 0.0) {
            diagnostics.add(new ContractDiagnostic("VAPOR_ONLY_WITH_POSITIVE_REFLUX",
                    "A vapor-only condenser cannot supply positive organic reflux"));
        }
        return diagnostics;
    }

    private static List<Unknown> enumerateUnknowns(
            V3ColumnTopology topology, int componentCount, V3CondenserComponentPhases condenserComponentPhases,
            V3TruncationSupport truncationSupport, V3WetTraySet wetTraySet, UnknownIndex index) {
        List<Unknown> unknowns = new ArrayList<>();
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                if (truncationSupport.hasLiquidUnknown(condenserComponentPhases, node, component)) {
                    index.put(UnknownFamily.LIQUID_COMPONENT_FLOW, node, component, unknowns.size());
                    unknowns.add(new Unknown(new UnknownId(UnknownFamily.LIQUID_COMPONENT_FLOW, node, component)));
                }
                if (truncationSupport.hasVaporUnknown(node, component)) {
                    index.put(UnknownFamily.VAPOR_COMPONENT_FLOW, node, component, unknowns.size());
                    unknowns.add(new Unknown(new UnknownId(UnknownFamily.VAPOR_COMPONENT_FLOW, node, component)));
                }
            }
            if (topology.hasTemperatureUnknown(node)) {
                index.put(UnknownFamily.TEMPERATURE, node, -1, unknowns.size());
                unknowns.add(new Unknown(new UnknownId(UnknownFamily.TEMPERATURE, node, -1)));
            }
            // Last in the node's block, so the contiguous stage layout gains exactly one unknown here and
            // exactly one row (the saturation equation) in the matching equation block.
            if (wetTraySet.hasFreeWaterUnknown(node)) {
                index.put(UnknownFamily.FREE_WATER_FLOW, node, -1, unknowns.size());
                unknowns.add(new Unknown(new UnknownId(UnknownFamily.FREE_WATER_FLOW, node, -1)));
            }
        }
        return unknowns;
    }

    /**
     * Each unknown's index in the ledger, by its {@link UnknownId} order key (P3 of batch 2026-11-13-codebase-review): the
     * key orders as {@link UnknownId#compareTo} does (family, then node, then component), so sorting keys sorts ids.
     */
    private static final class UnknownIndex {
        private final int nodeCount;
        private final int componentSlots;
        private final int[] indexes;

        UnknownIndex(int nodeCount, int componentCount) {
            this.nodeCount = nodeCount;
            this.componentSlots = componentCount + 1;
            this.indexes = new int[UnknownFamily.values().length * nodeCount * componentSlots];
            java.util.Arrays.fill(indexes, -1);
        }

        int key(UnknownFamily family, int node, int component) {
            return (family.ordinal() * nodeCount + node) * componentSlots + component + 1;
        }

        void put(UnknownFamily family, int node, int component, int index) {
            indexes[key(family, node, component)] = index;
        }

        int index(int key) {
            return indexes[key];
        }
    }

    /** One equation's references while it is enumerated: order keys of active unknowns, then sorted and made unique. */
    private static final class References {
        private final UnknownIndex index;
        private int[] keys = new int[64];
        private int size;

        References(UnknownIndex index) {
            this.index = index;
        }

        References clear() {
            size = 0;
            return this;
        }

        /** Adds the unknown if it is active, as the record set's {@code addIfActive} did. */
        void addIfActive(UnknownFamily family, int node, int component) {
            if (node < 0 || node >= index.nodeCount || component + 1 >= index.componentSlots) return;
            int key = index.key(family, node, component);
            if (index.index(key) < 0) return;
            if (size == keys.length) keys = java.util.Arrays.copyOf(keys, 2 * size);
            keys[size++] = key;
        }

        /** The unknown indexes in {@link UnknownId} order, each once: the sorted set the records held. */
        int[] ordered() {
            java.util.Arrays.sort(keys, 0, size);
            int unique = 0;
            for (int i = 0; i < size; i++) if (i == 0 || keys[i] != keys[i - 1]) keys[unique++] = keys[i];
            int[] result = new int[unique];
            for (int i = 0; i < unique; i++) result[i] = index.index(keys[i]);
            return result;
        }
    }

    private static void enumerateEquations(
            V3ColumnTopology topology, int componentCount, UnknownIndex index, List<EquationId> ids, List<int[]> rows,
            V3CondenserComponentPhases condenserComponentPhases, V3TruncationSupport truncationSupport,
            List<V3SideDrawSpec> sideDraws, V3WetTraySet wetTraySet) {
        boolean[] drawTrays = new boolean[topology.nodeCount()];
        for (V3SideDrawSpec draw : sideDraws) drawTrays[draw.trayNumber()] = true;
        References references = new References(index);
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                if (!truncationSupport.retains(node, component)) continue;
                // A one-phase point keeps its material row: the component is conserved into the phase that
                // is present, and only its equilibrium row goes away with the absent phase.
                ids.add(new EquationId(EquationFamily.COMPONENT_MATERIAL_BALANCE, node, component));
                rows.add(materialReferences(topology, node, component, componentCount, drawTrays, references.clear()));
                if (truncationSupport.hasEquilibriumRow(condenserComponentPhases, node, component)) {
                    ids.add(new EquationId(EquationFamily.VAPOR_LIQUID_EQUILIBRIUM, node, component));
                    rows.add(equilibriumReferences(node, componentCount, references.clear()));
                }
            }
            if (topology.hasEnergyEquation(node)) {
                ids.add(new EquationId(EquationFamily.ENERGY_BALANCE, node, -1));
                rows.add(energyReferences(topology, node, componentCount, references.clear()));
            }
            if (wetTraySet.hasFreeWaterUnknown(node)) {
                ids.add(new EquationId(EquationFamily.WATER_SATURATION, node, -1));
                rows.add(waterSaturationReferences(node, componentCount, references.clear()));
            }
        }
    }

    private static int[] materialReferences(
            V3ColumnTopology topology, int node, int component, int componentCount, boolean[] drawTrays,
            References references) {
        references.addIfActive(UnknownFamily.LIQUID_COMPONENT_FLOW, node, component);
        references.addIfActive(UnknownFamily.VAPOR_COMPONENT_FLOW, node, component);
        if (node > topology.condenserNode()) {
            references.addIfActive(UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, component);
            if (drawTrays[node - 1]) {
                for (int k = 0; k < componentCount; k++) {
                    references.addIfActive(UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, k);
                }
            }
        }
        if (node < topology.reboilerNode()) {
            references.addIfActive(UnknownFamily.VAPOR_COMPONENT_FLOW, node + 1, component);
        }
        return references.ordered();
    }

    private static int[] equilibriumReferences(int node, int componentCount, References references) {
        for (int phaseComponent = 0; phaseComponent < componentCount; phaseComponent++) {
            references.addIfActive(UnknownFamily.LIQUID_COMPONENT_FLOW, node, phaseComponent);
            references.addIfActive(UnknownFamily.VAPOR_COMPONENT_FLOW, node, phaseComponent);
        }
        references.addIfActive(UnknownFamily.TEMPERATURE, node, -1);
        // The water-dilution term of a wet column reads this node's water vapour, which is the authored
        // steam profile plus the free water the tray above sheds, and on tray one less the free water a
        // decanting drum takes as carry-over (a structural superset: the ledger does not see the drum's regime).
        if (node > 0) references.addIfActive(UnknownFamily.FREE_WATER_FLOW, node - 1, -1);
        if (node == 1) references.addIfActive(UnknownFamily.FREE_WATER_FLOW, 1, -1);
        return references.ordered();
    }

    private static int[] energyReferences(
            V3ColumnTopology topology, int node, int componentCount, References references) {
        for (int relatedNode = Math.max(topology.condenserNode(), node - 1);
                relatedNode <= Math.min(topology.reboilerNode(), node + 1); relatedNode++) {
            for (int component = 0; component < componentCount; component++) {
                references.addIfActive(UnknownFamily.LIQUID_COMPONENT_FLOW, relatedNode, component);
                references.addIfActive(UnknownFamily.VAPOR_COMPONENT_FLOW, relatedNode, component);
            }
            // Free water in from above, out below, and the water vapour of both this node and the one below.
            references.addIfActive(UnknownFamily.FREE_WATER_FLOW, relatedNode, -1);
        }
        references.addIfActive(UnknownFamily.TEMPERATURE, node, -1);
        return references.ordered();
    }

    /**
     * {@code ln(P_n W_n / ((V_hc,n + W_n) P_sat(T_n))) = 0}: this node's hydrocarbon vapour, its temperature,
     * and — through {@code W_n} — the free water of the tray directly above. The free water of the node's
     * <em>own</em> tray is deliberately absent below tray one: it cancels out of {@code W_n} exactly, because what
     * condenses here arrives at the tray below and comes straight back up. Tray one's row references its own
     * free water, which a decanting drum takes as carry-over ({@link V3ColumnProblem#carriesTopTrayWaterOver});
     * the reference is a structural superset where the drum cannot decant.
     */
    private static int[] waterSaturationReferences(int node, int componentCount, References references) {
        for (int component = 0; component < componentCount; component++) {
            references.addIfActive(UnknownFamily.VAPOR_COMPONENT_FLOW, node, component);
        }
        references.addIfActive(UnknownFamily.TEMPERATURE, node, -1);
        if (node > 0) references.addIfActive(UnknownFamily.FREE_WATER_FLOW, node - 1, -1);
        if (node == 1) references.addIfActive(UnknownFamily.FREE_WATER_FLOW, 1, -1);
        return references.ordered();
    }

    /**
     * Kuhn's maximum matching of equations to unknowns: equations in ledger order, each visiting its references in
     * {@link UnknownId} order. The references arrive as unknown indexes in that order (P3 of batch
     * 2026-11-13-codebase-review), so the augmenting order and the matching are those the records gave.
     */
    private static int maximumBipartiteMatching(int[][] references, int unknownCount) {
        int[] equationForUnknown = new int[unknownCount];
        java.util.Arrays.fill(equationForUnknown, -1);
        int[] visitedAt = new int[unknownCount];
        int matched = 0;
        for (int equation = 0; equation < references.length; equation++) {
            if (augment(equation, references, equationForUnknown, visitedAt, equation + 1)) matched++;
        }
        return matched;
    }

    private static boolean augment(
            int equation, int[][] references, int[] equationForUnknown, int[] visitedAt, int search) {
        for (int unknown : references[equation]) {
            if (visitedAt[unknown] == search) continue;
            visitedAt[unknown] = search;
            if (equationForUnknown[unknown] == -1
                    || augment(equationForUnknown[unknown], references, equationForUnknown, visitedAt, search)) {
                equationForUnknown[unknown] = equation;
                return true;
            }
        }
        return false;
    }

    /** A stable, UI-safe contract diagnostic emitted before numerical work is admitted. */
    public record ContractDiagnostic(String code, String detail) {
        public ContractDiagnostic {
            code = requireBounded(code, "code", 64);
            detail = requireBounded(detail, "detail", 512);
        }

        private static String requireBounded(String value, String name, int maximumLength) {
            value = Objects.requireNonNull(value, name);
            if (value.isBlank() || value.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is blank or exceeds the bounded contract");
            }
            return value;
        }
    }

    /** Stable semantic identity for one numerical unknown. */
    public record UnknownId(UnknownFamily family, int node, int component) implements Comparable<UnknownId> {
        public UnknownId {
            family = Objects.requireNonNull(family, "family");
            boolean nodeScoped = family == UnknownFamily.TEMPERATURE || family == UnknownFamily.FREE_WATER_FLOW;
            if (node < 0 || component < -1 || (nodeScoped && component != -1) || (!nodeScoped && component < 0)) {
                throw new IllegalArgumentException("Invalid V3 unknown semantic ID");
            }
        }

        @Override
        public int compareTo(UnknownId other) {
            int familyComparison = family.compareTo(other.family);
            if (familyComparison != 0) return familyComparison;
            int nodeComparison = Integer.compare(node, other.node);
            return nodeComparison != 0 ? nodeComparison : Integer.compare(component, other.component);
        }
    }

    /** Stable semantic identity for one residual equation. */
    public record EquationId(EquationFamily family, int node, int component) {
        public EquationId {
            family = Objects.requireNonNull(family, "family");
            boolean nodeScoped = family == EquationFamily.ENERGY_BALANCE || family == EquationFamily.WATER_SATURATION;
            if (node < 0 || component < -1 || (nodeScoped && component != -1) || (!nodeScoped && component < 0)) {
                throw new IllegalArgumentException("Invalid V3 equation semantic ID");
            }
        }
    }

    /** One unknown with no numerical value attached. */
    public record Unknown(UnknownId id) {
        public Unknown {
            id = Objects.requireNonNull(id, "id");
        }
    }

    /** One residual row and its structural references to active unknowns. */
    public record Equation(EquationId id, List<UnknownId> referencedUnknowns) {
        public Equation {
            id = Objects.requireNonNull(id, "id");
            referencedUnknowns = List.copyOf(referencedUnknowns);
            if (referencedUnknowns.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("V3 equation references cannot contain null");
            }
        }
    }

    public enum UnknownFamily {
        LIQUID_COMPONENT_FLOW,
        VAPOR_COMPONENT_FLOW,
        TEMPERATURE,
        /** Aqueous liquid leaving a wet tray downward; present only on the trays of the frozen wet set. */
        FREE_WATER_FLOW
    }

    public enum EquationFamily {
        COMPONENT_MATERIAL_BALANCE,
        VAPOR_LIQUID_EQUILIBRIUM,
        ENERGY_BALANCE,
        /** {@code ln(P_n W_n / ((V_hc,n + W_n) P_sat(T_n))) = 0} on a wet tray. */
        WATER_SATURATION
    }
}
