package com.wormzjl.icheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.science.fluid.linalg.*;
import java.util.*;

class SparseNewtonTest {
    @Test void aReusedDerivativeIsOnlyAGuessAndStillConvergesAfterInputChanges() {
        double[] target={2};var equations=new SparseNewton.Equations(){
            public int size(){return 1;}
            public int[][] columnRows(){return new int[][]{{0}};}
            public double[] residual(double[] x){return new double[]{Math.exp(x[0])-target[0]};}
        };
        var workspace=new SparseNewton.Workspace();var first=SparseNewton.solve(equations,new double[]{0},SparseNewton.Settings.defaults(),()->{},workspace);
        target[0]=2.01;var warm=SparseNewton.solve(equations,first.variables(),SparseNewton.Settings.defaults(),()->{},workspace);
        assertEquals(Math.log(2.01),warm.variables()[0],1e-8);
        target[0]=1000;var changed=SparseNewton.solve(equations,warm.variables(),SparseNewton.Settings.defaults(),()->{},workspace);
        assertEquals(Math.log(1000),changed.variables()[0],1e-8);
    }
    @Test void coupledNonlinearSystemConvergesWithoutMutatingTheInitialGuess() {
        var equations=new SparseNewton.Equations(){
            public int size(){return 2;}
            public int[][] columnRows(){return new int[][]{{0,1},{0,1}};}
            public double[] residual(double[] x){return new double[]{x[0]*x[0]+x[1]-5,x[0]+x[1]*x[1]-5};}
        };
        double[] initial={1,1};var result=SparseNewton.solve(equations,initial,SparseNewton.Settings.defaults(),()->{});
        double root=(-1+Math.sqrt(21))/2;assertArrayEquals(new double[]{root,root},result.variables(),1e-8);
        assertArrayEquals(new double[]{1,1},initial);assertTrue(result.residualNorm()<1e-8);
    }
    @Test void structuralColoringBatchesIndependentEquationsAndCancellationPropagates() {
        var equations=new SparseNewton.Equations(){
            public int size(){return 100;}
            public int[][] columnRows(){int[][] rows=new int[100][1];for(int i=0;i<100;i++)rows[i][0]=i;return rows;}
            public double[] residual(double[] x){double[] r=new double[100];for(int i=0;i<100;i++)r[i]=Math.exp(x[i])-2;return r;}
        };
        var result=SparseNewton.solve(equations,new double[100],SparseNewton.Settings.defaults(),()->{});
        assertEquals(1,result.colors());assertEquals(100,result.jacobianNonzeros());assertTrue(result.residualEvaluations()<30);
        assertEquals(Math.log(2),result.variables()[73],1e-8);
        assertThrows(CancellationException.class,()->SparseNewton.solve(equations,new double[100],SparseNewton.Settings.defaults(),()->{throw new CancellationException();}));
    }
    @Test void infeasibleEquationsCannotBeReportedAsConverged() {
        var equations=new SparseNewton.Equations(){
            public int size(){return 1;}
            public int[][] columnRows(){return new int[][]{{0}};}
            public double[] residual(double[] x){return new double[]{x[0]*x[0]+1};}
        };
        assertThrows(SparseNewton.Nonconvergence.class,()->SparseNewton.solve(equations,new double[]{1},SparseNewton.Settings.defaults(),()->{}));
    }

    // ---------------- from StructuralReuseTest ----------------
    @Test void forkReusesStructureButMustBuildFreshNumericFactorsForChangedCoefficients() {
        var workspace=new SparseNewton.Workspace();var settings=new SparseNewton.Settings(1,1e-12,1,8);
        var first=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){return new int[][]{{0}};}public double[] residual(double[] x){return new double[]{2*x[0]-4};}};
        assertEquals(2,SparseNewton.solve(first,new double[]{0},settings,()->{},workspace).variables()[0]);
        var second=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){throw new AssertionError("Sparsity should already be compiled");}public double[] residual(double[] x){return new double[]{4*x[0]-16};}};
        assertEquals(4,SparseNewton.solve(second,new double[]{0},settings,()->{},workspace.forkStructure()).variables()[0]);
        assertEquals(2,SparseNewton.solve(first,new double[]{0},settings,()->{},workspace).variables()[0]);
    }
    @Test void reusedPermutationRemainsValidWhenNumericZerosBecomeNonzero() {
        int n=130;int[] diagonalOffsets=new int[n+1],diagonalRows=new int[n];double[] diagonal=new double[n];
        for(int i=0;i<n;i++){diagonalOffsets[i]=i;diagonalRows[i]=i;diagonal[i]=2;}diagonalOffsets[n]=n;
        var ordering=SparseLuSolver.prepareOrdering(new SparseMatrix(n,diagonalOffsets,diagonalRows,diagonal));
        int[] offsets=new int[n+1];var rows=new ArrayList<Integer>();var values=new ArrayList<Double>();double[] rhs=new double[n];
        for(int col=0;col<n;col++){offsets[col]=rows.size();for(int row=Math.max(0,col-1);row<=Math.min(n-1,col+1);row++){rows.add(row);values.add(row==col?5.0:-1.0);rhs[row]+=row==col?5:-1;}}offsets[n]=rows.size();
        var changed=new SparseMatrix(n,offsets,rows.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToDouble(Double::doubleValue).toArray());
        for(double value:SparseLuSolver.factor(changed,ordering).solve(rhs))assertEquals(1,value,1e-12);
        assertThrows(IllegalArgumentException.class,()->SparseLuSolver.factor(new SparseMatrix(1,new int[]{0,1},new int[]{0},new double[]{1}),ordering));
    }
    /**
     * S1 F2 (batch 2026-11-15-fluid-step-cost): an entry the equations keep ({@link SparseNewton.Equations#keptEntries}) reaches
     * the factorization though its value is exactly 0, and the solve reaches the root it reaches without the mask. The system:
     * {@code x0^2 - 4} and {@code x1^3 - 8}, the second row declared on both columns, so its entry in column 0 is exactly 0
     * at every point. The factored matrix is read through {@code SolverDiagnostics.luFactorEntries} (the entries of L and U of
     * each factorization): 4 without the mask (two diagonal pivots), 5 with it (L(1,0) is stored, at 0), and through the
     * result's matrix count.
     */
    @Test void aKeptZeroEntryIsFactoredAndLeavesTheRootAsItWas() {
        var plain=measured(keptZero(false));var masked=measured(keptZero(true));
        assertArrayEquals(new double[]{2,2},masked.result().variables(),1e-8);
        assertArrayEquals(plain.result().variables(),masked.result().variables(),1e-12);
        assertEquals(2,plain.result().jacobianNonzeros());assertEquals(3,masked.result().jacobianNonzeros(),"the kept zero is in the matrix");
        assertTrue(plain.factorizations()>0,"the solves factor");
        assertEquals(plain.factorizations(),masked.factorizations(),"the same iterations");
        assertEquals(4*plain.factorizations(),plain.entries(),"L and U without the mask");
        assertEquals(5*masked.factorizations(),masked.entries(),"L and U store the kept zero");
    }
    private record Measured(SparseNewton.Result result,long factorizations,long entries) {}
    private static Measured measured(SparseNewton.Equations equations) {
        boolean enabled=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED;
        try {
            com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=true;
            long factorizations=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luFactorizations.sum(),entries=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luFactorEntries.sum();
            var result=SparseNewton.solve(equations,new double[]{1,1},SparseNewton.Settings.defaults(),()->{});
            return new Measured(result,com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luFactorizations.sum()-factorizations,
                    com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luFactorEntries.sum()-entries);
        } finally {com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=enabled;}
    }
    /** The system of {@link #aKeptZeroEntryIsFactoredAndLeavesTheRootAsItWas}, its entries by one-sided differences column by
     * column (the coloured sweep's arithmetic), the zero entry kept where {@code mask}. */
    private static SparseNewton.Equations keptZero(boolean mask) {
        return new SparseNewton.Equations(){
            public int size(){return 2;}
            public int[][] columnRows(){return new int[][]{{0,1},{1}};}
            public double[] residual(double[] x){return new double[]{x[0]*x[0]-4,x[1]*x[1]*x[1]-8};}
            @Override public int differentiateEntries(double[] x,double[] f,double differenceStep,int[] offsets,int[] rows,double[] entries,Runnable checkpoint) {
                for(int column=0;column<2;column++) {
                    double step=differenceStep*differenceScale(column,x[column]);double[] trial=x.clone();trial[column]+=step;double[] moved=residual(trial);
                    for(int entry=offsets[column];entry<offsets[column+1];entry++)entries[entry]=(moved[rows[entry]]-f[rows[entry]])/step;
                }
                return 2;
            }
            @Override public boolean[] keptEntries(){return mask?new boolean[]{true,true,true}:null;}
        };
    }
    @Test void staleTimestepPreconditionerCannotDetermineConvergenceAgainstOldEquations() {
        var workspace=new SparseNewton.Workspace();var settings=new SparseNewton.Settings(8,1e-12,1,8);
        var first=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){return new int[][]{{0}};}public double[] residual(double[] x){return new double[]{2*x[0]-4};}};
        SparseNewton.solve(first,new double[]{0},settings,()->{},workspace);
        var changed=new SparseNewton.Equations(){public int size(){return 1;}public int[][] columnRows(){return new int[][]{{0}};}public double[] residual(double[] x){return new double[]{-4*x[0]-16};}};
        var result=SparseNewton.solve(changed,new double[]{0},settings,()->{},workspace.forkPreconditioner());
        assertEquals(-4,result.variables()[0]);assertEquals(0,changed.residual(result.variables())[0]);assertEquals(2,SparseNewton.solve(first,new double[]{0},settings,()->{},workspace).variables()[0]);
    }
}
