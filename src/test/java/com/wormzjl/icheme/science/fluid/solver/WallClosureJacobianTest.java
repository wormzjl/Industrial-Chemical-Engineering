package com.wormzjl.icheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The vessel closure's Jacobian from phase tangents ({@link PhaseLayout#wallClosure}, batch 2026-11-15-fluid-step-cost,
 * S4) against the sweep it replaces: for every column the one-sided difference of the closure's {@code residual} at the
 * same step, inside the same bracket ({@link SparseNewton.Equations#linearising}), each trial decoded on the equation of
 * state. A tangent decode leaves out the phases' curvature inside the step, so an entry is not that double; it must agree
 * with it to 1e-5 of the largest entry of its row, the bound {@code BlockJacobianEquivalenceTest} holds the step's node
 * columns to against their form before the tangents.
 *
 * <p>The fixtures: a two-phase vessel, a liquid-full one on the taut branch, a slack bag beside the kink whose
 * temperature column crosses to the taut branch on its own (decision A423: the sweep states it on the point's branch),
 * an aqueous liquid beside an oil, a vessel holding solid moments (their rows read the unknowns), and one whose small
 * liquid is half a compound the vessel holds at a trace, so that compound's column moves the liquid's composition by
 * more than {@link FluidThermodynamics#TANGENT_COMPOSITION_STEP} and the liquid is evaluated on the equation of state
 * for it. A point at the numerical box's top temperature, whose temperature column leaves the box, hands the build back
 * to the coloured sweep.</p>
 */
class WallClosureJacobianTest {
    /** The difference step {@code InventoryEquilibrium.close} gives the closure's Newton. */
    private static final double STEP=1e-6;
    /** The bound on an entry, over the largest of its row (as {@code BlockJacobianEquivalenceTest}'s entries moved). */
    private static final double BOUND=1e-5;
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";

    private record Sweep(PhaseLayout layout,SparseNewton.Equations equations,double[] x,int columns,double worst,
                         long tangentPhases,long exactPhases,long exactColumns) {}

    /** The temperature's unknown: the column before the pressure's ({@link PhaseLayout}'s constructor). */
    private static int temperature(PhaseLayout layout){return layout.pressureVariable()-1;}

    private static Sweep compare(String name,FluidThermodynamics model,FluidThermodynamics.State state,double capacity,double workFrom,
                                 double temperatureShift,boolean taut) {
        var layout=new PhaseLayout(model,state);
        var equations=layout.wallClosure(PhaseLayout.totalAmounts(state),state.internalEnergy(),capacity,workFrom);
        double[] x=layout.encode(state);x[temperature(layout)]+=temperatureShift;
        assertEquals(taut,PhaseLayout.wall(layout.decode(x,0),capacity).taut(),name+": the wall's branch at the point");
        int size=equations.size();
        int[][] columnRows=equations.columnRows();
        int[] offsets=new int[size+1];for(int c=0;c<size;c++)offsets[c+1]=offsets[c]+columnRows[c].length;
        int[] rows=new int[offsets[size]];for(int c=0;c<size;c++)System.arraycopy(columnRows[c],0,rows,offsets[c],columnRows[c].length);
        // The base residual, outside the bracket, on the state's own branch: as SparseNewton evaluates it.
        double[] f=equations.residual(x);
        double norm=0;for(double value:f)norm=Math.max(norm,Math.abs(value));
        assertTrue(norm>0,name+": the point is off the closure's solution");

        double[] tangent=new double[rows.length],reference=new double[rows.length];
        boolean enabled=SolverDiagnostics.ENABLED;int swept;SolverDiagnostics.Sample sample;
        try {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            equations.linearising(x);
            try {
                swept=equations.differentiateEntries(x,f,STEP,offsets,rows,tangent,()->{});
                sample=SolverDiagnostics.sample();
                // The coloured sweep's entries: one column a trial (the pattern is dense), on the equation of state.
                for(int column=0;column<size;column++) {
                    double step=STEP*equations.differenceScale(column,x[column]);
                    double[] trial=x.clone();trial[column]+=step;
                    double[] perturbed=equations.residual(trial);
                    for(int entry=offsets[column];entry<offsets[column+1];entry++)reference[entry]=(perturbed[rows[entry]]-f[rows[entry]])/step;
                }
            } finally {equations.linearised();}
        } finally {SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
        assertEquals(size,swept,name+": the sweep did not sweep every column");
        assertEquals(0,sample.value("jacobianBlockFallbacks"),name+": the sweep refused the point");
        assertEquals(size,sample.value("jacobianBlockColumns"),name+": the columns counted");
        assertTrue(sample.value("jacobianTangentsBuilt")>0&&sample.value("jacobianTangentPhases")>0,name+": no phase came from a tangent");
        // The sweep leaves the closure's evaluation of its point as it was.
        assertArrayEquals(f,equations.residual(x),0,name+": the point's residual after the sweep");

        double[] rowScale=new double[size];
        for(int entry=0;entry<rows.length;entry++)rowScale[rows[entry]]=Math.max(rowScale[rows[entry]],Math.abs(reference[entry]));
        double worst=0;int worstEntry=0;
        for(int entry=0;entry<rows.length;entry++) {
            double scale=rowScale[rows[entry]];if(scale==0)scale=1;
            double error=Math.abs(tangent[entry]-reference[entry])/scale;
            if(error>=worst){worst=error;worstEntry=entry;}
        }
        int worstColumn=0;while(offsets[worstColumn+1]<=worstEntry)worstColumn++;
        System.out.printf(Locale.ROOT,"WALL_CLOSURE_JACOBIAN %-30s size %3d worst %.3e (column %d row %d tangent %.9e reference %.9e); phases: tangent %d exact %d no tangent %d; exact columns %d%n",
                name,size,worst,worstColumn,rows[worstEntry],tangent[worstEntry],reference[worstEntry],sample.value("jacobianTangentPhases"),
                sample.value("jacobianExactPhases"),sample.value("jacobianUntangentPhases"),sample.value("jacobianExactColumns"));
        assertTrue(worst<=BOUND,name+": row-scaled difference "+worst+" at column "+worstColumn+" row "+rows[worstEntry]
                +" tangent="+tangent[worstEntry]+" reference="+reference[worstEntry]);
        return new Sweep(layout,equations,x,swept,worst,sample.value("jacobianTangentPhases"),sample.value("jacobianExactPhases"),sample.value("jacobianExactColumns"));
    }

    /** The Tia Juana assay on {@code model}'s basis, its cuts alone where {@code cutsOnly} ({@code DirectLiquidJacobianRowsTest}). */
    private static double[] assay(FluidThermodynamics model,boolean cutsOnly) {
        var crude=com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane");
        var feed=crude.crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions();
        double[] n=new double[model.componentCount()];
        for(int c=0;c<feed.length;c++){String id=crude.componentBasis().componentId(c);if(!cutsOnly||id.startsWith("crude_"))n[model.components().indexOf(id)]=feed[c];}
        return n;
    }

    @Test void aTwoPhaseVesselsEntriesAreTheColumnDifferences() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),NETWORK);
        double[] n=assay(model,false);n[model.components().indexOf("Nitrogen")]=.1;
        var state=model.flashTP(350,150000,n,()->{});
        assertTrue(TestPhases.liquidVolume(state)>0&&state.vaporVolume()>0,"two-phase");
        compare("two-phase vessel",model,state,state.volume(),Double.NaN,1e-6,true);
    }

    @Test void aLiquidFullVesselsEntriesAreTheColumnDifferencesOnTheTautBranch() {
        var model=FluidTestSupport.networkModel();
        var state=model.flashTP(350,1e6,assay(model,true),()->{});
        assertTrue(TestPhases.liquidVolume(state)>0&&state.vaporVolume()==0,"liquid-full");
        compare("liquid-full vessel",model,state,state.volume(),Double.NaN,1e-6,true);
    }

    /**
     * Nitrogen at the ambient in a bag 1e-8 larger, 1e-9 colder in ln T than its charge: the wall is slack, and the
     * temperature column's trial (a step of 1e-6) fills the bag past its capacity, the taut branch on its own. The sweep
     * states that trial's wall row and bag volume on the point's slack branch, as the coloured sweep inside the bracket does.
     */
    @Test void aSlackBagsEntriesAreStatedOnThePointsBranch() {
        var model=FluidTestSupport.networkModel();
        var state=model.initialNitrogenCharge(1,298.15,PhaseLayout.AMBIENT_PRESSURE,()->{});
        double capacity=state.volume()*(1+1e-8);
        var sweep=compare("slack bag",model,state,capacity,PhaseLayout.bagVolume(state,capacity),-1e-9,false);
        int t=temperature(sweep.layout());
        double[] trial=sweep.x().clone();trial[t]+=STEP*sweep.equations().differenceScale(t,trial[t]);
        assertTrue(PhaseLayout.wall(sweep.layout().decode(trial,0),capacity).taut(),"the temperature column's trial is taut on its own branch");
    }

    /** The wet crude at 350 K and 1 atm: a gas, an oil and a small aqueous liquid ({@code TraceTruncationLayoutTest}). */
    @Test void anAqueousLiquidBesideAnOilHasTheColumnDifferences() {
        var model=FluidTestSupport.networkModel();
        double[] n=com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.worldCrude();
        n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=.1;n[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK]=.2;
        var state=model.flashTP(350,101325,n,()->{});
        assertTrue(state.vaporVolume()>0&&TestPhases.liquidVolume(state)>0&&TestPhases.waterVolume(state)>0,"a gas and two liquids");
        compare("aqueous beside oil",model,state,state.volume(),Double.NaN,1e-6,true);
    }

    /** 1 m3 of water at 350 K and 1.6 bar holding 100 kg of 100 um particles ({@code SolidClosureFeasibilityTest}). */
    @Test void aVesselHoldingSolidsHasTheColumnDifferencesOfItsMoments() {
        var model=FluidTestSupport.networkModel();
        double[] moles=new double[model.componentCount()];moles[moles.length-1]=1;
        moles[moles.length-1]/=model.flashTP(350,160000,moles,()->{}).volume();
        var state=model.flashTP(350,160000,moles,()->{}).withSolids(new SolidInventory(List.of(new SolidInventory.Population(
                model.solids.require("icheme:demo_particle"),ParticleSize.micrometres("100"),100))));
        var sweep=compare("vessel with solids",model,state,state.volume(),Double.NaN,1e-6,true);
        assertTrue(sweep.layout().solidVariable(sweep.layout().size()-1),"the layout carries the solid moments");
    }

    /**
     * A vessel of nitrogen at 1.5 bar beside 2e-9 mol of a liquid, half hexane and half a heavy cut, which hold nearly all
     * of both: the vessel is mostly gas, so their amounts are differenced on the bulk floor (1e-4 of the vessel), a step of
     * a tenth of each, and the liquid's composition moves by about 0.02 in their columns. The liquid of those columns is
     * the equation of state's, the gas the tangent's.
     */
    @Test void aColumnThatMovesASmallPhaseTakesThatPhaseFromTheEquationOfState() {
        var model=FluidTestSupport.networkModel();
        int nitrogen=model.components().indexOf("Nitrogen"),hexane=model.components().indexOf("N-hexane"),heavy=model.components().indexOf("crude_pc09");
        assertTrue(nitrogen>=0&&hexane>=0&&heavy>=0);
        int count=model.componentCount();
        double[] vapor=new double[count],liquid=new double[count],aqueous=new double[count];
        vapor[nitrogen]=1;vapor[hexane]=1e-12;vapor[heavy]=1e-15;
        liquid[nitrogen]=1e-12;liquid[hexane]=1e-9;liquid[heavy]=1e-9;
        var state=model.state(300,150000,vapor,liquid,aqueous);
        assertTrue(state.liquidPhase()!=null&&state.vaporPhase()!=null,"a gas beside a small liquid");
        var sweep=compare("small liquid off its tangent",model,state,state.volume(),Double.NaN,1e-6,true);
        assertTrue(sweep.exactPhases()>0&&sweep.exactColumns()>0,"no column took a phase from the equation of state");
    }

    /**
     * Nitrogen at the numerical box's top temperature: its temperature column's trial leaves the box, so the sweep returns
     * -1 and counts the fallback; SparseNewton then runs the coloured sweep with its bounded one-sided stencil.
     */
    @Test void aTrialOutsideTheNumericalBoxHandsTheBuildToTheColouredSweep() {
        var model=FluidTestSupport.networkModel();
        var state=model.initialNitrogenCharge(1,298.15,150000,()->{});
        var layout=new PhaseLayout(model,state);
        var equations=layout.wallClosure(PhaseLayout.totalAmounts(state),state.internalEnergy(),state.volume(),Double.NaN);
        double[] x=layout.encode(state);int t=temperature(layout);
        x[t]=DeterministicMath.log(NumericalBox.MAXIMUM_TEMPERATURE/350);
        while(layout.temperature(x,0)>NumericalBox.MAXIMUM_TEMPERATURE)x[t]=Math.nextDown(x[t]);
        double[] f=equations.residual(x);
        int size=equations.size();
        int[][] columnRows=equations.columnRows();
        int[] offsets=new int[size+1];for(int c=0;c<size;c++)offsets[c+1]=offsets[c]+columnRows[c].length;
        int[] rows=new int[offsets[size]];for(int c=0;c<size;c++)System.arraycopy(columnRows[c],0,rows,offsets[c],columnRows[c].length);
        double[] trial=x.clone();trial[t]+=STEP*equations.differenceScale(t,x[t]);
        assertTrue(layout.temperature(trial,0)>NumericalBox.MAXIMUM_TEMPERATURE,"the temperature column's trial leaves the box");
        boolean enabled=SolverDiagnostics.ENABLED;int swept;SolverDiagnostics.Sample sample;
        try {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            equations.linearising(x);
            try {
                swept=equations.differentiateEntries(x,f,STEP,offsets,rows,new double[rows.length],()->{});
                sample=SolverDiagnostics.sample();
                assertThrows(IllegalArgumentException.class,()->equations.residual(trial),"the coloured sweep's trial is refused too");
            } finally {equations.linearised();}
        } finally {SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
        assertEquals(-1,swept);
        assertEquals(1,sample.value("jacobianBlockFallbacks"));
        assertEquals(0,sample.value("jacobianBlockColumns"));
    }
}
