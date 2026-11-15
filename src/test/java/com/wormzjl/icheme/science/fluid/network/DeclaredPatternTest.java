package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The declared Jacobian pattern of a step's equations ({@link StepEquations#columnRows}) is true (batch
 * 2026-11-15-fluid-step-cost, S1 F1), on the fixtures of {@link BlockJacobianEquivalenceTest}: across a connection a column
 * declares only the other end's inflow rows ({@code PhaseLayout.inflowRows}), and no row it leaves out moves.
 *
 * <p>For every column, the whole residual at the point with that column perturbed by the Newton's difference step, inside a
 * Jacobian bracket ({@link StepEquations#linearising}), against the whole residual at the point in the same bracket: every
 * row of a node that the column does not declare is the base residual's double, bit for bit. The rows the structural-zero
 * rule of {@code StepEquations.buildSparsity} removes are left out of the comparison: a node's non-amount columns, where
 * none of its ends draws a phase, do not declare any node's material rows (component balances, a junction's mixing and
 * normalization rows), whose true derivative there is zero but whose difference is rounding noise; the block sweep and the
 * coloured sweep never read them, and {@link BlockJacobianEquivalenceTest} holds the declared entries.</p>
 *
 * <p>The count: the rule F1 replaced declared, on a node's column, the whole block of each node across an incident
 * connection, and on a connection's flow and control columns both ends' whole blocks. Rebuilt here from the layout sizes as
 * the union of today's declaration and those blocks (with the same structural zeros), it must hold more entries than
 * today's, and every entry it adds must be a row of a node other than the column's own that is not one of that node's
 * inflow rows: a wall, normalization or equilibrium row.</p>
 */
class DeclaredPatternTest {
    private final BlockJacobianEquivalenceTest fixtures=new BlockJacobianEquivalenceTest();

    @Test void aSolidChainDeclaresEveryRowItsColumnsMove() {
        check(fixtures.model,fixtures.chain(10),.05,"ten-node slurry chain");
    }
    @Test void aFilterIslandDeclaresEveryRowItsColumnsMove() {
        check(fixtures.model,fixtures.filterJunction(),.001,"filter island with a junction");
    }
    @Test void aDryChainDeclaresEveryRowItsColumnsMove() {
        var view=fixtures.model.on(fixtures.list(false),false);
        check(view,fixtures.mixtureChain(view,false,false,false),.05,"dry chain");
    }
    @Test void aChainAtItsVelocityCapDeclaresEveryRowItsColumnsMove() {
        var view=fixtures.model.on(fixtures.list(false),false);
        check(view,fixtures.mixtureChain(view,false,false,false,true),.05,"dry chain at its velocity cap");
    }
    @Test void aWetChainDeclaresEveryRowItsColumnsMove() {
        var view=fixtures.model.on(fixtures.list(false),true);
        check(view,fixtures.mixtureChain(view,true,false,false),.05,"wet chain");
    }
    @Test void aPolarChainDeclaresEveryRowItsColumnsMove() {
        var view=fixtures.model.on(fixtures.list(true),false);
        check(view,fixtures.mixtureChain(view,false,true,false),.05,"polar chain");
    }
    @Test void aChainCarryingACrystalDeclaresEveryRowItsColumnsMove() {
        var view=fixtures.model.on(fixtures.list(false),true);
        check(view,fixtures.mixtureChain(view,true,false,true),.05,"wet chain carrying ice");
    }

    private void check(FluidThermodynamics view,PassiveNetwork graph,double dt,String name) {
        var solver=new PassiveStepSolver(view);
        solver.solve(graph,dt,()->{});
        var equations=solver.acceptedEquations();double[] x=solver.acceptedPoint();
        assertNotNull(equations,name+": no solve was accepted");
        assertFalse(graph.pipes().isEmpty(),name+": the fixture has a connection");
        int size=equations.size();int[][] columnRows=equations.columnRows();
        BitSet material=materialRows(equations);boolean[] noisy=structuralZeroColumns(equations);
        int[] rowNode=rowNodes(equations);
        int compared=0,declared=0;
        equations.linearising(x);
        try {
            double[] base=equations.residual(x);
            for(int column=0;column<size;column++) {
                declared+=columnRows[column].length;
                var rows=new BitSet(size);for(int row:columnRows[column])rows.set(row);
                double step=1e-6*equations.differenceScale(column,x[column]);
                double[] trial=x.clone();trial[column]+=step;
                double[] moved=equations.residual(trial);
                for(int row=0;row<equations.edgeOffset;row++) {
                    if(rows.get(row)||noisy[column]&&material.get(row))continue;
                    compared++;
                    if(Double.doubleToRawLongBits(moved[row])!=Double.doubleToRawLongBits(base[row]))
                        fail(name+": column "+column+" moved row "+row+" (node "+rowNode[row]+", local row "+(row-equations.offsets[rowNode[row]])
                                +"), which it does not declare, from "+base[row]+" to "+moved[row]);
                }
            }
        } finally {equations.linearised();}
        assertTrue(compared>0,name+": no undeclared row was compared");

        // The rule F1 replaced: today's declaration with the other ends' whole blocks, under the same structural zeros.
        int replaced=0;
        for(int column=0;column<size;column++) {
            var rows=new BitSet(size);for(int row:columnRows[column])rows.set(row);
            var before=(BitSet)rows.clone();
            for(int node:acrossEnds(equations,column))if(equations.layout[node]!=null)before.set(equations.offsets[node],equations.offsets[node]+equations.nodeSize(node));
            if(noisy[column])before.andNot(material);
            replaced+=before.cardinality();
            before.andNot(rows);
            int own=column<equations.edgeOffset?rowNode[column]:-1;
            for(int row=before.nextSetBit(0);row>=0;row=before.nextSetBit(row+1)) {
                int node=rowNode[row];
                assertTrue(node>=0&&node!=own,name+": the replaced rule declared row "+row+" on column "+column+" that F1 drops, outside another node");
                int local=row-equations.offsets[node];
                assertTrue(Arrays.stream(equations.layout[node].inflowRows()).noneMatch(r->r==local),
                        name+": F1 drops inflow row "+local+" of node "+node+" from column "+column);
            }
        }
        System.out.printf(Locale.ROOT,"DECLARED_PATTERN %-30s unknowns %4d declared %6d replaced rule %6d (%.3f) undeclared node rows compared %7d%n",
                name,size,declared,replaced,declared/(double)replaced,compared);
        assertTrue(declared<replaced,name+": F1 declares "+declared+" entries, the rule it replaced "+replaced);
    }
    /** The node each row belongs to, -1 for a connection's row. */
    private static int[] rowNodes(StepEquations equations) {
        int[] node=new int[equations.size()];Arrays.fill(node,-1);
        for(int n=0;n<equations.layout.length;n++)for(int local=0;local<equations.nodeSize(n);local++)node[equations.offsets[n]+local]=n;
        return node;
    }
    /** The nodes whose whole block the replaced rule declared on {@code column} beyond what its own block gives: for a
     * node's column the other end of each incident connection, for a connection's flow or control column both ends. */
    private static List<Integer> acrossEnds(StepEquations equations,int column) {
        var ends=new ArrayList<Integer>();var pipes=equations.graph.pipes();
        int own=-1;for(int n=0;n<equations.layout.length;n++)if(column>=equations.offsets[n]&&column<equations.offsets[n]+equations.nodeSize(n))own=n;
        for(int edge=0;edge<pipes.size();edge++) {
            var pipe=pipes.get(edge);
            if(own>=0){if(pipe.first()==own)ends.add(pipe.second());if(pipe.second()==own)ends.add(pipe.first());}
            else if(column==equations.edgeOffset+edge||column==equations.controlOffsets[edge]){ends.add(pipe.first());ends.add(pipe.second());}
        }
        return ends;
    }
    /** The structural-zero rule's columns ({@code StepEquations.buildSparsity}): a node's non-amount columns where no end of
     * the node draws a phase. */
    private static boolean[] structuralZeroColumns(StepEquations equations) {
        boolean[] noisy=new boolean[equations.size()];
        for(int node=0;node<equations.layout.length;node++)if(equations.layout[node]!=null&&!equations.drawsPhase[node])
            for(int local=0;local<equations.layout[node].size();local++)if(!equations.layout[node].totalAmountVariable(local))noisy[equations.offsets[node]+local]=true;
        return noisy;
    }
    /** The structural-zero rule's rows: each node's component balances, and a junction's mixing and normalization rows. */
    private static BitSet materialRows(StepEquations equations) {
        var rows=new BitSet(equations.size());
        for(int node=0;node<equations.layout.length;node++)if(equations.layout[node]!=null) {
            int components=equations.layout[node].componentBalanceCount();boolean junction=equations.graph.reservoirs().get(node).junction();
            rows.set(equations.offsets[node],equations.offsets[node]+components-(junction?1:0));
            if(junction)rows.set(equations.offsets[node]+components+1);
        }
        return rows;
    }
}
