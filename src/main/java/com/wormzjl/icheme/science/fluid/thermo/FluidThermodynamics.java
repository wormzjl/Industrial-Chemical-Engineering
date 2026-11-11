package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import com.wormzjl.icheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Immutable fluid property snapshot and standalone TP initializer; coupled steps consume phase() directly. Phase
 * decisions (the TP initializer, the outer active-set check, the inventory refresh) come from the equilibrium engine
 * through {@link #equilibriumTP}; residuals never flash.
 *
 * <p>W3b of batch 2026-11-11-aqueous-solvents: water is a component of the cubic ({@link HydrocarbonModel}). A node holds
 * up to three fluid phases in three storage slots of its {@link State}: the vapour ({@link State#VAPOR}), a liquid
 * ({@link State#LIQUID}) and a second liquid ({@link State#AQUEOUS}). The labels a reader draws by, {@link #GAS},
 * {@link #OIL} and {@link #AQUEOUS}, follow from the state by the slot rule ({@link State#slot}): of two liquids the one
 * with the larger water mole fraction is the aqueous one; a single liquid is aqueous when water is at least half of it by
 * moles. Every adoption of an answer places its liquids by that rule, so a Newton pass keeps its storage and the label
 * follows the composition.</p>
 */
public final class FluidThermodynamics {
    public static final double R=8.31446261815324;
    public static final double DEFAULT_MAXIMUM_VELOCITY=100;
    /**
     * The network's ceiling on the trace cutoff, a thousandth of the column's: the network has no reference to answer to
     * inside a Newton pass, and the energy it can misplace by folding an omitted phase into the retained one is bounded
     * by the cutoff times the phase amount times the largest enthalpy of vaporization on the basis, which at 1e-5 is
     * still inside the interval controller's own energy gate.
     */
    public static final double MAX_TRACE_CUTOFF_MOLE_FRACTION=1e-5;
    /** The gameplay default for a network model built without an explicit cutoff, and the default of
     * the {@code fluidTraceCutoffMoleFraction} config entry that supplies one: ten times inside the
     * ceiling. 0 is the exact off switch, the identical numerical path rather than a small cutoff. */
    public static final double DEFAULT_TRACE_CUTOFF_MOLE_FRACTION=1e-6;
    public final HydrocarbonModel hydrocarbon;
    public final com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings;
    public final MixtureViscosity viscosity;
    public final SolidMaterialCatalog solids;
    public final double waterMolecularWeight;
    /** The package's water model: utility water's Region 1 ({@link #waterLiquid}) and the pump's reference density. */
    private final MaterialCatalog.Water water;
    private final double waterEnthalpyOffset;
    private final double maximumVelocity;
    /** Carried on the model, like {@link #maximumVelocity}, because every retained solver, workspace
     * and factorization is keyed on the model: replacing it is what makes a changed cutoff reach a
     * running island deterministically, and {@link #revision} carries it so a certificate issued
     * under a different unknown set is not kept. */
    private final TraceTruncationPolicy tracePolicy;

    /**
     * The gameplay model of the package the caller names: the world's network package
     * ({@link FluidMaterialCatalog#networkPackage}, which the network configuration requires to hold nitrogen) or a view
     * of it on fewer compounds (a simulator sheet's). A saved world is matched to it by compound ids and identity, never
     * by package id; a model without nitrogen refuses a nitrogen charge ({@link #initialNitrogenCharge}). No overload
     * takes a single number after the package: the velocity limit comes with the trace cutoff.
     */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId) {
        return forNetwork(catalog,packageId,DEFAULT_MAXIMUM_VELOCITY,DEFAULT_TRACE_CUTOFF_MOLE_FRACTION);
    }
    /** {@code traceCutoffMoleFraction} is the configured network cutoff: 0 is the exact off switch,
     * and the ceiling is {@link #MAX_TRACE_CUTOFF_MOLE_FRACTION}. */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,
                                                 double maximumVelocity,double traceCutoffMoleFraction) {
        return forNetwork(catalog,packageId,maximumVelocity,traceCutoffMoleFraction,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings.defaults());
    }
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings) {
        TraceTruncationPolicy.requireCutoff(traceCutoffMoleFraction,MAX_TRACE_CUTOFF_MOLE_FRACTION);
        return new FluidThermodynamics(catalog,packageId,
                maximumVelocity,TraceTruncationPolicy.of(traceCutoffMoleFraction),solidSettings);
    }

    /** Placement initializer only. Persistence must restore inventory instead of calling this again. */
    public State initialNitrogenCharge(double volume,double temperature,double pressure,Runnable checkpoint) {
        if(!Double.isFinite(volume)||volume<=0)throw new IllegalArgumentException("Positive finite vessel volume required");
        int nitrogen=hydrocarbon.components().indexOf(FluidMaterialCatalog.NITROGEN);if(nitrogen<0)throw new IllegalStateException("Network basis has no nitrogen");
        double[] n=new double[componentCount()];n[nitrogen]=1;
        var unit=flashTP(temperature,pressure,n,checkpoint);n[nitrogen]=volume/unit.volume();
        return flashTP(temperature,pressure,n,checkpoint);
    }

    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double maximumVelocity,TraceTruncationPolicy tracePolicy,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings) {
        this(catalog,packageId,maximumVelocity,tracePolicy,solidSettings,null);
    }
    /** The model of a compound list (batch 2026-11-04-refinery-compounds, V2): {@code whole}'s package restricted to it. */
    private FluidThermodynamics(MaterialCatalog catalog,String packageId,double maximumVelocity,TraceTruncationPolicy tracePolicy,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings,FluidThermodynamics whole) {
        // A whole model gives its package water as a compound of the cubic where the package does not name it (W3b): the
        // network supplies water itself, no package record is edited for it. A view is restricted from that catalog.
        if(whole==null)catalog=catalog.withWater(packageId);
        this.whole=whole==null?this:whole;views=whole==null?new java.util.HashMap<>():null;
        this.solidSettings=Objects.requireNonNull(solidSettings);
        if(!Double.isFinite(maximumVelocity)||maximumVelocity<=0)throw new IllegalArgumentException("Positive finite maximum velocity required");
        TraceTruncationPolicy.requireCutoff(Objects.requireNonNull(tracePolicy,"tracePolicy").cutoffMoleFraction(),MAX_TRACE_CUTOFF_MOLE_FRACTION);
        this.tracePolicy=tracePolicy;
        this.maximumVelocity=maximumVelocity;
        hydrocarbon=new HydrocarbonModel(catalog,packageId);
        viscosity=new MixtureViscosity(catalog,packageId);solids=catalog.solids();
        int n=hydrocarbon.componentCount();
        waterMolecularWeight=hydrocarbon.molecularWeight(n);
        water=com.wormzjl.icheme.science.material.MaterialRuntime.with(catalog,packageId,
                com.wormzjl.icheme.science.material.MaterialRuntime::water);
        domain=hydrocarbon.domain();
        // The crystal competition: the package's admitted crystals (ice among them on a list with water), on either ideal-gas
        // source (E27). A view without a crystal's compound admits none.
        var admitted=PhaseContract.admittedCrystals(catalog,packageId);
        crystalIds=List.copyOf(admitted);crystalSpecies=new boolean[n+1];crystalTriple=new double[n+1];
        Arrays.fill(crystalTriple,Double.NEGATIVE_INFINITY);
        crystalCeiling=new double[n+1];Arrays.fill(crystalCeiling,Double.NEGATIVE_INFINITY);
        for(String crystal:admitted) {
            var record=catalog.crystals().get(crystal);
            int i=record.component().equals(CubicPhaseEvaluator.WATER)?n:hydrocarbon.components().indexOf(record.component());
            if(i<0)throw new IllegalArgumentException("Crystal "+crystal+" of package "+packageId+" is made of "+record.component()+", which the network basis does not carry");
            crystalSpecies[i]=true;crystalCeiling[i]=Math.max(crystalCeiling[i],record.maximumTemperature());
            crystalTriple[i]=Math.max(crystalTriple[i],record.anchor().temperature());
        }
        // Region 1 at 298.15 K and 1 atm itself, the stable liquid: the pump's density and utility water's datum.
        var reference=WaterRegion1.evaluate(water,298.15,101325);
        waterEnthalpyOffset=com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.liquidEnthalpyOffset(water);
        // The pump's density is the network's own water at that state (W3b: the cubic's, 7e-6 off Region 1, which put a pumped
        // water line 3.5 Pa over its shutoff); a view keeps its whole model's, a package without water Region 1's.
        if(whole!=null)pumpReferenceDensity=whole.pumpReferenceDensity;
        else if(hydrocarbon.wet()) {
            double[] pure=new double[n+1];pure[n]=1;
            pumpReferenceDensity=waterMolecularWeight/hydrocarbon.phase(298.15,101325,pure,PengRobinsonKernel.Root.LIQUID,hydrocarbon.prepare(298.15),false).molarVolume();
        }
        else pumpReferenceDensity=water.molarMass()/(reference.specificVolume()*water.molarMass());
        this.catalog=catalog;
        thermodynamicRevision="fluid-trbdf2-r1:"+hydrocarbon.revision()+":"+viscosity.revision();
        // The trace cutoff joins the hydraulic acceptance law, not the thermodynamic reference: it changes which unknowns a
        // Newton pass carries, so a certificate issued under another cutoff describes a different numerical path - but it
        // changes no property data, so a saved island's inventory and energy stay readable across a change of it.
        // line-cap-saturation-v1 names the cap law of every throttling line (PassiveStepSolver.saturated, 2026-10-18): the
        // smooth saturation of the law's flow at the stream's velocity limit, max= the configured limit it is built from.
        revision=thermodynamicRevision+":line-cap-saturation-v1:trace-relative-v1:max="+Double.toHexString(maximumVelocity)
                +":trace="+Double.toHexString(tracePolicy.cutoffMoleFraction())+":solids-v1:"+solidSettings;
    }
    /**
     * The thermodynamic reference a saved island's inventory and energy are read against (the checkpoint's package
     * table): the network's integrator, the phase model's and the viscosity's revisions. Built once per model.
     */
    private final String thermodynamicRevision;
    /** The model of the whole package this one is a view of (itself for a whole package), and, on the whole one, its views by compound list. */
    private final FluidThermodynamics whole;
    private final java.util.Map<List<String>,FluidThermodynamics> views;
    /** The model this one is a view of: the package on its whole compound list, with the same settings. */
    public FluidThermodynamics whole(){return whole;}
    /** The compounds this model carries, in the package's order, water not among them. */
    public List<String> compounds(){return hydrocarbon.components();}
    /** Whether water is a component of this model's cubic: an island that can hold water (option A, 2026-11-11). */
    public boolean wet(){return hydrocarbon.wet();}
    /**
     * This model's package on {@code compounds} (an island's own list, batch 2026-11-04-refinery-compounds, V2) with water
     * in its cubic where the package carries water: the model of an island where water can arrive (W3b, option A). The same
     * velocity limit, trace cutoff and solid settings; {@link MaterialCatalog#restrict} of the whole package, in its order,
     * built once per list and kept by the whole model. Every compound must be one of the whole package's; water is never
     * named. A state that holds none of the other compounds has the same numbers on it as on the whole package (V1).
     */
    public FluidThermodynamics on(java.util.Collection<String> compounds){return on(compounds,true);}
    /**
     * The model of an island whose compound set is {@code set} (W3b option A, 2026-11-11): {@link #on(java.util.Collection,boolean)}
     * on its compounds, with water in the cubic exactly where the set names {@code Water}. The set is formed where an
     * island's list is (a topology compile, a rebuild from facts): {@code Water} where a node, a filter cake or a stock
     * holds water, or a column or still port can deliver it. Not a per-step decision: an island keeps its model until
     * its list is formed again.
     */
    public FluidThermodynamics island(java.util.Collection<String> set) {
        var compounds=new ArrayList<>(set);boolean water=compounds.remove(CubicPhaseEvaluator.WATER);
        return on(compounds,water);
    }
    /**
     * {@link #on(java.util.Collection)}, with water in the cubic only where {@code water} (option A of 2026-11-11, W3b): an
     * island whose nodes hold no water and whose boundaries can deliver none builds its phases without water, the
     * conserved basis still ending in water at exactly zero. The island decides which model it runs on when its
     * compound list is set (a topology change, a load), never per step.
     */
    public FluidThermodynamics on(java.util.Collection<String> compounds,boolean water) {
        var all=whole.compounds();var wanted=new java.util.HashSet<>(compounds);
        for(String id:wanted)if(!all.contains(id))throw new IllegalArgumentException("Compound "+id+" is not in package "+domain.packageId());
        var list=new ArrayList<>(all.stream().filter(wanted::contains).toList());
        boolean carries=whole.catalog.requirePackage(domain.packageId()).components().contains(CubicPhaseEvaluator.WATER);
        if(water&&carries)list.add(CubicPhaseEvaluator.WATER);
        var key=List.copyOf(list);
        if(key.equals(whole.hydrocarbon.species()))return whole;
        if(key.equals(hydrocarbon.species()))return this;
        synchronized(whole.views) {
            var known=whole.views.get(key);
            if(known!=null)return known;
            var view=new FluidThermodynamics(whole.catalog.restrict(domain.packageId(),key),domain.packageId(),whole.maximumVelocity,whole.tracePolicy,whole.solidSettings,whole);
            whole.views.put(key,view);return view;
        }
    }
    /**
     * The model's full revision: the thermodynamic one with the hydraulic acceptance law (the velocity clamp, the trace
     * cutoff, the solid settings). A certificate holds only under the revision it was issued with, and the world's
     * property guard resumes a held dimension only on its own.
     */
    private final String revision;
    public String thermodynamicRevision(){return thermodynamicRevision;}
    public String revision(){return revision;}
    /** The catalog this model was built from; the phase engine is built from it on first use. */
    private final MaterialCatalog catalog;
    /** The catalog this model was built from: a view's is the whole one's restricted to its list, its package under the same id. */
    public MaterialCatalog catalog(){return catalog;}
    /** The equilibrium engine that decides this model's phases; built on the first phase decision. */
    private volatile NetworkPhaseEngine phaseEngine;
    NetworkPhaseEngine phaseEngine() {
        var engine=phaseEngine;
        if(engine==null)synchronized(this) {
            engine=phaseEngine;
            if(engine==null)phaseEngine=engine=NetworkPhaseEngine.of(this,catalog,hydrocarbon.propertyPackage().id(),FluidTpEquilibrium.Settings.DEFAULT);
        }
        return engine;
    }
    /** The package's crystals as solids ({@link NetworkCrystals}): the crystals it admits, ice among them on a list with
     * water; built with the phase engine on first use. */
    private volatile NetworkCrystals crystalSolids;
    public NetworkCrystals crystalSolids() {
        var crystals=crystalSolids;
        if(crystals==null)synchronized(this) {
            crystals=crystalSolids;
            if(crystals==null)crystalSolids=crystals=NetworkCrystals.of(this,phaseEngine(),crystalIds,hydrocarbon.propertyPackage().id());
        }
        return crystals;
    }
    /**
     * The record a solid population's material id names (owner decision D23: crystals are solids): a crystal this package
     * admits ({@link #crystalSolids}), else a custom solid of the catalog ({@link #solids}); the catalog keeps the two
     * kinds' ids apart, so an id names one record. An id that names neither is refused: nothing read back (a checkpoint's
     * population, a pending recovery) can carry a solid this package does not define. Builds the crystals (and the phase
     * engine) only for an id among the package's crystals.
     */
    public SolidMaterialCatalog.SolidMaterial solid(String id) {
        if(crystalIds.contains(id))for(var crystal:crystalSolids().all())if(crystal.material().id().equals(id))return crystal.material();
        var custom=solids.materials().get(id);
        if(custom==null)throw new IllegalArgumentException("Unknown solid material: "+id+" names no crystal of package "+domain.packageId()+" and no solid material record");
        return custom;
    }
    /**
     * The crystal answer of a vessel's refresh (owner decision D23): the engine's answer for the vessel's fluid and
     * crystals together. {@code fluid} is its fluid phases as a network state without solids (null where the crystals
     * hold everything); {@code crystalMoles} the moles of each of the package's crystals ({@link NetworkCrystals#all}
     * order); {@code volume} the fluid's and the crystals' volume, the crystals in the engine's own model.
     */
    public record CrystalAnswer(double temperature,double pressure,State fluid,double[] crystalMoles,double volume) {
        public CrystalAnswer{crystalMoles=crystalMoles.clone();}
        @Override public double[] crystalMoles(){return crystalMoles.clone();}
        /** Whether a crystal is stable in this answer. */
        public boolean holdsCrystal(){for(double n:crystalMoles)if(n>0)return true;return false;}
    }
    /**
     * The crystal test of a taut vessel (D23, with D21's near-pure step): the equilibrium of {@code totals} (the conserved
     * basis, water last) at internal energy {@code energy} (J, the network's datum, inert solids excluded) and volume
     * {@code volume} (m3, the bag less the inert solids), the package's crystals competing where {@code competing}
     * ({@link #crystalsCompete}), warm-started at {@code (hintT, hintP)} when both are finite. Refusals are typed where the
     * engine types them ({@link UnsupportedPhases} of kind {@code CRYSTAL_PHASES} or {@code CRYSTAL_STEP},
     * {@link CriticalBandHold}); any other failure is a plain {@link IllegalArgumentException}.
     */
    public CrystalAnswer crystalsAtVolume(double[] totals,double energy,double volume,double hintT,double hintP,boolean competing,Runnable checkpoint) {
        checkpoint.run();
        var result=phaseEngine().crystalUv(engineEnergy(energy,totals),volume,totals,competing,hintT,hintP);
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        return crystalAnswer(result,totals);
    }
    /** {@link #crystalsAtVolume} for a slack vessel: the equilibrium at {@code pressure} (the ambient) with enthalpy
     * {@code enthalpy} (J, the network's datum, the fluid's and the crystals' only). */
    public CrystalAnswer crystalsAtPressure(double[] totals,double enthalpy,double pressure,boolean competing,Runnable checkpoint) {
        checkpoint.run();
        var result=phaseEngine().ph(pressure,engineEnergy(enthalpy,totals),totals,competing);
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        return crystalAnswer(result,totals);
    }
    /** A network-datum energy of {@code totals} on the engine's datum (D10): the formation enthalpies added per species on a
     * spine package; the network's own datum elsewhere ({@link NetworkPhaseEngine}). */
    private double engineEnergy(double energy,double[] totals) {
        var spine=hydrocarbon.spine();
        if(spine.isEmpty())return energy;
        var formation=spine.get().formationReference();
        return EnergyReference.sensible(formation.components()).rebase(energy,Arrays.copyOf(totals,hydrocarbon.componentCount()),formation);
    }
    private CrystalAnswer crystalAnswer(EquilibriumResult result,double[] totals) {
        var crystals=crystalSolids();
        switch(result.status()) {
            case CONVERGED->{}
            case UNSUPPORTED->{
                if(result.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES))throw new UnsupportedPhases(domain.packageId(),Double.NaN,Double.NaN,
                        UnsupportedPhases.Kind.CRYSTAL_PHASES,"a crystal beside a phase set the engine does not implement ("+result.detail()+")");
                throw new IllegalStateException("The network's equilibrium engine refused a crystal request it must answer: "+result.detail());
            }
            case NOT_CONVERGED->{
                if(result.detail().startsWith(EquilibriumResult.CRYSTAL_STEP))throw new UnsupportedPhases(domain.packageId(),Double.NaN,Double.NaN,
                        UnsupportedPhases.Kind.CRYSTAL_STEP,"a near-pure feed's crystal step the pure species' step does not answer ("+result.detail()+")");
                if(result.diagnostics().criticalBand())throw new CriticalBandHold(domain.packageId(),Double.NaN,Double.NaN,result.detail());
                throw new IllegalArgumentException("Crystal equilibrium did not converge: "+result.detail());
            }
        }
        var first=result.phases().getFirst();double t=first.temperature(),p=first.pressure();
        var all=crystals.all();double[] moles=new double[all.size()];double volume=0;
        var fluids=new ArrayList<PhaseAmounts>();
        for(var phase:result.phases()) {
            if(phase.kind()==PhaseKind.SOLID) {
                int k=-1;for(int i=0;i<all.size();i++)if(all.get(i).material().id().equals(phase.crystal()))k=i;
                if(k<0)throw new IllegalStateException("The engine answered crystal "+phase.crystal()+", which the network does not carry");
                moles[k]+=phase.amount(phase.amountsView().length==componentCount()?all.get(k).component():engineIndex(all.get(k).component()));
                volume+=phase.total()*phase.state().molarVolume();continue;
            }
            fluids.add(phase);
        }
        var terms=hydrocarbon.prepare(t);
        State state=fluids.isEmpty()?null:placed(t,p,fluids,terms,UnsupportedPhases.Kind.CRYSTAL_PHASES);
        return new CrystalAnswer(t,p,state,moles,volume+(state==null?0:state.volume()));
    }
    /** A conserved index on the engine's basis (the same but water's on a dry list, which has no water). */
    private int engineIndex(int component){return component;}
    /** The package's data ranges: its envelope, the input box of a device setting ({@link FluidDomain}). */
    public FluidDomain domain(){return domain;}
    /**
     * The data ranges the state at network node {@code node} lies outside, into {@code notices} (every model continues
     * past its data and the island's status line reports it): the package envelope; each carried component's ideal-gas
     * data and the viscosity data of each phase it is in (water's liquid viscosity, Region 1's range); each crystal's
     * record. Reads the state and the declared ranges only.
     */
    public void dataRanges(State state,long node,DataRangeNotices notices) {
        double t=state.temperature(),p=state.pressure();
        var envelope=domain.envelope();
        notices.check("package envelope temperature",t,envelope.minimumTemperature(),envelope.maximumTemperature(),"K",node);
        notices.check("package envelope pressure",p,envelope.minimumPressure(),envelope.maximumPressure(),"Pa",node);
        var eos=hydrocarbon.translated();var names=components();
        double[] vapor=state.vaporAmountsView(),liquid=state.liquidAmountsView(),aqueous=state.aqueousAmountsView();
        int n=hydrocarbon.componentCount();
        for(int i=0;i<=n;i++) {
            boolean condensed=liquid[i]>0||aqueous[i]>0;
            if(!condensed&&!(vapor[i]>0))continue;
            String name=names.get(i);
            int kernel=hydrocarbon.wet()||i<n?i:-1;
            if(kernel>=0)notices.check(name+" ideal-gas heat capacity",t,eos.idealGasMinimumTemperature(kernel),eos.idealGasMaximumTemperature(kernel),"K",node);
            if(i==n) {
                var data=viscosity.waterLiquidData();
                if(condensed&&data!=null)notices.check("liquid water viscosity",t,water.triplePoint(),WaterRegion1.REGION_1_MAXIMUM_TEMPERATURE,"K",node);
                continue;
            }
            var liquidData=condensed?viscosity.liquidData(i,t):null;
            if(liquidData!=null)notices.check(name+" liquid viscosity",t,liquidData.minimumTemperatureKelvin(),liquidData.maximumTemperatureKelvin(),"K",node);
            var vaporData=vapor[i]>0?viscosity.vaporData(i):null;
            if(vaporData!=null)notices.check(name+" vapour viscosity",t,vaporData.minimumTemperatureKelvin(),vaporData.maximumTemperatureKelvin(),"K",node);
        }
        for(var population:state.solids().populations()) {
            if(population.material().crystal()==null)continue;
            var record=crystalSolids().of(population.material()).anchored().record();
            notices.check(record.component()+" solid",t,record.minimumTemperature(),record.maximumTemperature(),"K",node);
            notices.check(record.component()+" solid pressure",p,record.minimumPressure(),record.maximumPressure(),"Pa",node);
        }
    }
    private final FluidDomain domain;
    /** The crystals the package admits, in declaration order; empty without a crystal competition. */
    private final List<String> crystalIds;
    /** Whether a conserved component (water last) is the species of an admitted crystal, and its crystal's triple-point temperature. */
    private final boolean[] crystalSpecies;
    private final double[] crystalTriple;
    /** Per conserved component, the highest ceiling of its admitted crystals' records: above it no crystal of it competes
     * (W3b: per species, so ice's 273.16 K does not cap carbon dioxide's 300 K). */
    private final double[] crystalCeiling;
    /**
     * The density a pump's "maximum pressure rise" is stated for: liquid water at 298.15 K and 101,325 Pa, from the
     * package's water model (IF97 Region 1 at that state), 997.05 kg/m3. A pump's rise limit on another fluid is the
     * setting scaled by the density of what it withdraws over this one: a head. See {@code PassiveStepSolver.riseLimit}.
     */
    public double pumpReferenceDensity(){return pumpReferenceDensity;}
    private final double pumpReferenceDensity;
    /** Conserved component basis: the compounds, followed by water. */
    public int componentCount() { return hydrocarbon.componentCount()+1; }

    /** Immutable conserved-component names, including water in the final slot. */
    public java.util.List<String> components() {
        var components=new java.util.ArrayList<>(hydrocarbon.components());
        components.add(CubicPhaseEvaluator.WATER);
        return java.util.List.copyOf(components);
    }

    /** Molecular weight in kg/mol for an index in the complete conserved basis. */
    public double molecularWeight(int component) {
        return hydrocarbon.molecularWeight(component);
    }

    /** Independently owned molecular-weight array in conserved-component order. */
    public double[] molecularWeights() {
        double[] weights=new double[componentCount()];
        for(int c=0;c<weights.length;c++)weights[c]=molecularWeight(c);
        return weights;
    }
    public double maximumVelocityMetresPerSecond(){return maximumVelocity;}
    /** The per-node phase support the step solver freezes its Newton unknowns with. */
    public TraceTruncationPolicy traceTruncation(){return tracePolicy;}
    /** A hydraulic rate bound only; temperature and phase allocation still come from the EOS/energy equations. */
    public double velocityLimit(State state){return Math.min(maximumVelocity,isothermalAcousticBound(state));}
    /**
     * Frozen-phase isothermal acoustic bound used by the configured hydraulic velocity clamp: the node's compliance is
     * {@code sum_phases V_phase kappa_phase}, each phase's own isothermal compressibility at the state.
     */
    public double isothermalAcousticBound(State state) {
        double compliance=0;
        for(int slot=0;slot<3;slot++) {
            var phase=state.phase(slot);
            if(phase!=null&&state.slotVolume(slot)>0)compliance+=state.slotVolume(slot)*phase.isothermalCompressibility();
        }
        // Solids are incompressible here: a state holding no fluid (a vessel holding only solids, D33) has no acoustic
        // bound, and the configured clamp alone limits what would leave it (nothing does: its ends are dry).
        if(compliance==0&&!holdsFluid(state))return Double.POSITIVE_INFINITY;
        double speed=state.volume()/Math.sqrt(state.mass()*compliance);
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid acoustic flow-domain bound");
        return speed;
    }
    /**
     * The gas's ideal-gas heat capacity ratio {@code k = c_p/(c_p − R)} at the state's temperature, for a compressor's
     * polytropic exponent (batch 2026-11-03-rotating-machinery, plan section 3.2): the vapour's components on the
     * package's own ideal-gas data (water's from its cubic record on a list with water). 1.4 for a state that holds no gas.
     */
    public double heatCapacityRatio(State state) {
        double t=state.temperature(),moles=0,cp=0;
        double[] vapor=state.vaporAmountsView();int n=hydrocarbon.componentCount();
        double total=0;for(double x:vapor)total+=x;
        if(!(total>0))return 1.4;
        var terms=hydrocarbon.prepare(t);
        for(int i=0;i<vapor.length;i++) {
            if(!(vapor[i]>0))continue;
            // Water on a list without water cannot be in a phase (HydrocarbonModel.kernelAmounts).
            cp+=vapor[i]*terms.idealGasHeatCapacity(i<n||hydrocarbon.wet()?i:0);moles+=vapor[i];
        }
        cp/=moles;
        return cp/(cp-R);
    }
    /**
     * The fluid a phase-selective vessel outlet draws, read off the vessel's own state with no flash and no extra
     * equation-of-state root: every term is a sum over the phase amounts, volumes and molar properties {@link #state}
     * already computed. Three streams partition the state: the gas ({@link #GAS}), the oil ({@link #OIL}) and the aqueous
     * liquid ({@link #AQUEOUS}), labelled by the slot rule ({@link State#slot}); each liquid carries its volume share of
     * every solid population (the solids are a uniform suspension in the combined liquid). The gas's velocity limit is
     * {@link #isothermalAcousticBound}'s formula restricted to the gas; a liquid stream's limit is the configured maximum.
     * A stream whose phase the state does not hold is not built ({@code null}): a port then draws the next phase of its
     * priority order.
     */
    public record PhaseStream(double[] moles,double mass,double volume,double viscosity,double specificEnthalpy,double velocityLimit,
                              SolidInventory.Moments solidMoments) {
        public PhaseStream {Objects.requireNonNull(moles);Objects.requireNonNull(solidMoments);}
        /** The moles themselves, for the solver packages. The caller must not mutate them. */
        public double[] molesView(){return moles;}
        @Override public double[] moles(){return moles.clone();}
    }
    /** Whether the state holds a gas phase to draw. */
    public static boolean holdsVapor(State state){return state.vaporVolume()>0;}
    /** Whether the state holds a liquid to draw (solids alone do not flow as a liquid). */
    public static boolean holdsLiquid(State state){return state.condensedVolume()>0;}
    /** Mass of a slot's fluid, kg. */
    public double slotMass(State state,int slot) {
        double mass=0;var amounts=state.amountsView(slot);
        for(int i=0;i<amounts.length;i++)mass+=amounts[i]*molecularWeight(i);
        return mass;
    }
    /** Mass of the vapour stream. */
    public double vaporMass(State state){return slotMass(state,State.VAPOR);}
    /** Mass of the condensed phase: both liquids, then every solid (the level head's mass). */
    public double liquidMass(State state){return slotMass(state,State.LIQUID)+slotMass(state,State.AQUEOUS)+state.solidMoments().mass();}
    /** Volume of the condensed phase: the two liquid volumes and the solid volume. */
    public static double liquidStreamVolume(State state){return state.condensedVolume()+state.solidMoments().volume();}
    /** The vapour stream's density, for a state that {@link #holdsVapor}. */
    public double vaporDensity(State state){return vaporMass(state)/state.vaporVolume();}
    /** The condensed phase's density (its mass over its volume), for a state that {@link #holdsLiquid}. */
    public double liquidDensity(State state){return liquidMass(state)/liquidStreamVolume(state);}
    /** The vapour stream's specific enthalpy (J/kg). */
    public double vaporSpecificEnthalpy(State state,Prepared prepared) {
        var gas=state.phase(State.VAPOR);
        return gas==null?0:total(state.vaporAmountsView())*gas.molarEnthalpy()/vaporMass(state);
    }
    /** The vapour stream's velocity limit: {@code min(maximum, V_v/sqrt(M_v V_v kappa_v))}. */
    public double vaporVelocityLimit(State state) {
        var gas=state.phase(State.VAPOR);
        double compliance=gas==null?0:state.vaporVolume()*gas.isothermalCompressibility();
        return Math.min(maximumVelocity,acoustic(state.vaporVolume(),vaporMass(state),compliance));
    }
    /**
     * {@code V / sqrt(M C)}. The product of a phase's mass and compliance goes subnormal for a trace phase (a vanishing
     * nitrogen vapour below about 2e-150 mol) and is zero below about 2e-158 mol, the bound then infinite; such a product
     * is evaluated as {@code sqrt(V/M) sqrt(V/C)}, ratios of like-sized quantities, and every product at or above
     * {@link Double#MIN_NORMAL} takes the original expression, bit for bit.
     */
    private static double acoustic(double volume,double mass,double compliance) {
        double product=mass*compliance;
        double speed=product>=Double.MIN_NORMAL?volume/Math.sqrt(product):Math.sqrt(volume/mass)*Math.sqrt(volume/compliance);
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid acoustic flow-domain bound");
        return speed;
    }
    /** The vapour stream's viscosity (Wilke over its components, water among them). */
    public double vaporViscosity(State state,MixtureViscosity.Workspace scratch,Prepared prepared) {
        var terms=prepared==null?null:match(prepared,state.temperature()).viscosities();
        return viscosity.vapor(state.temperature(),state.vaporAmountsView(),scratch,terms);
    }
    /** The vapour stream of {@code state}, or null when it holds no gas. */
    public PhaseStream vaporStream(State state,Prepared prepared,MixtureViscosity.Workspace scratch) {
        if(!holdsVapor(state))return null;
        return new PhaseStream(state.vaporAmounts(),vaporMass(state),state.vaporVolume(),vaporViscosity(state,scratch,prepared),
                vaporSpecificEnthalpy(state,prepared),vaporVelocityLimit(state),SolidInventory.Moments.ZERO);
    }
    /*
     * The three phases a vessel port can draw one after another (decision D11: ports carry mixed phases by priority), by
     * label: GAS, OIL (the liquid with less water) and AQUEOUS (the liquid with more; a single liquid that is at least half
     * water). Each liquid carries its volume share of every solid population, so the two liquid streams partition the
     * condensed phase, and with one liquid present its stream carries every solid.
     */
    public static final int GAS=0,OIL=1,AQUEOUS=2;
    /** Whether {@code state} holds the phase labelled {@code phase} ({@link #GAS}, {@link #OIL} or {@link #AQUEOUS}). */
    public static boolean holdsPhase(State state,int phase){return state.slot(phase)>=0;}
    /** Whether the state holds any fluid phase: not a vessel holding only solids ({@link #solidState}). */
    public static boolean holdsFluid(State state){return state.vaporVolume()>0||state.condensedVolume()>0;}
    /**
     * Owner decision D33: solids ride the liquid. A connection's BULK end draws a uniform share of what its donor holds,
     * the solids carried by the liquid at {@code solids * liquid drawn / liquid held}; where no liquid is held nothing
     * carries them (frost on a tank's walls, a dry bed), so the end draws the donor's gas exactly where that differs from
     * its bulk: a gas beside solids with no liquid (a stand-in liquid, D29 A, is a liquid here).
     */
    public static boolean bulkDrawsGas(State state) {
        return !(state.condensedVolume()>0)&&state.vaporVolume()>0&&!state.solids().empty();
    }
    /** The share of the state's solids a phase stream carries: none in the gas, the liquids' in proportion to their
     * volumes ({@code 1.0} exactly for the only liquid held). */
    public static double phaseSolidShare(State state,int phase) {
        if(phase==GAS)return 0;
        int slot=state.slot(phase);double condensed=state.condensedVolume();
        if(slot<0||!(condensed>0))return 0;
        return state.slotVolume(slot)==condensed?1.0:state.slotVolume(slot)/condensed;
    }
    /** The own density (no solids) of the liquid in storage slot {@code slot}: its fluid mass over its volume. */
    private double liquidPhaseDensityOfSlot(State state,int slot){return slotMass(state,slot)/state.slotVolume(slot);}
    /** The own density (no solids) of a liquid phase the state holds: its fluid mass over its volume. */
    public double liquidPhaseDensity(State state,int phase) {
        int slot=state.slot(phase);
        return slotMass(state,slot)/state.slotVolume(slot);
    }
    /** The heavier of the state's two liquids by their own densities: {@link #OIL} only when both are held and the oil is
     * the denser, {@link #AQUEOUS} otherwise (which one is named when at most one is held does not matter to a priority
     * order: an absent phase is skipped). */
    public int heavierLiquid(State state) {
        return holdsPhase(state,OIL)&&holdsPhase(state,AQUEOUS)&&liquidPhaseDensity(state,OIL)>liquidPhaseDensity(state,AQUEOUS)?OIL:AQUEOUS;
    }
    /** Mass of a phase stream, its solid share included; {@link #vaporMass} for the gas. */
    public double phaseMass(State state,int phase) {
        if(phase==GAS)return vaporMass(state);
        int slot=state.slot(phase);
        return (slot<0?0:slotMass(state,slot))+phaseSolidShare(state,phase)*state.solidMoments().mass();
    }
    /** Volume of a phase stream: the gas volume, or the liquid's volume plus its share of the solid volume. */
    public static double phaseVolume(State state,int phase) {
        if(phase==GAS)return state.vaporVolume();
        int slot=state.slot(phase);
        return (slot<0?0:state.slotVolume(slot))+phaseSolidShare(state,phase)*state.solidMoments().volume();
    }
    /** The conserved amounts (water last) of a phase stream. */
    public static double[] phaseMoles(State state,int phase) {
        int slot=state.slot(phase);
        return slot<0?new double[state.componentCount()]:state.amounts(slot);
    }
    /** The solid moments a phase stream carries: its share of the state's. */
    public static SolidInventory.Moments phaseSolidMoments(State state,int phase) {
        double share=phaseSolidShare(state,phase);if(share==0)return SolidInventory.Moments.ZERO;
        var m=state.solidMoments();return new SolidInventory.Moments(share*m.mass(),share*m.volume(),share*m.heatCapacity());
    }
    /** A phase stream's specific enthalpy (J/kg), its solid share included; {@code prepared} as for {@link #state}. */
    public double phaseSpecificEnthalpy(State state,int phase,Prepared prepared) {
        if(phase==GAS)return vaporSpecificEnthalpy(state,prepared);
        int slot=state.slot(phase);double h=0;
        if(slot>=0&&state.phase(slot)!=null)h+=total(state.amountsView(slot))*state.phase(slot).molarEnthalpy();
        h+=phaseSolidShare(state,phase)*state.solidMoments().enthalpy(state.temperature(),state.pressure());
        return h/phaseMass(state,phase);
    }
    /** A phase stream's velocity limit: {@link #vaporVelocityLimit} for the gas, the configured maximum for a liquid
     * with its solids (a liquid's own isothermal sound speed is 900 to 1500 m/s at the network's states, D37 S5). */
    public double phaseVelocityLimit(State state,int phase) {
        return phase==GAS?vaporVelocityLimit(state):maximumVelocity;
    }
    /** A phase stream's viscosity: {@link #vaporViscosity} for the gas; a liquid's own viscosity (one log-mixing rule over
     * its components, water among them) with the slurry correction for its share of the solids, over the stream's volume. */
    public double phaseViscosity(State state,int phase,MixtureViscosity.Workspace scratch,Prepared prepared) {
        if(phase==GAS)return vaporViscosity(state,scratch,prepared);
        var terms=prepared==null?null:match(prepared,state.temperature()).viscosities();
        int slot=state.slot(phase);
        double liquid=state.slotVolume(slot),value=liquid*viscosity.liquid(state.temperature(),state.amountsView(slot),terms).pascalSeconds();
        double solid=phaseSolidShare(state,phase)*state.solidMoments().volume();
        if(solid>0){double phi=solid/(liquid+solid);value=com.wormzjl.icheme.science.fluid.transport.SlurryTransport.effectiveViscosity(value/liquid,Math.min(phi,0.62-1e-9))*(liquid+solid);}
        return value/(liquid+solid);
    }
    /** The carrier viscosity of a phase stream (no slurry correction), which an inline filter's cake resistance scales
     * with: the gas's own, or the liquid's own. */
    public double phaseCarrierViscosity(State state,int phase,MixtureViscosity.Workspace scratch) {
        if(phase==GAS)return vaporViscosity(state,scratch,null);
        return viscosity.liquid(state.temperature(),state.amountsView(state.slot(phase))).pascalSeconds();
    }
    /** The stream of one phase of {@code state}, or null when it does not hold that phase; {@link #vaporStream} for the
     * gas. */
    public PhaseStream phaseStream(State state,int phase,Prepared prepared,MixtureViscosity.Workspace scratch) {
        if(!holdsPhase(state,phase))return null;
        if(phase==GAS)return vaporStream(state,prepared,scratch);
        return new PhaseStream(phaseMoles(state,phase),phaseMass(state,phase),phaseVolume(state,phase),phaseViscosity(state,phase,scratch,prepared),
                phaseSpecificEnthalpy(state,phase,prepared),phaseVelocityLimit(state,phase),phaseSolidMoments(state,phase));
    }
    /** The amount sum {@link #state} forms ({@code sum}), without its validation: a state's own amounts are valid. */
    private static double total(double[] values){double total=0;for(double value:values)total+=value;return total;}
    /**
     * Utility water (cooling towers, steam services) at the state pressure: Region 1 and its continuation past its range
     * ({@link WaterRegion1#evaluateLiquid}), on the package's water model. Not a network phase since W3b.
     */
    public WaterLiquid waterLiquid(double t,double p) {
        var w=new WaterRegion1.Workspace();WaterRegion1.evaluateLiquid(water,t,p,w);
        return new WaterLiquid(w.specificVolume()*water.molarMass(),w.specificEnthalpy()*water.molarMass()+waterEnthalpyOffset);
    }
    /**
     * Utility steam's ideal-gas molar enthalpy at {@code t} (J/mol, the datum of {@link #waterLiquid}): the water record's
     * Shomate polynomial, held at its end heat capacity past its range (owner rule of 2026-10-16). A cooling tower's
     * humid air; not a network phase since W3b.
     */
    public double vaporWaterEnthalpy(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        double low=water.triplePoint(),high=water.maximumTemperature(),at=Math.max(low,Math.min(high,t));
        double h=com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.vaporMolarEnthalpy(water,at);
        return at==t?h:h+com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.dVaporMolarEnthalpyDT(water,at)*(t-at);
    }
    /** Utility water's saturation pressure at {@code t} (Pa), the package's water record: steam services and ejectors. Not a
     * network phase rule since W3b. */
    public double saturationPressure(double t) {
        // Wagner-Pruss continued past its ends (a data range never refuses), infinite above the critical temperature.
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        return t>=water.criticalTemperature()?Double.POSITIVE_INFINITY:com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.wagnerPruss(water,t);
    }

    /**
     * A node's split in the record the saves and the checkpoint carry since before W3b (interim: step 6 of the batch
     * restates those formats): the compounds of both liquids, the compounds of the vapour, the water of the liquids and of
     * the vapour, and the vapour's compound partial pressure {@code P (1 - y_w)} (the meaning that field had). Amounts are
     * kept exactly; a second liquid's composition is not, and the reader ({@link #fromLegacySplit}) states the compounds'
     * liquid and the water as two liquids, which the next phase check and refresh equilibrate.
     */
    public record LegacySplit(double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {}
    /** {@code s} in the pre-W3b record ({@link LegacySplit}). */
    public static LegacySplit legacySplit(State s) {
        int n=s.componentCount()-1;double[] l=new double[n],v=new double[n];
        double[] vapor=s.vaporAmountsView(),liquid=s.liquidAmountsView(),aqueous=s.aqueousAmountsView();
        for(int i=0;i<n;i++){l[i]=liquid[i]+aqueous[i];v[i]=vapor[i];}
        double nv=total(vapor),wv=vapor[n];
        return new LegacySplit(l,v,s.liquidWater(),wv,nv>0?s.pressure()*((nv-wv)/nv):0);
    }
    /** The state of a pre-W3b record ({@link LegacySplit}) at {@code (t, p)}: the vapour with its water, the compounds'
     * liquid, and the liquid water as a second liquid (one liquid where only one of them is held), placed by the slot rule. */
    public State fromLegacySplit(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor) {
        int n=componentCount();
        if(liquid.length!=n-1||vapor.length!=n-1)throw new IllegalArgumentException("Fluid state basis mismatch");
        double[] v=Arrays.copyOf(vapor,n),l=Arrays.copyOf(liquid,n),a=new double[n];v[n-1]=waterVapor;
        if(total(liquid)>0)a[n-1]=waterLiquid;else l[n-1]=waterLiquid;
        return placedState(t,p,v,l,a,null);
    }
    /** Direct properties of specified phase amounts (conserved basis, water last), without an inner flash. */
    public State state(double t,double p,double[] vapor,double[] liquid,double[] aqueous) {
        return state(t,p,vapor.clone(),liquid.clone(),aqueous.clone(),null,null);
    }
    /** Every temperature-only property comes from the prepared bundle; a standalone caller passes
     * {@code null} and each one is computed here. */
    public State state(double t,double p,double[] vapor,double[] liquid,double[] aqueous,Prepared prepared) {
        return state(t,p,vapor.clone(),liquid.clone(),aqueous.clone(),null,match(prepared,t));
    }
    /** The returned state adopts the three arrays instead of copying them: for a caller that allocated them for this call
     * and never reads or writes them again. */
    public State adoptingState(double t,double p,double[] vapor,double[] liquid,double[] aqueous,Prepared prepared) {
        return state(t,p,vapor,liquid,aqueous,null,match(prepared,t));
    }
    private State state(double t,double p,double[] vapor,double[] liquid,double[] aqueous,
                        TranslatedPengRobinson.Workspace terms,Prepared prepared) {
        if(SolverDiagnostics.ENABLED) {
            SolverDiagnostics.stateCalls.increment();
            if(SolverDiagnostics.inJacobian())SolverDiagnostics.stateCallsInJacobian.increment();
        }
        int n=componentCount();
        if(vapor.length!=n||liquid.length!=n||aqueous.length!=n)throw new IllegalArgumentException("Fluid state basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid state is not finite");
        // No data range bounds a state (owner rule of 2026-10-16: every property continues past its data, and the
        // interval's notice reports it); only the numerical box refuses a trial, a plain IllegalArgumentException.
        NumericalBox.require(t,p);
        double nv=sum(vapor),nl=sum(liquid),na=sum(aqueous),volume=0,h=0,mass=0,vv=0,vl=0,va=0;
        if(terms==null&&(nv>0||nl>0||na>0))terms=prepared==null?hydrocarbon.prepare(t):prepared.pengRobinson();
        HydrocarbonModel.Phase gp=null,lp=null,ap=null;
        // Every phase at the state pressure; with a vapour beside it, a liquid slot on a single vapour-like root is refused.
        if(nv>0){gp=hydrocarbon.phase(t,p,vapor,PengRobinsonKernel.Root.VAPOR,terms,false);vv=nv*gp.molarVolume();volume+=vv;h+=nv*gp.molarEnthalpy();}
        if(nl>0){lp=hydrocarbon.phase(t,p,liquid,PengRobinsonKernel.Root.LIQUID,terms,nv>0);vl=nl*lp.molarVolume();volume+=vl;h+=nl*lp.molarEnthalpy();}
        if(na>0){ap=hydrocarbon.phase(t,p,aqueous,PengRobinsonKernel.Root.LIQUID,terms,nv>0);va=na*ap.molarVolume();volume+=va;h+=na*ap.molarEnthalpy();}
        for(int i=0;i<n;i++)mass+=(vapor[i]+liquid[i]+aqueous[i])*molecularWeight(i);
        if(!(volume>0)||!(mass>0)||!Double.isFinite(h))throw new IllegalArgumentException("Empty/nonfinite fluid properties");
        return new State(t,p,vapor,liquid,aqueous,volume,h,h-p*volume,mass,vv,vl,va,gp,lp,ap,SolidInventory.EMPTY,SolidInventory.Moments.ZERO);
    }

    /** Stationary dry inventory. Pressure is retained as an initialization hint, not a gas pressure. */
    public State solidState(double temperature,double pressure,com.wormzjl.icheme.science.fluid.state.SolidInventory solids) {
        if(solids.empty()||!Double.isFinite(temperature)||!Double.isFinite(pressure))throw new IllegalArgumentException("Dry solid state needs solids and a finite temperature and pressure");
        NumericalBox.require(temperature,pressure);
        var moments=solids.moments();var empty=new double[componentCount()];
        return new State(temperature,pressure,empty,empty,empty,moments.volume(),
                moments.enthalpy(temperature,pressure),moments.internalEnergy(temperature),moments.mass(),0,0,0,null,null,null,solids,moments);
    }

    /**
     * Owner decision D34: a fluid phase holding less than this share of its node's fluid amount is a trace phase and does
     * not exist; its material is in the node's other phases (A83). The share is taken in moles over every fluid phase and
     * component of the node, solids not counted: the unit the Newton states a node's balances in, so a phase below it is
     * below what the solve resolves, the same number for a condensate film in a gas and for the last bubble in a liquid.
     */
    public static final double PHASE_TRACE_FRACTION=1e-9;
    /**
     * The share under which a phase that is shrinking is vanishing ({@link #vanishing}), in any node: a phase holding
     * less than this share of its node's fluid, and a smaller share than where the solve that judges it started, leaves,
     * at a converged point as at a failed iterate (owner decision D38 C). A closure's 30 iterations end at a residual near
     * 1e-6 of the node's amount. A phase that does not shrink keeps the trace share ({@link #PHASE_TRACE_FRACTION})
     * alone; a proposal the next pass drives under this share is refused (owner decision D39 B). The share is taken by
     * volume, and for the gas by moles too ({@link #belowShare}).
     */
    public static final double VANISHING_SHARE=1e-6;
    /**
     * The share of its start share under which a shrinking phase of a failed iterate is collapsing ({@link #collapsing};
     * t38 form C, 2026-10-18): a solve that drives a phase to nothing moves its splits along a near-null direction of the
     * equilibrium block, so the Newton crawls and stops at its iteration limit with the phase at any small share (a
     * bubble-point receiver's seed vapour of 8 % by mass stopped at 1.3e-6, a hair over {@link #VANISHING_SHARE}, at every
     * step size). A phase a thousand times below where the solve started it is going, whatever share it stands at.
     */
    public static final double VANISHING_COLLAPSE=1e-3;
    /**
     * Folds the trace phases of one split in place (owner decisions D33, D34): nothing leaves the node and nothing is
     * booked, so every component amount and the node's mass are unchanged. The rule (A84, restated on three slots by W3b):
     * a trace liquid goes into the vapour where the vapour stands, else into the other liquid where that stands; a trace
     * vapour goes into the liquids that stand, each component shared between two standing liquids in proportion to what
     * they hold of it (into the larger where they hold none); a trace phase none of whose receivers stands stays, and a
     * receiving phase that is itself a trace receives nothing. {@code vanishing} ({@link #phaseBits}) names phases folded
     * as traces by the same rule (owner decision D38 C), which receive nothing either. Returns whether anything moved.
     *
     * <p>W3b deleted the carried gas of D41 (a junction's bubble beside free water, dry at the node's pressure, its water
     * in the free water): its cause, the free water's saturation and partial-pressure rows on a bubble, is gone; a bubble
     * now has the equilibrium rows of any gas.</p>
     */
    public boolean foldTracePhases(double[] vapor,double[] liquid,double[] aqueous){return foldTracePhases(vapor,liquid,aqueous,0,false);}
    private boolean foldTracePhases(double[] vapor,double[] liquid,double[] aqueous,int vanishing,boolean gasStands) {
        double nv=0,nl=0,na=0;for(double x:vapor)nv+=x;for(double x:liquid)nl+=x;for(double x:aqueous)na+=x;
        double total=nv+nl+na;
        if(!(total>0))return false;
        double limit=PHASE_TRACE_FRACTION*total;boolean moved=false;
        boolean tv=nv<limit&&!gasStands||(vanishing&2)!=0,tl=nl<limit||(vanishing&1)!=0,ta=na<limit||(vanishing&4)!=0;
        boolean sv=nv>0&&!tv,sl=nl>0&&!tl,sa=na>0&&!ta;
        if(nl>0&&tl&&(sv||sa)){move(liquid,sv?vapor:aqueous);moved=true;}
        if(na>0&&ta&&(sv||sl)){move(aqueous,sv?vapor:liquid);moved=true;}
        if(nv>0&&tv&&(sl||sa)) {
            for(int i=0;i<vapor.length;i++) {
                double x=vapor[i];if(x==0)continue;
                if(sl&&sa) {
                    double l=liquid[i],a=aqueous[i];
                    double toLiquid=l+a>0?x*(l/(l+a)):nl>=na?x:0;
                    liquid[i]+=toLiquid;aqueous[i]+=x-toLiquid;
                }
                else if(sl)liquid[i]+=x;else aqueous[i]+=x;
                vapor[i]=0;
            }
            moved=true;
        }
        return moved;
    }
    private static void move(double[] from,double[] to){for(int i=0;i<from.length;i++){to[i]+=from[i];from[i]=0;}}
    /**
     * {@code state} restated at its temperature and pressure with its trace phases folded ({@link #foldTracePhases}),
     * its solids kept; the state itself when it holds none. The fluid network's one statement of which phases a node has:
     * every flash ({@link #flashTP}), the reconstruction's restatement of a node and the phase set each Newton pass is
     * laid out for pass through it. Never inside a residual: a fold is a jump of the state function.
     */
    public State folded(State state){return folded(state,false,0);}
    /** {@link #folded(State)} for a node that is a pipe junction ({@code junction}) or a vessel (the same rule since W3b). */
    public State folded(State state,boolean junction){return folded(state,false,0);}
    /**
     * {@link #folded(State,boolean)} for a vessel whose wall is rigid ({@code rigid}; batch 2026-11-10-rigid-reservoir, owner
     * decision of 2026-11-10 amending D34 and D38 C): its gas phase is judged by its volume share alone ({@link #rigidGas}).
     */
    public State folded(State state,boolean junction,boolean rigid){return folded(state,rigid&&!junction,0);}
    /**
     * The check of a failed pass's last iterate (owner decisions D28 and D39): {@link #folded(State,boolean)} with the
     * phases vanishing since {@code from} folded too (D38 C, {@link #vanishing}), and nothing else. A failed iterate is not
     * a state, so its check only folds what the iterate empties and proposes nothing. A phase the pass needs appears at a
     * converged point's check ({@link #phaseCheck}) or at a smaller step.
     */
    public State folded(State state,boolean junction,State from){return folded(state,junction,false,from);}
    /** {@link #folded(State,boolean,State)} for a vessel whose wall is rigid ({@code rigid}, {@link #rigidGas}). */
    public State folded(State state,boolean junction,boolean rigid,State from){return folded(state,rigid&&!junction,from==null?0:collapsing(from,state,rigid));}
    private State folded(State state,boolean rigid,int vanishing) {
        var result=folded(state,null,rigid,vanishing);
        return result==state||state.solids().empty()?result:result.withSolidState(state.solids(),state.solidMoments());
    }
    private State folded(State state,TranslatedPengRobinson.Workspace terms,boolean rigid,int vanishing) {
        double[] v=state.vaporAmounts(),l=state.liquidAmounts(),a=state.aqueousAmounts();
        int gas=rigid?rigidGas(state):0;
        if(!foldTracePhases(v,l,a,vanishing|gas&~GAS_STANDS,(gas&GAS_STANDS)!=0))return state;
        SolverDiagnostics.count(SolverDiagnostics.traceFolds);
        return placedState(state.temperature(),state.pressure(),v,l,a,terms);
    }

    /**
     * The trace verdict on a rigid vessel's gas (batch 2026-11-10-rigid-reservoir; owner decision of 2026-11-10 amending D34):
     * the gas is judged by its volume share of the fluid alone. A liquid-full rigid tank drained below its vapour pressure
     * forms its first bubble at about 3e-11 of its moles but 2e-6 of its volume. The bit 2 where the gas is a trace by
     * volume ({@link #PHASE_TRACE_FRACTION}), {@link #GAS_STANDS} where it holds more; 0 with no gas. A bag keeps the mole
     * share: its volume follows the ambient, so its bubble moves no pressure.
     */
    private static int rigidGas(State state) {
        double gas=state.vaporVolume(),fluid=state.condensedVolume()+gas;
        if(!(gas>0))return 0;
        return gas<PHASE_TRACE_FRACTION*fluid?2:GAS_STANDS;
    }
    /** {@link #rigidGas}'s verdict that the gas stands, outside the {@link #phaseBits} bits. */
    private static final int GAS_STANDS=16;
    /** A state's fluid phases as bits of its storage slots: the liquid slot 1, the vapour 2, the second (aqueous) liquid slot 4. */
    public static int phaseBits(State state) {
        return (state.phase(State.LIQUID)!=null?1:0)|(state.phase(State.VAPOR)!=null?2:0)|(state.phase(State.AQUEOUS)!=null?4:0);
    }
    /**
     * The outer phase check of one node of the fluid network (owner decisions D25 and D26), never inside a residual. A
     * node at fixed amounts, energy and volume has one equilibrium, which its own rows find once its layout holds every
     * phase that equilibrium needs; a TP answer at the node's temperature and pressure tells which phases are missing but
     * not how much of each the node holds. So the check never swaps one populated phase set for another on a TP verdict,
     * and an added phase starts at the node's own split:
     * <ul>
     * <li>a phase leaves only when the solve empties it: the trace fold ({@link #folded}, D34), and a phase held below
     * {@link #VANISHING_SHARE} and at a smaller share than at {@code from} (owner decision D38 C, {@link #vanishing});</li>
     * <li>a node holding every phase its material can form has none to add, and no TP answer is formed: a vapour and a
     * liquid on a list without water, all three slots with water (W3b); a node holding no fluid has none. A stand-in liquid ({@link #standIn}, owner
     * decision D31 B) is no liquid of the node's own and never makes it complete;</li>
     * <li>where the TP answer ({@link #flashTP}) holds a phase the node lacks that is not in the solve's record
     * ({@code record}, A85), the node's own split decides it (D26): a vessel's {@code vesselSplit} closes its inventory
     * from the vessel's own state at the crossing (owner decision D40 A, {@link #crossing}); a pipe junction's
     * {@link #junctionSplit} closes its own rows at its pressure and enthalpy from the engine's PH answer there
     * ({@link #equilibriumPH}; the TP answer with the junction's populated phases, {@link #tpSeed}, where that fails). A
     * node whose own split has no start, fails or drives a phase to nothing on its way proposes nothing;</li>
     * <li>the own split decides by where its closure went, converged or not: a proposed phase it drove under the vanishing
     * share is refused ({@link #nothing}), and the refusal is written to the record so the TP answer does not propose it
     * again in this solve. A proposed phase that stands seeds the node.</li>
     * </ul>
     *
     * @return {@code state} itself when it keeps its phases and gains none; otherwise the node's new seed
     */
    public State phaseCheck(State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,Runnable checkpoint) {
        return phaseCheck(state,junction,from,record,at,prepared,vesselSplit,false,checkpoint);
    }
    /** {@link #phaseCheck}; {@code rigid}: a rigid vessel's (batch 2026-11-10-rigid-reservoir), whose crossing holds its volume at any pressure. */
    public State phaseCheck(State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,boolean rigid,Runnable checkpoint) {
        int shrinking=from==null?0:vanishing(from,state,rigid);
        var kept=folded(state,rigid&&!junction,shrinking);var checked=kept;
        int excluded=(record==null?0:record[at])|shrinking&phaseBits(state)&~phaseBits(kept),refused=0;
        double[] totals=kept.totals();
        int own=ownPhaseBits(kept);
        // A node holding no fluid (a bed of solids alone) has no phase to add.
        boolean complete=!(sum(totals)>0)||(totals[totals.length-1]>0?own==7:(own&3)==3);
        if(!complete) {
            if(prepared!=null&&prepared.temperature()!=kept.temperature())prepared=null;
            var equilibrium=folded(equilibriumTP(kept.temperature(),kept.pressure(),totals,checkpoint,prepared).withSolidState(kept.solids(),kept.solidMoments()),junction);
            int proposed=lacking(kept,equilibrium)&~excluded;
            if(proposed!=0) {
                State start,split;
                if(junction) {
                    var engine=equilibriumPH(kept.temperature(),kept.pressure(),state.enthalpy()-state.solidMoments().enthalpy(state.temperature(),state.pressure()),totals,checkpoint);
                    start=engine!=null?engine.withSolidState(kept.solids(),kept.solidMoments()):tpSeed(kept,equilibrium,prepared);
                    split=junctionSplit(totals,state.enthalpy(),state.mass(),kept.pressure(),start,checkpoint);
                }
                else{start=crossing(state,kept,proposed,totals,rigid,checkpoint);split=start==null||vesselSplit==null?null:vesselSplit.apply(start);}
                if(split!=null) {
                    SolverDiagnostics.count(SolverDiagnostics.phaseSeeds);
                    var seed=folded(split.withSolidState(kept.solids(),kept.solidMoments()),junction,rigid);
                    refused=proposed&(~lacking(kept,seed)|nothing(split,rigid));
                    int added=lacking(kept,seed)&~excluded;
                    if(added!=0&&(added&refused)==0)checked=seed;
                }
                else refused=proposed;
                if(refused!=0)SolverDiagnostics.count(SolverDiagnostics.phaseRefusals);
            }
        }
        if(record!=null)record[at]|=phaseBits(state)&~phaseBits(checked)|refused;
        return checked;
    }
    /** {@link #phaseBits} of the phases a node holds as its own (owner decision D31 B): a stand-in liquid ({@link #standIn}) is
     * a crystal's condensed share, no liquid of the node's own. */
    private int ownPhaseBits(State state){return phaseBits(state)&~standIn(state);}
    /** The phases ({@link #phaseBits}) of {@code answer} that {@code node} lacks: an own phase the node holds none of, and a
     * stand-in where the node holds none in that slot. */
    private int lacking(State node,State answer){return ownPhaseBits(answer)&~ownPhaseBits(node)|phaseBits(answer)&~phaseBits(node);}
    /**
     * The phases ({@link #phaseBits}) a solve from {@code start} is driving to nothing at {@code end}, its answer or the last
     * iterate it stopped at, in any node: each holds less than {@link #VANISHING_SHARE} of {@code end}'s fluid
     * ({@link #belowShare}) and a smaller share than it held at {@code start}. A phase the solve grows, however small, is
     * not among them (owner decisions D26 amended, D38 C, D39 B).
     */
    public static int vanishing(State start,State end){return vanishing(start,end,false);}
    /** {@link #vanishing(State,State)} in a vessel whose wall is rigid ({@code rigid}): its gas judged by volume ({@link #belowShare}). */
    public static int vanishing(State start,State end,boolean rigid) {
        double[] s=phaseAmounts(start),e=phaseAmounts(end),ev=phaseVolumes(end);double ts=sum(s),te=sum(e),tv=sum(ev);int bits=0;
        for(int k=0;k<3;k++)if(belowShare(k,e,te,ev,tv,rigid)&&e[k]*ts<s[k]*te)bits|=1<<k;
        return bits;
    }
    /** The phases ({@link #phaseBits}) {@code state} holds below the vanishing share of its fluid ({@link #belowShare}),
     * whatever their direction (owner decision of 2026-10-19). */
    static int nothing(State state,boolean rigid) {
        double[] e=phaseAmounts(state),ev=phaseVolumes(state);double te=sum(e),tv=sum(ev);int bits=0;
        for(int k=0;k<3;k++)if(e[k]>0&&belowShare(k,e,te,ev,tv,rigid))bits|=1<<k;
        return bits;
    }
    /**
     * Whether phase {@code k} ({@link #phaseBits} order: liquid slot, vapour, aqueous slot) is below
     * {@link #VANISHING_SHARE}. Every phase is below the share by volume (owner decision of 2026-10-18: a gas holds about a
     * hundred times its mole share in volume). A liquid is judged on its volume share alone (owner decision 2026-10-20 for
     * the hydrocarbon liquid; W3b states it for both liquid slots: a film of any composition holds about twenty times its
     * volume share in moles). The gas must also be below the share by moles, except in a vessel whose wall is rigid,
     * where its volume alone is judged (owner decision of 2026-11-10).
     */
    private static boolean belowShare(int k,double[] e,double te,double[] ev,double tv,boolean rigid) {
        return ev[k]<VANISHING_SHARE*tv&&(k!=1||rigid||e[k]<VANISHING_SHARE*te);
    }
    /** A state's fluid volume by phase, in {@link #phaseBits} order. */
    private static double[] phaseVolumes(State state){return new double[]{state.slotVolume(State.LIQUID),state.vaporVolume(),state.slotVolume(State.AQUEOUS)};}
    /**
     * The phases a solve from {@code start} that stopped short of a root is driving to nothing at its last iterate
     * {@code end} (t38 form C, 2026-10-18): {@link #vanishing}, and as well every phase shrinking to less than
     * {@link #VANISHING_COLLAPSE} of its start share, whatever share it stands at.
     */
    public static int collapsing(State start,State end){return collapsing(start,end,false);}
    /** {@link #collapsing(State,State)} in a vessel whose wall is rigid ({@code rigid}, {@link #vanishing(State,State,boolean)}). */
    public static int collapsing(State start,State end,boolean rigid) {
        double[] s=phaseAmounts(start),e=phaseAmounts(end);double ts=sum(s),te=sum(e);int bits=vanishing(start,end,rigid);
        for(int k=0;k<3;k++)if(e[k]*ts<VANISHING_COLLAPSE*s[k]*te)bits|=1<<k;
        return bits;
    }
    /** A state's fluid amount by phase, in {@link #phaseBits} order. */
    private static double[] phaseAmounts(State state){return new double[]{sum(state.liquidAmountsView()),sum(state.vaporAmountsView()),sum(state.aqueousAmountsView())};}
    /**
     * The TP-based state of {@link #phaseCheck} (decision D26): the TP answer {@code equilibrium} at {@code kept}'s
     * temperature and pressure, for {@code kept}'s totals, with the populated phases of {@code kept} it lacks, so that no
     * TP verdict takes a populated phase away (D25): each component shared between the answer's phases and those kept
     * phases in proportion to what each holds of it. A junction's seed where its PH answer fails; never a vessel's.
     */
    private State tpSeed(State kept,State equilibrium,Prepared prepared) {
        int answer=phaseBits(equilibrium),lacking=phaseBits(kept)&~answer;
        if(lacking==0)return equilibrium;
        int n=componentCount();double[][] out=new double[3][n];int[] bit={2,1,4};
        for(int c=0;c<n;c++) {
            double[] share=new double[3];double sum=0;
            for(int slot=0;slot<3;slot++) {
                share[slot]=(answer&bit[slot])!=0?equilibrium.amountsView(slot)[c]:(lacking&bit[slot])!=0?kept.amountsView(slot)[c]:0;
                sum+=share[slot];
            }
            double total=kept.vaporAmountsView()[c]+kept.liquidAmountsView()[c]+kept.aqueousAmountsView()[c];
            if(!(sum>0)){for(int slot=0;slot<3;slot++)if((phaseBits(kept)&bit[slot])!=0){out[slot][c]=total;break;}continue;}
            for(int slot=0;slot<3;slot++)out[slot][c]=total*(share[slot]/sum);
        }
        try{return adoptingState(kept.temperature(),kept.pressure(),out[0],out[1],out[2],prepared).withSolidState(kept.solids(),kept.solidMoments());}
        catch(IllegalArgumentException unevaluable){return equilibrium;}
    }
    /**
     * A pipe junction's own split (owner decision D26, {@link #phaseCheck}; the arriving-phase seed of
     * {@code PassiveStepSolver.arrivingPhases}): its own rows ({@link PhaseLayout#junctionResidual}), isolated at the
     * pressure {@code p} it holds, with the amounts {@code amounts} (the conserved basis, water last), the enthalpy
     * {@code enthalpy} (J, the network's datum) and the mass {@code mass} its rows weigh them by, closed from {@code start}
     * in {@code start}'s phase layout. The closed point is restated at exactly {@code p} and {@code amounts}. {@code null}
     * where the closure fails or drives a phase of {@code start} to nothing on its way ({@link #vanishing}, D38 C).
     */
    public State junctionSplit(double[] amounts,double enthalpy,double mass,double p,State start,Runnable checkpoint) {
        try {
            var layout=new PhaseLayout(this,start,null,null,PhaseLayout.support(this,start,null,null));
            int n=componentCount();double[] fractions=new double[n];
            for(int i=0;i<n;i++)fractions[i]=amounts[i]*molecularWeight(i)/mass;
            var solved=SparseNewton.solve(layout.junctionClosure(fractions,enthalpy/mass,p,start),layout.encode(start),
                    new SparseNewton.Settings(SparseNewton.ITERATIONS,1e-10,1e-6,24),checkpoint);
            var closed=layout.decode(solved.variables(),0);
            // Exactly what the junction holds: each component split as the closure split it, at exactly the retained pressure.
            double[][] out=new double[3][n];
            for(int i=0;i<n;i++) {
                double v=closed.vaporAmountsView()[i],l=closed.liquidAmountsView()[i],a=closed.aqueousAmountsView()[i],total=v+l+a;
                if(!(total>0)){int slot=closed.phase(State.LIQUID)!=null?State.LIQUID:closed.phase(State.AQUEOUS)!=null?State.AQUEOUS:State.VAPOR;out[slot][i]=amounts[i];continue;}
                out[0][i]=amounts[i]*(v/total);out[2][i]=amounts[i]*(a/total);out[1][i]=amounts[i]-out[0][i]-out[2][i];
            }
            return adoptingState(closed.temperature(),p,out[0],out[1],out[2],null).withSolidState(start.solids(),start.solidMoments());
        }
        catch(PhaseLayout.Vanished|SparseNewton.Nonconvergence|IllegalArgumentException failed){return null;}
    }
    /**
     * A vessel's own state at a crossing (owner decision D40 A), the start of its own split in {@link #phaseCheck}: the
     * engine's equilibrium of the fluid it holds under its wall's law, the problem its own closure solves
     * ({@code InventoryEquilibrium}), as a network state ({@link #adopt}) folded as a vessel: a slack bag at its fluid
     * enthalpy and the ambient pressure ({@link #equilibriumPH}), a taut vessel at its fluid's internal energy and volume
     * ({@link #equilibriumUV}), warm-started at its temperature and pressure, both under the step's competition. The TP
     * answer says only which phase is missing: its amounts ignore the vessel's volume and are never the seed. {@code null}
     * where the engine does not converge, answers a phase set the network cannot carry, or answers none of the phases
     * {@code proposed} adds (no crossing).
     */
    private State crossing(State state,State kept,int proposed,double[] totals,boolean rigid,Runnable checkpoint) {
        double t=kept.temperature(),p=kept.pressure();var solids=state.solidMoments();
        var own=!rigid&&p<=PhaseLayout.AMBIENT_PRESSURE*(1+SLACK_PRESSURE)?equilibriumPH(t,p,state.enthalpy()-solids.enthalpy(state.temperature(),state.pressure()),totals,checkpoint)
                :equilibriumUV(t,p,state.internalEnergy()-solids.internalEnergy(state.temperature()),state.volume()-solids.volume(),totals,checkpoint);
        if(own==null)return null;
        var seed=folded(own.withSolidState(kept.solids(),kept.solidMoments()),false,rigid);
        return (lacking(kept,seed)&proposed)!=0?seed:null;
    }
    /** How far above the ambient pressure, relatively, a vessel's state still reads as its wall's slack branch in
     * {@link #crossing}: the slack row holds it at the ambient to the solve's tolerance (a vessel's closure, 1e-11). */
    private static final double SLACK_PRESSURE=1e-9;

    /**
     * Initializer, boundary and outer-check flash; the time-step solver never calls it in a residual evaluation: the
     * equilibrium of {@link #equilibriumTP} with its trace phases folded ({@link #folded}).
     */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint) {
        return flashTP(t,p,overall,checkpoint,null);
    }
    /** {@link #flashTP} that uses {@code prepared}'s Peng-Robinson workspace when it is for exactly {@code t}. */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint,Prepared prepared) {
        TranslatedPengRobinson.Workspace given=prepared!=null&&prepared.temperature()==t?prepared.pengRobinson():null;
        return folded(equilibriumTP(t,p,overall,checkpoint,prepared),given,false,0);
    }
    /**
     * The equilibrium split of {@link #flashTP} before its trace phases are folded; not a state the network lays out or
     * commits. The phases of {@code overall} (the conserved basis, water last) at {@code (t, p)} are decided by the
     * equilibrium engine ({@link NetworkPhaseEngine}: stability test, split, critical band, phase identification), up to
     * three fluid phases, placed in the state's slots by the slot rule ({@link #placed}). Refusals: a state outside the
     * numerical box is a plain {@link IllegalArgumentException}; an answer the engine could not converge inside the
     * critical band is the typed {@link CriticalBandHold}; a further fluid phase beyond three is {@link UnsupportedPhases};
     * any other failure is a plain {@link IllegalArgumentException}. A TP answer never moves material into a crystal
     * (owner decision D23): on a package that admits crystals a request carrying a crystal's species at or below the
     * crystals' ceiling carries its crystals in the fluid phases ({@link #stepCompetition}), the stand-in ({@link #standIn}).
     * {@code checkpoint} runs once before the engine and once per equation-of-state evaluation the engine reports.
     */
    public State equilibriumTP(double t,double p,double[] overall,Runnable checkpoint,Prepared prepared) {
        TranslatedPengRobinson.Workspace given=prepared!=null&&prepared.temperature()==t?prepared.pengRobinson():null;
        SolverDiagnostics.count(SolverDiagnostics.flashCalls);
        if(overall.length!=componentCount())throw new IllegalArgumentException("Fluid basis mismatch");
        if(sum(overall)<=0)throw new IllegalArgumentException("Empty fluid initialization");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid initialization is not finite");
        // Refused before any equilibrium is formed: outside the numerical box no model is trusted at all.
        NumericalBox.require(t,p);
        checkpoint.run();
        var result=phaseEngine().tp(t,p,overall,stepCompetition(t,overall));
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        return adopt(t,p,overall,result,given);
    }
    /**
     * A pipe junction's own split (owner decision D26, {@link #phaseCheck}), the phases of an empty bag's
     * ({@code PassiveStepSolver.emptyBagSplit}, D54) and a slack vessel's crossing ({@link #crossing}): the engine's PH
     * equilibrium of {@code overall} at the node's pressure {@code p} and its fluid enthalpy {@code h} (J, the network's
     * datum), under the step's competition at {@code t}, as a network state ({@link #adopt}, unfolded); {@code null} where
     * the engine does not converge or answers a phase set the network cannot carry.
     */
    public State equilibriumPH(double t,double p,double h,double[] overall,Runnable checkpoint){return equilibriumPH(t,p,h,overall,stepCompetition(t,overall),checkpoint);}
    /** {@link #equilibriumPH} of the fluid phases alone, for the balanced start's density tables ({@code PassiveStepSolver.BalanceNet}). */
    public State fluidPH(double t,double p,double h,double[] overall,Runnable checkpoint){return equilibriumPH(t,p,h,overall,PhaseCompetition.FLUID_ONLY,checkpoint);}
    private State equilibriumPH(double t,double p,double h,double[] overall,PhaseCompetition competition,Runnable checkpoint) {
        checkpoint.run();
        var result=phaseEngine().ph(p,engineEnergy(h,overall),overall,competition);
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        if(!result.converged()||result.phases().isEmpty())return null;
        try{return adopt(result.phases().getFirst().temperature(),p,overall,result,null);}
        catch(IllegalArgumentException refused){return null;}
    }
    /** {@link #equilibriumUV}, for a rigid vessel that flashes (batch 2026-11-10-rigid-reservoir, {@code PassiveStepSolver.flashes}). */
    public State equilibriumAtVolume(double t,double p,double u,double v,double[] overall,Runnable checkpoint){return equilibriumUV(t,p,u,v,overall,checkpoint);}
    /** A taut vessel's own state at a crossing ({@link #crossing}, owner decision D40 A), the UV twin of {@link #equilibriumPH}. */
    private State equilibriumUV(double t,double p,double u,double v,double[] overall,Runnable checkpoint) {
        checkpoint.run();
        var result=phaseEngine().crystalUv(engineEnergy(u,overall),v,overall,stepCompetition(t,overall),t,p);
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        if(!result.converged()||result.phases().isEmpty())return null;
        var answer=result.phases().getFirst();
        try{return adopt(answer.temperature(),answer.pressure(),overall,result,null);}
        catch(IllegalArgumentException refused){return null;}
    }

    /** The crystals the package admits, in declaration order; empty without a crystal competition. */
    List<String> crystals(){return crystalIds;}
    /**
     * Whether a request on {@code overall} (conserved basis, water last) at {@code t} is made under the crystal competition:
     * the package admits crystals, the request carries a crystal's species, and {@code t} is at or below the crystals'
     * ceiling. Otherwise fluid-only. Gas hydrates are not modelled (decision 6 A), so ice and a competing crystal coexist.
     */
    public boolean crystalsCompete(double t,double[] overall) {
        if(crystalIds.isEmpty())return false;
        for(int i=0;i<crystalSpecies.length;i++)if(crystalSpecies[i]&&overall[i]>0&&t<=crystalCeiling[i])return true;
        return false;
    }
    /**
     * The competition of a step-level TP, PH or UV decision on {@code overall} at {@code t}: where crystals would compete
     * ({@link #crystalsCompete}), the fluid phases carrying them (owner decision D23: only a vessel's refresh books a crystal,
     * {@link #crystalsAtVolume}, {@link #crystalsAtPressure}); otherwise fluid-only.
     */
    PhaseCompetition stepCompetition(double t,double[] overall) {
        return crystalsCompete(t,overall)?PhaseCompetition.FLUID_CARRYING_CRYSTALS:PhaseCompetition.FLUID_ONLY;
    }

    /**
     * Owner decision D29 A, on both liquid slots since W3b: the liquid slots ({@link #phaseBits}) whose liquid is a
     * crystal's condensed share alone, the stand-in: a liquid holding crystal species only, below their crystals' triple
     * points, beside a vapour, as a frost answer's crystal is carried (ice is a package crystal with the stand-in rule). It
     * is no phase of the answer, so the trace support reads the answer's own fluid phase and the stand-in admits every
     * component the node holds ({@code PhaseLayout.support}); in the outer phase check it is no liquid of the node's own
     * (owner decision D31 B). W3b added the triple-point bound: with water a component, a pure water liquid beside a gas
     * above freezing is the node's own liquid. Never on a package without crystals.
     */
    public int standIn(State state) {
        if(crystalIds.isEmpty()||state.phase(State.VAPOR)==null)return 0;
        int bits=0;
        if(standIn(state,State.LIQUID))bits|=1;
        if(standIn(state,State.AQUEOUS))bits|=4;
        return bits;
    }
    private boolean standIn(State state,int slot) {
        if(state.phase(slot)==null)return false;
        double[] x=state.amountsView(slot);double t=state.temperature();
        for(int i=0;i<x.length;i++)if(x[i]>0&&!(crystalSpecies[i]&&t<crystalTriple[i]))return false;
        return true;
    }
    /** Whether the state holds a stand-in liquid ({@link #standIn}). */
    public boolean condensedShareOnly(State state){return standIn(state)!=0;}

    /** The engine's answer as a network state (see {@link #equilibriumTP}), or its typed refusal; {@code terms}, when
     * given, is the prepared Peng-Robinson workspace of exactly {@code t}. */
    State adopt(double t,double p,double[] overall,EquilibriumResult result,TranslatedPengRobinson.Workspace terms) {
        switch(result.status()) {
            case CONVERGED->{}
            case NOT_CONVERGED->{
                if(result.diagnostics().criticalBand())throw new CriticalBandHold(domain.packageId(),t,p,result.detail());
                // A converged split with a product proved unstable, or left unresolved: a further fluid phase is indicated
                // or not excluded, which a node cannot carry beyond three; a typed hold, never adopted with fewer phases.
                boolean unstable=result.detail().startsWith(EquilibriumResult.UNSTABLE_PRODUCT);
                if(unstable||result.detail().startsWith(EquilibriumResult.UNRESOLVED_PRODUCT))throw new UnsupportedPhases(domain.packageId(),t,p,
                        UnsupportedPhases.Kind.THREE_PHASES,"a further fluid phase is "+(unstable?"indicated":"not excluded")+" ("+result.detail()
                        +"); the fluid network carries a vapour and two liquids");
                throw new IllegalArgumentException("Fluid TP equilibrium did not converge: "+result.detail());
            }
            default->throw new IllegalStateException("The network's equilibrium engine refused a request it must answer: "+result.detail());
        }
        if(terms==null)terms=hydrocarbon.prepare(t);
        var fluids=new ArrayList<PhaseAmounts>();
        for(var phase:result.phases()) {
            if(phase.kind()==PhaseKind.SOLID)throw new IllegalStateException("A step-level answer booked a crystal (owner decision D23)");
            fluids.add(phase);
        }
        // A pure component at its own coexistence pressure, the split undetermined by T and P: a single liquid.
        if(result.classification()==EquilibriumResult.Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
            double[] none=new double[componentCount()];
            return placedState(t,p,none,overall.clone(),none.clone(),terms);
        }
        return placed(t,p,fluids,terms,UnsupportedPhases.Kind.THREE_PHASES);
    }
    /**
     * The engine's fluid phases in the state's slots (W3b): a vapour-like phase in the vapour slot, the liquids by the slot
     * rule ({@link #placedState}); a one-root phase by its own phase identification parameter. More than one vapour or
     * more than two liquids is a typed hold of {@code kind} (a fourth fluid phase).
     */
    private State placed(double t,double p,List<PhaseAmounts> fluids,TranslatedPengRobinson.Workspace terms,UnsupportedPhases.Kind kind) {
        int n=componentCount();double[] vapor=new double[n];var liquids=new ArrayList<double[]>();boolean gas=false;
        for(var phase:fluids) {
            double[] amounts=conserved(phase.amountsView());
            boolean liquid=switch(phase.kind()) {
                case LIQUID->true;
                case VAPOR->false;
                default->hydrocarbon.liquidLike(t,p,amounts,phase.state().root(),terms);
            };
            if(liquid)liquids.add(amounts);
            else{if(gas)throw new UnsupportedPhases(domain.packageId(),t,p,kind,"two vapour-like phases; the fluid network carries one vapour");gas=true;vapor=amounts;}
        }
        if(liquids.size()>2)throw new UnsupportedPhases(domain.packageId(),t,p,kind,"three liquids; the fluid network carries a vapour and two liquids");
        double[] first=liquids.isEmpty()?new double[n]:liquids.get(0),second=liquids.size()<2?new double[n]:liquids.get(1);
        return placedState(t,p,vapor,first,second,terms);
    }
    /** An engine phase's amounts on the conserved basis (water last, zero on a list without water). */
    private double[] conserved(double[] engine){return engine.length==componentCount()?engine.clone():Arrays.copyOf(engine,componentCount());}
    /**
     * The state of a vapour and two liquids (either may be empty) with the liquids in their canonical slots: of two the one
     * with the larger water mole fraction in the aqueous slot, at equal fractions (two liquids without water, a carried
     * crystal's stand-in beside a dry liquid) the denser by mass; a single liquid in the aqueous slot when water is at least
     * half of it by moles, in the liquid slot otherwise ({@link State#slot}).
     */
    private State placedState(double t,double p,double[] vapor,double[] first,double[] second,TranslatedPengRobinson.Workspace terms) {
        double s1=sum(first),s2=sum(second);int w=componentCount()-1;
        double[] liquid,aqueous;
        if(s1>0&&s2>0) {
            if(first[w]/s1==second[w]/s2) {
                var tied=state(t,p,vapor,first,second,terms,null);
                return liquidPhaseDensityOfSlot(tied,State.LIQUID)>liquidPhaseDensityOfSlot(tied,State.AQUEOUS)?state(t,p,vapor,second,first,terms,null):tied;
            }
            boolean swap=first[w]/s1>second[w]/s2;liquid=swap?second:first;aqueous=swap?first:second;
        }
        else {
            double[] only=s1>0?first:second,none=s1>0?second:first;double so=s1>0?s1:s2;
            boolean water=so>0&&only[w]/so>=0.5;
            liquid=water?none:only;aqueous=water?only:none;
        }
        return state(t,p,vapor,liquid,aqueous,terms,null);
    }

    /**
     * A node's phases the engine could not converge inside the declared critical band: the engine's typed
     * {@code CRITICAL_BAND} failure, a {@link ThermoHold}; no split is fabricated and no single phase is claimed stable.
     */
    public static final class CriticalBandHold extends ThermoHold {
        public CriticalBandHold(String packageId,double temperature,double pressure,String detail) {
            this(packageId,temperature,pressure,detail,NO_NODE);
        }
        private CriticalBandHold(String packageId,double temperature,double pressure,String detail,long node) {
            super("Critical band: the phases at "+temperature+" K, "+pressure+" Pa (package "+packageId+")"
                    +" did not converge inside the declared critical band; "+detail,packageId,temperature,pressure,detail,node);
        }
        @Override public CriticalBandHold at(long node) {
            return node()!=NO_NODE||node==NO_NODE?this:new CriticalBandHold(packageId,temperature,pressure,detail,node);
        }
        @Override public String reasonKey(){return REASON_PREFIX+"critical band";}
        @Override public boolean sameAs(ThermoHold other) {
            return other instanceof CriticalBandHold band&&band.node()==node()&&band.packageId.equals(packageId);
        }
        @Override String what(){return "the phases did not converge inside the declared critical band";}
    }

    /**
     * An equilibrium answer the network cannot carry, typed with the equilibrium service's reason: a node has a vapour slot
     * and two liquid slots (D8: a phase set outside the qualified ones is refused, never answered with fewer phases). The
     * kinds: a further fluid phase (a fourth, or a second vapour), a crystal beside a phase set the engine does not
     * implement, and a crystal step the engine does not answer. {@code LIQUID_LIQUID} is no longer raised (W3b: two
     * liquids are carried); it is kept so a stored reason key still reads.
     */
    public static final class UnsupportedPhases extends ThermoHold {
        /** What the network cannot carry. */
        public enum Kind {
            /** Not raised since W3b (two liquids are carried). */
            LIQUID_LIQUID,
            /** A fluid phase beyond the vapour and two liquids: a converged split whose product the stability re-check proved
             * unstable ({@link EquilibriumResult#UNSTABLE_PRODUCT}) or left unresolved, or an answer with a fourth fluid phase. */
            THREE_PHASES,
            /** The engine's typed {@link EquilibriumResult#CRYSTAL_PHASES} hold under the crystal competition; the engine's
             * reason is {@code NOT_IMPLEMENTED}. */
            CRYSTAL_PHASES,
            /** The engine's typed {@link EquilibriumResult#CRYSTAL_STEP} hold of a vessel refresh's crystal answer (D21 option
             * C): a near-pure feed's crystal step the pure species' step does not answer. Also an answer that froze a
             * vessel's other species with its crystals ({@code VesselCrystals}), which the refresh names the vessel with. */
            CRYSTAL_STEP;
            /** The equilibrium service's reason for this kind. */
            EquilibriumResult.UnsupportedReason reason() {
                return this==CRYSTAL_PHASES||this==CRYSTAL_STEP?EquilibriumResult.UnsupportedReason.NOT_IMPLEMENTED
                        :EquilibriumResult.UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED;
            }
        }
        private final Kind kind;
        public UnsupportedPhases(String packageId,double temperature,double pressure,Kind kind,String detail) {
            this(packageId,temperature,pressure,kind,detail,NO_NODE);
        }
        private UnsupportedPhases(String packageId,double temperature,double pressure,Kind kind,String detail,long node) {
            super("Unsupported phases: "+kind.reason()+" ("+kind+") at "+temperature+" K, "+pressure+" Pa (package "+packageId+"): "+detail,
                    packageId,temperature,pressure,detail,node);
            this.kind=Objects.requireNonNull(kind);
        }
        /** What the network cannot carry. */
        public Kind kind(){return kind;}
        @Override public UnsupportedPhases at(long node) {
            return node()!=NO_NODE||node==NO_NODE?this:new UnsupportedPhases(packageId,temperature,pressure,kind,detail,node);
        }
        @Override public String reasonKey(){return REASON_PREFIX+"phases "+kind;}
        @Override public boolean sameAs(ThermoHold other) {
            return other instanceof UnsupportedPhases phases&&phases.kind==kind&&phases.node()==node()&&phases.packageId.equals(packageId);
        }
        @Override String what() {
            return switch(kind) {
                case LIQUID_LIQUID->"two liquids of one slot";
                case THREE_PHASES->"a further fluid phase, which the network does not carry";
                case CRYSTAL_PHASES->"a crystal beside a phase set the equilibrium engine does not implement";
                case CRYSTAL_STEP->"a near-pure crystal step the equilibrium engine does not answer";
            };
        }
    }
    private static double sum(double[] values) {double total=0;for(double value:values){if(value<0||!Double.isFinite(value))throw new IllegalArgumentException("Invalid phase amount");total+=value;}return total;}

    /** The temperature-only property bundle for one exact node temperature. */
    public Prepared prepare(double temperature) { return new Prepared(this,temperature); }

    private Prepared own(Prepared prepared) {
        if(prepared.owner!=this)throw new IllegalArgumentException("Prepared temperature belongs to another model");
        return prepared;
    }
    private Prepared match(Prepared prepared,double t) {
        if(prepared!=null&&own(prepared).temperature!=t)throw new IllegalArgumentException("Prepared temperature belongs to another state");
        return prepared;
    }
    /**
     * Everything at one exact temperature that neither composition nor pressure can change: the PR mixing terms and the
     * viscosities' pure-component terms. A colored Jacobian perturbs a node's composition and pressure columns dozens of
     * times at an unchanged temperature, and every residual at that temperature repeats them, so this is where those
     * evaluations meet. Each member is computed when it is first asked for.
     */
    public static final class Prepared {
        private final FluidThermodynamics owner;
        private final double temperature;
        private TranslatedPengRobinson.Workspace terms;
        private MixtureViscosity.Prepared transport;
        private Prepared(FluidThermodynamics owner,double temperature) {
            if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid prepared temperature");
            this.owner=owner;this.temperature=temperature;
        }
        public double temperature() { return temperature; }
        /** The prepared Peng-Robinson workspace of this temperature. It is mutable scratch, so a
         * {@code Prepared} - like the node cache that holds it - belongs to one solving thread. */
        TranslatedPengRobinson.Workspace pengRobinson() {
            return terms==null?terms=owner.hydrocarbon.prepare(temperature):terms;
        }
        /** The pure-component transport terms of this temperature, for the decode's Transport row. */
        public MixtureViscosity.Prepared viscosities() {
            return transport==null?transport=owner.viscosity.prepare(temperature):transport;
        }
    }
    public record WaterLiquid(double molarVolume,double molarEnthalpy) {}
    /**
     * A node's fluid: up to three phases in storage slots ({@link #VAPOR}, {@link #LIQUID}, {@link #AQUEOUS}), each with
     * its amounts on the conserved basis (water last), its volume and its molar properties (null for an empty slot), and
     * the node's solids. The labels ({@link FluidThermodynamics#GAS}, {@link FluidThermodynamics#OIL},
     * {@link FluidThermodynamics#AQUEOUS}) follow from the composition ({@link #slot}). The phase amounts belong to the
     * record from construction on: {@link FluidThermodynamics#state} copies a caller's arrays, and
     * {@link FluidThermodynamics#adoptingState} is the entry for a caller that allocated them for this call alone.
     */
    public record State(double temperature,double pressure,double[] vaporAmounts,double[] liquidAmounts,double[] aqueousAmounts,
                        double volume,double enthalpy,double internalEnergy,double mass,
                        double vaporVolume,double liquidSlotVolume,double aqueousSlotVolume,
                        HydrocarbonModel.Phase vaporPhase,HydrocarbonModel.Phase liquidPhase,HydrocarbonModel.Phase aqueousPhase,
                        SolidInventory solids,SolidInventory.Moments solidMoments) {
        /** The storage slots. */
        public static final int VAPOR=0,LIQUID=1,AQUEOUS=2;
        public State { Objects.requireNonNull(solids); Objects.requireNonNull(solidMoments); }
        public State withSolids(SolidInventory inventory) { return withSolidState(inventory,inventory.moments()); }
        /** Moment-only trial states never become owned inventories before population reconstruction. */
        public State withSolidState(SolidInventory inventory,SolidInventory.Moments moments) {
            return new State(temperature,pressure,vaporAmounts,liquidAmounts,aqueousAmounts,
                    volume-solidMoments.volume()+moments.volume(),
                    enthalpy-solidMoments.enthalpy(temperature,pressure)+moments.enthalpy(temperature,pressure),
                    internalEnergy-solidMoments.internalEnergy(temperature)+moments.internalEnergy(temperature),
                    mass-solidMoments.mass()+moments.mass(),vaporVolume,liquidSlotVolume,aqueousSlotVolume,vaporPhase,liquidPhase,aqueousPhase,
                    inventory,moments);
        }
        /** Conserved component basis length, with water in the final position. */
        public int componentCount(){return vaporAmounts.length;}
        @Override public double[] vaporAmounts(){return vaporAmounts.clone();}
        @Override public double[] liquidAmounts(){return liquidAmounts.clone();}
        @Override public double[] aqueousAmounts(){return aqueousAmounts.clone();}
        /** The amounts themselves, for the solver packages. The caller must not mutate them. */
        public double[] vaporAmountsView(){return vaporAmounts;}
        public double[] liquidAmountsView(){return liquidAmounts;}
        public double[] aqueousAmountsView(){return aqueousAmounts;}
        public double[] amountsView(int slot){return slot==VAPOR?vaporAmounts:slot==LIQUID?liquidAmounts:aqueousAmounts;}
        public double[] amounts(int slot){return amountsView(slot).clone();}
        /** A slot's molar properties, null for an empty slot. */
        public HydrocarbonModel.Phase phase(int slot){return slot==VAPOR?vaporPhase:slot==LIQUID?liquidPhase:aqueousPhase;}
        public double slotVolume(int slot){return slot==VAPOR?vaporVolume:slot==LIQUID?liquidSlotVolume:aqueousSlotVolume;}
        /** The volume of both liquids. */
        public double condensedVolume(){return liquidSlotVolume+aqueousSlotVolume;}
        /** Each component's amount over the three slots, water last. */
        public double[] totals() {
            double[] n=new double[vaporAmounts.length];
            for(int i=0;i<n.length;i++)n[i]=vaporAmounts[i]+liquidAmounts[i]+aqueousAmounts[i];
            return n;
        }
        /** The fluid volume of the phase labelled {@code label} ({@link #slot}), 0 where the state holds none. */
        public double labelVolume(int label){int slot=slot(label);return slot<0?0:slotVolume(slot);}
        /** The molar properties of the phase labelled {@code label}, null where the state holds none. */
        public HydrocarbonModel.Phase labelPhase(int label){int slot=slot(label);return slot<0?null:phase(slot);}
        /** The amounts of the phase labelled {@code label} (water last; a new zero array where the state holds none). The
         * caller must not mutate them. */
        public double[] labelAmountsView(int label){int slot=slot(label);return slot<0?new double[vaporAmounts.length]:amountsView(slot);}
        /** Water's amount over the state's liquids, mol. */
        public double liquidWater(){int w=vaporAmounts.length-1;return liquidAmounts[w]+aqueousAmounts[w];}
        /** Water's mole fraction in a slot, 0 for an empty one. */
        public double waterFraction(int slot) {
            double[] x=amountsView(slot);double total=0;for(double v:x)total+=v;
            return total>0?x[x.length-1]/total:0;
        }
        /**
         * The storage slot holding the phase labelled {@code label} ({@link FluidThermodynamics#GAS}, {@code OIL},
         * {@code AQUEOUS}), -1 where the state holds none: the vapour slot for the gas; of two liquids the one with the
         * larger water mole fraction is the aqueous one (the second slot at a tie, where the network places the denser
         * liquid by mass: two liquids without water, a carried crystal's stand-in beside a dry liquid; the label's name is
         * then wider than its meaning); a single liquid is aqueous when water is at least half of it by moles, the oil
         * otherwise.
         */
        public int slot(int label) {
            if(label==FluidThermodynamics.GAS)return vaporVolume>0?VAPOR:-1;
            boolean l=liquidSlotVolume>0,a=aqueousSlotVolume>0;
            if(l&&a) {
                boolean second=waterFraction(AQUEOUS)>=waterFraction(LIQUID);
                return label==FluidThermodynamics.AQUEOUS==second?AQUEOUS:LIQUID;
            }
            if(!l&&!a)return -1;
            int only=l?LIQUID:AQUEOUS;
            boolean aqueous=waterFraction(only)>=0.5;
            return (label==FluidThermodynamics.AQUEOUS)==aqueous?only:-1;
        }
    }
}
