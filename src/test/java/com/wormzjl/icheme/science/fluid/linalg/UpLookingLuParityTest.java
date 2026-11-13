package com.wormzjl.icheme.science.fluid.linalg;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.CoolingTower;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.csc.decomposition.lu.LuUpLooking_DSCC;
import org.ejml.sparse.csc.linsol.lu.LinearSolverLu_DSCC;
import org.junit.jupiter.api.Test;

/**
 * The parity gate of finding P-17 (batch 2026-11-13-codebase-review): {@link UpLookingLu} against EJML 0.44's
 * {@code LuUpLooking_DSCC} on the same columns, raw bits, entry by entry: L, U, the row pivots and the solutions of several
 * right-hand sides; a singular matrix is rejected by both at the same column. Each case runs twice in one instance (the
 * second on the kept reach, with no search) and once more with a changed pivot in the middle (searching from the column
 * after the first changed pivot on). The matrices: those the step hands the LU on a heat side, a boiling junction and a
 * cooling tower, and random patterns with forced pivot swaps and with singular columns.
 */
class UpLookingLuParityTest {
    record Matrix(int size,int[] columns,int[] rows,double[] values) {
        Matrix scaledColumn(int column,int entry,double factor) {
            double[] changed=values.clone();changed[columns[column]+entry]*=factor;return new Matrix(size,columns,rows,changed);
        }
    }
    private record Ejml(boolean factored,int[] lColumns,int[] lRows,double[] lValues,int[] uColumns,int[] uRows,double[] uValues,int[] pinv,LinearSolverLu_DSCC solver,int pivots) {}

    private static Ejml ejml(Matrix m) {
        var a=new DMatrixSparseCSC(m.size(),m.size(),m.values().length);
        System.arraycopy(m.columns(),0,a.col_idx,0,m.size()+1);System.arraycopy(m.rows(),0,a.nz_rows,0,m.rows().length);System.arraycopy(m.values(),0,a.nz_values,0,m.values().length);
        a.nz_length=m.values().length;a.indicesSorted=true;
        var lu=new LuUpLooking_DSCC(null);var solver=new LinearSolverLu_DSCC(lu);
        boolean factored=solver.setA(a);
        int[] pinv=Arrays.copyOf(lu.getPinv(),m.size());int pivots=0;for(int p:pinv)if(p>=0)pivots++;
        if(!factored)return new Ejml(false,null,null,null,null,null,null,pinv,solver,pivots);
        var l=lu.getL();var u=lu.getU();
        return new Ejml(true,Arrays.copyOf(l.col_idx,m.size()+1),Arrays.copyOf(l.nz_rows,l.nz_length),Arrays.copyOf(l.nz_values,l.nz_length),
                Arrays.copyOf(u.col_idx,m.size()+1),Arrays.copyOf(u.nz_rows,u.nz_length),Arrays.copyOf(u.nz_values,u.nz_length),pinv,solver,pivots);
    }
    private static void assertBits(double[] expected,double[] actual,String what) {
        assertEquals(expected.length,actual.length,what+" length");
        for(int i=0;i<expected.length;i++)
            if(Double.doubleToRawLongBits(expected[i])!=Double.doubleToRawLongBits(actual[i]))fail(what+"["+i+"]: "+expected[i]+" against "+actual[i]);
    }
    /** Factors {@code m} in {@code lu} and checks every output against a fresh EJML decomposition. */
    private static Ejml assertParity(UpLookingLu lu,Matrix m,String name) {
        var expected=ejml(m);
        boolean factored=lu.factor(m.size(),m.columns(),m.rows(),m.values());
        assertEquals(expected.factored(),factored,name+": accepted by both or rejected by both");
        if(!factored){assertEquals(expected.pivots(),lu.failedColumn(),name+": rejected at the same column");return expected;}
        assertArrayEquals(expected.pinv(),lu.rowPivots(),name+": row pivots");
        assertArrayEquals(expected.lColumns(),lu.lowerColumns(),name+": L columns");assertArrayEquals(expected.lRows(),lu.lowerRows(),name+": L rows");
        assertBits(expected.lValues(),lu.lowerValues(),name+": L");
        assertArrayEquals(expected.uColumns(),lu.upperColumns(),name+": U columns");assertArrayEquals(expected.uRows(),lu.upperRows(),name+": U rows");
        assertBits(expected.uValues(),lu.upperValues(),name+": U");
        var random=new Random(m.size()*31L+m.values().length);
        for(int vector=0;vector<4;vector++) {
            double[] b=new double[m.size()];
            for(int i=0;i<b.length;i++)b[i]=vector==0?1:vector==1&&i%3!=0?0:random.nextGaussian()*Math.pow(10,random.nextInt(9)-4);
            var x=new DMatrixRMaj(new double[m.size()]);expected.solver().solve(new DMatrixRMaj(b.clone()),x);
            double[] mine=new double[m.size()];lu.solve(b,mine);
            assertBits(x.data,mine,name+": solution "+vector);
        }
        return expected;
    }
    /** The row each column pivots on, from {@code pinv}. */
    private static int[] pivotRows(int[] pinv){int[] rows=new int[pinv.length];for(int i=0;i<pinv.length;i++)if(pinv[i]>=0)rows[pinv[i]]=i;return rows;}
    /** Twice in one instance (no search the second time), then {@code changed} (searching after its first changed pivot). */
    private static void gate(Matrix m,Matrix changed,String name) {
        var lu=new UpLookingLu();
        var first=assertParity(lu,m,name);
        long searches=lu.searches();
        assertParity(lu,m,name+" again");
        if(first.factored())assertEquals(searches,lu.searches(),name+": the kept reach serves every column");
        if(changed==null||!first.factored())return;
        searches=lu.searches();
        var second=assertParity(lu,changed,name+" changed");
        if(!second.factored())return;
        int[] before=pivotRows(first.pinv()),after=pivotRows(second.pinv());int k=0;
        while(k<before.length&&before[k]==after[k])k++;
        assertEquals(k<before.length?before.length-k-1:0,lu.searches()-searches,name+": searches after the first changed pivot, column "+k);
    }

    /** Diagonal plus random entries; {@code weakDiagonal} makes the diagonal tiny, so most columns pivot off it. */
    private static Matrix random(int n,double density,boolean weakDiagonal,long seed) {
        var random=new Random(seed);int[] columns=new int[n+1];var rows=new ArrayList<Integer>();var values=new ArrayList<Double>();
        for(int c=0;c<n;c++) {
            for(int r=0;r<n;r++)if(r==c||random.nextDouble()<density) {
                rows.add(r);values.add(r==c?(weakDiagonal?1e-6*random.nextGaussian():1+random.nextDouble()):random.nextGaussian());
            }
            columns[c+1]=rows.size();
        }
        return new Matrix(n,columns,rows.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToDouble(Double::doubleValue).toArray());
    }
    @Test void randomPatternsMatchEjmlBitForBit() {
        int cases=0;
        for(int n:new int[]{1,2,5,12,40,130})for(double density:new double[]{.05,.2,.5})for(boolean weak:new boolean[]{false,true})for(long seed=1;seed<=3;seed++) {
            var m=random(n,density,weak,seed*1000+n);
            // Change the column in the middle: its first stored entry scaled up, which moves that column's pivot as a rule.
            int middle=n/2;
            var changed=m.columns()[middle+1]>m.columns()[middle]?m.scaledColumn(middle,0,1e6):null;
            gate(m,changed,"n "+n+", density "+density+(weak?", weak diagonal":"")+", seed "+seed);cases++;
        }
        assertTrue(cases>100);
    }
    @Test void singularColumnsAreRejectedByBothAtTheSameColumn() {
        // An empty column, a column on rows already pivoted, and an exact zero pivot.
        var empty=new Matrix(3,new int[]{0,1,1,2},new int[]{0,2},new double[]{2,3});
        var covered=new Matrix(3,new int[]{0,1,2,3},new int[]{0,0,2},new double[]{2,5,1});
        var tied=new Matrix(2,new int[]{0,2,4},new int[]{0,1,0,1},new double[]{1,2,2,4});
        for(var m:List.of(empty,covered,tied))gate(m,null,"singular "+Arrays.toString(m.values()));
        var lu=new UpLookingLu();
        assertFalse(lu.factor(covered.size(),covered.columns(),covered.rows(),covered.values()));assertEquals(1,lu.failedColumn());
        // After a rejection the next factorization of the same pattern searches anew and matches.
        var fine=new Matrix(3,new int[]{0,1,2,3},new int[]{0,1,2},new double[]{2,5,1});
        assertParity(lu,fine,"after a rejection");
    }

    /** The matrices one step of {@code graph} hands the LU, as the solver equilibrates, permutes and sorts them. */
    private static List<Matrix> stepMatrices(FluidThermodynamics model,PassiveNetwork graph,double dt) {
        var seen=new ArrayList<Matrix>();
        SparseLuSolver.observer=(size,columns,rows,values)->seen.add(new Matrix(size,columns,rows,values));
        try{new PassiveStepSolver(model).solve(graph,dt,()->{});}
        catch(RuntimeException failure){/* the matrices it reached are the gate's; the step's own outcome is not */}
        finally{SparseLuSolver.observer=null;}
        return seen;
    }
    private static FluidThermodynamics.State water(FluidThermodynamics model,double t,double p,double volume) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]*=volume/model.flashTP(t,p,n,()->{}).volume();return model.flashTP(t,p,n,()->{});
    }
    @Test void theStepsMatricesMatchEjmlBitForBit() {
        var model=FluidTestSupport.networkModel();var run=new PipeResistance.Geometry(10,.05,.000045,0);
        // A cooled heat side: generator water through the side into a junction, then a run to a void.
        var source=water(model,350,300_000,1);
        var heat=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,water(model,300,101_325,1),PassiveNetwork.NodeKind.VOID)),
            List.of(new PassiveNetwork.Pipe(5,0,1,run).withHeat(new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300},0,0)),new PassiveNetwork.Pipe(6,1,2,run)));
        // A junction the heat boils (a flashed junction seed).
        var hot=water(model,420,1_000_000,1);
        var boil=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,hot,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,hot,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,water(model,300,800_000,1),PassiveNetwork.NodeKind.VOID)),
            List.of(new PassiveNetwork.Pipe(5,0,1,run).withHeat(new HeatSide(22,HeatCurve.Films.DEFAULT,new double[]{540},0,0)),new PassiveNetwork.Pipe(6,1,2,run)));
        // A cooling tower: warm water sprayed into a half-full basin.
        var tower=CoolingTower.induced(8,2,1,1);var basinWater=water(model,300,101_325,tower.basinVolume()/2);
        var basin=new PassiveNetwork.Inventory(tower.basinVolume(),PhaseLayout.totalAmounts(basinWater),basinWater.internalEnergy(),basinWater.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var spray=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(0,0,water(model,308.15,200_000,1),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(1,0,basinWater,PassiveNetwork.NodeKind.RESERVOIR,basin)),
            List.of(new PassiveNetwork.Pipe(5,0,1,run).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY)));
        int matrices=0;
        for(var island:List.of(java.util.Map.entry("heat side",List.of(heat,.1)),java.util.Map.entry("boiling junction",List.of(boil,2.)),java.util.Map.entry("tower",List.of(spray,.1)))) {
            var seen=stepMatrices(model,(PassiveNetwork)island.getValue().get(0),(Double)island.getValue().get(1));
            assertFalse(seen.isEmpty(),island.getKey()+": the step factors");
            for(int i=0;i<seen.size();i++) {
                var m=seen.get(i);int middle=m.size()/2;
                var changed=m.columns()[middle+1]>m.columns()[middle]?m.scaledColumn(middle,0,1e6):null;
                gate(m,changed,island.getKey()+" matrix "+i);matrices++;
            }
        }
        System.out.printf(java.util.Locale.ROOT,"P-17 gate: %d step matrices%n",matrices);
    }
    /**
     * P-17's counters for the report, printed: on a heat side's island solved five times by one retained solver (after a warm
     * solve), the LU factorizations, the columns factored and the reach searches; and the bytes one {@code Storage.factor}
     * allocates on a step's own matrix once the storage has its size. Counts, not times.
     */
    @Test void printsTheLuCounters() {
        var model=FluidTestSupport.networkModel();var run=new PipeResistance.Geometry(10,.05,.000045,0);var source=water(model,350,300_000,1);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,water(model,300,101_325,1),PassiveNetwork.NodeKind.VOID)),
            List.of(new PassiveNetwork.Pipe(5,0,1,run).withHeat(new HeatSide(2,HeatCurve.Films.DEFAULT,new double[]{300},0,0)),new PassiveNetwork.Pipe(6,1,2,run)));
        boolean enabled=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED;
        try {
            com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=true;
            var solver=new PassiveStepSolver(model);solver.solve(graph,.1,()->{});
            long columns=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luColumnsFactored.sum(),searches=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luReachSearches.sum(),factorizations=com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luFactorizations.sum();
            for(int i=0;i<5;i++)solver.solve(graph,.1,()->{});
            System.out.printf(java.util.Locale.ROOT,"P-17 counters, heat side, five solves: %d LU factorizations, %d columns factored, %d reach searches%n",
                    com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luFactorizations.sum()-factorizations,
                    com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luColumnsFactored.sum()-columns,
                    com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.luReachSearches.sum()-searches);
        } finally {com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=enabled;}
        var m=stepMatrices(model,graph,.1).getFirst();
        var matrix=new SparseMatrix(m.size(),m.columns(),m.rows(),m.values());var ordering=SparseLuSolver.prepareOrdering(matrix);
        var storage=new SparseLuSolver.Storage(com.wormzjl.icheme.science.fluid.SolverOwnership.confinedToCurrentThread());storage.factor(matrix,ordering);
        var threads=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();long bytes=threads.getCurrentThreadAllocatedBytes();
        int repeats=20;for(int i=0;i<repeats;i++)assertNotNull(storage.factor(matrix,ordering));
        System.out.printf(java.util.Locale.ROOT,"P-17 counters, Storage.factor on a %d-unknown step matrix: %d bytes a factorization%n",m.size(),(threads.getCurrentThreadAllocatedBytes()-bytes)/repeats);
    }
}
