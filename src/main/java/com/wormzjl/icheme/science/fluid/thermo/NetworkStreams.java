package com.wormzjl.icheme.science.fluid.thermo;

import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.AQUEOUS;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.GAS;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.OIL;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.R;
import static com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.total;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.PhaseStream;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.Prepared;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;

/**
 * The stream functions of a network model (finding F8 of batch 2026-11-13-codebase-review): a node's hydraulic velocity
 * limits and the streams a vessel port draws (decision D11), with their masses, volumes, densities, enthalpies and
 * viscosities, read off a state with no flash. One per {@link FluidThermodynamics}, built in its constructor; the
 * model's public methods of the same names forward here.
 */
final class NetworkStreams {
    private final FluidThermodynamics model;
    NetworkStreams(FluidThermodynamics model){this.model=model;}
    /** A hydraulic rate bound only; temperature and phase allocation still come from the EOS/energy equations. */
    public double velocityLimit(State state){return Math.min(model.maximumVelocity,isothermalAcousticBound(state));}
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
        if(positiveNormal(compliance))return acoustic(state.volume(),state.mass(),compliance);
        // If the sum itself rounded out of range, divide each phase volume before multiplying by its compressibility.
        double compressibility=0;
        for(int slot=0;slot<3;slot++) {
            var phase=state.phase(slot);
            if(phase!=null&&state.slotVolume(slot)>0)compressibility+=(state.slotVolume(slot)/state.volume())*phase.isothermalCompressibility();
        }
        return acousticFromCompressibility(state.volume(),state.mass(),compressibility);
    }
    /**
     * The gas's ideal-gas heat capacity ratio {@code k = c_p/(c_p − R)} at the state's temperature, for a compressor's
     * polytropic exponent (batch 2026-11-03-rotating-machinery, plan section 3.2): the vapour's components on the
     * package's own ideal-gas data (water's from its cubic record on a list with water). A state with no gas that is one
     * dense fluid ({@link State#dense()}) takes the same formula on that fluid's amounts (S2b of batch
     * 2026-11-15-supercritical-fluids); 1.4 for any other state that holds no gas.
     */
    public double heatCapacityRatio(State state) {
        double t=state.temperature(),moles=0,cp=0;
        double[] vapor=state.vaporAmountsView();int n=model.hydrocarbon.componentCount();
        if(!(state.vaporVolume()>0)&&state.dense())vapor=state.amountsView(state.oneFluidSlot());
        double total=0;for(double x:vapor)total+=x;
        if(!(total>0))return 1.4;
        var terms=model.hydrocarbon.prepare(t);
        for(int i=0;i<vapor.length;i++) {
            if(!(vapor[i]>0))continue;
            // Water on a list without water cannot be in a phase (HydrocarbonModel.kernelAmounts).
            cp+=vapor[i]*terms.idealGasHeatCapacity(i<n||model.hydrocarbon.wet()?i:0);moles+=vapor[i];
        }
        cp/=moles;
        return cp/(cp-R);
    }
    /** The least isentropic exponent {@link #isentropicExponent} gives, and its value for a state it cannot read. */
    static final double LEAST_EXPONENT=1.05,NO_EXPONENT=1.4;
    /**
     * The isentropic exponent {@code n = K_s/P} of the phase a plunger pump draws from {@code state} (batch
     * 2026-11-16-supercritical-followups, P1): {@code K_s = −v/(∂v/∂P)_s}, {@code (∂v/∂P)_s = (∂v/∂P)_T + T (∂v/∂T)_P²/c_p}.
     * The phase: the liquid slot's where the state holds liquid, the aqueous slot's where it holds only that, else the slot
     * of its one fluid ({@link State#oneFluidSlot()}), else the vapour's. {@code v} and {@code (∂v/∂P)_T} are the state's
     * own phase's; {@code (∂v/∂T)_P} and the real {@code c_p = (∂h/∂T)_P} the central differences of that phase evaluated
     * again at {@code T (1 ± 10⁻⁴)} on its root and amounts (the phase record carries neither). An ideal gas gives γ;
     * water at 300 K and 1 MPa about 2,200. Where either evaluation is refused or lands on another root (another
     * vapour-like flag, or a volume a twentieth away), or {@code c_p} is not positive or {@code (∂v/∂P)_s} not negative, the
     * isothermal exponent {@code 1/(κ_T P)} of the phase's own compressibility stands in: at most the isentropic one, so the
     * clearance loss errs high and a liquid never reads as a gas. {@link #NO_EXPONENT} only where that is not finite and
     * positive, or the state holds no fluid. At least {@link #LEAST_EXPONENT}.
     */
    public double isentropicExponent(State state) {
        int slot=state.slotVolume(State.LIQUID)>0?State.LIQUID:state.slotVolume(State.AQUEOUS)>0?State.AQUEOUS
                :state.oneFluidSlot()>=0?state.oneFluidSlot():state.vaporVolume()>0?State.VAPOR:-1;
        var phase=slot<0?null:state.phase(slot);
        if(phase==null)return NO_EXPONENT;
        double t=state.temperature(),p=state.pressure(),h=1e-4*t,v=phase.molarVolume(),dvdp=phase.volumePressureDerivative();
        double n=isentropic(phase,t,p,h,state.amountsView(slot),slot==State.VAPOR?com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.VAPOR
                :com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID,slot!=State.VAPOR&&state.vaporVolume()>0);
        if(!(n>0&&Double.isFinite(n)))n=-v/(dvdp*p);
        return n>0&&Double.isFinite(n)?Math.max(LEAST_EXPONENT,n):NO_EXPONENT;
    }
    /** {@link #isentropicExponent} by its differences, NaN where they cannot be read. */
    private double isentropic(HydrocarbonModel.Phase phase,double t,double p,double h,double[] amounts,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root root,boolean beside) {
        HydrocarbonModel.Phase up,down;
        try {
            up=model.hydrocarbon.phase(t+h,p,amounts,root,model.hydrocarbon.prepare(t+h),beside);
            down=model.hydrocarbon.phase(t-h,p,amounts,root,model.hydrocarbon.prepare(t-h),beside);
        } catch(IllegalArgumentException lost) {
            return Double.NaN;
        }
        double v=phase.molarVolume(),dvdp=phase.volumePressureDerivative();
        for(var other:new HydrocarbonModel.Phase[]{up,down})if(other.vaporLike()!=phase.vaporLike()||!(Math.abs(other.molarVolume()-v)<.05*v))return Double.NaN;
        double dvdt=(up.molarVolume()-down.molarVolume())/(2*h),cp=(up.molarEnthalpy()-down.molarEnthalpy())/(2*h);
        double isentropicSlope=dvdp+t*dvdt*dvdt/cp;
        if(!(cp>0)||!Double.isFinite(cp)||!(isentropicSlope<0))return Double.NaN;
        return -v/(isentropicSlope*p);
    }
    /**
     * The reduced densities {@code v_c / v} between which one fluid goes from gas-like to liquid-like (S2b and S4 of batch
     * 2026-11-15-supercritical-fluids): gas-like at or below {@link #GAS_LIKE_UNTIL}, liquid-like from
     * {@link #LIQUID_LIKE_FROM}, a {@link MixtureViscosity#smoothstep} between. An ordinary liquid ({@code v/b} 1.1 to 1.5, so
     * 2.6 to 3.6) and an ordinary gas (below 0.5 up to {@code Tr} 0.97 at saturation) are wholly one or the other. The
     * heat-transfer film reads it on every one-fluid state ({@code HeatCurve}); a compressor's condensed share reads it on
     * a dense one ({@code InletPhase}). A pump reads its own weight ({@link #pumpLiquidLikeWeight}). An owner option
     * (S2_NETWORK_REVIEW.md, S4_MACHINES_REVIEW.md).
     */
    public static final double GAS_LIKE_UNTIL=.5,LIQUID_LIKE_FROM=1.5;
    /**
     * The liquid-like weight of a state of one fluid ({@link State#oneFluidSlot()}): 0 at or below reduced density
     * {@link #GAS_LIKE_UNTIL}, 1 from {@link #LIQUID_LIKE_FROM}, a smoothstep between; NaN for any other state.
     */
    public static double liquidLikeWeight(State state) {
        if(state.oneFluidSlot()<0)return Double.NaN;
        return MixtureViscosity.smoothstep((state.reducedDensity()-GAS_LIKE_UNTIL)/(LIQUID_LIKE_FROM-GAS_LIKE_UNTIL));
    }
    /**
     * A pump's own upper bound of the liquid-like weight (owner, 2026-11-16, option 1 B of DENSE_PUMP_RESEARCH_REVIEW.md
     * of batch 2026-11-15-supercritical-fluids): a centrifugal pump keeps its full head on one dense fluid from its critical
     * density; only its rise {@code ρ g H} falls with the density. Pumps run carbon dioxide down to 400 to 500 kg/m3 (reduced
     * density 0.86 to 1.07), one test pump stably at 340 kg/m3 (0.73). The lower bound is {@link #GAS_LIKE_UNTIL}, the gas rule.
     */
    public static final double PUMP_LIQUID_LIKE_FROM=1.0;
    /**
     * A pump's liquid-like weight of a state of one fluid ({@link State#oneFluidSlot()}): 0 at or below reduced density
     * {@link #GAS_LIKE_UNTIL}, 1 from {@link #PUMP_LIQUID_LIKE_FROM}, a smoothstep between; NaN for any other state. A
     * pump's vapour share of one dense fluid is {@code 1} minus it ({@code InletPhase}).
     */
    public static double pumpLiquidLikeWeight(State state) {
        if(state.oneFluidSlot()<0)return Double.NaN;
        return MixtureViscosity.smoothstep((state.reducedDensity()-GAS_LIKE_UNTIL)/(PUMP_LIQUID_LIKE_FROM-GAS_LIKE_UNTIL));
    }
    /** Whether the state holds a gas phase to draw. */
    public static boolean holdsVapor(State state){return state.vaporVolume()>0;}
    /** Whether the state holds a liquid to draw (solids alone do not flow as a liquid). */
    public static boolean holdsLiquid(State state){return state.condensedVolume()>0;}
    /** Mass of a slot's fluid, kg. */
    public double slotMass(State state,int slot) {
        double mass=0;var amounts=state.amountsView(slot);
        for(int i=0;i<amounts.length;i++)mass+=amounts[i]*model.molecularWeight(i);
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
        return Math.min(model.maximumVelocity,phaseAcoustic(state.vaporVolume(),vaporMass(state),gas==null?0:gas.isothermalCompressibility()));
    }
    /**
     * {@code V / sqrt(M C)}. The product of a phase's mass and compliance goes subnormal for a trace phase (a vanishing
     * nitrogen vapour below about 2e-150 mol) and is zero below about 2e-158 mol, the bound then infinite; such a product
     * is evaluated as {@code sqrt(V/M) sqrt(V/C)}, ratios of like-sized quantities. An overflowing product takes the same
     * path. A finite normal product takes the original expression, bit for bit.
     */
    private static double acoustic(double volume,double mass,double compliance) {
        double product=mass*compliance;
        return checkedAcoustic(positiveNormal(product)?volume/Math.sqrt(product):Math.sqrt(volume/mass)*Math.sqrt(volume/compliance));
    }
    private static double phaseAcoustic(double volume,double mass,double compressibility) {
        double compliance=volume*compressibility;
        return positiveNormal(compliance)?acoustic(volume,mass,compliance):acousticFromCompressibility(volume,mass,compressibility);
    }
    private static double acousticFromCompressibility(double volume,double mass,double compressibility) {
        return checkedAcoustic(Math.sqrt(volume/mass)/Math.sqrt(compressibility));
    }
    private static boolean positiveNormal(double value){return value>=Double.MIN_NORMAL&&Double.isFinite(value);}
    private static double checkedAcoustic(double speed) {
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid acoustic flow-domain bound");
        return speed;
    }
    /** The vapour stream's viscosity: {@link #slotViscosity} of the vapour slot. */
    public double vaporViscosity(State state,MixtureViscosity.Workspace scratch,Prepared prepared) {
        var terms=prepared==null?null:model.match(prepared,state.temperature()).viscosities();
        return slotViscosity(state,State.VAPOR,scratch,terms);
    }
    /**
     * The fluid viscosity of one held slot, the one rule every stream and node reads (S2b of batch
     * 2026-11-15-supercritical-fluids): the vapour slot, and a liquid slot whose root is on a supercritical isotherm of its
     * own composition, read {@link MixtureViscosity#dense} at the slot's molar density (its amounts over its volume); any
     * other liquid slot reads {@link MixtureViscosity#liquid} as before. A gas below a quarter of its critical density keeps
     * its Wilke value bit for bit; a dense fluid's viscosity follows its density across the slot line. {@code terms}: the
     * state's own temperature's viscosity terms, or null.
     */
    public double slotViscosity(State state,int slot,MixtureViscosity.Workspace scratch,MixtureViscosity.Prepared terms) {
        double t=state.temperature();double[] amounts=state.amountsView(slot);
        var phase=state.phase(slot);
        if(slot==State.VAPOR||phase!=null&&phase.traits()!=null&&phase.traits().supercriticalIsotherm())
            return model.viscosity.dense(t,amounts,total(amounts)/state.slotVolume(slot),scratch,terms);
        return model.viscosity.liquid(t,amounts,terms).pascalSeconds();
    }
    /** The vapour stream of {@code state}, or null when it holds no gas. */
    public PhaseStream vaporStream(State state,Prepared prepared,MixtureViscosity.Workspace scratch) {
        if(!holdsVapor(state))return null;
        return new PhaseStream(state.vaporAmounts(),vaporMass(state),state.vaporVolume(),vaporViscosity(state,scratch,prepared),
                vaporSpecificEnthalpy(state,prepared),vaporVelocityLimit(state),SolidInventory.Moments.ZERO);
    }
    /** Whether {@code state} holds the phase labelled {@code phase} ({@link FluidThermodynamics#GAS}, {@link FluidThermodynamics#OIL} or {@link FluidThermodynamics#AQUEOUS}). */
    public static boolean holdsPhase(State state,int phase){return state.slot(phase)>=0;}
    /** Whether the state holds any fluid phase: not a vessel holding only solids ({@link FluidThermodynamics#solidState}). */
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
    /** The own density (no solids) of a liquid phase the state holds: its fluid mass over its volume. */
    public double liquidPhaseDensity(State state,int phase) {
        int slot=state.slot(phase);
        return slotMass(state,slot)/state.slotVolume(slot);
    }
    /** The heavier of the state's two liquids by their own densities: {@link FluidThermodynamics#OIL} only when both are held and the oil is
     * the denser, {@link FluidThermodynamics#AQUEOUS} otherwise (which one is named when at most one is held does not matter to a priority
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
    /** A phase stream's specific enthalpy (J/kg), its solid share included; {@code prepared} as for {@link FluidThermodynamics#state}. */
    public double phaseSpecificEnthalpy(State state,int phase,Prepared prepared) {
        if(phase==GAS)return vaporSpecificEnthalpy(state,prepared);
        int slot=state.slot(phase);double h=0;
        if(slot>=0&&state.phase(slot)!=null)h+=total(state.amountsView(slot))*state.phase(slot).molarEnthalpy();
        h+=phaseSolidShare(state,phase)*state.solidMoments().enthalpy(state.temperature(),state.pressure());
        return h/phaseMass(state,phase);
    }
    /** A phase stream's velocity limit: {@link #vaporVelocityLimit} for the gas, the configured maximum for a liquid
     * with its solids (a liquid's own isothermal sound speed is 900 to 1500 m/s at the network's states, D37 S5). A liquid
     * slot that holds a state's one dense fluid ({@link State#dense()}) takes the vapour's law on its own slot, its solids'
     * mass with it: such a fluid's sound speed falls below 100 m/s near its critical point (S2b of batch
     * 2026-11-15-supercritical-fluids). */
    public double phaseVelocityLimit(State state,int phase) {
        if(phase==GAS)return vaporVelocityLimit(state);
        int slot=state.slot(phase);
        if(slot<0||slot!=state.oneFluidSlot()||!state.dense())return model.maximumVelocity;
        return Math.min(model.maximumVelocity,phaseAcoustic(state.slotVolume(slot),phaseMass(state,phase),state.phase(slot).isothermalCompressibility()));
    }
    /** A phase stream's viscosity: {@link #vaporViscosity} for the gas; a liquid's own viscosity (one log-mixing rule over
     * its components, water among them) with the slurry correction for its share of the solids, over the stream's volume. */
    public double phaseViscosity(State state,int phase,MixtureViscosity.Workspace scratch,Prepared prepared) {
        if(phase==GAS)return vaporViscosity(state,scratch,prepared);
        var terms=prepared==null?null:model.match(prepared,state.temperature()).viscosities();
        int slot=state.slot(phase);
        double liquid=state.slotVolume(slot),value=liquid*slotViscosity(state,slot,scratch,terms);
        double solid=phaseSolidShare(state,phase)*state.solidMoments().volume();
        if(solid>0){double phi=solid/(liquid+solid);value=com.wormzjl.icheme.science.fluid.transport.SlurryTransport.effectiveViscosity(value/liquid,Math.min(phi,0.62-1e-9))*(liquid+solid);}
        return value/(liquid+solid);
    }
    /** The carrier viscosity of a phase stream (no slurry correction), which an inline filter's cake resistance scales
     * with: the gas's own, or the liquid's own. */
    public double phaseCarrierViscosity(State state,int phase,MixtureViscosity.Workspace scratch) {
        if(phase==GAS)return vaporViscosity(state,scratch,null);
        return slotViscosity(state,state.slot(phase),scratch,null);
    }
    /** The stream of one phase of {@code state}, or null when it does not hold that phase; {@link #vaporStream} for the
     * gas. */
    public PhaseStream phaseStream(State state,int phase,Prepared prepared,MixtureViscosity.Workspace scratch) {
        if(!holdsPhase(state,phase))return null;
        if(phase==GAS)return vaporStream(state,prepared,scratch);
        return new PhaseStream(phaseMoles(state,phase),phaseMass(state,phase),phaseVolume(state,phase),phaseViscosity(state,phase,scratch,prepared),
                phaseSpecificEnthalpy(state,phase,prepared),phaseVelocityLimit(state,phase),phaseSolidMoments(state,phase));
    }
}
