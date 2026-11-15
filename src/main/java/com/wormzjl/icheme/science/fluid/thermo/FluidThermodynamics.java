package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
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
import com.wormzjl.icheme.science.fluid.state.LegacySplit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.thermo.phase.PackageBinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
     * The game's ambient pressure (Pa): the outside of every vessel's wall, and the pressure of the air the network
     * reduction's ambient state holds.
     */
    public static final double AMBIENT_PRESSURE=101325;
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
    final MaterialCatalog.Water water;
    /** {@link #water}'s numbers for the water module, mapped once. */
    final com.wormzjl.icheme.science.thermo.water.WaterConstants waterConstants;
    final double waterEnthalpyOffset;
    final double maximumVelocity;
    /** Process time against online time ({@link SimulationTime}): carried on the model, like {@link #maximumVelocity}, so a
     * changed factor reaches every island through a new model and {@link #revision}. */
    private final SimulationTime time;
    /** Carried on the model, like {@link #maximumVelocity}, because every retained solver, workspace
     * and factorization is keyed on the model: replacing it is what makes a changed cutoff reach a
     * running island deterministically, and {@link #revision} carries it so a certificate issued
     * under a different unknown set is not kept. */
    private final TraceTruncationPolicy tracePolicy;
    /** The stream functions and the pure-water functions of this model ({@link NetworkStreams}, {@link NetworkWater}): built
     * once in the constructor, on this model; they hold nothing but it. */
    final NetworkStreams streams;
    final NetworkWater pureWater;

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
        return forNetwork(catalog,packageId,maximumVelocity,traceCutoffMoleFraction,solidSettings,SimulationTime.ONLINE);
    }
    /** The world's model: {@code time} is the server's time factor ({@code fluid.timeScale}), captured at server start. */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings,SimulationTime time) {
        TraceTruncationPolicy.requireCutoff(traceCutoffMoleFraction,MAX_TRACE_CUTOFF_MOLE_FRACTION);
        return new FluidThermodynamics(catalog,packageId,
                maximumVelocity,TraceTruncationPolicy.of(traceCutoffMoleFraction),solidSettings,time,null);
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
        this(catalog,packageId,maximumVelocity,tracePolicy,solidSettings,SimulationTime.ONLINE,null);
    }
    /** The model of a compound list (batch 2026-11-04-refinery-compounds, V2): {@code whole}'s package restricted to it. */
    private FluidThermodynamics(MaterialCatalog catalog,String packageId,double maximumVelocity,TraceTruncationPolicy tracePolicy,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings,SimulationTime time,FluidThermodynamics whole) {
        // A whole model gives its package water as a compound of the cubic where the package does not name it (W3b): the
        // network supplies water itself, no package record is edited for it. A view is restricted from that catalog.
        if(whole==null)catalog=catalog.withWater(packageId);
        this.whole=whole==null?this:whole;views=whole==null?new java.util.HashMap<>():null;
        streams=new NetworkStreams(this);pureWater=new NetworkWater(this);
        this.solidSettings=Objects.requireNonNull(solidSettings);this.time=Objects.requireNonNull(time,"time");
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
        var admitted=PackageBinding.admittedCrystals(catalog,packageId);
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
        waterConstants=com.wormzjl.icheme.science.material.WaterRecords.constants(water);
        waterEnthalpyOffset=com.wormzjl.icheme.science.thermo.water.WaterDatum.liquidEnthalpyOffset(waterConstants);
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
        // The fluid checkpoint writes it as an NBT string (FluidCheckpointCodec, the core record's PropertyRevision), at most
        // 65,535 UTF-8 bytes: refused here, at the model, rather than at the first save (MaterialCatalog.MAX_IDENTITY).
        int revisionBytes=thermodynamicRevision.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(revisionBytes>MAX_THERMODYNAMIC_REVISION_BYTES)
            throw new IllegalArgumentException("the model's thermodynamic revision is "+revisionBytes+" UTF-8 bytes, above "+MAX_THERMODYNAMIC_REVISION_BYTES);
        // The trace cutoff joins the hydraulic acceptance law, not the thermodynamic reference: it changes which unknowns a
        // Newton pass carries, so a certificate issued under another cutoff describes a different numerical path - but it
        // changes no property data, so a saved island's inventory and energy stay readable across a change of it.
        // line-cap-saturation-v1 names the cap law of every throttling line (PassiveStepSolver.saturated, 2026-10-18): the
        // smooth saturation of the law's flow at the stream's velocity limit, max= the configured limit it is built from.
        revision=thermodynamicRevision+":line-cap-saturation-v1:trace-relative-v1:max="+Double.toHexString(maximumVelocity)
                +":trace="+Double.toHexString(tracePolicy.cutoffMoleFraction())+":solids-v1:"+solidSettings
                // time= the time factor (batch 2026-11-11-minecraft-time-delta): a solved interval, a certificate and a
                // junction buffer describe process seconds per tick, so a changed factor drops them and the islands
                // rebuild from the facts. Factor 1 carries no part: the revisions of worlds saved before the setting stand.
                +(time.factor()==1?"":":time="+time.factor());
    }
    /**
     * The thermodynamic reference a saved island's inventory and energy are read against (the checkpoint's package
     * table): the network's integrator, the phase model's and the viscosity's revisions. Built once per model.
     */
    private final String thermodynamicRevision;
    /** The model of the whole package this one is a view of (itself for a whole package), and, on the whole one, its views by compound list. */
    final FluidThermodynamics whole;
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
            var view=new FluidThermodynamics(whole.catalog.restrict(domain.packageId(),key),domain.packageId(),whole.maximumVelocity,whole.tracePolicy,whole.solidSettings,whole.time,whole);
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
    /** The longest {@link #thermodynamicRevision}, UTF-8 bytes: the NBT string the fluid checkpoint stores it in. */
    public static final int MAX_THERMODYNAMIC_REVISION_BYTES=65535;
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
        var formation=hydrocarbon.formationReference().orElseThrow();
        return EnergyReference.sensible(formation.components()).rebase(energy,Arrays.copyOf(totals,hydrocarbon.componentCount()),formation);
    }
    private CrystalAnswer crystalAnswer(EquilibriumResult result,double[] totals) {
        var crystals=crystalSolids();
        switch(result.status()) {
            case CONVERGED->{}
            case UNSUPPORTED->{
                if(result.failureKind()==EquilibriumResult.FailureKind.CRYSTAL_PHASES)throw new UnsupportedPhases(domain.packageId(),Double.NaN,Double.NaN,
                        UnsupportedPhases.Kind.CRYSTAL_PHASES,"a crystal beside a phase set the engine does not implement ("+result.detail()+")");
                throw new IllegalStateException("The network's equilibrium engine refused a crystal request it must answer: "+result.detail());
            }
            case NOT_CONVERGED->{
                if(result.failureKind()==EquilibriumResult.FailureKind.CRYSTAL_STEP&&!result.failedInCriticalBand())throw new UnsupportedPhases(domain.packageId(),Double.NaN,Double.NaN,
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
    /** The time factor this model integrates under: process seconds per online tick ({@link SimulationTime}). */
    public SimulationTime time(){return time;}
    /** This model under another time factor, every other setting the same (a view stays the same compound list); itself on its own. */
    public FluidThermodynamics withTime(SimulationTime value) {
        if(time.equals(value))return this;
        var timed=new FluidThermodynamics(whole.catalog,whole.domain.packageId(),maximumVelocity,tracePolicy,solidSettings,value,null);
        // A view keeps its compound list and whether water is in its cubic (W3b option A).
        return whole==this?timed:timed.on(compounds(),wet());
    }
    /** The per-node phase support the step solver freezes its Newton unknowns with. */
    public TraceTruncationPolicy traceTruncation(){return tracePolicy;}
    /**
     * The stream functions ({@link NetworkStreams}, which documents each): a node's hydraulic velocity limits, the
     * compressor's heat capacity ratio and the streams a vessel port draws (decision D11), read off the state with no
     * flash. The phase streams' labels {@link #GAS}, {@link #OIL} and {@link #AQUEOUS} and the record {@link PhaseStream}
     * stay here.
     */
    public double velocityLimit(State state){return streams.velocityLimit(state);}
    public double isothermalAcousticBound(State state){return streams.isothermalAcousticBound(state);}
    public double heatCapacityRatio(State state){return streams.heatCapacityRatio(state);}
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
    public static boolean holdsVapor(State state){return NetworkStreams.holdsVapor(state);}
    public static boolean holdsLiquid(State state){return NetworkStreams.holdsLiquid(state);}
    public double slotMass(State state,int slot){return streams.slotMass(state,slot);}
    public double vaporMass(State state){return streams.vaporMass(state);}
    public double liquidMass(State state){return streams.liquidMass(state);}
    public static double liquidStreamVolume(State state){return NetworkStreams.liquidStreamVolume(state);}
    public double vaporDensity(State state){return streams.vaporDensity(state);}
    public double liquidDensity(State state){return streams.liquidDensity(state);}
    public double vaporSpecificEnthalpy(State state,Prepared prepared){return streams.vaporSpecificEnthalpy(state,prepared);}
    public double vaporVelocityLimit(State state){return streams.vaporVelocityLimit(state);}
    public double vaporViscosity(State state,MixtureViscosity.Workspace scratch,Prepared prepared){return streams.vaporViscosity(state,scratch,prepared);}
    public PhaseStream vaporStream(State state,Prepared prepared,MixtureViscosity.Workspace scratch){return streams.vaporStream(state,prepared,scratch);}
    /*
     * The three phases a vessel port can draw one after another (decision D11: ports carry mixed phases by priority), by
     * label: GAS, OIL (the liquid with less water) and AQUEOUS (the liquid with more; a single liquid that is at least half
     * water). Each liquid carries its volume share of every solid population, so the two liquid streams partition the
     * condensed phase, and with one liquid present its stream carries every solid.
     */
    public static final int GAS=0,OIL=1,AQUEOUS=2;
    public static boolean holdsPhase(State state,int phase){return NetworkStreams.holdsPhase(state,phase);}
    public static boolean holdsFluid(State state){return NetworkStreams.holdsFluid(state);}
    public static boolean bulkDrawsGas(State state){return NetworkStreams.bulkDrawsGas(state);}
    public static double phaseSolidShare(State state,int phase){return NetworkStreams.phaseSolidShare(state,phase);}
    /** The own density (no solids) of the liquid in storage slot {@code slot}: its fluid mass over its volume. */
    private double liquidPhaseDensityOfSlot(State state,int slot){return slotMass(state,slot)/state.slotVolume(slot);}
    public double liquidPhaseDensity(State state,int phase){return streams.liquidPhaseDensity(state,phase);}
    public int heavierLiquid(State state){return streams.heavierLiquid(state);}
    public double phaseMass(State state,int phase){return streams.phaseMass(state,phase);}
    public static double phaseVolume(State state,int phase){return NetworkStreams.phaseVolume(state,phase);}
    public static double[] phaseMoles(State state,int phase){return NetworkStreams.phaseMoles(state,phase);}
    public static SolidInventory.Moments phaseSolidMoments(State state,int phase){return NetworkStreams.phaseSolidMoments(state,phase);}
    public double phaseSpecificEnthalpy(State state,int phase,Prepared prepared){return streams.phaseSpecificEnthalpy(state,phase,prepared);}
    public double phaseVelocityLimit(State state,int phase){return streams.phaseVelocityLimit(state,phase);}
    public double phaseViscosity(State state,int phase,MixtureViscosity.Workspace scratch,Prepared prepared){return streams.phaseViscosity(state,phase,scratch,prepared);}
    public double phaseCarrierViscosity(State state,int phase,MixtureViscosity.Workspace scratch){return streams.phaseCarrierViscosity(state,phase,scratch);}
    public double slotViscosity(State state,int slot,MixtureViscosity.Workspace scratch,MixtureViscosity.Prepared terms){return streams.slotViscosity(state,slot,scratch,terms);}
    public PhaseStream phaseStream(State state,int phase,Prepared prepared,MixtureViscosity.Workspace scratch){return streams.phaseStream(state,phase,prepared,scratch);}
    /** The amount sum {@link #state} forms ({@code sum}), without its validation: a state's own amounts are valid. */
    static double total(double[] values){double total=0;for(double value:values)total+=value;return total;}
    /**
     * The pure-water functions ({@link NetworkWater}, which documents each): utility water (cooling towers, steam services)
     * on the package's water model and pure water's saturation curve, built once for the whole model on the first read.
     */
    public WaterLiquid waterLiquid(double t,double p){return pureWater.waterLiquid(t,p);}
    public double vaporWaterEnthalpy(double t){return pureWater.vaporWaterEnthalpy(t);}
    public double saturationPressure(double t){return pureWater.saturationPressure(t);}
    public double saturationTemperature(double p){return pureWater.saturationTemperature(p);}

    /** {@code s} in the pre-W3b record ({@link LegacySplit}). */
    public static LegacySplit legacySplit(State s) {
        int n=s.componentCount()-1;double[] l=new double[n],v=new double[n];
        double[] vapor=s.vaporAmountsView(),liquid=s.liquidAmountsView(),aqueous=s.aqueousAmountsView();
        for(int i=0;i<n;i++){l[i]=liquid[i]+aqueous[i];v[i]=vapor[i];}
        double nv=total(vapor),wv=vapor[n];
        return new LegacySplit(l,v,s.liquidWater(),wv,nv>0?s.pressure()*((nv-wv)/nv):0);
    }
    /** The state of a pre-W3b record ({@link LegacySplit}) at {@code (t, p)}, read for a facts row without the slot fields: the vapour with its water, the compounds'
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
    /**
     * A column of the node Jacobian moving a mole fraction of a phase by more than this takes that phase from the equation
     * of state, not from its tangent (ANALYTIC_JACOBIAN_PLAN.md section 3.4: a step that is not small for a phase).
     */
    public static final double TANGENT_COMPOSITION_STEP=1e-3;
    /**
     * The tangents of {@code state}'s phases about it, by storage slot ({@link HydrocarbonModel#tangent}): null for a slot
     * the state does not hold and for a root that cannot be differentiated. Each tangent's base is the state's own phase.
     */
    public HydrocarbonModel.Tangent[] tangents(State state,Prepared prepared) {
        double t=state.temperature(),p=state.pressure();
        var tangents=new HydrocarbonModel.Tangent[3];
        TranslatedPengRobinson.Workspace terms=null;
        boolean gas=state.phase(State.VAPOR)!=null;
        double[] support=support(prepared,state.vaporAmountsView(),state.liquidAmountsView(),state.aqueousAmountsView());
        for(int slot=0;slot<3;slot++) {
            if(state.phase(slot)==null)continue;
            if(terms==null){terms=prepared==null?hydrocarbon.prepare(t):match(prepared,t).pengRobinson();hydrocarbon.carry(terms,support);}
            tangents[slot]=hydrocarbon.tangent(t,p,state.amountsView(slot),slot==State.VAPOR?PengRobinsonKernel.Root.VAPOR:PengRobinsonKernel.Root.LIQUID,
                    terms,slot!=State.VAPOR&&gas);
            if(tangents[slot]!=null)SolverDiagnostics.count(SolverDiagnostics.jacobianTangentsBuilt);
        }
        return tangents;
    }
    /**
     * {@link #adoptingState} beside a base state whose {@code tangents} these are (section 3.2 of the plan): each phase
     * that has a tangent, and whose composition the point moves by at most {@link #TANGENT_COMPOSITION_STEP}, is the
     * tangent's first-order phase; any other is evaluated on the equation of state. The totals are formed by the same
     * arithmetic. For the Jacobian's perturbed decodes alone: no state of the network is built from it.
     */
    public State adoptingState(double t,double p,double[] vapor,double[] liquid,double[] aqueous,Prepared prepared,HydrocarbonModel.Tangent[] tangents) {
        return state(t,p,vapor,liquid,aqueous,null,match(prepared,t),tangents);
    }
    /** One slot's phase: its tangent's where the step is small for the phase, the equation of state's otherwise. */
    private HydrocarbonModel.Phase slotPhase(HydrocarbonModel.Tangent tangent,double t,double p,double[] amounts,PengRobinsonKernel.Root root,
                                             Prepared prepared,boolean vaporCoexists,double[] support) {
        if(tangent!=null&&tangent.compositionShift(amounts)<=TANGENT_COMPOSITION_STEP) {
            SolverDiagnostics.count(SolverDiagnostics.jacobianTangentPhases);
            return tangent.at(t,p,amounts);
        }
        SolverDiagnostics.count(tangent!=null?SolverDiagnostics.jacobianExactPhases:SolverDiagnostics.jacobianUntangentPhases);
        var terms=prepared==null?hydrocarbon.prepare(t):prepared.pengRobinson();
        hydrocarbon.carry(terms,support);
        return hydrocarbon.phase(t,p,amounts,root,terms,vaporCoexists);
    }
    /**
     * The kernel support of a node's evaluation (conserved basis, nonzero where carried): the compounds {@code prepared}'s
     * owner named ({@link Prepared#carry}) and every compound the node holds; null (the whole list) where none is named.
     */
    private static double[] support(Prepared prepared,double[] vapor,double[] liquid,double[] aqueous) {
        if(prepared==null||prepared.carried==null)return null;
        boolean[] named=prepared.carried;
        if(named.length!=vapor.length)throw new IllegalArgumentException("Carried compounds basis mismatch");
        double[] support=new double[vapor.length];
        for(int i=0;i<support.length;i++)support[i]=named[i]||vapor[i]!=0||liquid[i]!=0||aqueous[i]!=0?1:0;
        return support;
    }
    private State state(double t,double p,double[] vapor,double[] liquid,double[] aqueous,
                        TranslatedPengRobinson.Workspace terms,Prepared prepared) {
        return state(t,p,vapor,liquid,aqueous,terms,prepared,null);
    }
    private State state(double t,double p,double[] vapor,double[] liquid,double[] aqueous,
                        TranslatedPengRobinson.Workspace terms,Prepared prepared,HydrocarbonModel.Tangent[] tangents) {
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
        if(terms==null&&tangents==null&&(nv>0||nl>0||na>0))terms=prepared==null?hydrocarbon.prepare(t):prepared.pengRobinson();
        // The compounds the node holds and its owner named, or the whole list (KERNEL_COST_PLAN.md Part A).
        double[] support=support(prepared,vapor,liquid,aqueous);
        if(terms!=null)hydrocarbon.carry(terms,support);
        HydrocarbonModel.Phase gp=null,lp=null,ap=null;
        // Every phase at the state pressure; with a vapour beside it, a liquid slot on a single vapour-like root is refused.
        // Beside a base state (tangents), a phase is its tangent's where the step is small for it (slotPhase).
        if(nv>0){gp=tangents==null?hydrocarbon.phase(t,p,vapor,PengRobinsonKernel.Root.VAPOR,terms,false):slotPhase(tangents[State.VAPOR],t,p,vapor,PengRobinsonKernel.Root.VAPOR,prepared,false,support);vv=nv*gp.molarVolume();volume+=vv;h+=nv*gp.molarEnthalpy();}
        if(nl>0){lp=tangents==null?hydrocarbon.phase(t,p,liquid,PengRobinsonKernel.Root.LIQUID,terms,nv>0):slotPhase(tangents[State.LIQUID],t,p,liquid,PengRobinsonKernel.Root.LIQUID,prepared,nv>0,support);vl=nl*lp.molarVolume();volume+=vl;h+=nl*lp.molarEnthalpy();}
        if(na>0){ap=tangents==null?hydrocarbon.phase(t,p,aqueous,PengRobinsonKernel.Root.LIQUID,terms,nv>0):slotPhase(tangents[State.AQUEOUS],t,p,aqueous,PengRobinsonKernel.Root.LIQUID,prepared,nv>0,support);va=na*ap.molarVolume();volume+=va;h+=na*ap.molarEnthalpy();}
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
     * converged point's check ({@code StepPhaseRules.phaseCheck}) or at a smaller step.
     */
    public State folded(State state,boolean junction,State from){return folded(state,junction,false,from);}
    /** {@link #folded(State,boolean,State)} for a vessel whose wall is rigid ({@code rigid}, {@link #rigidGas}). */
    public State folded(State state,boolean junction,boolean rigid,State from){return folded(state,rigid&&!junction,from==null?0:collapsing(from,state,rigid));}
    /** {@link #folded(State)} with the phases {@code vanishing} ({@link #phaseBits}) folded as traces too, a rigid vessel's gas
     * judged by its volume where {@code rigid} ({@link #rigidGas}): the outer phase check's fold ({@code StepPhaseRules.phaseCheck}). */
    public State folded(State state,boolean rigid,int vanishing) {
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
    public static int nothing(State state,boolean rigid) {
        double[] e=phaseAmounts(state),ev=phaseVolumes(state);double te=sum(e),tv=sum(ev);int bits=0;
        for(int k=0;k<3;k++)if(e[k]>0&&belowShare(k,e,te,ev,tv,rigid))bits|=1<<k;
        return bits;
    }
    /**
     * Whether phase {@code k} ({@link #phaseBits} order: liquid slot, vapour, aqueous slot) is below
     * {@link #VANISHING_SHARE}. Every phase is below the share by volume (owner decision of 2026-10-18: a gas holds about a
     * hundred times its mole share in volume). The hydrocarbon liquid (the liquid slot) is judged on its volume share alone
     * (owner decision 2026-10-20: a methane tank at its dew point condensed 0.077 mL a step, 1.5e-6 of its moles but 7.7e-8
     * of its volume, and the pass laid out with that film had no root above 3.7e-4 s). The aqueous liquid and the gas must
     * also be below the share by moles, the gas except in a vessel whose wall is rigid, where its volume alone is judged
     * (owner decision of 2026-11-10). Judged by volume alone, the condensate of a cooling humid headspace and a slow water
     * feed into a gas tank stood as supersaturated vapour until they filled a millionth of the vessel, then condensed at once
     * (1.2 g in a 1 m3 tank at 1.5 bar, 2 K warmer in one 0.1 s slice; W3b stage A).
     */
    private static boolean belowShare(int k,double[] e,double te,double[] ev,double tv,boolean rigid) {
        return ev[k]<VANISHING_SHARE*tv&&(k==0||k==1&&rigid||e[k]<VANISHING_SHARE*te);
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
     * A pipe junction's own split (owner decision D26, {@code StepPhaseRules.phaseCheck}), the phases of an empty bag's
     * ({@code PassiveStepSolver.emptyBagSplit}, D54) and a slack vessel's crossing ({@code StepPhaseRules.crossing}): the engine's PH
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
    /** A taut vessel's own state at a crossing ({@code StepPhaseRules.crossing}, owner decision D40 A), the UV twin of {@link #equilibriumPH}. */
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
                boolean unstable=result.failureKind()==EquilibriumResult.FailureKind.UNSTABLE_PRODUCT;
                if(unstable||result.failureKind()==EquilibriumResult.FailureKind.UNRESOLVED_PRODUCT)throw new UnsupportedPhases(domain.packageId(),t,p,
                        UnsupportedPhases.Kind.THREE_PHASES,"a further fluid phase is "+(unstable?"indicated":"not excluded")+" ("+result.detail()
                        +"); the fluid network carries a vapour and two liquids");
                throw new IllegalArgumentException("Fluid TP equilibrium did not converge: "+result.detail());
            }
            default->throw new IllegalStateException("The network's equilibrium engine refused a request it must answer: "+result.detail());
        }
        if(terms==null)terms=hydrocarbon.prepare(t);
        // The engine's phases are placed on the whole list: no owner names a support here.
        hydrocarbon.carry(terms,null);
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
     *
     * <p>One dense fluid (owner decision O2 A, batch 2026-11-15-supercritical-fluids, S2c): an answer of exactly one fluid
     * phase whose root is on a supercritical isotherm of its own composition
     * ({@link com.wormzjl.icheme.science.thermo.PhaseTraits#supercriticalIsotherm()}, mixtures included) is liquid iff its
     * reduced density ({@link com.wormzjl.icheme.science.thermo.PhaseTraits#reducedDensity()}) is at least 1, whatever
     * label the engine gave it: the engine labels a one-root mixture by the identification parameter itself, so the slot
     * line of a pure component and of the same component with a trace of another stays the same. The traits are the ones
     * the engine's phase carries; a phase without them (no traits, or a NaN attraction) is placed by the rule below. Every
     * answer of two or more fluid phases keeps the engine's labels: a LIQUID or VAPOR label as given, a FLUID one by the
     * root's identification parameter ({@link HydrocarbonModel#liquidLike}).</p>
     */
    private State placed(double t,double p,List<PhaseAmounts> fluids,TranslatedPengRobinson.Workspace terms,UnsupportedPhases.Kind kind) {
        int n=componentCount();double[] vapor=new double[n];var liquids=new ArrayList<double[]>();boolean gas=false;
        var dense=fluids.size()==1?fluids.getFirst().traits():null;
        if(dense!=null&&!dense.supercriticalIsotherm())dense=null;
        for(var phase:fluids) {
            double[] amounts=conserved(phase.amountsView());
            boolean liquid=dense!=null?dense.reducedDensity()>=1:switch(phase.kind()) {
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
    /** The sum of a node's or a phase's amounts, refusing a negative or non-finite one: every state builder's and the outer phase check's. */
    public static double sum(double[] values) {double total=0;for(double value:values){if(value<0||!Double.isFinite(value))throw new IllegalArgumentException("Invalid phase amount");total+=value;}return total;}

    /** The temperature-only property bundle for one exact node temperature. */
    public Prepared prepare(double temperature) { return new Prepared(this,temperature); }
    /**
     * {@link #prepare(double)} into {@code reuse}, one of this model's bundles (null: a new one), refilled in place when its
     * temperature differs (finding P-18, batch 2026-11-13-codebase-review): its Peng-Robinson workspace is prepared again
     * at the new temperature on the whole list, as a new one is, and gives the same bits. The caller must read nothing it
     * derived from {@code reuse} at its previous temperature after this call.
     */
    public Prepared prepare(double temperature,Prepared reuse) {
        if(reuse==null)return prepare(temperature);
        if(own(reuse).temperature==temperature)return reuse;
        reuse.refill(temperature);return reuse;
    }

    private Prepared own(Prepared prepared) {
        if(prepared.owner!=this)throw new IllegalArgumentException("Prepared temperature belongs to another model");
        return prepared;
    }
    Prepared match(Prepared prepared,double t) {
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
        private double temperature;
        private TranslatedPengRobinson.Workspace terms;
        /** A workspace kept from an earlier temperature, prepared at this one on its first use ({@link #pengRobinson}). */
        private TranslatedPengRobinson.Workspace kept;
        /** The Peng-Robinson workspaces bundles have built (each a whole kernel workspace), process-wide: a test's and the
         * bench's counter (P-18). */
        private static final java.util.concurrent.atomic.LongAdder BUILT=new java.util.concurrent.atomic.LongAdder();
        public static long workspacesBuilt(){return BUILT.sum();}
        private MixtureViscosity.Prepared transport;
        private Prepared(FluidThermodynamics owner,double temperature) {
            if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid prepared temperature");
            this.owner=owner;this.temperature=temperature;
        }
        /**
         * This bundle at {@code temperature}, as a new one is: every member unformed, the carried compounds unnamed, and the
         * Peng-Robinson workspace kept to be prepared again on its first use (P-18).
         */
        private void refill(double temperature) {
            if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid prepared temperature");
            if(terms!=null)kept=terms;
            terms=null;transport=null;carried=null;this.temperature=temperature;
        }
        public double temperature() { return temperature; }
        /**
         * The compounds this bundle's evaluations carry in the kernel (conserved basis), beside the node's own amounts:
         * named by its owner at every use, from the mask it laid the node out with in that pass ({@code PhaseLayout}),
         * so every compound whose {@code ln phi} that owner reads at zero amount is carried. Unnamed (null), every
         * evaluation carries the whole list.
         */
        public void carry(boolean[] components) { carried=components; }
        private boolean[] carried;
        /** The prepared Peng-Robinson workspace of this temperature. It is mutable scratch, so a
         * {@code Prepared} - like the node cache that holds it - belongs to one solving thread. */
        TranslatedPengRobinson.Workspace pengRobinson() {
            if(terms!=null)return terms;
            var reuse=kept;kept=null;
            if(reuse==null){BUILT.increment();return terms=owner.hydrocarbon.prepare(temperature);}
            // A new workspace is prepared on the whole list. A kept one is prepared on the support its last use left, then
            // carries the whole list again, which prepares the rest at this temperature: each compound's, pair's and cell's
            // terms are their own arithmetic at this temperature (the kernel's stamps), so the bits are a new workspace's,
            // and every term a new one would refuse is refused. A refusal leaves the workspace half prepared under the
            // kernel's stamps, so it is dropped and the next use builds a new one.
            owner.hydrocarbon.translated().prepare(temperature,reuse);
            reuse.carryAll();
            return terms=reuse;
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
        /** The fluid's enthalpy, J: the state's less its solids' at its temperature and pressure. */
        public double fluidEnthalpy() { return enthalpy-solidMoments.enthalpy(temperature,pressure); }
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
        /**
         * The one slot that holds fluid, when exactly one of the three has a positive volume; else -1 (two or three phases,
         * or none). S2a of batch 2026-11-15-supercritical-fluids.
         */
        public int oneFluidSlot() {
            boolean v=vaporVolume>0,l=liquidSlotVolume>0,a=aqueousSlotVolume>0;
            if(v&&!l&&!a)return VAPOR;
            if(l&&!v&&!a)return LIQUID;
            if(a&&!v&&!l)return AQUEOUS;
            return -1;
        }
        /**
         * Whether the state is one fluid on a supercritical isotherm of its own composition ({@link #oneFluidSlot()} and its
         * phase's {@link com.wormzjl.icheme.science.thermo.PhaseTraits#supercriticalIsotherm()}): a fluid whose density runs
         * from a gas's to a liquid's with no phase change, whichever slot it sits in.
         */
        public boolean dense() {
            int slot=oneFluidSlot();
            var traits=slot<0||phase(slot)==null?null:phase(slot).traits();
            return traits!=null&&traits.supercriticalIsotherm();
        }
        /** The one fluid's reduced density {@code v_c / v} on the untranslated cubic; NaN unless {@link #oneFluidSlot()} holds one. */
        public double reducedDensity() {
            int slot=oneFluidSlot();
            var traits=slot<0||phase(slot)==null?null:phase(slot).traits();
            return traits==null?Double.NaN:traits.reducedDensity();
        }
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
