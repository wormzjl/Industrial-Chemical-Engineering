package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.MachineLaw;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.Npsh;
import com.wormzjl.icheme.science.machine.PositiveDisplacement;

/** Immutable actuator settings; the active regime belongs to a solve attempt, never to this shared value. */
public sealed interface FlowControl permits FlowControl.Passive,FlowControl.Mover,FlowControl.Valve {
    record Passive() implements FlowControl {}
    /**
     * A steam turbine (batch 2026-11-03-rotating-machinery, plan section 3.4, owner decision D22): a driver of its own,
     * separate from the machines it drives, which a shaft train joins later (W3). Its internal governor limits admission
     * through the ordinary mass-flow valve rows. {@code massTarget} is a derived slice input in kg/s, -1 for the full-open
     * startup passage; facts carry only the design. The turbine takes {@link #shaftPower} out of the stream: the
     * exhaust leaves at {@code h_in − W/ṁ}, and the receiver's flash gives its temperature and wetness. The work leaves the
     * island as a {@link ConservativeTransport.BoundaryTransfer.Kind#SHAFT} transfer at the connection, which W3 reads as the
     * power the turbine delivers. A reverse flow passes as through an open line and does no work.
     *
     * @param design its rated power and isentropic efficiency η_is
     */
    record Turbine(com.wormzjl.icheme.science.machine.SteamTurbine design,double massTarget) implements Valve {
        public Turbine(com.wormzjl.icheme.science.machine.SteamTurbine design){this(design,-1);}
        public Turbine {
            java.util.Objects.requireNonNull(design);
            if(!Double.isFinite(massTarget)||massTarget<0&&massTarget!=-1)throw new IllegalArgumentException("Invalid turbine admission target");
        }
        @Override public double targetPressure(){return 0;}
        @Override public boolean pressureControl(){return false;}
        @Override public boolean massControl(){return true;}
        @Override public boolean bare(){return massTarget<0;}
        @Override public double targetMassFlow(double density){return massTarget;}
        public Turbine withMassTarget(double target){return new Turbine(design,target);}
        /**
         * The shaft power (W, out of the stream, never negative) of {@code massFlow} (kg/s, forward) expanding from the inlet's
         * real {@code inletPressure} (Pa) and {@code inletDensity} (kg/m3) to {@code exhaustPressure} (Pa), for the inlet gas's
         * heat capacity ratio {@code k}: {@code ṁ η_is Δh_is} ({@link com.wormzjl.icheme.science.machine.SteamTurbine#isentropicDrop}).
         * At {@code k ≤ 1}, where the drop's formula divides by zero, the drop is its limit at {@code k → 1}, the isothermal
         * {@code (P_in/ρ_in) ln(P_in/P_out)}: a numerical guard only, since the ideal-gas ratio the solver reads exceeds one.
         */
        public double shaftPower(double massFlow,double inletDensity,double inletPressure,double exhaustPressure,double k) {
            if(!(massFlow>0)||!(inletDensity>0)||!(exhaustPressure<inletPressure))return 0;
            double drop=k>1?com.wormzjl.icheme.science.machine.SteamTurbine.isentropicDrop(inletPressure,inletDensity,exhaustPressure,k)
                    :inletPressure/inletDensity*com.wormzjl.icheme.science.math.DeterministicMath.log(inletPressure/Math.max(exhaustPressure,1e-12*inletPressure));
            return design.work(massFlow,drop);
        }
    }
    /**
     * A device that moves fluid from its connection's first end (its suction) to its second: the liquid pump and the gas
     * compressor (decisions D1, D2, D7, D8 of documentation/2026-09-26-phase-ports-and-compressor). Every mover runs on its
     * own law, one row that ties its flow to its rise ({@link #lawResidual}, mode {@code MOVER_LAW}; batch
     * 2026-11-03-rotating-machinery: the owner chose the law over the set-flow pair on 2026-11-04, form F2e). A set-flow pump
     * or compressor is a positive-displacement machine: its set flow is the displacement, its rise limit the relief rise
     * ({@link PositiveDisplacement}). A {@link Machine} is on its curve. Movers share the modes, the shutoff band and the
     * carry; they differ in the law and the shaft work. Every mover takes what its suction line delivers, gas and liquid
     * alike (request 11, owner decisions D19 and D20): its law counts the suction mixture's volume, a centrifugal machine's
     * head or efficiency reads the suction's vapour or liquid share once per solve ({@code PassiveStepSolver.readSuctions}),
     * and only a dry suction (D58) or, at a compressor, solids refuse it. A pump has one more mode,
     * {@code PUMP_SUCTION_LIMIT} (cavitation, on its NPSH3 floor, {@link Mover#npsh}); a compressor draws gas, which has no
     * vapour pressure to fall below, and never takes it. Every quantity reads the suction only (decision D4): the stream the first end draws, and the first end's
     * pressure; a set-flow pump's relief rise reads the density of its supply, the stream its supply walk ends at, so it primes
     * its own line (owner decision D11, A2; {@code PassiveStepSolver.riseDensity}).
     */
    sealed interface Mover extends FlowControl permits Pump,Compressor,Machine,Ejector {
        /** The largest suction volume flow a device's controls take (m3/s). */
        double MAXIMUM_VOLUME_FLOW=10;
        /** Configured suction volume flow (m3/s), retained while switched off. */
        double requestedVolumeFlow();
        /** Whether the constructed mover is commanded on; one-block devices retain their enabled behavior. */
        boolean enabled();
        /** Effective target: Off uses the existing zero-flow closed regime. */
        default double targetVolumeFlow(){return enabled()?requestedVolumeFlow():0;}
        /** Whether it moves liquid (a pump) or gas (a compressor). */
        boolean liquid();
        /**
         * The largest pressure rise (Pa) the device can add over what its suction draws: {@code suctionDensity} (kg/m3) is
         * the density of the stream the first end draws, {@code suctionPressure} (Pa) the first end's driving pressure,
         * {@code referenceDensity} the pump setting's reference fluid density
         * ({@link com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics#pumpReferenceDensity()}), {@code k} the
         * gas's heat capacity ratio ({@link com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics#heatCapacityRatio}).
         * A law's rise at no flow.
         */
        double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity,double k);
        /**
         * The power (W) the device puts into what it moves, booked into the discharge: {@code massFlow} (kg/s, forward),
         * {@code head} (Pa, the rise the device holds), both clipped at zero, and the suction's density, pressure and
         * {@code k} only. A pump gives the ideal work (owner decision D37, simplification S1 of the rotating-machinery
         * batch); a compressor the polytropic work over its efficiency (plan section 4.4).
         */
        double power(double massFlow,double head,double suctionDensity,double suctionPressure,double k);
        /** The rise (Pa) the mover's law holds at suction volume flow {@code flow} (m3/s): finite at every flow, falling with
         * a forward flow. */
        double lawRise(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k);
        /** {@code d(lawRise)/dQ}, Pa per m3/s. */
        double lawRiseSlope(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k);
        /** The law as the {@code MOVER_LAW} row, Pa: zero on the law, rising with the flow and the head. By default the head
         * less the law's rise at {@code flow}, whose head derivative is one, so the row is never empty; a set-flow mover
         * states it in the flow form ({@link PositiveDisplacement#residual}), whose flow derivative is never zero. */
        default double lawResidual(double flow,double head,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return head-lawRise(flow,suctionDensity,suctionPressure,referenceDensity,k);
        }
        /**
         * Whether a supply (what the suction line delivers, {@link InletPhase.Supply}) is refused for the next slice
         * ({@code INLET_WRONG_PHASE}), given whether the device stood refused at the committed slice end: a dry suction
         * ({@link InletPhase.Supply#dry}, decision D58, with its hysteresis), and at a compressor a solid above the trace.
         * Gas at a pump and liquid at a compressor refuse nothing (request 11, owner decision D19): the law moves them.
         */
        boolean refuses(InletPhase.Supply supply,boolean refused);
        /** A liquid mover's NPSH design ({@link Npsh}; owner decisions D19 and D20 Q11 A); null for a gas mover and a stopped
         * one. */
        default Npsh npsh(){return null;}
        /** The device's name in reasons ("pump", "compressor"). */
        String deviceName();
    }
    /** Suction volumetric target (m3/s), maximum added pressure (Pa). A liquid pump (decision D1), positive displacement:
     * gas at its suction fills its displacement with the mixture, so it delivers {@code (1 − α)} of it as liquid (plan
     * section 3.6; simplification T2: a reciprocating pump's gas lock is not modelled). */
    record Pump(double requestedVolumeFlow,double maximumAddedPressure,boolean enabled) implements Mover {
        public Pump(double targetVolumeFlow,double maximumAddedPressure){this(targetVolumeFlow,maximumAddedPressure,true);}
        /** The largest rise a pump's controls take, for the reference fluid (Pa). */
        public static final double MAXIMUM_ADDED_PRESSURE=2e6;
        public Pump {
            if(!Double.isFinite(requestedVolumeFlow)||requestedVolumeFlow<0||!Double.isFinite(maximumAddedPressure)||maximumAddedPressure<=0)
                throw new IllegalArgumentException("Invalid pump settings");
        }
        /** The setting is the rise for the reference fluid (water at 298.15 K, 1 atm), scaled by the density of the
         * pump's supply over the reference's: a head on what the pump is fed (owner decision D11, A2). */
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return maximumAddedPressure*suctionDensity/referenceDensity;
        }
        /** {@code q v_s head}: the hydraulic power on the suction's specific volume. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure,double k) {
            return Math.max(0,massFlow)/suctionDensity*Math.max(0,head);
        }
        @Override public boolean liquid(){return true;}
        @Override public double lawRise(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return corner(this,suctionDensity,suctionPressure,referenceDensity,k).rise(flow,1);
        }
        @Override public double lawRiseSlope(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return corner(this,suctionDensity,suctionPressure,referenceDensity,k).riseSlope(flow,1);
        }
        @Override public double lawResidual(double flow,double head,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return corner(this,suctionDensity,suctionPressure,referenceDensity,k).residual(flow,head,1);
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused){return supply.dry(refused);}
        /** An in-line mover is a constructed machine (owner decision D19), chosen for its duty (D21): its design point is its
         * set flow at the speed that keeps its NPSH3 at {@link Npsh#DUTY_NPSH3}, at most the 2-pole speed ({@link Npsh#forDuty}). */
        @Override public Npsh npsh(){return targetVolumeFlow()>0?Npsh.forDuty(targetVolumeFlow(),Motor.ratedSpeed(2)):null;}
        @Override public String deviceName(){return "pump";}
    }
    /**
     * A gas compressor (decisions D2, D7, D8): suction volume flow target (m3/s at inlet conditions) and maximum pressure
     * ratio {@code r_max} (discharge over suction). Its rise limit is {@code (r_max - 1) P_suction}; its work the polytropic
     * work at the default polytropic efficiency, on suction properties, booked into the discharge (batch
     * 2026-11-03-rotating-machinery, plan section 4.4; the ideal isothermal work before). Positive
     * displacement: liquid at its suction takes its own volume of the displacement, so the gas it delivers is the
     * displacement less the liquid's volume (plan section 3.6). It refuses a supply that carries any solid population above
     * {@link com.wormzjl.icheme.science.fluid.transport.SlurryTransport#DEFAULT_TRACE_VOLUME_FRACTION} of its volume.
     */
    record Compressor(double requestedVolumeFlow,double maximumPressureRatio,boolean enabled,double setFlow,double setPressure) implements Mover {
        public Compressor(double requestedVolumeFlow,double maximumPressureRatio,boolean enabled){this(requestedVolumeFlow,maximumPressureRatio,enabled,0,0);}
        public Compressor withDisplacement(double flow){return new Compressor(flow,maximumPressureRatio,enabled,setFlow,setPressure);}
        public Compressor withEnabled(boolean value){return new Compressor(requestedVolumeFlow,maximumPressureRatio,value,setFlow,setPressure);}
        public Compressor(double targetVolumeFlow,double maximumPressureRatio){this(targetVolumeFlow,maximumPressureRatio,true);}
        /** The largest pressure ratio a compressor's controls take (plan Appendix B). */
        public static final double MAXIMUM_PRESSURE_RATIO=10;
        public Compressor {
            if(!(setFlow>=0)||setFlow>MAXIMUM_VOLUME_FLOW||!(setPressure>=0)||!Double.isFinite(setFlow+setPressure))throw new IllegalArgumentException("Invalid compressor demand");
            if(!Double.isFinite(requestedVolumeFlow)||requestedVolumeFlow<0||!Double.isFinite(maximumPressureRatio)||maximumPressureRatio<=1)
                throw new IllegalArgumentException("Invalid compressor settings");
        }
        /** {@code (r_max - 1) P_suction} (decision D7): the discharge stands at most {@code r_max} times the suction. */
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return (maximumPressureRatio-1)*suctionPressure;
        }
        /** {@code ṁ H_p/η_p} at {@link CentrifugalCompressor#DEFAULT_EFFICIENCY}: the polytropic head of raising the suction
         * stream by {@code head} on suction properties and {@code k}, over the efficiency. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure,double k) {
            double sigma=CentrifugalCompressor.sigma(k,CentrifugalCompressor.DEFAULT_EFFICIENCY);
            return Math.max(0,massFlow)*Math.max(0,CentrifugalCompressor.polytropicHead(1+Math.max(0,head)/suctionPressure,suctionPressure,suctionDensity,sigma))
                    /CentrifugalCompressor.DEFAULT_EFFICIENCY;
        }
        @Override public boolean liquid(){return false;}
        @Override public double lawRise(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return corner(this,suctionDensity,suctionPressure,referenceDensity,k).rise(flow,1);
        }
        @Override public double lawRiseSlope(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return corner(this,suctionDensity,suctionPressure,referenceDensity,k).riseSlope(flow,1);
        }
        @Override public double lawResidual(double flow,double head,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return corner(this,suctionDensity,suctionPressure,referenceDensity,k).residual(flow,head,1);
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused){return refusesSupply(supply,refused);}
        /** A gas machine's refusal: a dry suction or a solid above the trace. Liquid refuses nothing (request 11): a wet
         * suction is compressed as the mixture it is. */
        static boolean refusesSupply(InletPhase.Supply supply,boolean refused) {
            return supply.dry(refused)||supply.largestSolidVolumeFraction()>com.wormzjl.icheme.science.fluid.transport.SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION;
        }
        @Override public String deviceName(){return "compressor";}
    }
    /**
     * A rotodynamic machine on a shaft (batch 2026-11-03-rotating-machinery, plan sections 3.1 and 3.2): a centrifugal pump
     * or compressor on its curve, at the speed its driver set for the slice. It has no set flow: the line and the curve
     * settle it. Its {@link #requestedVolumeFlow} is the rated flow at its speed, a scale for seeds, zero when it stands.
     *
     * @param law         the machine's curve and efficiency
     * @param speed       its speed for the slice, a share of the rated speed; zero stands it closed. A slice input its
     *                    shaft train sets at dispatch (W3); saved facts carry none
     * @param enabled     whether it runs
     * @param setFlow     the suction volume flow (m³/s) a regulating driver holds (W3, reading A30); 0 for none
     * @param setPressure the discharge pressure (Pa) a regulating driver holds; 0 for none
     */
    record Machine(MachineLaw law,double speed,boolean enabled,double setFlow,double setPressure) implements Mover {
        public Machine(MachineLaw law,double speed,boolean enabled){this(law,speed,enabled,0,0);}
        public Machine {
            java.util.Objects.requireNonNull(law);
            if(!Double.isFinite(speed)||speed<0)throw new IllegalArgumentException("Invalid machine speed "+speed);
            if(!Double.isFinite(setFlow)||setFlow<0||setFlow>MAXIMUM_VOLUME_FLOW||!Double.isFinite(setPressure)||setPressure<0)throw new IllegalArgumentException("Invalid machine set points");
        }
        /** This machine at {@code value}, its share of the rated speed for a slice. */
        public Machine withSpeed(double value){return value==speed?this:new Machine(law,value,enabled,setFlow,setPressure);}
        /** Whether {@code other} is this machine at another speed: the same law, switch and set points. */
        public boolean sameButSpeed(Machine other){return law.equals(other.law)&&enabled==other.enabled&&setFlow==other.setFlow&&setPressure==other.setPressure;}
        @Override public double requestedVolumeFlow(){return law.ratedFlow()*speed;}
        @Override public boolean liquid(){return law.liquid();}
        /** The curve's rise at no flow. */
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return law.rise(0,speed,suctionDensity,suctionPressure,k);
        }
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure,double k) {
            return law.fluidPower(massFlow,head,suctionDensity,suctionPressure,k);
        }
        @Override public double lawRise(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return law.rise(flow,speed,suctionDensity,suctionPressure,k);
        }
        @Override public double lawRiseSlope(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return law.riseSlope(flow,speed,suctionDensity,suctionPressure,k);
        }
        /** The law's own row: the head form for a centrifugal curve, the flow form for a vacuum pump (batch 2026-11-10-vacuum-pump). */
        @Override public double lawResidual(double flow,double head,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return law.residual(flow,head,speed,suctionDensity,suctionPressure,k);
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused) {
            return liquid()?supply.dry(refused):Compressor.refusesSupply(supply,refused);
        }
        @Override public Npsh npsh(){return law instanceof CentrifugalPump pump&&speed>0?pump.npsh(speed):null;}
        /** This machine with its pump's head scaled by {@code share} ({@link CentrifugalPump#withHeadShare}, the gas share
         * {@link com.wormzjl.icheme.science.machine.GasEntrainment#headShare}); a compressor as it is. */
        public Machine withHeadShare(double share){return law instanceof CentrifugalPump pump&&share!=1?new Machine(pump.withHeadShare(share),speed,enabled,setFlow,setPressure):this;}
        @Override public String deviceName(){return liquid()?"pump":law instanceof com.wormzjl.icheme.science.machine.VacuumPump?"vacuum pump":"compressor";}
    }
    /**
     * A steam ejector set's suction-to-discharge connection (batch 2026-11-10-steam-ejector-flow, owner "mover with a curve" and
     * "Yes, correlation"): its curve ({@link com.wormzjl.icheme.science.machine.SteamEjector#capacity}, the sheet's vacuum
     * suction speed on its suction) as a set-flow mover's law (form F2e), the displacement the curve's suction volume at the
     * trial's suction pressure, the relief rise the set's maximum discharge less the suction, so the relief line's last tenth
     * is the set's break. It does no work: the motive steam that compresses the load arrives at the discharge junction with
     * its own enthalpy, through the set's motive valve ({@link ValveSource#EJECTOR}). Without steam it stands CLOSED.
     *
     * @param design         its suction
     * @param motivePressure the motive steam's pressure at the nozzle at the slice's start, Pa (a slice input)
     * @param delivered      the share of the steam its law asked that its nozzle got over the last booked slice, 0 to 1 (a slice input)
     */
    record Ejector(com.wormzjl.icheme.science.machine.SteamEjector design,double motivePressure,double delivered) implements Mover {
        public Ejector(com.wormzjl.icheme.science.machine.SteamEjector design){this(design,0,0);}
        public Ejector {
            java.util.Objects.requireNonNull(design);
            if(!(motivePressure>=0)||!(delivered>=0&&delivered<=1)||!Double.isFinite(motivePressure))throw new IllegalArgumentException("Invalid ejector motive");
        }
        /** The same set on the motive of another slice. */
        public Ejector withMotive(double pressure,double share){return new Ejector(design,pressure,share);}
        /** The curve's volume at 10 kPa: a scale for seeds, 0 without steam. */
        @Override public double requestedVolumeFlow(){return design.capacity(1e4,motivePressure,delivered);}
        @Override public boolean enabled(){return true;}
        @Override public boolean liquid(){return false;}
        /** The maximum discharge less the suction, never below a thousandth of the maximum discharge (a broken set holds none). */
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            double top=com.wormzjl.icheme.science.machine.SteamEjector.maximumDischarge(motivePressure),d=top-suctionPressure,e=1e-3*Math.max(top,1);
            return .5*(d+Math.sqrt(d*d+e*e));
        }
        /** None: the motive's enthalpy does the work. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure,double k){return 0;}
        private com.wormzjl.icheme.science.machine.PositiveDisplacement curve(double suctionDensity,double suctionPressure,double k) {
            double q=design.capacity(suctionPressure,motivePressure,delivered);
            return new com.wormzjl.icheme.science.machine.PositiveDisplacement(q>0?q:1e-12,riseLimit(suctionDensity,suctionPressure,suctionDensity,k));
        }
        @Override public double lawRise(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return curve(suctionDensity,suctionPressure,k).rise(flow,1);
        }
        @Override public double lawRiseSlope(double flow,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return curve(suctionDensity,suctionPressure,k).riseSlope(flow,1);
        }
        @Override public double lawResidual(double flow,double head,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return curve(suctionDensity,suctionPressure,k).residual(flow,head,1);
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused){return Compressor.refusesSupply(supply,refused);}
        @Override public String deviceName(){return "ejector";}
    }
    /** The law of a set-flow mover (form F2e): its set flow is the displacement, its rise limit the relief rise. */
    private static PositiveDisplacement corner(Mover mover,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
        return new PositiveDisplacement(mover.targetVolumeFlow(),mover.riseLimit(suctionDensity,suctionPressure,referenceDensity,k));
    }
    /** A passive throttling actuator. It never adds shaft work or pressure. */
    sealed interface Valve extends FlowControl permits PressureValve,ControlValve,Turbine {
        double targetPressure();
        default boolean pressureControl(){return true;}
        default boolean massControl(){return false;}
        default boolean bare(){return false;}
        default double targetMassFlow(double density){return Double.NaN;}
    }
    /**
     * What a control valve's sensor measures, saved by name. {@code SHAFT_SPEED} (batch 2026-11-03-rotating-machinery, owner
     * decision D27): a turbine's governor valve, its sensor the speed sensor on the turbine's shaft train; it regulates a
     * mass flow, its {@link ControlValve#massTarget}, which the engine sets once per slice from the train's power demand.
     * {@code BURNER} (batch 2026-11-09-furnace-fuel-power): a fired heater's fuel valve, on the run into its firebox; the engine
     * sets its mass target once per slice from the radiant coil's demand ({@link Burners}), 0 with the burners out or unpowered.
     * {@code FLARE} (batch 2026-11-09-flare-stack): a flare's tip limit, on the run into its tip; the engine sets its mass
     * target once per slice to the most the flare passes ({@link Flare#limit}), 0 while solids arrive.
     * {@code STILL} (batch 2026-11-09-batch-distiller): a batch still's outlet valve, on the run out of its distillate or vent
     * generator; the engine sets its mass target once per slice from what the still's head account holds.
     * {@code EJECTOR} (batch 2026-11-10-steam-ejector-flow): a steam ejector's motive valve, inside the set from its motive
     * junction to its discharge junction; the engine sets its mass target once per slice to the steam {@code EjectorLaw} asks
     * for the load the set's curve holds.
     */
    enum ValveSource { NONE, PRESSURE, MASS_FLOW, VOLUME_FLOW, SHAFT_SPEED, BURNER, FLARE, STILL, EJECTOR,
        /** A prime mover's fuel valve (batch 2026-11-10-prime-movers): on the run into its fuel void; the engine sets its mass target once per slice from its governor. */
        ENGINE,
        /** A vessel's gas blanketing valve (batch 2026-11-11-gas-blanketing): on the run into its blanket gas port; the interval solver sets its mass target at each step to the gas its vessel lacks at its set pressure ({@link GasBlanket#plan}). */
        BLANKET;
        /** Whether a valve of this source regulates its mass flow. */
        public boolean mass(){return this==MASS_FLOW||this==SHAFT_SPEED||this==BURNER||this==FLARE||this==STILL||this==EJECTOR||this==ENGINE||this==BLANKET;}
        /** Whether the engine sets this valve's mass target each slice (a governor or a fuel valve), not the player. */
        public boolean planned(){return this==SHAFT_SPEED||this==BURNER||this==FLARE||this==STILL||this==EJECTOR||this==ENGINE||this==BLANKET;}
        /** Whether this is a valve the engine builds into a sink (a firebox or a flare tip), on the run into it. */
        public boolean sink(){return this==BURNER||this==FLARE||this==ENGINE;}
    }
    /** Configured facts; source identity/revision invalidate endpoint carry when a component or target changes. */
    record ControlValve(ValveSource source,double targetPressure,double massTarget,double volumeTarget,java.util.UUID sensor,long revision) implements Valve {
        public ControlValve(ValveSource source,double target,java.util.UUID sensor,long revision){this(source,source==ValveSource.PRESSURE||source==ValveSource.NONE?target:200000,source.mass()?target:1,source==ValveSource.VOLUME_FLOW?target:.01,sensor,revision);}
        public ControlValve {
            java.util.Objects.requireNonNull(source);
            if(!Double.isFinite(targetPressure)||targetPressure<=0||!Double.isFinite(massTarget)||massTarget<0||!Double.isFinite(volumeTarget)||volumeTarget<0||revision<0||
                    source==ValveSource.NONE&&sensor!=null)throw new IllegalArgumentException("Invalid control valve");
        }
        public static ControlValve unconfigured(){return new ControlValve(ValveSource.NONE,200000,null,0);}
        /** A fired heater's fuel valve's source identity: it is never bare. */
        public static final java.util.UUID BURNER_SENSOR=new java.util.UUID(0x6275726e65720000L,1);
        /** A fired heater's fuel valve, shut until its first slice plan. */
        public static ControlValve burner(){return new ControlValve(ValveSource.BURNER,200000,0,.01,BURNER_SENSOR,0);}
        /** A flare's tip limit's source identity. */
        public static final java.util.UUID FLARE_SENSOR=new java.util.UUID(0x666c617265000000L,1);
        /** A flare's tip limit, shut until its first slice plan. */
        public static ControlValve flare(){return new ControlValve(ValveSource.FLARE,200000,0,.01,FLARE_SENSOR,0);}
        /** A batch still's outlet valve's source identity. */
        public static final java.util.UUID STILL_SENSOR=new java.util.UUID(0x7374696c6c000000L,1);
        /** A batch still's outlet valve, shut until its first slice plan. */
        public static ControlValve still(){return new ControlValve(ValveSource.STILL,200000,0,.01,STILL_SENSOR,0);}
        /** A steam ejector's motive valve's source identity. */
        public static final java.util.UUID EJECTOR_SENSOR=new java.util.UUID(0x656a6563746f7200L,1);
        /** A steam ejector's motive valve, shut until its first slice plan. */
        public static ControlValve ejector(){return new ControlValve(ValveSource.EJECTOR,200000,0,.01,EJECTOR_SENSOR,0);}
        /** A prime mover's fuel valve's source identity. */
        public static final java.util.UUID ENGINE_SENSOR=new java.util.UUID(0x656e67696e650000L,1);
        /** A prime mover's fuel valve, shut until its first slice plan. */
        public static ControlValve engine(){return new ControlValve(ValveSource.ENGINE,200000,0,.01,ENGINE_SENSOR,0);}
        /** A gas blanketing valve's source identity. */
        public static final java.util.UUID BLANKET_SENSOR=new java.util.UUID(0x626c616e6b657400L,1);
        /** A vessel's gas blanketing valve, shut until its first slice plan; its set pressure is its vessel's ({@link PassiveNetwork.Inventory#blanket}). */
        public static ControlValve blanket(){return new ControlValve(ValveSource.BLANKET,200000,0,.01,BLANKET_SENSOR,0);}
        @Override public boolean pressureControl(){return source==ValveSource.PRESSURE;}
        @Override public boolean massControl(){return source.mass();}
        @Override public boolean bare(){return sensor==null;}
        public double target(){return switch(source){case NONE,PRESSURE->targetPressure;case MASS_FLOW,SHAFT_SPEED,BURNER,FLARE,STILL,EJECTOR,ENGINE,BLANKET->massTarget;case VOLUME_FLOW->volumeTarget;};}
        @Override public double targetMassFlow(double density){return source.mass()?massTarget:volumeTarget*density;}
        public ControlValve withTarget(double value){return new ControlValve(source,source==ValveSource.PRESSURE?value:targetPressure,source.mass()?value:massTarget,source==ValveSource.VOLUME_FLOW?value:volumeTarget,sensor,Math.addExact(revision,1));}
        /** A governor valve's mass target for one slice (W3): a slice input, its revision kept, as a column's port plan is. */
        public ControlValve withMassTarget(double value){return new ControlValve(source,targetPressure,value,volumeTarget,sensor,revision);}
        public ControlValve withSource(ValveSource source,java.util.UUID sensor,long revision){return new ControlValve(source,targetPressure,massTarget,volumeTarget,sensor,revision);}
    }
    /** Effective flow-area opening, using the host bore and local density. */
    static double opening(double massFlow,double head,double density,double boreArea) {
        if(head<=0)return 1;
        if(massFlow==0)return 0;
        return 1/Math.sqrt(1+2*density*boreArea*boreArea*head/(massFlow*massFlow));
    }
    /** Sustains upstream absolute pressure; reverse flow is closed. */
    record PressureValve(double targetPressure) implements Valve {
        public PressureValve {if(!Double.isFinite(targetPressure)||targetPressure<=0)throw new IllegalArgumentException("Invalid valve target");}
    }
    /**
     * The regime of one connection in a solve. {@code INLET_WRONG_PHASE} (decision D6) is a mover refused for a whole
     * slice because its suction is dry or, at a compressor, carries solids ({@link InletPhase}): in the solver it is {@code CLOSED} (flow exactly
     * zero, never reopened within the slice), and it persists as an endpoint mode so the next slice's hysteresis reads it.
     * Endpoint modes are state, saved as ordinals in the checkpoint format only.
     *
     * <p>{@code PUMP_SUCTION_LIMIT} (owner decision D59 (4)) is a liquid pump that cavitates: its law would pull its suction
     * below its floor, the bubble pressure of the liquid it draws (water's saturation pressure; for a hydrocarbon liquid its
     * bubble pressure at its supplier's temperature and composition, owner decision D14) plus the breakdown NPSH at its
     * flow ({@link Npsh#breakdownPressure}, owner decision D20 Q11 A), so the row {@code P_suction = floor} replaces the
     * law's row and the flow is what the suction line delivers at that pressure. It persists as an endpoint mode. The velocity
     * cap is no mode: it is part of every throttling line's law ({@code PassiveStepSolver.saturated}, 2026-10-18).
     *
     * <p>{@code MOVER_LAW} (batch 2026-11-03-rotating-machinery) is a running mover: one row, the rise falling with the
     * flow ({@link Mover#lawResidual}). It closes on a reverse drive and takes the suction limit. The set-flow pair it
     * replaced, a target row and a head-limit row, is gone (form F2b, 2026-11-04).
     *
     * <p>A {@link Turbine} stands in {@code VALVE_OPEN} for its whole solve: its actuator row holds no throttled pressure,
     * so its own resistance sets its flow.
     */
    enum Mode { PASSIVE, VALVE_REGULATING, VALVE_OPEN, CLOSED, INLET_WRONG_PHASE, PUMP_SUCTION_LIMIT, VALVE_CONFLICT, MOVER_LAW }
}
