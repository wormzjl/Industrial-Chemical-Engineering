package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SideDrawContractTest {
    @Test
    void validatesRatesAndCanonicalizesAnImmutableDistinctTrayList() {
        for (double rate : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new V3SideDrawSpec(1, rate));
        }
        assertThrows(IllegalArgumentException.class, () -> new V3SideDrawSpec(0, 1));
        List<V3SideDrawSpec> draws = new ArrayList<>(List.of(new V3SideDrawSpec(4, 2), new V3SideDrawSpec(1, 3)));
        V3ColumnInput input = input(draws);
        draws.clear();
        assertEquals(List.of(new V3SideDrawSpec(1, 3), new V3SideDrawSpec(4, 2)), input.sideDraws());
        assertThrows(UnsupportedOperationException.class, () -> input.sideDraws().clear());
        assertEquals(input, input(input.sideDraws()));
        assertEquals(input.hashCode(), input(input.sideDraws()).hashCode());
        assertNotEquals(input, input(List.of()));
        assertThrows(IllegalArgumentException.class, () -> input(null));
        assertThrows(IllegalArgumentException.class, () -> input(Arrays.asList((V3SideDrawSpec) null)));
        assertThrows(IllegalArgumentException.class, () -> input(List.of(new V3SideDrawSpec(2, 1), new V3SideDrawSpec(2, 2))));
        var tooMany = java.util.stream.IntStream.rangeClosed(1, V3ColumnInput.MAX_SIDE_DRAWS + 1).mapToObj(tray -> new V3SideDrawSpec(tray, 1)).toList();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> input(tooMany)).getMessage().contains("at most " + V3ColumnInput.MAX_SIDE_DRAWS));
    }

    @Test
    void resolverRejectsBoundaryNodesAndCalculatorReportsGlobalInfeasibility() {
        assertThrows(IllegalArgumentException.class, () -> resolve(input(List.of(new V3SideDrawSpec(5, 1)))));
        V3ColumnInput impossible = input(List.of(new V3SideDrawSpec(2, 100)));
        assertThrows(IllegalArgumentException.class, () -> resolve(impossible));
        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, V3TestInputs.calculate(impossible));
        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
    }

    @Test
    void drawParametersWidenNeighborReferencesWithoutChangingDegreesOfFreedom() {
        V3ColumnProblem plain = resolve(input(List.of()));
        V3ColumnProblem drawn = resolve(input(List.of(new V3SideDrawSpec(2, 5))));
        assertTrue(drawn.hasSideDraws());
        assertEquals(5, drawn.nodeSideDrawMolPerSecond(2));
        assertEquals(plain.degreeOfFreedomLedger().unknownCount(), drawn.degreeOfFreedomLedger().unknownCount());
        assertEquals(plain.degreeOfFreedomLedger().equationCount(), drawn.degreeOfFreedomLedger().equationCount());
        assertTrue(drawn.degreeOfFreedomLedger().isValid());
        var equation = drawn.degreeOfFreedomLedger().equations().stream().filter(row -> row.id().node() == 3
                && row.id().component() == 0 && row.id().family() == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE)
                .findFirst().orElseThrow();
        assertTrue(equation.referencedUnknowns().contains(new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 2, 1)));
        assertNotEquals(V3InputDigest.of(plain, "mesh-v1", "ideal-v1", "m0-v1"),
                V3InputDigest.of(drawn, "mesh-v1", "ideal-v1", "m0-v1"));
    }

    @Test
    void emptyDrawListPreservesTheLegacyDigestByteStream() {
        // Independently encoded from the pre-draw named-field stream, with fixed revision identifiers.
        assertEquals("a5f1f262a370c2cbea3b87a382464865d9d040f184e739f8574539ed58842301",
                V3InputDigest.of(resolve(input(List.of())), "mesh-v1", "ideal-v1", "m0-v1").hexadecimalSha256());
    }

    @Test
    void intermediateStageGeometryMapsOnlyTheFeedAndStripsAuthoredDraws() {
        V3ColumnInput mapped = V3ContinuationLadder.withStageGeometry(input(List.of(
                new V3SideDrawSpec(1, 2), new V3SideDrawSpec(2, 3), new V3SideDrawSpec(4, 5))), 2);
        assertEquals(List.of(), mapped.sideDraws());
        assertEquals(1, mapped.feedStageNumber());
    }

    private static V3ColumnProblem resolve(V3ColumnInput input) {
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    static V3ColumnInput input(List<V3SideDrawSpec> draws) {
        return new V3ColumnInput(1, "test:ideal_binary", "test:baseline", new V3ComponentBasis(List.of("methane", "n-pentane")),
                new double[] {40, 60}, 450, 4, 2, 250_000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(4.17), new V3ColumnSpecification.ReboilerDuty(8_000_000)), draws);
    }

    // Merged from V3SideDrawsTest.
    @Test
    void centralizesLiquidTotalAndWithdrawalArithmetic() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(
                V3SideDrawContractTest.input(java.util.List.of()), V3CondenserPhaseBranch.TWO_PHASE);
        double[][] liquid = new double[problem.topology().nodeCount()][2];
        double[][] vapor = new double[problem.topology().nodeCount()][2];
        double[] temperatures = new double[problem.topology().nodeCount()];
        for (int node = 0; node < temperatures.length; node++) {
            temperatures[node] = 400;
            liquid[node] = new double[] {30, 70};
            vapor[node] = new double[] {40, 60};
        }
        V3DryMeshState state = new V3DryMeshState(problem.topology(), 2, liquid, vapor, temperatures);
        V3SideDrawSpec.Withdrawal withdrawal = V3SideDrawSpec.withdrawal(state, 2, 25);
        assertEquals(100, withdrawal.liquidTotalMolPerSecond());
        assertEquals(0.25, withdrawal.fraction());
        assertThrows(IllegalArgumentException.class, () -> V3SideDrawSpec.withdrawal(state, 2, -1));
    }
}
