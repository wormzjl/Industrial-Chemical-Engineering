package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.state.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The block Jacobian sweep against central differences of the whole-island residual (ANALYTIC_JACOBIAN_PLAN.md of batch
 * 2026-11-11-aqueous-solvents, test 3), on the two island shapes the sweep used to refuse (a chain carrying solids, an
 * island with an inline filter and a junction) and on chains of a dry, a wet and a polar mixture and of one carrying a
 * crystal.
 *
 * <p>The sweep decodes a perturbed node through its phase tangents, so an entry is no longer the double a lone forward
 * difference of the residual produces: it is the row's derivative without the phases' curvature inside the step. The
 * reference is therefore a second-order difference of the residual, at two steps (its own truncation and rounding show
 * in their spread) and on the side the sweep steps to: the solid moments sit on the kink of their projection at zero,
 * where a central difference halves the slope. The error is stated over the largest entry of the row. On the node
 * columns each fixture's entries must agree to 1e-6, or to the reference's spread where that is larger (the two
 * liquid-full water islands, 2.4e-4), no worse than the entries of the form before the tangents (every perturbed phase
 * from the equation of state) against the same reference, and within 1e-5 of those entries. What is left on the
 * mixture chains, 5e-7, is half the step: the forward difference of the unknowns' own exponentials (ln T, ln P, the
 * split ratios), which the tangents do not touch. An edge, actuator or filter column decodes no node, and its entries
 * are the doubles they were.
 *
 * <p>The failure this was first built for is silent and still caught, by any tolerance. A neighbour's three aggregate
 * solid rows are written twice - once by {@code PhaseLayout.balanceRows}/{@code junctionRows} against the layout's own
 * seed reference, then over the top of it against the targets the evaluation accumulated - so a sweep that stops at the
 * first write differentiates the neighbour against the seed reference while the base residual holds the real target.
 * Every such entry is then wrong by the whole difference of the two targets divided by the difference step.
 */
class BlockJacobianEquivalenceTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final List<String> DRY=List.of("Nitrogen","Methane","Ethane","Propane","N-butane","N-pentane","N-hexane","Benzene","Toluene",
            "crude_pc03","crude_pc06","crude_pc09","CarbonDioxide");
    private static final double[] DRY_MOLES={0.06,0.12,0.03,0.03,0.05,0.05,0.04,0.16,0.10,0.08,0.12,0.08,0.08};

    private FluidThermodynamics.State water(double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(350,pressure,n,()->{}).volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    private SolidInventory grade(String micrometres,double mass) {
        return new SolidInventory(List.of(new SolidInventory.Population(
                model.solids.require("icheme:demo_particle"),ParticleSize.micrometres(micrometres),mass)));
    }
    /** {@code count} reservoirs in series, the first holding 100 kg of 100 um particles. */
    private PassiveNetwork chain(int count) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<count;i++) {
            var state=water(i==0?160000:150000-100*i);
            if(i==0)state=state.withSolids(grade("100",100));
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(100+i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    /** A junction fed by a filter edge and by a plain edge, draining into one finite reservoir. The
     * filter keeps every particle it is handed, so the junction's incoming solids come from the
     * plain edge alone and its own solid rows are the mass-fraction-scaled junction form. */
    private PassiveNetwork filterJunction() {
        var nodes=List.of(
                new PassiveNetwork.Reservoir(1,0,water(200000).withSolids(grade("100",80)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(180000).withSolids(grade("60",40)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,water(160000),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(4,0,water(150000)));
        var pipes=List.of(
                new PassiveNetwork.Pipe(10,0,2,List.of(new PipeResistance.Geometry(100,.05,.000045,0)),
                        new FlowControl.Passive(),0,new InlineFilter(10,1e6,SolidInventory.EMPTY,0)),
                new PassiveNetwork.Pipe(11,1,2,new PipeResistance.Geometry(100,.05,.000045,0)),
                new PassiveNetwork.Pipe(12,2,3,new PipeResistance.Geometry(100,.05,.000045,0)));
        return new PassiveNetwork(nodes,pipes);
    }
    /**
     * Six 1 m3 reservoirs of one mixture in series, 1.5 bar +- 3 kPa and 320 K +- 6 K along the chain, so mass and heat
     * move (the islands of the cost baseline): the dry list, with water (a gas and two liquids in every node) or with
     * hydrogen sulfide and methyl mercaptan; {@code crystal} puts 5 kg of the model's ice in the first node.
     */
    private PassiveNetwork mixtureChain(FluidThermodynamics view,boolean water,boolean polar,boolean crystal) {
        return mixtureChain(view,water,polar,crystal,false);
    }
    /** {@code capped}: 8 bar in the first node and 2 m pipes, so every flow stands at its velocity cap. */
    private PassiveNetwork mixtureChain(FluidThermodynamics view,boolean water,boolean polar,boolean crystal,boolean capped) {
        var ids=new ArrayList<>(DRY);var amounts=new ArrayList<Double>();
        for(double m:DRY_MOLES)amounts.add(m);
        if(polar){ids.add("HydrogenSulfide");amounts.add(0.04);ids.add("MethylMercaptan");amounts.add(0.02);}
        if(water){ids.add("Water");amounts.add(0.33);}
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<6;i++) {
            double pressure=capped?(i==0?800000:150000-10000*i):150000+3000*DeterministicMath.cos(i*0.9),temperature=320+6*DeterministicMath.cos(i*1.3);
            double[] n=new double[view.componentCount()];
            for(int c=0;c<ids.size();c++)n[view.components().indexOf(ids.get(c))]=amounts.get(c);
            var unit=view.flashTP(temperature,pressure,n,()->{});
            for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            var state=view.flashTP(temperature,pressure,n,()->{});
            if(crystal&&i==0) {
                var ice=view.crystalSolids().all().stream().filter(c->c.water()).findFirst().orElseThrow();
                state=state.withSolids(new SolidInventory(List.of(new SolidInventory.Population(ice.material(),ParticleSize.micrometres("100"),5))));
            }
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(i,i-1,i,new PipeResistance.Geometry(capped?2:100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    private List<String> list(boolean polar) {
        var ids=new ArrayList<>(DRY);
        if(polar){ids.add("HydrogenSulfide");ids.add("MethylMercaptan");}
        return ids;
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfASolidChain() {
        var comparison=compare(model,chain(10),.05,"ten-node slurry chain");
        assertTrue(comparison.solidCoupling()>0,
                "No neighbour solid row moved with a donor column, so the comparison never reached the rows that matter");
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfAFilterIsland() {
        var comparison=compare(model,filterJunction(),.001,"filter island with a junction");
        assertTrue(comparison.solidCoupling()>0,"No neighbour solid row moved with a donor column");
        assertTrue(comparison.filterColumns()>0,"The island carried no swept filter column");
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfADryChain() {
        var view=model.on(list(false),false);
        assertTrue(compare(view,mixtureChain(view,false,false,false),.05,"dry chain").tangentPhases()>0);
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfAChainAtItsVelocityCap() {
        var view=model.on(list(false),false);
        assertTrue(compare(view,mixtureChain(view,false,false,false,true),.05,"dry chain at its velocity cap").tangentPhases()>0);
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfAWetChain() {
        var view=model.on(list(false),true);
        var graph=mixtureChain(view,true,false,false);
        for(var node:graph.reservoirs())assertTrue(node.state().vaporPhase()!=null&&node.state().liquidPhase()!=null&&node.state().aqueousPhase()!=null,"a gas and two liquids");
        assertTrue(compare(view,graph,.05,"wet chain").tangentPhases()>0);
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfAPolarChain() {
        var view=model.on(list(true),false);
        assertTrue(compare(view,mixtureChain(view,false,true,false),.05,"polar chain").tangentPhases()>0);
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfAChainCarryingACrystal() {
        var view=model.on(list(false),true);
        var comparison=compare(view,mixtureChain(view,true,false,true),.05,"wet chain carrying ice");
        assertTrue(comparison.solidCoupling()>0,"No neighbour solid row moved with a donor column");
    }

    private record Comparison(int columns,int entries,int solidCoupling,int filterColumns,long tangentPhases) {}

    private Comparison compare(FluidThermodynamics view,PassiveNetwork graph,double dt,String name) {
        var solver=new PassiveStepSolver(view);
        solver.solve(graph,dt,()->{});
        var equations=solver.acceptedEquations();double[] x=solver.acceptedPoint();
        assertNotNull(equations,name+": no solve was accepted");
        int size=equations.size();
        int[][] columnRows=equations.columnRows();
        int[] offsets=new int[size+1];for(int c=0;c<size;c++)offsets[c+1]=offsets[c]+columnRows[c].length;
        int[] rows=new int[offsets[size]];for(int c=0;c<size;c++)System.arraycopy(columnRows[c],0,rows,offsets[c],columnRows[c].length);

        double[] base=equations.residual(x);
        double[] block=new double[rows.length],before=new double[rows.length];
        boolean enabled=SolverDiagnostics.ENABLED;int swept;long tangentPhases,exactPhases,untangentPhases;
        try {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            swept=equations.differentiateEntries(x,base,1e-6,offsets,rows,block,()->{});
            var sample=SolverDiagnostics.sample();
            assertEquals(0,sample.value("jacobianBlockFallbacks"),name+": the block sweep refused the island");
            tangentPhases=sample.value("jacobianTangentPhases");exactPhases=sample.value("jacobianExactPhases");untangentPhases=sample.value("jacobianUntangentPhases");
        } finally {SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
        assertEquals(size,swept,name+": the block sweep did not sweep every column");
        // The form before the tangents: every perturbed phase from the equation of state.
        assertEquals(size,equations.differentiateEntries(x,base,1e-6,offsets,rows,before,()->{},false),name);

        // Second-order differences of the residual at two steps, on the side the sweep steps to.
        double[][] reference=new double[2][rows.length];
        double[] steps={1e-6,1e-5};
        for(int k=0;k<2;k++)for(int column=0;column<size;column++) {
            double step=steps[k]*equations.differenceScale(column,x[column]);
            double[] trial=x.clone();trial[column]+=step;
            double[] once=equations.residual(trial);
            trial[column]=x[column]+2*step;
            double[] twice=equations.residual(trial);
            for(int entry=offsets[column];entry<offsets[column+1];entry++)
                reference[k][entry]=(4*once[rows[entry]]-twice[rows[entry]]-3*base[rows[entry]])/(2*step);
        }
        boolean[] nodeColumn=new boolean[size];
        for(int node=0;node<equations.layout.length;node++)
            if(equations.layout[node]!=null)for(int local=0;local<equations.layout[node].size();local++)nodeColumn[equations.offsets[node]+local]=true;
        double[] rowScale=new double[size];
        for(int entry=0;entry<rows.length;entry++)rowScale[rows[entry]]=Math.max(rowScale[rows[entry]],Math.abs(reference[0][entry]));

        double worstBlock=0,worstBefore=0,spread=0,edgeBlock=0,edgeBefore=0,moved=0;int worstEntry=-1;
        for(int entry=0,column=0;entry<rows.length;entry++) {
            while(offsets[column+1]<=entry)column++;
            double scale=rowScale[rows[entry]];
            if(scale==0)scale=1;
            double blockError=Math.min(Math.abs(block[entry]-reference[0][entry]),Math.abs(block[entry]-reference[1][entry]))/scale;
            double beforeError=Math.min(Math.abs(before[entry]-reference[0][entry]),Math.abs(before[entry]-reference[1][entry]))/scale;
            // An edge, actuator or filter column decodes no node: its entries are the ones they were.
            if(!nodeColumn[column]){edgeBlock=Math.max(edgeBlock,blockError);edgeBefore=Math.max(edgeBefore,beforeError);continue;}
            spread=Math.max(spread,Math.abs(reference[0][entry]-reference[1][entry])/scale);
            worstBefore=Math.max(worstBefore,beforeError);
            moved=Math.max(moved,Math.abs(block[entry]-before[entry])/scale);
            if(blockError>=worstBlock){worstBlock=blockError;worstEntry=entry;}
        }
        int worstColumn=0;while(offsets[worstColumn+1]<=worstEntry)worstColumn++;
        System.out.printf(Locale.ROOT,"JACOBIAN_ERROR %-30s entries %6d node columns: tangents %.3e before %.3e reference spread %.3e (worst column %d row %d block %.9e reference %.9e) moved %.3e; edge columns: %.3e before %.3e; phases: tangent %d exact %d no tangent %d%n",
                name,rows.length,worstBlock,worstBefore,spread,worstColumn,rows[worstEntry],block[worstEntry],reference[0][worstEntry],moved,edgeBlock,edgeBefore,tangentPhases,exactPhases,untangentPhases);
        assertEquals(edgeBefore,edgeBlock,0.0,name+": an edge column's entries moved");
        assertTrue(worstBlock<=Math.max(1e-6,spread),name+": row-scaled error "+worstBlock+" at column "+worstColumn+" row "+rows[worstEntry]
                +" block="+block[worstEntry]+" reference="+reference[0][worstEntry]);
        assertTrue(worstBlock<=Math.max(worstBefore,spread),name+": the entries from tangents ("+worstBlock+") are worse than the entries before them ("+worstBefore+")");
        assertTrue(moved<=1e-5,name+": an entry moved by "+moved+" of its row's largest");

        // Every neighbour solid row that actually responds to a donor's own column. This is the
        // coupling the sweep used to skip; a comparison that never reaches one proves nothing.
        int coupling=0;
        for(int node=0;node<equations.layout.length;node++) {
            if(equations.layout[node]==null)continue;
            int first=equations.offsets[node]+equations.layout[node].componentBalanceCount()+2;
            for(int column=0;column<size;column++) {
                if(column>=equations.offsets[node]&&column<equations.offsets[node]+equations.layout[node].size())continue;
                for(int entry=offsets[column];entry<offsets[column+1];entry++)
                    if(rows[entry]>=first&&rows[entry]<first+3&&reference[0][entry]!=0)coupling++;
            }
        }
        int filters=0;for(int offset:equations.filterOffsets)if(offset>=0)filters++;
        System.out.printf(Locale.ROOT,"block jacobian: %s columns=%d entries=%d neighbour-solid-couplings=%d filter columns=%d%n",
                name,swept,rows.length,coupling,filters);
        return new Comparison(swept,rows.length,coupling,filters,tangentPhases);
    }
}
