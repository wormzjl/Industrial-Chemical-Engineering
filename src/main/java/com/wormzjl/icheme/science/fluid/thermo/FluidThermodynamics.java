package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.FreeWaterIce;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import com.wormzjl.icheme.science.thermo.phase.SteamVirial;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Immutable fluid property snapshot and standalone TP initializer; coupled steps consume phase() directly. Phase
 * decisions (the TP initializer, the outer active-set check, the inventory refresh) come from the equilibrium engine
 * through {@link #equilibriumTP}; residuals never flash.
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
    /** Resolved once from the catalog and package this model was built for, instead of through the
     * calculation context on every water evaluation: the context costs a {@code Context} allocation
     * and a {@code ThreadLocal} set/remove per call, and a coefficient read inside costs a
     * {@code ThreadLocal} lookup and two immutable-map probes. */
    private final MaterialCatalog.Water water;
    /** The ideal-gas functions of the free water's steam, continued past the water record's range ({@link NetworkPhaseEngine.Steam}). */
    private final NetworkPhaseEngine.Steam steam;
    private final double waterEnthalpyOffset;
    private final double maximumVelocity;
    /** Carried on the model, like {@link #maximumVelocity}, because every retained solver, workspace
     * and factorization is keyed on the model: replacing it is what makes a changed cutoff reach a
     * running island deterministically, and {@link #revision} carries it so a certificate issued
     * under a different unknown set is not kept. */
    private final TraceTruncationPolicy tracePolicy;

    /**
     * The canonical gameplay basis, which includes nitrogen. {@code packageId} is resolved through
     * {@link FluidMaterialCatalog#resolveNetworkPackage}, which accepts only the registered network package, and a
     * package without nitrogen is refused rather than run on a shorter basis. No overload takes a single number after
     * the package: the velocity limit comes with the trace cutoff.
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
        return new FluidThermodynamics(catalog,FluidMaterialCatalog.resolveNetworkPackage(catalog,packageId),
                maximumVelocity,TraceTruncationPolicy.of(traceCutoffMoleFraction),solidSettings);
    }

    /** Placement initializer only. Persistence must restore inventory instead of calling this again. */
    public State initialNitrogenCharge(double volume,double temperature,double pressure,Runnable checkpoint) {
        if(!Double.isFinite(volume)||volume<=0)throw new IllegalArgumentException("Positive finite vessel volume required");
        int nitrogen=hydrocarbon.components().indexOf(FluidMaterialCatalog.NITROGEN);if(nitrogen<0)throw new IllegalStateException("Network basis has no nitrogen");
        double[] n=new double[hydrocarbon.componentCount()+1];n[nitrogen]=1;
        var unit=flashTP(temperature,pressure,n,checkpoint);n[nitrogen]=volume/unit.volume();
        return flashTP(temperature,pressure,n,checkpoint);
    }

    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double maximumVelocity,TraceTruncationPolicy tracePolicy,com.wormzjl.icheme.science.fluid.transport.SlurryTransport.Settings solidSettings) {
        this.solidSettings=Objects.requireNonNull(solidSettings);
        if(!Double.isFinite(maximumVelocity)||maximumVelocity<=0)throw new IllegalArgumentException("Positive finite maximum velocity required");
        TraceTruncationPolicy.requireCutoff(Objects.requireNonNull(tracePolicy,"tracePolicy").cutoffMoleFraction(),MAX_TRACE_CUTOFF_MOLE_FRACTION);
        this.tracePolicy=tracePolicy;
        this.maximumVelocity=maximumVelocity;
        hydrocarbon=new HydrocarbonModel(catalog,packageId);
        viscosity=new MixtureViscosity(catalog,packageId);solids=catalog.solids();
        waterMolecularWeight=catalog.requirePackage(packageId).water().molarMass();
        // The same resolution the context form performs, including its fallback package.
        water=com.wormzjl.icheme.science.material.MaterialRuntime.with(catalog,packageId,
                com.wormzjl.icheme.science.material.MaterialRuntime::water);
        steam=new NetworkPhaseEngine.Steam(water);
        domain=hydrocarbon.domain();
        // The crystal competition: the package's admitted crystals, on a spine package only (a crystal is anchored to the
        // spine family's liquid, and its enthalpy is moved to the network datum by the spine's formation enthalpies). None
        // on every bundled network package: the fluid-only path is unchanged.
        var admitted=hydrocarbon.spine().isPresent()?PhaseContract.admittedCrystals(catalog,packageId):List.<String>of();
        crystalIds=List.copyOf(admitted);crystalSpecies=new boolean[hydrocarbon.componentCount()];
        double ceiling=Double.POSITIVE_INFINITY;
        for(String crystal:admitted) {
            var record=catalog.crystals().get(crystal);int i=hydrocarbon.components().indexOf(record.component());
            if(i<0)throw new IllegalArgumentException("Crystal "+crystal+" of package "+packageId+" is made of "+record.component()+", which the network basis does not carry");
            crystalSpecies[i]=true;ceiling=Math.min(ceiling,record.maximumTemperature());
        }
        crystalCeiling=ceiling;
        // Region 1 at 298.15 K and 1 atm itself, the stable liquid: the network's water datum and the pump's density.
        var reference=WaterRegion1.evaluate(water,298.15,101325);
        waterEnthalpyOffset=V3WaterProperties.liquidMolarEnthalpy(water,298.15)-reference.specificEnthalpy()*waterMolecularWeight;
        pumpReferenceDensity=waterMolecularWeight/(reference.specificVolume()*waterMolecularWeight);
        // The free water's ice (WP4): its record anchored to this model's liquid water at the triple point, on the network's
        // water datum, with this model's steam; below the melting line the free water is ice, on every package.
        var ice=water.ice();
        if(ice==null)throw new IllegalArgumentException("Package "+packageId+": water model "+water.revision()+" has no ice, which the fluid network needs");
        var iceRecord=catalog.crystals().get(ice.crystal());
        var triple=waterLiquid(iceRecord.anchor().temperature(),iceRecord.anchor().pressure());
        freeWaterIce=FreeWaterIce.of(iceRecord,ice.coefficients(),ice.exponents(),this::saturationPressure,triple.molarVolume(),triple.molarEnthalpy(),steam);
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
     * table): the network's integrator, the hydrocarbon model's and the viscosity's revisions. Built once per model.
     */
    private final String thermodynamicRevision;
    /**
     * The model's full revision: the thermodynamic one with the hydraulic acceptance law (the velocity clamp, the trace
     * cutoff, the solid settings). A certificate holds only under the revision it was issued with, and the world's
     * property guard resumes a held dimension only on its own.
     */
    private final String revision;
    public String thermodynamicRevision(){return thermodynamicRevision;}
    public String revision(){return revision;}
    /** The free water's ice below its melting line (WP4, {@link FreeWaterIce}): the phase engine's and the vessels' ice. */
    private final FreeWaterIce freeWaterIce;
    public FreeWaterIce freeWaterIce(){return freeWaterIce;}
    /** The catalog this model was built from; the phase engine is built from it on first use. */
    private final MaterialCatalog catalog;
    /** The equilibrium engine that decides this model's phases; built on the first phase decision. */
    private volatile NetworkPhaseEngine phaseEngine;
    NetworkPhaseEngine phaseEngine() {
        var engine=phaseEngine;
        if(engine==null)synchronized(this) {
            engine=phaseEngine;
            if(engine==null)phaseEngine=engine=NetworkPhaseEngine.of(this,catalog,hydrocarbon.propertyPackage().id(),water,FluidTpEquilibrium.Settings.DEFAULT);
        }
        return engine;
    }
    /** The package's crystals as solids ({@link NetworkCrystals}): the crystals it admits and, on every package, the free
     * water's ice (WP4); built with the phase engine on first use. */
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
     * admits or the free water's ice ({@link #crystalSolids}), else a custom solid of the catalog ({@link #solids}); the
     * catalog keeps the two kinds' ids apart, so an id names one record. An id that names neither is refused: nothing read
     * back (a checkpoint's population, a pending recovery) can carry a solid this package does not define. Builds the
     * crystals (and the phase engine) only for an id among the package's crystals.
     */
    public SolidMaterialCatalog.SolidMaterial solid(String id) {
        if(crystalIds.contains(id)||id.equals(freeWaterIce.crystal()))for(var crystal:crystalSolids().all())if(crystal.material().id().equals(id))return crystal.material();
        var custom=solids.materials().get(id);
        if(custom==null)throw new IllegalArgumentException("Unknown solid material: "+id+" names no crystal of package "+domain.packageId()+" and no solid material record");
        return custom;
    }
    /**
     * The crystal answer of a vessel's refresh (owner decision D23): the engine's answer for the vessel's fluid and
     * crystals together, the package's crystals competing where they compete and the free water's ice by water's own
     * rule. {@code fluid} is its fluid phases as a network state without solids (null where the crystals hold
     * everything); {@code crystalMoles} the moles of each of the package's crystals ({@link NetworkCrystals#all} order);
     * {@code volume} the fluid's and the crystals' volume, the crystals in the engine's own model.
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
     * ({@link #crystalsCompete}), the free water freezing by its own rule, warm-started at {@code (hintT, hintP)} when
     * both are finite. Refusals are typed where the engine types them ({@link UnsupportedPhases} of kind
     * {@code CRYSTAL_PHASES} or {@code CRYSTAL_STEP}, {@link CriticalBandHold}); any other failure is a plain
     * {@link IllegalArgumentException}.
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
     * spine package; the network's own datum elsewhere ({@link NetworkPhaseEngine}). Water is on the network's datum in both. */
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
        // With free water the hydrocarbons' own classification decides (WP4): the free water and its ice are water's rule.
        var split=result.freeWater();
        var hydrocarbons=split==null?result.classification():split.hydrocarbon();
        if(hydrocarbons==EquilibriumResult.Classification.LIQUID_LIQUID)throw new UnsupportedPhases(domain.packageId(),t,p,UnsupportedPhases.Kind.LIQUID_LIQUID,
                "a liquid-liquid split (two liquid-like hydrocarbon phases); the fluid network carries one hydrocarbon liquid");
        var all=crystals.all();double[] moles=new double[all.size()];double volume=0;
        int n=hydrocarbon.componentCount();double[] liquid=new double[n],vapor=new double[n];boolean fluid=false,liquidSlot=false,vaporSlot=false;
        double waterLiquid=0,waterVapor=0;
        var terms=hydrocarbon.prepare(t);
        for(var phase:result.phases()) {
            if(phase.kind()==PhaseKind.SOLID) {
                int k=-1;for(int i=0;i<all.size();i++)if(all.get(i).material().id().equals(phase.crystal()))k=i;
                if(k<0)throw new IllegalStateException("The engine answered crystal "+phase.crystal()+", which the network does not carry");
                moles[k]+=phase.amount(all.get(k).component());volume+=phase.total()*phase.state().molarVolume();continue;
            }
            if(phase.kind()==PhaseKind.FREE_WATER){waterLiquid+=phase.amount(n);fluid=true;continue;}
            boolean toLiquid=switch(phase.kind()) {
                case LIQUID->true;
                case VAPOR->false;
                default->HydrocarbonModel.liquidLike(hydrocarbon.translated().evaluateValues(t,p,Arrays.copyOf(phase.amountsView(),n),phase.state().root(),terms));
            };
            if(toLiquid?liquidSlot:vaporSlot)throw new UnsupportedPhases(domain.packageId(),t,p,UnsupportedPhases.Kind.CRYSTAL_PHASES,"two fluid phases of one slot beside a crystal");
            if(toLiquid)liquidSlot=true;else{vaporSlot=true;waterVapor+=phase.amount(n);}
            System.arraycopy(phase.amountsView(),0,toLiquid?liquid:vapor,0,n);fluid=true;
        }
        State state=fluid?state(t,p,liquid,vapor,waterLiquid,waterVapor,split==null?p:split.hydrocarbonPressure(),terms,null):null;
        return new CrystalAnswer(t,p,state,moles,volume+(state==null?0:state.volume()));
    }
    /** The package's data ranges: its envelope, the input box of a device setting ({@link FluidDomain}). */
    public FluidDomain domain(){return domain;}
    /**
     * The data ranges the state at network node {@code node} lies outside, into {@code notices} (every model continues
     * past its data and the island's status line reports it): the package envelope; each carried component's ideal-gas
     * data and the viscosity data of each phase it is in; free liquid water's Region 1 range (its viscosity's too) and the
     * pressure its metastable liquid has data to; the steam's range and qualified partial pressure; each crystal's
     * record. Reads the state and the declared ranges only, evaluating no model but the water's saturation pressure.
     */
    public void dataRanges(State state,long node,DataRangeNotices notices) {
        double t=state.temperature(),p=state.pressure();
        var envelope=domain.envelope();
        notices.check("package envelope temperature",t,envelope.minimumTemperature(),envelope.maximumTemperature(),"K",node);
        notices.check("package envelope pressure",p,envelope.minimumPressure(),envelope.maximumPressure(),"Pa",node);
        var eos=hydrocarbon.translated();var names=hydrocarbon.components();
        double[] liquid=state.liquidView(),vapor=state.vaporView();
        for(int i=0;i<liquid.length;i++) {
            if(!(liquid[i]>0)&&!(vapor[i]>0))continue;
            String name=names.get(i);
            notices.check(name+" ideal-gas heat capacity",t,eos.idealGasMinimumTemperature(i),eos.idealGasMaximumTemperature(i),"K",node);
            var liquidData=liquid[i]>0?viscosity.liquidData(i,t):null;
            if(liquidData!=null)notices.check(name+" liquid viscosity",t,liquidData.minimumTemperatureKelvin(),liquidData.maximumTemperatureKelvin(),"K",node);
            var vaporData=vapor[i]>0?viscosity.vaporData(i):null;
            if(vaporData!=null)notices.check(name+" vapour viscosity",t,vaporData.minimumTemperatureKelvin(),vaporData.maximumTemperatureKelvin(),"K",node);
        }
        if(state.waterLiquid()>0) {
            notices.check("liquid water",t,water.triplePoint(),WaterRegion1.REGION_1_MAXIMUM_TEMPERATURE,"K",node);
            if(t>=water.triplePoint()&&t<=WaterRegion1.REGION_1_MAXIMUM_TEMPERATURE)notices.check("liquid water pressure",p,
                    saturationPressure(t)-WaterRegion1.METASTABLE_MARGIN_PASCAL,WaterRegion1.REGION_1_MAXIMUM_PRESSURE,"Pa",node);
        }
        if(state.waterVapor()>0) {
            notices.check("water vapour",t,steam.minimumTemperature(),steam.maximumTemperature(),"K",node);
            notices.check("water vapour partial pressure",state.waterPartialPressure(),0,waterVaporPressureLimit(t),"Pa",node);
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
    /** Whether a hydrocarbon component is the species of an admitted crystal. */
    private final boolean[] crystalSpecies;
    /** The lowest ceiling of the admitted crystals' records: above it no crystal competes (the fluid-only domain applies). */
    private final double crystalCeiling;
    /**
     * The density a pump's "maximum pressure rise" is stated for: liquid water at 298.15 K and 101,325 Pa, from this
     * model's own water (IF97 Region 1 at that state), 997.05 kg/m3 (NIST: 997.0). A
     * pump's rise limit on another fluid is the setting scaled by the density of
     * what it withdraws over this one: a head, so a pump that pushes water 500 kPa pushes nitrogen at 1 atm about
     * 575 Pa. See {@code PassiveStepSolver.riseLimit}.
     */
    public double pumpReferenceDensity(){return pumpReferenceDensity;}
    private final double pumpReferenceDensity;
    /** Conserved component basis: registered hydrocarbon/gas components, followed by water. */
    public int componentCount() { return hydrocarbon.componentCount()+1; }

    /** Immutable conserved-component names, including water in the final slot. */
    public java.util.List<String> components() {
        var components=new java.util.ArrayList<>(hydrocarbon.components());
        components.add("Water");
        return java.util.List.copyOf(components);
    }

    /** Molecular weight in kg/mol for an index in the complete conserved basis. */
    public double molecularWeight(int component) {
        return component==hydrocarbon.componentCount()?waterMolecularWeight:hydrocarbon.molecularWeight(component);
    }

    /** Independently owned molecular-weight array in conserved-component order. */
    public double[] molecularWeights() {
        double[] weights=new double[componentCount()];
        for(int c=0;c<weights.length;c++)weights[c]=molecularWeight(c);
        return weights;
    }
    /**
     * The free water's saturation pressure: the Wagner-Pruss equation, below the water model's triple point the
     * supercooled liquid's ({@link V3WaterProperties#wagnerPruss}), infinite from the critical temperature on (water there
     * is steam).
     */
    public double saturationPressure(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        return t>=water.criticalTemperature()?Double.POSITIVE_INFINITY:V3WaterProperties.wagnerPruss(water,t);
    }
    /** The saturation pressure of a prepared node temperature, evaluated once for that temperature. */
    public double saturationPressure(Prepared prepared) { return own(prepared).saturationPressure(); }
    /**
     * The vapour pressure of the condensed free water at {@code t} and {@code p}: its saturation pressure, the ice's below
     * the melting line, where its condensed stand-in is ice (WP4). What a gas beside free water holds of it as steam: the
     * TP answer's test of a water-only node and the steam a vessel's gas takes beside the free water arriving in it
     * ({@code PassiveStepSolver.arrivingInVessel}).
     */
    public double freeWaterVaporPressure(double t,double p){return t<freeWaterIce.meltingTemperature(p)?freeWaterIce.fugacity(t,p):saturationPressure(t);}
    /** The ideal-gas part of the free water's steam enthalpy, J/mol ({@link NetworkPhaseEngine.Steam}); a state adds the second
     * virial's residual at the steam's partial pressure ({@link SteamVirial#residualEnthalpy}). */
    public double vaporWaterEnthalpy(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        return steam.enthalpy(t);
    }
    /** The ideal-gas functions of the free water's steam: the phase engine's water vapour (with {@link SteamVirial}) and the notices' data range. */
    NetworkPhaseEngine.Steam steam(){return steam;}
    public double maximumVelocityMetresPerSecond(){return maximumVelocity;}
    /** The per-node phase support the step solver freezes its Newton unknowns with. */
    public TraceTruncationPolicy traceTruncation(){return tracePolicy;}
    /** A hydraulic rate bound only; temperature and phase allocation still come from the EOS/energy equations. */
    public double velocityLimit(State state){return Math.min(maximumVelocity,isothermalAcousticBound(state));}
    /**
     * Frozen-phase isothermal acoustic bound used by the configured hydraulic velocity clamp: the node's compliance is
     * {@code sum_phases V_phase kappa_phase}, the hydrocarbon liquid's and the free water's own isothermal
     * compressibility at the state (the equation of state's and Region 1's), and the gas's.
     */
    public double isothermalAcousticBound(State state) {
        double compliance=state.waterVolume()*state.waterCompressibility();
        if(state.liquidVolume()>0)compliance+=state.liquidVolume()*state.liquidProperties().isothermalCompressibility();
        if(state.vaporVolume()>0) {
            double stiffness=steamStiffness(state);
            if(state.vaporProperties()!=null){var gas=state.vaporProperties();stiffness+=-gas.molarVolume()/gas.volumePressureDerivative();}
            compliance+=state.vaporVolume()/stiffness;
        }
        // Solids are incompressible here: a state holding no fluid (a vessel holding only solids, D33) has no acoustic
        // bound, and the configured clamp alone limits what would leave it (nothing does: its ends are dry).
        if(compliance==0&&!holdsFluid(state))return Double.POSITIVE_INFINITY;
        double speed=state.volume()/Math.sqrt(state.mass()*compliance);
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid acoustic flow-domain bound");
        return speed;
    }
    /**
     * The fluid a phase-selective vessel outlet draws, read off the vessel's own state with no flash and no extra
     * equation-of-state root: every term is a sum over the phase amounts, volumes and molar properties {@link #state}
     * already computed. The vapour stream is the hydrocarbon vapour and the water vapour ({@code moles} are {@code vapor}
     * with the water vapour last, the gas volume, enthalpy {@code n_v h_v + n_wv h_wv(T, p_w)}, no solid). The condensed phase
     * is the hydrocarbon liquid, the free water and every solid; a port draws its two liquids one after the other
     * (decision D11), so each liquid is its own stream ({@link #phaseStream}) with its volume share of the solids and of
     * {@code H_solids(T,P)}; {@link #liquidMass}, {@link #liquidStreamVolume} and {@link #liquidDensity} state the whole
     * condensed phase. The streams partition the state. The vapour's velocity limit is {@link #isothermalAcousticBound}'s
     * formula restricted to the vapour ({@code sqrt(stiffness/rho_v)}, capped by the configured maximum), not the far
     * lower bound of the two-phase mixture; a liquid stream's limit is the configured maximum. A stream whose phase the
     * state does not hold is not built ({@code null}): a port then draws the next phase of its priority order.
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
    /** Whether the state holds a hydrocarbon liquid or free water to draw (solids alone do not flow as a liquid). */
    public static boolean holdsLiquid(State state){return state.liquidVolume()+state.waterVolume()>0;}
    /** Mass of the vapour stream: hydrocarbon vapour then water vapour, summed in the order {@link #state} sums the whole. */
    public double vaporMass(State state) {
        double mass=0;var v=state.vaporView();
        for(int i=0;i<v.length;i++)mass+=v[i]*hydrocarbon.molecularWeight(i);
        return mass+state.waterVapor()*waterMolecularWeight;
    }
    /** Mass of the condensed phase: hydrocarbon liquid, free water, then every solid (the level head's mass). */
    public double liquidMass(State state) {
        double mass=0;var l=state.liquidView();
        for(int i=0;i<l.length;i++)mass+=l[i]*hydrocarbon.molecularWeight(i);
        return mass+state.waterLiquid()*waterMolecularWeight+state.solidMoments().mass();
    }
    /** Volume of the condensed phase: the two liquid volumes and the solid volume. */
    public static double liquidStreamVolume(State state){return state.liquidVolume()+state.waterVolume()+state.solidMoments().volume();}
    /** The vapour stream's density, for a state that {@link #holdsVapor}. */
    public double vaporDensity(State state){return vaporMass(state)/state.vaporVolume();}
    /** The condensed phase's density (its mass over its volume), for a state that {@link #holdsLiquid}. */
    public double liquidDensity(State state){return liquidMass(state)/liquidStreamVolume(state);}
    /** The vapour stream's specific enthalpy (J/kg); {@code prepared} as for {@link #state}, or null. */
    public double vaporSpecificEnthalpy(State state,Prepared prepared) {
        double h=0;
        if(state.vaporProperties()!=null)h+=total(state.vaporView())*state.vaporProperties().molarEnthalpy();
        if(state.waterVapor()>0)h+=state.waterVapor()*((prepared==null?vaporWaterEnthalpy(state.temperature()):match(prepared,state.temperature()).vaporEnthalpy())
                +SteamVirial.residualEnthalpy(state.temperature(),state.waterPartialPressure()));
        return h/vaporMass(state);
    }
    /**
     * The steam's part of the gas's isothermal stiffness {@code -V dP/dV}: {@code p_w V / (V - n_w B)} of the virial steam
     * ({@link SteamVirial}), {@code p_w Z} for steam alone; zero without steam.
     */
    private static double steamStiffness(State state) {
        double n=state.waterVapor(),v=state.vaporVolume();
        return n>0?state.waterPartialPressure()*v/(v-n*SteamVirial.secondVirial(state.temperature())):0;
    }
    /** The vapour stream's velocity limit: {@code min(maximum, V_v/sqrt(M_v V_v/stiffness))}. */
    public double vaporVelocityLimit(State state) {
        double stiffness=steamStiffness(state);
        if(state.vaporProperties()!=null){var gas=state.vaporProperties();stiffness+=-gas.molarVolume()/gas.volumePressureDerivative();}
        return Math.min(maximumVelocity,acoustic(state.vaporVolume(),vaporMass(state),state.vaporVolume()/stiffness));
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
    /** The vapour stream's viscosity: the vapour term of the solver's volume average, over the gas volume. */
    public double vaporViscosity(State state,MixtureViscosity.Workspace scratch,Prepared prepared) {
        var terms=prepared==null?null:match(prepared,state.temperature()).viscosities();
        return viscosity.vapor(state.temperature(),state.vaporView(),state.waterVapor(),scratch,terms);
    }
    /** The vapour stream of {@code state}, or null when it holds no gas. */
    public PhaseStream vaporStream(State state,Prepared prepared,MixtureViscosity.Workspace scratch) {
        if(!holdsVapor(state))return null;
        var v=state.vaporView();double[] n=Arrays.copyOf(v,v.length+1);n[v.length]=state.waterVapor();
        return new PhaseStream(n,vaporMass(state),state.vaporVolume(),vaporViscosity(state,scratch,prepared),
                vaporSpecificEnthalpy(state,prepared),vaporVelocityLimit(state),SolidInventory.Moments.ZERO);
    }
    /*
     * The three phases a vessel port can draw one after another (decision D11: ports carry mixed phases by priority). GAS
     * is the vapour stream above. The condensed phase splits into the hydrocarbon liquid (OIL) and the free water (WATER),
     * each carrying its volume share of every solid population (the solids are a uniform suspension in the combined
     * liquid, the carrier the solid mobility check reads), so the OIL and WATER streams partition the condensed phase, and
     * with one liquid present its stream carries every solid. All read the state's own amounts and molar properties.
     */
    public static final int GAS=0,OIL=1,WATER=2;
    /** Whether {@code state} holds {@code phase} ({@link #GAS}, {@link #OIL} or {@link #WATER}) to draw: a positive
     * volume of it. */
    public static boolean holdsPhase(State state,int phase) {
        return switch(phase){case GAS->state.vaporVolume()>0;case OIL->state.liquidVolume()>0;case WATER->state.waterVolume()>0;default->throw new IllegalArgumentException("phase "+phase);};
    }
    /** Whether the state holds any fluid phase: not a vessel holding only solids ({@link #solidState}). */
    public static boolean holdsFluid(State state) {
        return state.vaporVolume()>0||state.liquidVolume()>0||state.waterVolume()>0;
    }
    /**
     * Owner decision D33: solids ride the liquid. A connection's BULK end draws a uniform share of what its donor holds,
     * the solids carried by the liquid at {@code solids * liquid drawn / liquid held}; where no liquid is held nothing
     * carries them (frost on a tank's walls, a dry bed), so the end draws the donor's gas exactly where that differs from
     * its bulk: a gas beside solids with no liquid (a stand-in liquid, D29 A, is a liquid here). The solids are read as
     * the populations the state carries, which a step's iterates keep from their pass's seed, so the answer is fixed for
     * a pass. The one reading of a bulk end: the stage guard's outlet, the static end reads ({@code PhaseDraw.leading}),
     * the step's donor terms and the reconstruction's booking.
     */
    public static boolean bulkDrawsGas(State state) {
        return !(state.liquidVolume()>0)&&!(state.waterVolume()>0)&&state.vaporVolume()>0&&!state.solids().empty();
    }
    /** The share of the state's solids a phase stream carries: none in the gas, the liquids' in proportion to their
     * volumes ({@code 1.0} exactly for the only liquid held). */
    public static double phaseSolidShare(State state,int phase) {
        if(phase==GAS)return 0;
        double oil=state.liquidVolume(),water=state.waterVolume();
        return oil+water>0?(phase==OIL?oil/(oil+water):water/(oil+water)):0;
    }
    /** The own density (no solids) of a liquid phase the state holds: its fluid mass over its volume. */
    public double liquidPhaseDensity(State state,int phase) {
        if(phase==WATER)return state.waterLiquid()*waterMolecularWeight/state.waterVolume();
        double mass=0;var l=state.liquidView();for(int i=0;i<l.length;i++)mass+=l[i]*hydrocarbon.molecularWeight(i);
        return mass/state.liquidVolume();
    }
    /** The heavier of the state's two liquids by their own densities: {@link #OIL} only when both are held and the
     * hydrocarbon liquid is the denser, {@link #WATER} otherwise (which one is named when at most one is held does not
     * matter to a priority order: an absent phase is skipped). */
    public int heavierLiquid(State state) {
        return state.liquidVolume()>0&&state.waterVolume()>0&&liquidPhaseDensity(state,OIL)>liquidPhaseDensity(state,WATER)?OIL:WATER;
    }
    /** Mass of a phase stream, its solid share included; {@link #vaporMass} for the gas. */
    public double phaseMass(State state,int phase) {
        if(phase==GAS)return vaporMass(state);
        double mass=0;
        if(phase==OIL){var l=state.liquidView();for(int i=0;i<l.length;i++)mass+=l[i]*hydrocarbon.molecularWeight(i);}
        else mass=state.waterLiquid()*waterMolecularWeight;
        return mass+phaseSolidShare(state,phase)*state.solidMoments().mass();
    }
    /** Volume of a phase stream: the gas volume, or the liquid's volume plus its share of the solid volume. */
    public static double phaseVolume(State state,int phase) {
        if(phase==GAS)return state.vaporVolume();
        return (phase==OIL?state.liquidVolume():state.waterVolume())+phaseSolidShare(state,phase)*state.solidMoments().volume();
    }
    /** The conserved amounts (water last) of a phase stream. */
    public static double[] phaseMoles(State state,int phase) {
        var view=phase==GAS?state.vaporView():state.liquidView();double[] n=new double[view.length+1];
        switch(phase) {
            case GAS->{System.arraycopy(view,0,n,0,view.length);n[view.length]=state.waterVapor();}
            case OIL->System.arraycopy(view,0,n,0,view.length);
            default->n[view.length]=state.waterLiquid();
        }
        return n;
    }
    /** The solid moments a phase stream carries: its share of the state's. */
    public static SolidInventory.Moments phaseSolidMoments(State state,int phase) {
        double share=phaseSolidShare(state,phase);if(share==0)return SolidInventory.Moments.ZERO;
        var m=state.solidMoments();return new SolidInventory.Moments(share*m.mass(),share*m.volume(),share*m.heatCapacity());
    }
    /** A phase stream's specific enthalpy (J/kg), its solid share included; {@code prepared} as for {@link #state}. */
    public double phaseSpecificEnthalpy(State state,int phase,Prepared prepared) {
        if(phase==GAS)return vaporSpecificEnthalpy(state,prepared);
        double h=0,t=state.temperature(),p=state.pressure();
        if(phase==OIL){if(state.liquidProperties()!=null)h+=total(state.liquidView())*state.liquidProperties().molarEnthalpy();}
        else if(state.waterLiquid()>0)h+=state.waterLiquid()*waterLiquid(t,p,prepared).molarEnthalpy;
        h+=phaseSolidShare(state,phase)*state.solidMoments().enthalpy(t,p);
        return h/phaseMass(state,phase);
    }
    /** A phase stream's velocity limit: {@link #vaporVelocityLimit} for the gas, the configured maximum for a liquid
     * with its solids. A liquid's own isothermal sound speed {@code sqrt(1/(kappa rho))} is 900 to 1500 m/s at the
     * network's states (water 1480, LNG about 1000 at 112 K, crude about 900), so it never binds below the cap; only a
     * pure light liquid within a few kelvin of its critical point would fall under it (owner decision D37, S5). */
    public double phaseVelocityLimit(State state,int phase) {
        return phase==GAS?vaporVelocityLimit(state):maximumVelocity;
    }
    /** A phase stream's viscosity: {@link #vaporViscosity} for the gas; a liquid's own viscosity with the slurry
     * correction for its share of the solids, over the stream's volume. */
    public double phaseViscosity(State state,int phase,MixtureViscosity.Workspace scratch,Prepared prepared) {
        if(phase==GAS)return vaporViscosity(state,scratch,prepared);
        var terms=prepared==null?null:match(prepared,state.temperature()).viscosities();
        double t=state.temperature(),liquid,value;
        if(phase==OIL){liquid=state.liquidVolume();value=liquid*viscosity.liquid(t,state.liquidView(),terms).pascalSeconds();}
        else{liquid=state.waterVolume();value=liquid*viscosity.waterLiquid(t,terms);}
        double solid=phaseSolidShare(state,phase)*state.solidMoments().volume();
        if(solid>0){double phi=solid/(liquid+solid);value=com.wormzjl.icheme.science.fluid.transport.SlurryTransport.effectiveViscosity(value/liquid,Math.min(phi,0.62-1e-9))*(liquid+solid);}
        return value/(liquid+solid);
    }
    /** The carrier viscosity of a phase stream (no slurry correction), which an inline filter's cake resistance scales
     * with: the gas's own, or the liquid's own. */
    public double phaseCarrierViscosity(State state,int phase,MixtureViscosity.Workspace scratch) {
        if(phase==GAS)return vaporViscosity(state,scratch,null);
        return phase==OIL?viscosity.liquid(state.temperature(),state.liquidView()).pascalSeconds():viscosity.waterLiquid(state.temperature());
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
     * The partial pressure up to which the free water's second-virial steam ({@link SteamVirial}) is qualified, Pa: a
     * data range, which a notice reports (owner rule of 2026-10-16); above it the virial steam continues. Each band holds
     * the pressure where the virial's volume first leaves 2 % of the full IAPWS-IF97 Region 2 at the band's lower
     * temperature, rounded down to two digits (computed 2026-10-28, {@code SteamVirialTest} checks it): 1.8 MPa below
     * 500 K, where the virial holds 2 % up to saturation (to 480.9 K and 1.82 MPa); 4.5 MPa from 575 K (4.9 MPa at
     * 584 K); 34 MPa from 850 K; 65 MPa from 875 K (Region 2's least from there to its 1073.15 K ceiling, 65.5 MPa at
     * 1000 K). The ideal steam's limits were 0.125 to 2 MPa.
     */
    public static double waterVaporPressureLimit(double t) {
        int band=0;while(band<VAPOR_LIMIT_TEMPERATURES.length&&t>=VAPOR_LIMIT_TEMPERATURES[band])band++;
        return VAPOR_LIMIT_PRESSURES[band];
    }
    /** The lower temperatures of {@link #waterVaporPressureLimit}'s bands after the first, K. */
    private static final double[] VAPOR_LIMIT_TEMPERATURES={500,525,550,575,600,625,650,675,700,725,750,775,800,825,850,875};
    /** The limit of each band, Pa, the first below 500 K. */
    private static final double[] VAPOR_LIMIT_PRESSURES={1.8e6,2.2e6,2.8e6,3.6e6,4.5e6,5.5e6,6.7e6,8.1e6,9.6e6,11e6,13e6,15e6,18e6,22e6,27e6,34e6,65e6};
    /**
     * Liquid water at the state pressure, into the prepared temperature's Region 1 workspace (a fresh one for a
     * standalone call): Region 1 and its continuation past its range ({@link WaterRegion1#evaluateLiquid}).
     */
    private WaterRegion1.Workspace waterAt(double t,double p,Prepared prepared) {
        var workspace=prepared==null?new WaterRegion1.Workspace():prepared.water();
        WaterRegion1.evaluateLiquid(water,t,p,workspace);
        return workspace;
    }
    public WaterLiquid waterLiquid(double t,double p) { return waterLiquid(t,p,null); }
    /** The Region 1 temperature terms and the saturation pressure are functions of temperature alone, so a prepared
     * temperature keeps them for every pressure trial at that temperature; the pressure terms are evaluated per call. */
    public WaterLiquid waterLiquid(double t,double p,Prepared prepared) {
        var w=waterAt(t,p,match(prepared,t));
        return new WaterLiquid(w.specificVolume()*waterMolecularWeight,w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset);
    }

    /** Direct properties of specified phase amounts, without an inner flash. */
    public State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {
        return state(t,p,liquid.clone(),vapor.clone(),waterLiquid,waterVapor,hydrocarbonPressure,null,null);
    }
    /** Every temperature-only property comes from the prepared bundle; a standalone caller passes
     * {@code null} and each one is computed here, exactly as the mixing terms already were. */
    public State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,Prepared prepared) {
        return state(t,p,liquid.clone(),vapor.clone(),waterLiquid,waterVapor,hydrocarbonPressure,null,match(prepared,t));
    }
    /** The returned state adopts {@code liquid} and {@code vapor} instead of copying them: for a
     * caller that allocated them for this call and never reads or writes them again. */
    public State adoptingState(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,Prepared prepared) {
        return state(t,p,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure,null,match(prepared,t));
    }
    private State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,
                        TranslatedPengRobinson.Workspace terms,Prepared prepared) {
        if(SolverDiagnostics.ENABLED) {
            SolverDiagnostics.stateCalls.increment();
            if(SolverDiagnostics.inJacobian())SolverDiagnostics.stateCallsInJacobian.increment();
        }
        int n=hydrocarbon.componentCount();
        if(liquid.length!=n||vapor.length!=n)throw new IllegalArgumentException("Fluid state basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p)||!Double.isFinite(waterLiquid)||!Double.isFinite(waterVapor))throw new IllegalArgumentException("Fluid state is not finite");
        // No data range bounds a state (owner rule of 2026-10-16: every property continues past its data, and the
        // interval's notice reports it); only the numerical box refuses a trial, a plain IllegalArgumentException.
        NumericalBox.require(t,p);
        // Free liquid water below its melting line is the stand-in of its ice (WP4, decision 3 A): a step carries the water
        // a TP answer states as ice as supercooled liquid, Region 1 held at its 273.16 K values, until a vessel's refresh
        // freezes it (owner decision D23: crystal mass moves only there).
        double nl=sum(liquid),nv=sum(vapor),volume=0,h=0,mass=0,gasVolume=0,vl=0,vw=0;
        // A vapour is evaluated at its hydrocarbon partial pressure, an unknown of its own and a pressure the kernel reads:
        // the box bounds it too, from its own floor (a trace in a gas that is nearly all steam) to the box's 1 GPa. Bundled
        // fuzz seeds 145, 157 and 164 took it to 6.6e25 to 1.5e292 Pa, where floating point loses the cubic's root.
        if(nv>0)NumericalBox.requirePartialPressure(hydrocarbonPressure);
        if(terms==null&&(nl>0||nv>0))terms=prepared==null?hydrocarbon.prepare(t):prepared.pengRobinson();
        HydrocarbonModel.Phase lp=null,vp=null;
        // The liquid at the state pressure; with a vapour beside it, a liquid slot on a single vapour-like root is refused.
        if(nl>0) {lp=hydrocarbon.phase(t,p,liquid,PengRobinsonKernel.Root.LIQUID,terms,nv>0);vl=nl*lp.molarVolume();volume+=vl;h+=nl*lp.molarEnthalpy();}
        if(nv>0) {vp=hydrocarbon.phase(t,hydrocarbonPressure,vapor,PengRobinsonKernel.Root.VAPOR,terms);gasVolume=nv*vp.molarVolume();h+=nv*vp.molarEnthalpy();}
        if(waterLiquid<0||waterVapor<0||!Double.isFinite(waterLiquid+waterVapor))throw new IllegalArgumentException("Invalid water split");
        double kw=0;
        if(waterLiquid>0) {
            var w=waterAt(t,p,prepared);double molarVolume=w.specificVolume()*waterMolecularWeight;
            vw=waterLiquid*molarVolume;volume+=vw;h+=waterLiquid*(w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset);
            kw=-w.volumePressureDerivative()/w.specificVolume();
        }
        // The steam is a second-virial gas (SteamVirial, separate-free-water-v3): alone at the state pressure, n_w (R T/P + B);
        // beside the hydrocarbon vapour in that vapour's volume (Dalton), p_w = n_w R T / (V - n_w B).
        double pw=0;
        if(waterVapor>0) {
            if(nv==0){gasVolume=waterVapor*SteamVirial.molarVolume(t,p);pw=p;}
            else pw=SteamVirial.partialPressure(waterVapor,t,gasVolume);
            if(!(gasVolume>0)||!Double.isFinite(pw))throw new IllegalArgumentException("No virial steam volume at "+t+" K and "+(nv==0?p:pw)+" Pa");
            h+=waterVapor*((prepared==null?vaporWaterEnthalpy(t):prepared.vaporEnthalpy())+SteamVirial.residualEnthalpy(t,pw));
        }
        volume+=gasVolume;
        for(int i=0;i<n;i++)mass+=(liquid[i]+vapor[i])*hydrocarbon.molecularWeight(i);
        mass+=(waterLiquid+waterVapor)*waterMolecularWeight;
        if(!(volume>0)||!(mass>0)||!Double.isFinite(h))throw new IllegalArgumentException("Empty/nonfinite fluid properties");
        return new State(t,p,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure,pw,volume,h,h-p*volume,mass,
                vl,vw,gasVolume,lp,vp,SolidInventory.EMPTY,SolidInventory.Moments.ZERO,kw);
    }

    /** Stationary dry inventory. Pressure is retained as an initialization hint, not a gas pressure. */
    public State solidState(double temperature,double pressure,com.wormzjl.icheme.science.fluid.state.SolidInventory solids) {
        if(solids.empty()||!Double.isFinite(temperature)||!Double.isFinite(pressure))throw new IllegalArgumentException("Dry solid state needs solids and a finite temperature and pressure");
        NumericalBox.require(temperature,pressure);
        var moments=solids.moments();var empty=new double[hydrocarbon.componentCount()];
        return new State(temperature,pressure,empty,empty,0,0,0,0,moments.volume(),
                moments.enthalpy(temperature,pressure),moments.internalEnergy(temperature),moments.mass(),0,0,0,null,null,solids,moments,0);
    }

    /**
     * Owner decision D34: a fluid phase holding less than this share of its node's fluid amount is a trace phase and does
     * not exist; its material is in the node's other phase (A83). The share is taken in moles over every fluid phase and
     * component of the node, solids not counted: the unit the Newton states a node's balances in, so a phase below it is
     * below what the solve resolves, the same number for a condensate film in a gas and for the last bubble in a liquid.
     */
    public static final double PHASE_TRACE_FRACTION=1e-9;
    /**
     * The share under which a phase that is shrinking is vanishing ({@link #vanishing}), in any node: a phase holding
     * less than this share of its node's fluid amount, and a smaller share than where the solve that judges it started,
     * leaves, at a converged point as at a failed iterate (owner decision D38 C). It is what a solve that stops short
     * resolves: a closure's 30 iterations end at a residual near 1e-6 of the node's amount, and a liquid-full tank beside
     * dry ice stalled with its vapour at 1e-9 to 3e-9 of the moles and still falling, which the trace share alone keeps or
     * drops at rounding. 1e-6 of a junction's holdup is about a milligram of LNG. A phase that does not shrink keeps the
     * trace share ({@link #PHASE_TRACE_FRACTION}) alone; a proposal the next pass drives under this share is refused
     * (owner decision D39 B). The share is taken by moles and by volume ({@link #belowShare}); owner decision 2026-10-20,
     * amending D38 C: a shrinking hydrocarbon liquid vanishes on its volume share alone.
     */
    public static final double VANISHING_SHARE=1e-6;
    /**
     * The share of its start share under which a shrinking phase of a failed iterate is collapsing ({@link #collapsing};
     * t38 form C, 2026-10-18): a solve that drives a phase to nothing moves its ln(v/l) splits along a near-null
     * direction of the equilibrium block (the rows {@code x_i - ln K_i - ln(V/L)} are singular under a common shift), so
     * the Newton crawls and stops at its iteration limit with the phase at any small share (a bubble-point receiver's
     * seed vapour of 8 % by mass stopped at 1.3e-6, a hair over {@link #VANISHING_SHARE}, at every step size). A phase a
     * thousand times below where the solve started it is going, whatever share it stands at: it is folded and the pass
     * solved again without it; a phase that exists comes back by the converged point's check, and a proposal that
     * collapses again is refused (owner decision D39 B).
     */
    public static final double VANISHING_COLLAPSE=1e-3;
    /**
     * Folds the trace phases of one split in place (owner decisions D33, D34): nothing leaves the node and nothing is
     * booked, so every component amount and the node's mass are unchanged. The rule (A84): a trace hydrocarbon liquid
     * goes into the gas phase when that holds hydrocarbons; a trace of free water goes into the gas as water vapour; a
     * trace gas goes into the liquids, its hydrocarbons into the hydrocarbon liquid and its water vapour into the free
     * water, when each part has its liquid; a trace gas whose gas components have no hydrocarbon liquid, in a node with
     * free water, is a carried gas (owner decision D41, {@link #carriedGas}): its water vapour goes into the free water and
     * its gas components stay a dry gas at the node's pressure; a trace phase none of whose receiving phases exists stays,
     * and a receiving phase that is itself a trace receives nothing. {@code water} is {free water, water vapour}. Returns
     * whether anything moved. {@link #folded} and every flash apply the rule to a state.
     */
    public boolean foldTracePhases(double[] liquid,double[] vapor,double[] water){return foldTracePhases(liquid,vapor,water,0);}
    /**
     * {@link #foldTracePhases(double[],double[],double[])} with the phases {@code vanishing} names ({@link #phaseBits};
     * owner decision D38 C) folded as traces by the same rule: a hydrocarbon liquid (1) or free water (4) it names, and a
     * gas every part of which it names (2 for its hydrocarbons, 8 for its water vapour). A phase it names receives
     * nothing either. A vanishing gas whose hydrocarbons have no hydrocarbon liquid to go into stays whole: the carried
     * gas's split is a trace gas's only (such a gas, nitrogen over water, leaves through the node's ports, and its humidity
     * folded alone is given back by the next TP answer, a pass cycle).
     */
    boolean foldTracePhases(double[] liquid,double[] vapor,double[] water,int vanishing) {
        double nl=0,nh=0;for(double x:liquid)nl+=x;for(double x:vapor)nh+=x;
        double nv=nh+water[1],nw=water[0],total=nl+nv+nw;
        if(!(total>0))return false;
        double limit=PHASE_TRACE_FRACTION*total;boolean moved=false;
        // A phase is a trace under the trace share or where it is vanishing; a phase the node holds that is no trace stands.
        boolean tl=nl<limit||(vanishing&1)!=0,tw=nw<limit||(vanishing&4)!=0,tv=nv<limit||(nh==0||(vanishing&2)!=0)&&(water[1]==0||(vanishing&8)!=0);
        boolean sl=nl>0&&!tl,sw=nw>0&&!tw,sv=nv>0&&!tv;
        if(nl>0&&tl&&nh>0&&sv){for(int i=0;i<liquid.length;i++){vapor[i]+=liquid[i];liquid[i]=0;}moved=true;}
        if(nw>0&&tw&&sv){water[1]+=water[0];water[0]=0;moved=true;}
        if(nv>0&&tv&&(nh==0||sl)&&(water[1]==0||sw)) {
            for(int i=0;i<vapor.length;i++){liquid[i]+=vapor[i];vapor[i]=0;}water[0]+=water[1];water[1]=0;moved=true;
        }
        else if(nv>0&&nv<limit&&nh>0&&!sl&&water[1]>0&&sw){water[0]+=water[1];water[1]=0;moved=true;}
        return moved;
    }
    /**
     * Owner decision D41: a gas bubble in a liquid-full pipe is carried by the liquid, at the liquid's velocity, to the
     * next gas space, where it joins the gas there; in the pipe it sits at the liquid's temperature and pressure with its
     * own volume and is in no equilibrium with the water around it. Such a carried gas is a dry gas at the node's
     * pressure (its hydrocarbon partial pressure is the pressure) whose water is in the free water, so the node states no
     * saturation row and no partial-pressure row for it; it leaves with the liquid in proportion, and the first node with
     * a gas space gives it its vapour again. A node holds a carried gas when its gas is a trace ({@link #foldTracePhases}),
     * or when it is a pipe junction ({@code junction}) whose only liquid is free water and whose free water fills more of
     * it than its gas (a vessel separates its gas upwards by gravity; a junction is a piece of pipe the liquid runs
     * through). Returns whether {@code state} holds a gas of the second kind that is not yet dry.
     */
    public static boolean carriedGas(State state,boolean junction) {
        if(!junction||!(state.waterVapor()>0)||!(state.waterLiquid()>0)||state.liquidVolume()>0||!(state.vaporVolume()<state.waterVolume()))return false;
        for(double x:state.vaporView())if(x>0)return true;
        return false;
    }
    /**
     * Whether {@code state}'s hydrocarbons are an omitted trace (decision D41; for vessels since 2026-10-27, seed-236
     * trace). Free water is the node's only standing phase (no steam), and its hydrocarbon is below
     * {@link #PHASE_TRACE_FRACTION} of its fluid moles: by decision D34 no hydrocarbon phase exists, and the trace fold
     * ({@link #foldTracePhases}) has no phase to give it to. A node's step layout and a vessel's closure carry no unknown
     * and no row for it ({@code PhaseLayout.support}). Its split and that split's equilibrium are below what the rows
     * resolve, and an amount unknown of it is differenced at a step that swamps it. The transport reconstruction carries
     * it exactly, the state keeps it in the phases its own flash gives, and a share at or above the trace is a phase
     * appearance.
     *
     * <p>A junction omits a carried dry gas only. Its liquid trace is what its outflow carries to the vessels after it,
     * whose rows resolve what they hold. Omitted from the junction's streams, a 3.6e-8 mol crude trace stayed out of the
     * Newton's view of the 1 m3 tank it fed, which held 3.4e-7 mol, while the reconstruction booked it; the tank's
     * balance rows missed by up to 1.5 and the equation gate refused every step. A vessel omits liquid and gas, but only
     * while their volume is within {@link #OMITTED_TRACE_VOLUME} of the vessel's. Its wall row resolves the volume of its
     * contents, and through a liquid-full wall their pressure (1e-9 of a water tank's volume is 2 Pa). Bundled fuzz seed
     * 236: a water-full tank's 1e-21 mol of crude, laid out as two trace phases from 1e-12 entry traces, refused every
     * step (its Jacobian at 1/32 s and over, its closure's two-phase rows below).
     */
    public static boolean omittedTrace(State state,boolean junction) {
        if(state.waterVapor()>0||!(state.waterLiquid()>0)||junction&&state.liquidVolume()>0)return false;
        double nh=0;for(double x:state.liquidView())nh+=x;for(double x:state.vaporView())nh+=x;
        if(!(nh>0&&nh<PHASE_TRACE_FRACTION*(nh+state.waterLiquid())))return false;
        return junction||state.liquidVolume()+state.vaporVolume()<=OMITTED_TRACE_VOLUME*state.volume();
    }
    /**
     * The share of a vessel's volume within which its hydrocarbon trace is omitted ({@link #omittedTrace}): ten ulps of
     * the volume. A liquid-full water vessel's pressure then moves 2e-6 Pa (2e-11 of a 1e5 Pa hydraulic row), under the
     * Newton's 1e-10 and the reconstruction's 1e-8 gate.
     */
    public static final double OMITTED_TRACE_VOLUME=1e-15;
    /**
     * The hydrocarbon partial pressure of a split the trace rule restated ({@link #foldTracePhases}): a gas holding
     * hydrocarbons and no water vapour, the carried gas of decision D41, is at the node's pressure, as a layout decodes a
     * dry gas; any other split keeps the one it was stated at.
     */
    public static double foldedHydrocarbonPressure(double pressure,double hydrocarbonPressure,double[] vapor,double waterVapor) {
        if(waterVapor>0)return hydrocarbonPressure;
        for(double x:vapor)if(x>0)return pressure;
        return hydrocarbonPressure;
    }
    /**
     * {@code state} restated at its temperature and pressure with its trace phases folded ({@link #foldTracePhases}),
     * its solids kept; the state itself when it holds none. The fluid network's one statement of which phases a node has:
     * every flash ({@link #flashTP}), the reconstruction's restatement of a node and the phase set each Newton pass is
     * laid out for pass through it. Never inside a residual: a fold is a jump of the state function.
     */
    public State folded(State state){return folded(state,0);}
    private State folded(State state,int vanishing) {
        var result=folded(state,null,vanishing);
        return result==state||state.solids().empty()?result:result.withSolidState(state.solids(),state.solidMoments());
    }
    /**
     * {@link #folded(State)} for a node that is a pipe junction ({@code junction}) or a vessel, with the carried gas of
     * decision D41 ({@link #carriedGas}): a liquid-full junction's gas is restated dry at the junction's pressure, its
     * water vapour in the free water. Nothing leaves the node and nothing is booked, as for the trace fold.
     */
    public State folded(State state,boolean junction){return folded(state,junction,0);}
    /**
     * The check of a failed pass's last iterate (owner decisions D28 and D39): {@link #folded(State,boolean)} with the
     * phases vanishing since {@code from} folded too (D38 C, {@link #vanishing}), and nothing else. A failed iterate is not
     * a state, so its check only folds what the iterate empties and proposes nothing: no TP answer and no own split of a
     * non-state (a failed iterate on the box's 1 Pa floor seeded a junction at 51 K with 13 300 m3 of gas, and a third
     * fluid phase was raised at 17 K about a state the network never reached). A phase the pass needs appears at a
     * converged point's check ({@link #phaseCheck}) or at a smaller step.
     */
    public State folded(State state,boolean junction,State from){return folded(state,junction,from==null?0:collapsing(from,state));}
    /** {@link #folded(State,boolean)} with the phases {@code vanishing} names folded as traces too (owner decision D38 C,
     * {@link #foldTracePhases(double[],double[],double[],int)}). */
    private State folded(State state,boolean junction,int vanishing) {
        var result=folded(state,vanishing);
        if(!carriedGas(result,junction))return result;
        SolverDiagnostics.count(SolverDiagnostics.traceFolds);
        var carried=state(result.temperature(),result.pressure(),result.liquid(),result.vapor(),result.waterLiquid()+result.waterVapor(),0,result.pressure(),null,null);
        return result.solids().empty()?carried:carried.withSolidState(result.solids(),result.solidMoments());
    }
    private State folded(State state,TranslatedPengRobinson.Workspace terms,int vanishing) {
        double[] l=state.liquid(),v=state.vapor(),water={state.waterLiquid(),state.waterVapor()};
        if(!foldTracePhases(l,v,water,vanishing))return state;
        SolverDiagnostics.count(SolverDiagnostics.traceFolds);
        return state(state.temperature(),state.pressure(),l,v,water[0],water[1],
                foldedHydrocarbonPressure(state.pressure(),state.hydrocarbonPartialPressure(),v,water[1]),terms,null);
    }

    /** A state's fluid phases as bits: hydrocarbon liquid 1, hydrocarbon vapour 2, free water 4, water vapour 8. */
    public static int phaseBits(State state) {
        return (state.liquidVolume()>0?1:0)|(state.vaporProperties()!=null?2:0)|(state.waterLiquid()>0?4:0)|(state.waterVapor()>0?8:0);
    }
    /**
     * The outer phase check of one node of the fluid network (owner decisions D25 and D26), never inside a residual. A
     * node at fixed amounts, energy and volume has one equilibrium, which its own rows find once its layout holds every
     * phase that equilibrium needs; a TP answer at the node's temperature and pressure tells which phases are missing but
     * not how much of each the node holds. So the check never swaps one populated phase set for another on a TP verdict,
     * and an added phase starts at the node's own split:
     * <ul>
     * <li>a phase leaves only when the solve empties it: the trace fold ({@link #folded}, D34), and a phase held below
     * {@link #VANISHING_SHARE} and at a smaller share than at {@code from} (owner decision D38 C, {@link #vanishing}), in a
     * junction as in a vessel, also at a vessel closure's unsuccessful last iterate (D28 A'). A phase the fold removed as
     * vanishing is not proposed again by this check;</li>
     * <li>a node holding every phase its material can form has none to add, and no TP answer is formed. A stand-in liquid
     * ({@link #condensedShareOnly}, owner decision D31 B) is no liquid of the node's own and never makes it
     * liquid-complete; in a TP answer it is a phase like any other, decided by the node's own split ({@link #lacking});</li>
     * <li>where the TP answer ({@link #flashTP}) holds a phase the node lacks that is not in the solve's record
     * ({@code record}, A85), the node's own split decides it (D26): the network's closure of what the node holds, the rows
     * its passes state. A vessel's {@code vesselSplit} closes its inventory (UV while its wall is taut, PH at the ambient
     * while slack) from the vessel's own state at the crossing (owner decision D40 A, {@link #crossing}). A pipe junction's
     * {@link #junctionSplit} closes its own rows at its pressure and enthalpy from the engine's PH answer there
     * ({@link #equilibriumPH}; the TP answer with the junction's populated phases, {@link #tpSeed}, where that fails). The
     * engine's answers say which phase is missing, never whether the node's rows hold it: under the crystal competition a
     * supersaturated CO2 frosts in them, while the network carries its share dissolved until a vessel's refresh (D23), and
     * LNG holding 4.9 % CO2 at a pump's discharge junction (182 kPa, 120 K) was proposed 4.2 mol/kg of vapour, paid by the
     * frost's heat and its stripped liquid, that its rows refuted at every step (OD4's cold fuzz seed 285). A node whose
     * own split has no start, fails or drives a phase to nothing on its way proposes nothing;</li>
     * <li>the own split decides by where its closure went, converged or not (the D26 amendment): a proposed phase it drove
     * under the trace share, that it is driving to nothing ({@link #vanishing}) or that it leaves vanishing against
     * {@code from} is refused, and the refusal is written to the record so the TP answer does not propose it again in
     * this solve (a boiling liquid in a vessel with no room for a bubble pressurises and stays liquid). A proposed phase
     * that stands seeds the node, and the seed is taken only when it then holds a phase the node lacks that is not in the
     * record and none that was refused.</li>
     * </ul>
     * The rule decides which phases a node is laid out with; how much of each it holds is its rows' answer: a rigid
     * vessel cooled past its bubble point gains the small vapour of its own energy and volume, not the TP split's several
     * vessel volumes.
     *
     * <p>{@code record}, {@code at}: the solve's A85 record of the node's phases ({@link #phaseBits}), read as the phases
     * the solve removed or refused and written with those this check removes and refuses ({@code null}: none); the
     * network solver also writes there a phase this check added that the next pass refutes (owner decision D39 B).
     * {@code junction}: whether the node is a pipe junction; {@code from}: the node's state where the solve that reached
     * {@code state} started, which the vanishing rule reads, or {@code null}; {@code prepared}: the node's prepared
     * temperature state, used only at exactly that temperature; {@code vesselSplit}: a vessel's own split from a start
     * holding the phases to lay it out with, {@code null} where its closure fails (ignored for a junction, whose own split
     * is {@link #junctionSplit}).
     *
     * @return {@code state} itself when it keeps its phases and gains none; otherwise the node's new seed
     */
    public State phaseCheck(State state,boolean junction,State from,int[] record,int at,Prepared prepared,UnaryOperator<State> vesselSplit,Runnable checkpoint) {
        // Owner decision D38 C: the phases vanishing since the solve's start fold as traces, and what that fold removed is not
        // offered back by this check's TP answer.
        int shrinking=from==null?0:vanishing(from,state);
        var kept=folded(state,junction,shrinking);var checked=kept;
        int excluded=(record==null?0:record[at])|shrinking&phaseBits(state)&~phaseBits(kept),refused=0;
        boolean hydrocarbonComplete=ownPhaseBits(kept)==phaseBits(kept)&&(kept.liquidProperties()==null)==(kept.vaporProperties()==null);
        boolean waterComplete=kept.waterLiquid()>0&&kept.waterVapor()>0||kept.waterLiquid()+kept.waterVapor()==0;
        if(!(hydrocarbonComplete&&waterComplete)) {
            if(prepared!=null&&prepared.temperature()!=kept.temperature())prepared=null;
            int n=hydrocarbon.componentCount();double[] totals=new double[n+1];
            for(int i=0;i<n;i++)totals[i]=kept.liquidView()[i]+kept.vaporView()[i];
            totals[n]=kept.waterLiquid()+kept.waterVapor();
            var equilibrium=folded(equilibriumTP(kept.temperature(),kept.pressure(),totals,checkpoint,prepared).withSolidState(kept.solids(),kept.solidMoments()),junction);
            int proposed=lacking(kept,equilibrium)&~excluded;
            if(proposed!=0) {
                State start,own;
                // A junction's energy is its enthalpy (its mixing rows, D46): the solve's, before the fold restated it. Its own
                // split is its rows' closure, started from the engine's PH answer there (the TP answer where that fails).
                if(junction) {
                    var engine=equilibriumPH(kept.temperature(),kept.pressure(),state.enthalpy()-state.solidMoments().enthalpy(state.temperature(),state.pressure()),totals,checkpoint);
                    start=engine!=null?engine.withSolidState(kept.solids(),kept.solidMoments()):tpSeed(kept,equilibrium,prepared);
                    own=junctionSplit(totals,state.enthalpy(),state.mass(),kept.pressure(),start,checkpoint);
                }
                // Owner decision D40 A: a vessel starts from its own state at the crossing, never from the TP answer's amounts.
                else{start=crossing(state,kept,proposed,totals,checkpoint);own=start==null||vesselSplit==null?null:vesselSplit.apply(start);}
                if(own!=null) {
                    SolverDiagnostics.count(SolverDiagnostics.phaseSeeds);
                    var seed=folded(own.withSolidState(kept.solids(),kept.solidMoments()),junction);
                    // The D26 amendment, amended by the owner on 2026-10-19 (t40's trace of bundled seed 678): the own split's
                    // verdict on a proposed phase is where its closure took it (D38 C); a proposed phase the closure holds below
                    // the vanishing share of the node's fluid (belowShare) is nothing, whatever its direction ({@link #nothing}:
                    // the amended fold's statement applied to a phase arriving), so the two direction tests against the
                    // solve's start go. A receiver landing on its bubble point was proposed 5.8e-11 m3 of vapour (6.5e-7 of its
                    // volume) that its closure grew slightly, which no direction test refused, and the pass laid out with it
                    // had no root.
                    refused=proposed&(~lacking(kept,seed)|nothing(own));
                    int added=lacking(kept,seed)&~excluded;
                    if(added!=0&&(added&refused)==0)checked=seed;
                }
                // A node with no start, or whose own split fails or drives a phase to nothing, proposes nothing (D40 A).
                else refused=proposed;
                if(refused!=0)SolverDiagnostics.count(SolverDiagnostics.phaseRefusals);
            }
        }
        if(record!=null)record[at]|=phaseBits(state)&~phaseBits(checked)|refused;
        return checked;
    }
    /**
     * {@link #phaseBits} of the phases a node holds as its own, for {@link #phaseCheck} (owner decision D31 B, for every
     * node): a stand-in liquid ({@link #condensedShareOnly}) is a crystal's condensed share, no liquid of the node's own, so
     * its liquid bit is not set and a node holding one is never liquid-complete.
     */
    private int ownPhaseBits(State state){return condensedShareOnly(state)?phaseBits(state)&~1:phaseBits(state);}
    /**
     * The phases ({@link #phaseBits}) of {@code answer} that {@code node} lacks, for {@link #phaseCheck}: a liquid of its own
     * where the node holds none of its own (a stand-in in its slot does not count, D31 B), and a stand-in where the node holds
     * no liquid at all. A stand-in is a phase like any other (owner decision of 2026-10-16, form 4): it carries the energy of
     * the frost a stream brings until the next refresh (liquid CO2 let down into a tank of its gas freezes in part at once),
     * and the node's own split decides it, keeping it where it stands and refusing it where its closure drives it to
     * nothing (a supersaturated gas whose CO2 has no liquid to stand in).
     */
    private int lacking(State node,State answer){return ownPhaseBits(answer)&~ownPhaseBits(node)|phaseBits(answer)&~phaseBits(node);}
    /**
     * The phases ({@link #phaseBits}) a solve from {@code start} is driving to nothing at {@code end}, its answer or the last
     * iterate it stopped at, in any node: each holds less than {@link #VANISHING_SHARE} of {@code end}'s fluid amount and a
     * smaller share than it held at {@code start}. A phase the solve grows, however small, is not among them. The D26
     * amendment by direction (owner decision of 2026-10-16, form 4) for a vessel's own closure, given by owner decision D38
     * C (2026-10-17) to a junction's own split and to the solve's own point ({@link #phaseCheck}: {@code start} the node
     * where its step or closure started), and by owner decision D39 B (2026-10-18) to the network's pass after a proposal:
     * {@code start} the node's seed holding the phase the converged point's check added, {@code end} the pass's failed
     * iterate, where a proposed phase it names is refuted.
     */
    public static int vanishing(State start,State end) {
        double[] s=phaseAmounts(start),e=phaseAmounts(end),ev=phaseVolumes(end);double ts=sum(s),te=sum(e),tv=sum(ev);int bits=0;
        // The direction (shrinking) is taken by moles; the share by belowShare.
        for(int k=0;k<4;k++)if(belowShare(k,e,te,ev,tv)&&e[k]*ts<s[k]*te)bits|=1<<k;
        return bits;
    }
    /**
     * The phases ({@link #phaseBits}) {@code state} holds below the vanishing share of its fluid ({@link #belowShare}),
     * whatever their direction: nothing at the node's resolution (owner decision of 2026-10-19 on t40's trace of bundled
     * seed 678), the amended fold's statement ({@link #vanishing}) for a phase arriving as for one leaving.
     */
    static int nothing(State state) {
        double[] e=phaseAmounts(state),ev=phaseVolumes(state);double te=sum(e),tv=sum(ev);int bits=0;
        for(int k=0;k<4;k++)if(e[k]>0&&belowShare(k,e,te,ev,tv))bits|=1<<k;
        return bits;
    }
    /**
     * Whether phase {@code k} ({@link #phaseBits} order) of a node with phase amounts {@code e} (total {@code te}) and
     * phase volumes {@code ev} (total {@code tv}) is below {@link #VANISHING_SHARE}: the one share criterion of
     * {@link #vanishing} and {@link #nothing}.
     * <ul>
     * <li>Every phase is below the share by volume. Owner decision of 2026-10-18 on t40's trace (D38 C amended): a gas
     * holds about a hundred times its mole share in volume, so a mole test alone folded a 21 mL bubble of 2.6e-7 of a
     * liquid-full tank's moles, which at the tank's compressibility moved its pressure by 16 %. The converged point's
     * check proposed it back, and the interval's step halved on every jump down to 1e-15 s (bundled seeds 407 and 26).
     * Below 1e-6 of the volume a fold moves a liquid node by 2 kPa at most, under the state cap.</li>
     * <li>A water liquid and either gas must also be below the share by moles. A hydrocarbon liquid is judged on its
     * volume share alone (owner decision 2026-10-20, amending D38 C). This is the mirror of the bubble: a liquid in a gas
     * vessel holds about twenty times its volume share in moles. A methane tank at its dew point (the D34 gate,
     * minimized bundled seed 322) condensed 0.077 mL a step, 1.5e-6 of its moles but 7.7e-8 of its volume. The mole
     * test kept that film as a phase, and the pass laid out with it had no root at any step above 3.7e-4 s.</li>
     * </ul>
     */
    private static boolean belowShare(int k,double[] e,double te,double[] ev,double tv) {
        return ev[k]<VANISHING_SHARE*tv&&(k==0||e[k]<VANISHING_SHARE*te);
    }
    /** A state's fluid volume by phase, in {@link #phaseBits} order; a gas phase's volume shared between the hydrocarbon
     * vapour and the steam by moles. */
    private static double[] phaseVolumes(State state) {
        double hv=sum(state.vaporView()),wv=state.waterVapor(),gas=hv+wv,vv=state.vaporVolume();
        return new double[]{state.liquidVolume(),gas>0?vv*hv/gas:0,state.waterVolume(),gas>0?vv*wv/gas:0};
    }
    /**
     * The phases a solve from {@code start} that stopped short of a root is driving to nothing at its last iterate
     * {@code end} (t38 form C, 2026-10-18): {@link #vanishing}, and as well every phase shrinking to less than
     * {@link #VANISHING_COLLAPSE} of its start share, whatever share it stands at. A failed iterate is no state, and its
     * trajectory is the evidence: a phase it has collapsed a thousandfold is leaving. A converged point keeps the share
     * rule alone ({@link #phaseCheck}): a tank whose water a port drained to its condensate within the step holds a
     * phase a thousand times smaller than at its start that is not vanishing but drained.
     */
    public static int collapsing(State start,State end) {
        double[] s=phaseAmounts(start),e=phaseAmounts(end);double ts=sum(s),te=sum(e);int bits=vanishing(start,end);
        for(int k=0;k<4;k++)if(e[k]*ts<VANISHING_COLLAPSE*s[k]*te)bits|=1<<k;
        return bits;
    }
    /** A state's fluid amount by phase, in {@link #phaseBits} order: hydrocarbon liquid, vapour, free water, water vapour. */
    private static double[] phaseAmounts(State state){return new double[]{sum(state.liquidView()),sum(state.vaporView()),state.waterLiquid(),state.waterVapor()};}
    /**
     * The TP-based state of {@link #phaseCheck} (decision D26): the TP answer {@code equilibrium} at {@code kept}'s
     * temperature and pressure, for {@code kept}'s totals, with the populated phases of {@code kept} it lacks, so that no
     * TP verdict takes a populated phase away (D25). Where the answer holds every phase of {@code kept} it is the answer;
     * otherwise each component is shared between the answer's phases and those kept phases in proportion to what each
     * holds of it (pure water the answer states all steam: half its water liquid, half steam), and a gas holding
     * hydrocarbons and steam is at Dalton's hydrocarbon partial pressure, a dry gas at the node's pressure. The answer
     * itself where that gas would pass the steam approximation's qualified partial pressure. A junction's seed where its
     * PH answer fails; never a vessel's (owner decision D40 A, {@link #crossing}).
     */
    private State tpSeed(State kept,State equilibrium,Prepared prepared) {
        int answer=phaseBits(equilibrium),lacking=phaseBits(kept)&~answer;
        if(lacking==0)return equilibrium;
        double[] kl=kept.liquidView(),kv=kept.vaporView(),el=equilibrium.liquidView(),ev=equilibrium.vaporView();
        double[] l=new double[kl.length],v=new double[kl.length];double nv=0;
        for(int c=0;c<l.length;c++) {
            double a=(answer&1)!=0?el[c]:(lacking&1)!=0?kl[c]:0,b=(answer&2)!=0?ev[c]:(lacking&2)!=0?kv[c]:0,total=kl[c]+kv[c];
            l[c]=b>0?total*(a/(a+b)):total;v[c]=total-l[c];nv+=v[c];
        }
        double a=(answer&4)!=0?equilibrium.waterLiquid():(lacking&4)!=0?kept.waterLiquid():0;
        double b=(answer&8)!=0?equilibrium.waterVapor():(lacking&8)!=0?kept.waterVapor():0,water=kept.waterLiquid()+kept.waterVapor();
        double wl=b>0?water*(a/(a+b)):water,wv=water-wl,p=kept.pressure();
        try{return adoptingState(kept.temperature(),p,l,v,wl,wv,nv>0&&wv>0?p*(nv/(nv+wv)):p,prepared).withSolidState(kept.solids(),kept.solidMoments());}
        catch(IllegalArgumentException steamLimit){return equilibrium;}
    }
    /**
     * A pipe junction's own split (owner decision D26, {@link #phaseCheck}; the arriving-phase seed of
     * {@code PassiveStepSolver.arrivingPhases}): its own rows ({@link PhaseLayout#junctionResidual}), isolated at the
     * pressure {@code p} it holds, with the amounts {@code amounts} (the conserved basis, water last), the enthalpy
     * {@code enthalpy} (J, the network's datum) and the mass {@code mass} its rows weigh them by, closed from {@code start}
     * in {@code start}'s phase layout. A vessel's own split is its closure the same way ({@code InventoryEquilibrium}), so
     * a phase is proposed only where the rows the next pass states hold it: the engine's answer is the start, not the
     * verdict. The closed point is restated at exactly {@code p} and {@code amounts}. {@code null} where the closure fails
     * or drives a phase of {@code start} to nothing on its way ({@link #vanishing}, owner decision D38 C, read at every
     * point it evaluates): a phase the rows empty is crawled to by a Newton that cannot converge with it present (a
     * junction's trace vapour cost cold fuzz seed 118 174 710 closure iterations before this exit, 12 147 with it).
     */
    public State junctionSplit(double[] amounts,double enthalpy,double mass,double p,State start,Runnable checkpoint) {
        try {
            var layout=new PhaseLayout(this,start,null,null,PhaseLayout.support(this,start,null,null,true));
            int n=hydrocarbon.componentCount();double[] fractions=new double[n+1];
            for(int i=0;i<=n;i++)fractions[i]=amounts[i]*(i==n?waterMolecularWeight:hydrocarbon.molecularWeight(i))/mass;
            var solved=SparseNewton.solve(layout.junctionClosure(fractions,enthalpy/mass,p,start),layout.encode(start),
                    new SparseNewton.Settings(SparseNewton.ITERATIONS,1e-10,1e-6,24),checkpoint);
            var closed=layout.decode(solved.variables(),0);
            // Exactly what the junction holds: each component split as the closure split it, at exactly the retained pressure.
            double[] l=closed.liquid(),v=closed.vapor();
            for(int i=0;i<n;i++){double total=l[i]+v[i];l[i]=total>0?amounts[i]*(l[i]/total):amounts[i];v[i]=amounts[i]-l[i];}
            double water=closed.waterLiquid()+closed.waterVapor(),wl=water>0?amounts[n]*(closed.waterLiquid()/water):amounts[n];
            return state(closed.temperature(),p,l,v,wl,amounts[n]-wl,closed.hydrocarbonPartialPressure()*(p/closed.pressure())).withSolidState(start.solids(),start.solidMoments());
        }
        catch(PhaseLayout.Vanished|SparseNewton.Nonconvergence|IllegalArgumentException failed){return null;}
    }
    /**
     * A vessel's own state at a crossing (owner decision D40 A), the start of its own split in {@link #phaseCheck}: the
     * engine's equilibrium of the fluid it holds under its wall's law, the problem its own closure solves
     * ({@code InventoryEquilibrium}), as a network state ({@link #adopt}) folded as a vessel: a slack bag at its fluid
     * enthalpy and the ambient pressure ({@link #equilibriumPH}), a taut vessel at its fluid's internal energy and volume
     * ({@link #equilibriumUV}), warm-started at its temperature and pressure, both under the competition of the vessel's
     * TP request ({@link #crystalsCompete}); a crystal the engine forms is carried as its stand-in (D23). The fluid alone
     * is no answer there: LNG with 0.7 % of CO2 cooled past its dew point in a bag of nitrogen is three fluid phases with
     * a CO2-rich liquid, which the network does not carry. The TP answer says only which phase is missing: its amounts
     * ignore the vessel's volume and are never the seed (a liquid-full slush tank drawn below its bubble point was offered
     * 13 to 29 m3 of vapour for 0.78 m3 of fluid space, and its closure from there failed). {@code null} where the engine
     * does not converge, answers a phase set the network cannot carry, or answers none of the phases {@code proposed}
     * adds (no crossing), so the vessel proposes nothing.
     */
    private State crossing(State state,State kept,int proposed,double[] totals,Runnable checkpoint) {
        double t=kept.temperature(),p=kept.pressure();var solids=state.solidMoments();
        // The slack branch of the wall states the pressure the ambient's (PhaseLayout.wallRow); a taut wall carries more.
        var own=p<=PhaseLayout.AMBIENT_PRESSURE*(1+SLACK_PRESSURE)?equilibriumPH(t,p,state.enthalpy()-solids.enthalpy(state.temperature(),state.pressure()),totals,checkpoint)
                :equilibriumUV(t,p,state.internalEnergy()-solids.internalEnergy(state.temperature()),state.volume()-solids.volume(),totals,checkpoint);
        if(own==null)return null;
        var seed=folded(own.withSolidState(kept.solids(),kept.solidMoments()),false);
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
    /** {@link #flashTP} that uses {@code prepared}'s Peng-Robinson workspace when it is for exactly {@code t}, instead of
     * preparing a new one (the arithmetic is the same). */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint,Prepared prepared) {
        TranslatedPengRobinson.Workspace given=prepared!=null&&prepared.temperature()==t?prepared.pengRobinson():null;
        return folded(equilibriumTP(t,p,overall,checkpoint,prepared),given,0);
    }
    /**
     * The equilibrium split of {@link #flashTP} before its trace phases are folded; not a state the network lays out or
     * commits ({@code flashTP} is {@code folded(equilibriumTP(...))}). The step solver's phase correction reads both to
     * know which phase the trace rule, rather than the equilibrium, removed (D34, A85). The phases of {@code overall}
     * (the conserved basis, water last) at {@code (t, p)} are decided by the equilibrium engine
     * ({@link NetworkPhaseEngine}: stability test, split with its second-order finish, critical band, phase
     * identification, the separate free-water rule); this adapter states that answer as a network {@link State} with the
     * network's own direct phase evaluations ({@link #state}). Slots: a split's lighter phase is the vapour slot and its
     * denser phase the liquid slot; a one-root phase takes the slot its label or its own phase identification parameter
     * gives; with water the engine's free-water record gives the water split and the hydrocarbon vapour's partial
     * pressure; a pure component exactly at its coexistence pressure is a single liquid here. Refusals: a state outside
     * the numerical box is a plain {@link IllegalArgumentException} (no data range bounds a state); an answer the engine
     * could not converge inside the critical band is the typed {@link CriticalBandHold}; a liquid-liquid split and a
     * split with a product proved unstable are {@link UnsupportedPhases} (a node carries one hydrocarbon liquid and one
     * vapour); any other failure is a plain {@link IllegalArgumentException}. Below its melting line the engine states
     * the free water's condensed part as ice; a TP answer never moves material into a crystal (owner decision D23), so the
     * network carries it as free liquid water, the stand-in ({@link #state}), until a vessel's refresh freezes it; on a
     * package that admits crystals a request carrying a crystal's species at or below the crystals' ceiling is made under
     * the crystal competition ({@link #crystalsCompete}), and where a crystal forms the answer's vapour is the vapour and
     * the liquid slot takes the rest with the crystal's share condensed in it ({@link #adopt}). A crystal beside a phase
     * set the engine does not implement is the typed {@link UnsupportedPhases.Kind#CRYSTAL_PHASES} hold.
     * {@code checkpoint} runs once before the engine and once per equation-of-state evaluation the engine reports.
     */
    public State equilibriumTP(double t,double p,double[] overall,Runnable checkpoint,Prepared prepared) {
        TranslatedPengRobinson.Workspace given=prepared!=null&&prepared.temperature()==t?prepared.pengRobinson():null;
        SolverDiagnostics.count(SolverDiagnostics.flashCalls);
        int n=hydrocarbon.componentCount();if(overall.length!=n+1)throw new IllegalArgumentException("Fluid basis mismatch");
        double[] hc=Arrays.copyOf(overall,n);double nh=sum(hc),w=overall[n];
        if(!Double.isFinite(w)||w<0||nh+w<=0)throw new IllegalArgumentException("Empty fluid initialization");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid initialization is not finite");
        // Refused before any equilibrium is formed: outside the numerical box no model is trusted at all.
        NumericalBox.require(t,p);
        if(nh==0) {
            // Below the melting line the condensed water is ice (its stand-in here), whose vapour pressure is the ice's.
            boolean condensed=p>=freeWaterVaporPressure(t,p);
            return state(t,p,hc,hc,condensed?w:0,condensed?0:w,0);
        }
        checkpoint.run();
        var result=phaseEngine().tp(t,p,overall,crystalsCompete(t,overall));
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        return adopt(t,p,overall,result,given);
    }
    /**
     * A pipe junction's own split (owner decision D26, {@link #phaseCheck}), the phases of an empty bag's
     * ({@code PassiveStepSolver.emptyBagSplit}, D54) and a slack vessel's crossing ({@link #crossing}): the engine's PH
     * equilibrium of {@code overall} (the conserved basis, water last) at the node's pressure {@code p} and its fluid
     * enthalpy {@code h} (J, the network's datum), under the competition of the node's TP request at {@code t}
     * ({@link #crystalsCompete}), as a network state ({@link #adopt}, unfolded); {@code null} where the engine does not
     * converge or answers a phase set the network cannot carry. A spine package's engine states energies on the spine's
     * formation datum, the network on its sensible one (D10), so the target moves by the formation enthalpies.
     */
    public State equilibriumPH(double t,double p,double h,double[] overall,Runnable checkpoint){return equilibriumPH(t,p,h,overall,crystalsCompete(t,overall),checkpoint);}
    /**
     * {@link #equilibriumPH} with the crystal competition stated by the caller: fluid-only ({@code crystals} false) for a
     * starting guess that never books a crystal, the balanced start's density tables ({@code PassiveStepSolver.BalanceNet}),
     * whose junction's final mixture keeps the competition.
     */
    public State equilibriumPH(double t,double p,double h,double[] overall,boolean crystals,Runnable checkpoint) {
        var spine=hydrocarbon.spine();double target=h;
        if(spine.isPresent()) {
            var formation=spine.get().formationReference();
            target=EnergyReference.sensible(formation.components()).rebase(h,Arrays.copyOf(overall,hydrocarbon.componentCount()),formation);
        }
        checkpoint.run();
        var result=phaseEngine().ph(p,target,overall,crystals);
        for(int evaluation=result.diagnostics().kernelEvaluations();evaluation>0;evaluation--)checkpoint.run();
        if(!result.converged()||result.phases().isEmpty())return null;
        try{return adopt(result.phases().getFirst().temperature(),p,overall,result,null);}
        catch(IllegalArgumentException refused){return null;}
    }
    /**
     * A taut vessel's own state at a crossing ({@link #crossing}, owner decision D40 A), the UV twin of
     * {@link #equilibriumPH}: the engine's equilibrium of {@code overall} (the conserved basis, water last) at its fluid's
     * internal energy {@code u} (J, the network's datum) and volume {@code v} (m3), under the competition of the vessel's
     * TP request at {@code t} ({@link #crystalsCompete}), warm-started at {@code t} and {@code p}, as a network state
     * ({@link #adopt}, unfolded); {@code null} where the engine does not converge or answers a phase set the network cannot
     * carry. {@code checkpoint} runs as for {@link #equilibriumTP}. Never inside a residual.
     */
    private State equilibriumUV(double t,double p,double u,double v,double[] overall,Runnable checkpoint) {
        checkpoint.run();
        var result=phaseEngine().crystalUv(engineEnergy(u,overall),v,overall,crystalsCompete(t,overall),t,p);
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
     * ceiling (the competition's existence limit, not a data range: above it no crystal is stable). Otherwise fluid-only.
     * Water takes no part: its ice is its own rule's (WP4), and gas hydrates are not modelled (decision 6 A), so ice and a
     * competing crystal coexist.
     */
    public boolean crystalsCompete(double t,double[] overall) {
        if(crystalIds.isEmpty()||!(t<=crystalCeiling))return false;
        for(int i=0;i<crystalSpecies.length;i++)if(crystalSpecies[i]&&overall[i]>0)return true;
        return false;
    }

    /**
     * Owner decision D29 A: whether {@code state}'s hydrocarbon liquid is the stand-in's condensed share alone
     * ({@link #adopt}): a liquid holding crystal species only, beside a vapour, as a frost answer's crystal is carried. It
     * is no phase of the answer, so the trace support reads the answer's own fluid phase, the vapour, and the stand-in
     * admits every component the node holds ({@code PhaseLayout.support}); in the outer phase check it is no liquid of
     * the node's own (owner decision D31 B) and never makes the node liquid-complete. Never on a package without crystals.
     */
    public boolean condensedShareOnly(State state) {
        if(crystalIds.isEmpty()||!(state.liquidVolume()>0)||state.vaporProperties()==null)return false;
        double[] l=state.liquidView();
        for(int i=0;i<l.length;i++)if(l[i]>0&&!crystalSpecies[i])return false;
        return true;
    }

    /** The engine's answer as a network state (see {@link #equilibriumTP}), or its typed refusal; {@code terms}, when
     * given, is the prepared Peng-Robinson workspace of exactly {@code t}. */
    State adopt(double t,double p,double[] overall,EquilibriumResult result,TranslatedPengRobinson.Workspace terms) {
        switch(result.status()) {
            case CONVERGED->{}
            case NOT_CONVERGED->{
                if(result.diagnostics().criticalBand())throw new CriticalBandHold(domain.packageId(),t,p,result.detail());
                // A converged split with a product proved unstable: a third fluid phase is indicated, which a node cannot
                // carry; a typed hold like the critical band's, never adopted with fewer phases. A product whose stability
                // the re-check left unresolved is the same hold short of the proof: the split is no answer, and a third
                // phase is not excluded (the design review's E9: a junction's arrival flash held untyped, naming no node).
                boolean unstable=result.detail().startsWith(EquilibriumResult.UNSTABLE_PRODUCT);
                if(unstable||result.detail().startsWith(EquilibriumResult.UNRESOLVED_PRODUCT))throw new UnsupportedPhases(domain.packageId(),t,p,
                        UnsupportedPhases.Kind.THREE_PHASES,"a third fluid phase is "+(unstable?"indicated":"not excluded")+" ("+result.detail()
                        +"); the fluid network carries one hydrocarbon liquid and one hydrocarbon vapour");
                throw new IllegalArgumentException("Fluid TP equilibrium did not converge: "+result.detail());
            }
            case UNSUPPORTED->{
                // A crystal beside a phase set the engine does not implement (two liquids, three fluid phases, two
                // crystals): held typed, never answered with fewer phases.
                if(result.detail().startsWith(EquilibriumResult.CRYSTAL_PHASES))throw new UnsupportedPhases(domain.packageId(),t,p,
                        UnsupportedPhases.Kind.CRYSTAL_PHASES,"a crystal beside a phase set the engine does not implement ("+result.detail()+")");
                throw new IllegalStateException("The network's equilibrium engine refused a request it must answer: "+result.detail());
            }
            default->throw new IllegalStateException("The network's equilibrium engine refused a request it must answer: "+result.detail());
        }
        int n=hydrocarbon.componentCount();double[] liquid=new double[n],vapor=new double[n];
        double waterLiquid=0,waterVapor=0,hydrocarbonPressure=p;
        if(terms==null)terms=hydrocarbon.prepare(t);
        switch(result.classification()) {
            // Two liquid-like hydrocarbon phases: the network carries one hydrocarbon liquid, so the lighter liquid must
            // not take the vapour slot; a typed hold, never adopted with fewer phases.
            case LIQUID_LIQUID->throw new UnsupportedPhases(domain.packageId(),t,p,UnsupportedPhases.Kind.LIQUID_LIQUID,
                    "a liquid-liquid split (two liquid-like hydrocarbon phases); the fluid network carries one hydrocarbon liquid");
            // A crystal in the answer. A TP phase decision never moves material into a crystal (owner decision D23: only a
            // vessel's refresh does), so the mobile phases carry the crystal's share until then as condensed matter: the
            // vapour is the answer's vapour and the liquid slot takes the rest, a supersaturated liquid, per component by
            // difference and never negative, so the amounts are conserved exactly. In the vapour slot the crystal's mass
            // would take a gas's volume and energy (a CO2 let-down below the triple point, stated all vapour, ran to CO2's
            // 90 K floor).
            case VAPOR_SOLID,LIQUID_SOLID,VAPOR_LIQUID_SOLID,SOLID_PRESENT->{
                var classification=result.classification();
                if(classification==EquilibriumResult.Classification.VAPOR_SOLID||classification==EquilibriumResult.Classification.VAPOR_LIQUID_SOLID)
                    for(var phase:result.phases())if(phase.kind()==PhaseKind.VAPOR||phase.kind()==PhaseKind.FLUID)System.arraycopy(phase.amountsView(),0,vapor,0,n);
                for(int i=0;i<n;i++) {
                    liquid[i]=Math.max(overall[i]-vapor[i],0.0);
                    vapor[i]=overall[i]-liquid[i];
                }
            }
            // A pure component at its own coexistence pressure, the split undetermined by T and P: a single liquid, or
            // the vapour of a pure sublimation coexistence.
            case PURE_COEXISTENCE_UNDERDETERMINED->{
                boolean sublimation=false;for(var state:result.coexisting())sublimation|=state.kind()==PhaseKind.SOLID;
                System.arraycopy(overall,0,sublimation?vapor:liquid,0,n);
            }
            // Free water beside the hydrocarbons. Its ice (WP4), which a TP answer never freezes (D23), is carried as its
            // stand-in, free liquid water, until a vessel's refresh. Beside a competing crystal the hydrocarbons are stated as
            // a dry crystal answer is: the vapour is the answer's and the liquid slot takes the rest.
            case FREE_WATER->{
                var split=result.freeWater();
                waterLiquid=split.condensed();waterVapor=split.waterVapor();hydrocarbonPressure=split.hydrocarbonPressure();
                boolean crystal=split.hydrocarbon().holdsCrystal();
                for(var phase:result.phases()) {
                    if(phase.kind()==PhaseKind.VAPOR)System.arraycopy(phase.amountsView(),0,vapor,0,n);
                    else if(phase.kind()==PhaseKind.LIQUID&&!crystal)System.arraycopy(phase.amountsView(),0,liquid,0,n);
                }
                if(crystal)for(int i=0;i<n;i++){liquid[i]=Math.max(overall[i]-vapor[i],0.0);vapor[i]=overall[i]-liquid[i];}
            }
            default->{
                for(var phase:result.phases()) {
                    boolean liquidSlot=switch(phase.kind()) {
                        case LIQUID->true;
                        case VAPOR->false;
                        default->{
                            // A pure supercritical fluid or a merged near-critical split: one root, whose own phase
                            // identification parameter decides the slot (both slots evaluate that same root).
                            double[] amounts=Arrays.copyOf(phase.amountsView(),n);
                            yield HydrocarbonModel.liquidLike(hydrocarbon.translated().evaluateValues(t,p,amounts,phase.state().root(),terms));
                        }
                    };
                    System.arraycopy(phase.amountsView(),0,liquidSlot?liquid:vapor,0,n);
                }
            }
        }
        return state(t,p,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure,terms,null);
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
     * An equilibrium answer the network cannot carry, typed with the equilibrium service's reason: a node has one
     * hydrocarbon liquid slot and one vapour slot (D8: a phase set outside the qualified ones is refused, never answered
     * with fewer phases). The kinds: a liquid-liquid split, a third fluid phase (indicated by a converged split whose
     * product the stability re-check proved unstable, or not excluded where it left one unresolved), a crystal beside a
     * phase set the engine does not implement, and a crystal step the engine does not answer. Free water below its melting
     * line is no hold: it is ice (WP4).
     */
    public static final class UnsupportedPhases extends ThermoHold {
        /** What the network cannot carry. */
        public enum Kind {
            LIQUID_LIQUID,
            /** A converged split whose product the stability re-check proved unstable ({@link EquilibriumResult#UNSTABLE_PRODUCT})
             * or left unresolved ({@link EquilibriumResult#UNRESOLVED_PRODUCT}): a third fluid phase indicated or not
             * excluded. */
            THREE_PHASES,
            /** The engine's typed {@link EquilibriumResult#CRYSTAL_PHASES} hold under the crystal competition (two liquids
             * beside the crystal, three fluid phases beside it, two crystals); the engine's reason is
             * {@code NOT_IMPLEMENTED}. */
            CRYSTAL_PHASES,
            /** The engine's typed {@link EquilibriumResult#CRYSTAL_STEP} hold of a vessel refresh's crystal answer (D21 option
             * C): a near-pure feed's crystal step the pure species' step does not answer; another attempt at the same
             * material meets the same step, so the island holds at once. Also an answer that froze a vessel's other species
             * with its crystals ({@code VesselCrystals}: their traces leave a fluid, D21), which the refresh names the vessel
             * with. */
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
                case LIQUID_LIQUID->"two hydrocarbon liquids, which the network does not carry";
                case THREE_PHASES->"a third fluid phase, which the network does not carry";
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
     * Everything at one exact temperature that neither composition nor pressure can change: the PR
     * mixing terms, the temperature terms of IF97 Region 1 (in its workspace, which also evaluates the
     * pressure terms of each trial without allocating), the water saturation pressure and the ideal-gas
     * water-vapor enthalpy.
     *
     * <p>A colored Jacobian perturbs a node's composition and pressure columns dozens of times at an
     * unchanged temperature, and every residual at that temperature repeats them, so this is where
     * those evaluations meet. Each member is computed when it is first asked for: a state evaluates
     * only the properties of the phases it holds.</p>
     */
    public static final class Prepared {
        private final FluidThermodynamics owner;
        private final double temperature;
        private TranslatedPengRobinson.Workspace terms;
        private WaterRegion1.Workspace water;
        private MixtureViscosity.Prepared transport;
        private double saturationPressure=Double.NaN,vaporEnthalpy=Double.NaN;
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
        /** This temperature's Region 1 workspace: the temperature terms once, the pressure terms per trial. */
        WaterRegion1.Workspace water() {
            return water==null?water=new WaterRegion1.Workspace():water;
        }
        double saturationPressure() {
            return Double.isNaN(saturationPressure)?saturationPressure=owner.saturationPressure(temperature):saturationPressure;
        }
        double vaporEnthalpy() {
            return Double.isNaN(vaporEnthalpy)?vaporEnthalpy=owner.vaporWaterEnthalpy(temperature):vaporEnthalpy;
        }
        /** The pure-component transport terms of this temperature, for the decode's Transport row. */
        public MixtureViscosity.Prepared viscosities() {
            return transport==null?transport=owner.viscosity.prepare(temperature):transport;
        }
    }
    public record WaterLiquid(double molarVolume,double molarEnthalpy) {}
    /**
     * The phase amounts belong to the record from construction on. {@link #state} is the only
     * place one is built: it copies a caller's arrays, and {@link #adoptingState} is the entry for
     * a caller that allocated them for this call alone, so a state still never shares an array with
     * anything a caller can reach. {@code waterCompressibility} is the free liquid water's own isothermal
     * compressibility at the state (Region 1's {@code -(dv/dP)/v}, 1/Pa), zero without free water.
     */
    public record State(double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,
                        double hydrocarbonPartialPressure,double waterPartialPressure,double volume,double enthalpy,double internalEnergy,
                        double mass,double liquidVolume,double waterVolume,double vaporVolume,HydrocarbonModel.Phase liquidProperties,
                        HydrocarbonModel.Phase vaporProperties, SolidInventory solids, SolidInventory.Moments solidMoments,
                        double waterCompressibility) {
        public State { Objects.requireNonNull(solids); Objects.requireNonNull(solidMoments); }
        public State withSolids(SolidInventory inventory) { return withSolidState(inventory,inventory.moments()); }
        /** Moment-only trial states never become owned inventories before population reconstruction. */
        public State withSolidState(SolidInventory inventory,SolidInventory.Moments moments) {
            return new State(temperature,pressure,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPartialPressure,waterPartialPressure,
                    volume-solidMoments.volume()+moments.volume(),
                    enthalpy-solidMoments.enthalpy(temperature,pressure)+moments.enthalpy(temperature,pressure),
                    internalEnergy-solidMoments.internalEnergy(temperature)+moments.internalEnergy(temperature),
                    mass-solidMoments.mass()+moments.mass(),liquidVolume,waterVolume,vaporVolume,liquidProperties,vaporProperties,inventory,moments,
                    waterCompressibility);
        }
        /** Conserved component basis length, with water in the final position. */
        public int componentCount(){return liquid.length+1;}
        @Override public double[] liquid(){return liquid.clone();}
        @Override public double[] vapor(){return vapor.clone();}
        /** The amounts themselves, for the solver packages. The caller must not mutate them. */
        public double[] liquidView(){return liquid;}
        public double[] vaporView(){return vapor;}
    }
}
