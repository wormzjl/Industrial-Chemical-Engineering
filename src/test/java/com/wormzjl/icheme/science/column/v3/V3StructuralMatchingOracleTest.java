package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class V3StructuralMatchingOracleTest {
    @Test
    void matchingAgreesWithExhaustiveSubsetOracleForSparseAndDeficientGraphs() throws Exception {
        // The matching on int tables (P3 of batch 2026-11-13-codebase-review): each row's unknown indexes in id order.
        var matching = V3DegreeOfFreedomLedger.class.getDeclaredMethod("maximumBipartiteMatching", int[][].class, int.class);
        matching.setAccessible(true);
        Random random = new Random(20260909);
        for (int sample = 0; sample < 250; sample++) {
            int columns = 1 + random.nextInt(7);
            int rows = 1 + random.nextInt(7);
            int[] edges = new int[rows];
            List<V3DegreeOfFreedomLedger.Unknown> unknowns = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                unknowns.add(new V3DegreeOfFreedomLedger.Unknown(new V3DegreeOfFreedomLedger.UnknownId(
                        V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 0, column)));
            }
            int[][] references = new int[rows][];
            for (int row = 0; row < rows; row++) {
                List<Integer> referenced = new ArrayList<>();
                for (int column = 0; column < columns; column++) {
                    if (random.nextInt(3) == 0) {
                        edges[row] |= 1 << column;
                        referenced.add(column);
                    }
                }
                references[row] = referenced.stream().mapToInt(Integer::intValue).toArray();
            }
            assertEquals(exhaustive(edges, 0, 0), matching.invoke(null, references, unknowns.size()), "graph " + sample);
        }
    }

    private static int exhaustive(int[] edges, int row, int used) {
        if (row == edges.length) return 0;
        int best = exhaustive(edges, row + 1, used);
        for (int remaining = edges[row] & ~used; remaining != 0; remaining &= remaining - 1) {
            int column = Integer.lowestOneBit(remaining);
            best = Math.max(best, 1 + exhaustive(edges, row + 1, used | column));
        }
        return best;
    }
}
