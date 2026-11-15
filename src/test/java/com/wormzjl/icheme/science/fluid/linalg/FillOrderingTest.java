package com.wormzjl.icheme.science.fluid.linalg;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The Newton's fill-reducing ordering (batch 2026-11-15-fluid-step-cost, S1 F3, {@link SparseLuSolver#prepareFillOrdering}):
 * minimum degree on the symmetric graph of the pattern. It is a permutation, the same array on every call, and the
 * identity under 128 unknowns; on a chain of dense blocks (the shape of a network of node blocks) its L and U hold no more
 * entries than those of the transport's reverse Cuthill-McKee ({@link SparseLuSolver#prepareOrdering}), read the way the S0
 * counter reads them ({@code SolverDiagnostics.luFactorEntries}, the entries {@code UpLookingLu} stores after each
 * factorization), and both solve a right-hand side to the same solution. The chain's values are diagonally dominant by
 * columns after the row equilibration (diagonal 100, every other entry at most 1 in size, at most 22 of them a column), so
 * the partial pivoting keeps each pivot on the diagonal and the count is the ordering's own fill.
 */
class FillOrderingTest {
    /** {@code blocks} dense blocks of {@code width} unknowns in a chain; each block is coupled to the next by three entries in
     * each direction, between three of its unknowns and three of the next block's. */
    private static SparseMatrix blockChain(int blocks,int width) {
        int n=blocks*width;var random=new Random(1234L+n);
        var columns=new ArrayList<TreeMap<Integer,Double>>();for(int c=0;c<n;c++)columns.add(new TreeMap<>());
        for(int b=0;b<blocks;b++)for(int i=0;i<width;i++)for(int j=0;j<width;j++)
            columns.get(b*width+j).put(b*width+i,i==j?100:2*random.nextDouble()-1);
        for(int b=0;b+1<blocks;b++)for(int k=0;k<3;k++) {
            int from=b*width+(3*k+5)%width,to=(b+1)*width+(7*k+2)%width;
            columns.get(from).put(to,2*random.nextDouble()-1);columns.get(to).put(from,2*random.nextDouble()-1);
        }
        int[] offsets=new int[n+1];var rows=new ArrayList<Integer>();var values=new ArrayList<Double>();
        for(int c=0;c<n;c++){for(var entry:columns.get(c).entrySet()){rows.add(entry.getKey());values.add(entry.getValue());}offsets[c+1]=rows.size();}
        return new SparseMatrix(n,offsets,rows.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToDouble(Double::doubleValue).toArray());
    }
    private static void assertPermutation(int[] permutation,String name) {
        boolean[] seen=new boolean[permutation.length];
        for(int position:permutation){assertTrue(position>=0&&position<permutation.length,name+": a position in range");assertFalse(seen[position],name+": each position once");seen[position]=true;}
    }

    @Test void theFillOrderingIsTheSamePermutationOnEveryCallAndTheIdentityUnder128() {
        var chain=blockChain(8,20);
        int[] first=SparseLuSolver.prepareFillOrdering(chain).permutation(),second=SparseLuSolver.prepareFillOrdering(chain).permutation();
        assertEquals(160,first.length);
        assertPermutation(first,"160 unknowns");
        assertArrayEquals(first,second,"two calls give the same order");
        int[] identity=new int[first.length];for(int i=0;i<identity.length;i++)identity[i]=i;
        // Block 0's unknowns coupled to block 1 have one neighbour more than the rest of the block, so they come after it.
        assertFalse(Arrays.equals(identity,first),"a chain of 160 unknowns is ordered");
        var small=blockChain(6,20);int[] under=SparseLuSolver.prepareFillOrdering(small).permutation();
        int[] smallIdentity=new int[120];for(int i=0;i<smallIdentity.length;i++)smallIdentity[i]=i;
        assertArrayEquals(smallIdentity,under,"the identity under 128 unknowns");
    }

    private record Factored(long entries,double[] solution) {}
    private static Factored factored(SparseMatrix matrix,SparseLuSolver.Ordering ordering,double[] rhs) {
        boolean enabled=SolverDiagnostics.ENABLED;
        try {
            SolverDiagnostics.ENABLED=true;
            long before=SolverDiagnostics.luFactorEntries.sum();
            var factorization=SparseLuSolver.factor(matrix,ordering);
            long entries=SolverDiagnostics.luFactorEntries.sum()-before;
            return new Factored(entries,factorization.solve(rhs));
        } finally {SolverDiagnostics.ENABLED=enabled;}
    }
    @Test void onAChainOfDenseBlocksTheFillOrderingStoresNoMoreThanTheBandOrdering() {
        var chain=blockChain(8,20);int n=chain.size();
        double[] rhs=new double[n];
        for(int c=0;c<n;c++)for(int e=chain.columnStart(c);e<chain.columnEnd(c);e++)rhs[chain.rowAt(e)]+=chain.valueAt(e)*(1+.01*c);
        var fill=factored(chain,SparseLuSolver.prepareFillOrdering(chain),rhs);
        var band=factored(chain,SparseLuSolver.prepareOrdering(chain),rhs);
        System.out.printf(Locale.ROOT,"FILL_ORDERING chain of 8 dense blocks of 20: L and U entries, minimum degree %d, reverse Cuthill-McKee %d%n",fill.entries(),band.entries());
        assertTrue(fill.entries()>0&&band.entries()>0,"the factorizations are counted");
        assertTrue(fill.entries()<=band.entries(),"minimum degree stores "+fill.entries()+" entries, reverse Cuthill-McKee "+band.entries());
        double largest=0,difference=0;
        for(int i=0;i<n;i++){largest=Math.max(largest,Math.abs(band.solution()[i]));difference=Math.max(difference,Math.abs(fill.solution()[i]-band.solution()[i]));}
        assertTrue(difference<=1e-12*largest,"the two orders solve to the same solution: "+difference+" of "+largest);
        for(int i=0;i<n;i++)assertEquals(1+.01*i,fill.solution()[i],1e-10,"the solution of the right-hand side");
    }
}
