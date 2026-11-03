package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.MachineLaw;
import com.wormzjl.icheme.science.machine.PositiveDisplacement;

/** Immutable actuator settings; the active regime belongs to a solve attempt, never to this shared value. */
public sealed interface FlowControl permits FlowControl.Passive,FlowControl.Mover,FlowControl.Valve {
    record Passive() implements FlowControl {}
    /**
     * A device that moves fluid from its connection's first end (its suction) to its second: the liquid pump and the gas
     * compressor (decisions D1, D2, D7, D8 of documentation/2026-09-26-phase-ports-and-compressor). Every mover runs on its
     * own law, one row whose rise falls with the flow ({@link #lawResidual}, mode {@code MOVER_LAW}; batch
     * 2026-11-03-rotating-machinery, form F2b, the owner's choice of 2026-11-04). A set-flow pump or compressor is a
     * positive-displacement machine: its set flow is the displacement, its rise limit the relief rise
     * ({@link PositiveDisplacement}). A {@link Machine} is on its curve. Movers share the modes, the shutoff band and the
     * carry; they differ in the law, the shaft work and the inlet phase they admit. A pump has one more mode,
     * {@code PUMP_SUCTION_LIMIT} (cavitation); a compressor draws gas, which has no vapour pressure to fall below, and never
     * takes it. Every quantity reads the suction only (decision D4): the stream the first end draws, and the first end's
     * pressure.
     */
    sealed interface Mover extends FlowControl permits Pump,Compressor,Machine {
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
        /** The law as the {@code MOVER_LAW} row, Pa: the head less the law's rise at {@code flow}. Its head derivative is one,
         * so the row is never empty. */
        default double lawResidual(double flow,double head,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
            return head-lawRise(flow,suctionDensity,suctionPressure,referenceDensity,k);
        }
        /**
         * Whether a supply (what the suction line delivers, {@link InletPhase.Supply}) is refused for the next slice
         * ({@code INLET_WRONG_PHASE}), given whether the device stood refused at the committed slice end: a dry suction
         * ({@link InletPhase.Supply#dry}, decision D58), or the wrong phase by a refusal threshold, with a lower resume
         * threshold the measure must fall below before a refused device runs again.
         */
        boolean refuses(InletPhase.Supply supply,boolean refused);
        /** The device's name in reasons ("pump", "compressor"). */
        String deviceName();
    }
    /** Suction volumetric target (m3/s), maximum added pressure (Pa). A liquid pump (decision D1): it refuses a supply whose
     * vapour fills more than {@link #VAPOUR_REFUSE} of its fluid volume, and runs again once that share is below
     * {@link #VAPOUR_RESUME}. */
    record Pump(double requestedVolumeFlow,double maximumAddedPressure,boolean enabled) implements Mover {
        public Pump(double targetVolumeFlow,double maximumAddedPressure){this(targetVolumeFlow,maximumAddedPressure,true);}
        /** The largest rise a pump's controls take, for the reference fluid (Pa). */
        public static final double MAXIMUM_ADDED_PRESSURE=2e6;
        /** Vapour share of the supply's fluid volume above which a pump refuses (plan Appendix B, decision D6). */
        public static final double VAPOUR_REFUSE=.02;
        /** Vapour share below which a refused pump runs again. */
        public static final double VAPOUR_RESUME=.005;
        public Pump {
            if(!Double.isFinite(requestedVolumeFlow)||requestedVolumeFlow<0||!Double.isFinite(maximumAddedPressure)||maximumAddedPressure<=0)
                throw new IllegalArgumentException("Invalid pump settings");
        }
        /** The setting is the rise for the reference fluid (water at 298.15 K, 1 atm), scaled by the suction's density: a
         * head, the one quantity a real pump fixes (decision D4: suction density only). */
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
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused){return refusesSupply(supply,refused);}
        /** A liquid machine's refusal: a dry suction, or vapour above {@link #VAPOUR_REFUSE} of the fluid volume. */
        static boolean refusesSupply(InletPhase.Supply supply,boolean refused) {
            if(supply.dry(refused))return true;
            double share=supply.vapourVolumeShare();
            return share>VAPOUR_REFUSE||refused&&!(share<VAPOUR_RESUME);
        }
        @Override public String deviceName(){return "pump";}
    }
    /**
     * A gas compressor (decisions D2, D7, D8): suction volume flow target (m3/s at inlet conditions) and maximum pressure
     * ratio {@code r_max} (discharge over suction). Its rise limit is {@code (r_max - 1) P_suction}; its work the polytropic
     * work at the default polytropic efficiency, on suction properties, booked into the discharge (batch
     * 2026-11-03-rotating-machinery, plan section 4.4; the ideal isothermal work before). It
     * refuses a supply whose condensed phases (hydrocarbon liquid, free water and solids) exceed
     * {@link #CONDENSED_REFUSE} of its mass, or that carries any solid population above
     * {@link com.wormzjl.icheme.science.fluid.transport.SlurryTransport#DEFAULT_TRACE_VOLUME_FRACTION} of its volume,
     * and runs again once the condensed share is below {@link #CONDENSED_RESUME} (and no solid is above the trace).
     */
    record Compressor(double requestedVolumeFlow,double maximumPressureRatio,boolean enabled) implements Mover {
        public Compressor(double targetVolumeFlow,double maximumPressureRatio){this(targetVolumeFlow,maximumPressureRatio,true);}
        /** The largest pressure ratio a compressor's controls take (plan Appendix B). */
        public static final double MAXIMUM_PRESSURE_RATIO=10;
        /** Condensed share of the supply's mass above which a compressor refuses (plan Appendix B, decision D6). */
        public static final double CONDENSED_REFUSE=.01;
        /** Condensed share below which a refused compressor runs again. */
        public static final double CONDENSED_RESUME=.002;
        public Compressor {
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
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused){return refusesSupply(supply,refused);}
        /** A gas machine's refusal: a dry suction, a solid above the trace, or condensed phases above {@link #CONDENSED_REFUSE}
         * of the mass. */
        static boolean refusesSupply(InletPhase.Supply supply,boolean refused) {
            if(supply.dry(refused))return true;
            if(supply.largestSolidVolumeFraction()>com.wormzjl.icheme.science.fluid.transport.SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION)return true;
            double share=supply.condensedMassShare();
            return share>CONDENSED_REFUSE||refused&&!(share<CONDENSED_RESUME);
        }
        @Override public String deviceName(){return "compressor";}
    }
    /**
     * A rotodynamic machine on a shaft (batch 2026-11-03-rotating-machinery, plan sections 3.1 and 3.2): a centrifugal pump
     * or compressor on its curve, at the speed its driver set for the slice. It has no set flow: the line and the curve
     * settle it. Its {@link #requestedVolumeFlow} is the rated flow at its speed, a scale for seeds, zero when it stands.
     *
     * @param law     the machine's curve and efficiency
     * @param speed   its speed for the slice, a share of the rated speed; zero stands it closed
     * @param enabled whether it runs
     */
    record Machine(MachineLaw law,double speed,boolean enabled) implements Mover {
        public Machine {
            java.util.Objects.requireNonNull(law);
            if(!Double.isFinite(speed)||speed<0)throw new IllegalArgumentException("Invalid machine speed "+speed);
        }
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
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused) {
            return liquid()?Pump.refusesSupply(supply,refused):Compressor.refusesSupply(supply,refused);
        }
        @Override public String deviceName(){return liquid()?"pump":"compressor";}
    }
    /** The corner law of a set-flow mover (form F2b): its set flow is the displacement, its rise limit the relief rise. */
    private static PositiveDisplacement corner(Mover mover,double suctionDensity,double suctionPressure,double referenceDensity,double k) {
        return new PositiveDisplacement(mover.targetVolumeFlow(),mover.riseLimit(suctionDensity,suctionPressure,referenceDensity,k));
    }
    /** A passive throttling actuator. It never adds shaft work or pressure. */
    sealed interface Valve extends FlowControl permits PressureValve,ControlValve {
        double targetPressure();
        default boolean pressureControl(){return true;}
        default boolean bare(){return false;}
        default double targetMassFlow(double density){return Double.NaN;}
    }
    enum ValveSource { NONE, PRESSURE, MASS_FLOW, VOLUME_FLOW }
    /** Configured facts; source identity/revision invalidate endpoint carry when a component or target changes. */
    record ControlValve(ValveSource source,double targetPressure,double massTarget,double volumeTarget,java.util.UUID sensor,long revision) implements Valve {
        public ControlValve(ValveSource source,double target,java.util.UUID sensor,long revision){this(source,source==ValveSource.PRESSURE||source==ValveSource.NONE?target:200000,source==ValveSource.MASS_FLOW?target:1,source==ValveSource.VOLUME_FLOW?target:.01,sensor,revision);}
        public ControlValve {
            java.util.Objects.requireNonNull(source);
            if(!Double.isFinite(targetPressure)||targetPressure<=0||!Double.isFinite(massTarget)||massTarget<0||!Double.isFinite(volumeTarget)||volumeTarget<0||revision<0||
                    source==ValveSource.NONE&&sensor!=null)throw new IllegalArgumentException("Invalid control valve");
        }
        public static ControlValve unconfigured(){return new ControlValve(ValveSource.NONE,200000,null,0);}
        @Override public boolean pressureControl(){return source==ValveSource.PRESSURE;}
        @Override public boolean bare(){return sensor==null;}
        public double target(){return switch(source){case NONE,PRESSURE->targetPressure;case MASS_FLOW->massTarget;case VOLUME_FLOW->volumeTarget;};}
        @Override public double targetMassFlow(double density){return source==ValveSource.MASS_FLOW?massTarget:volumeTarget*density;}
        public ControlValve withTarget(double value){return new ControlValve(source,source==ValveSource.PRESSURE?value:targetPressure,source==ValveSource.MASS_FLOW?value:massTarget,source==ValveSource.VOLUME_FLOW?value:volumeTarget,sensor,Math.addExact(revision,1));}
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
     * slice because its supply is the wrong phase or dry ({@link InletPhase}): in the solver it is {@code CLOSED} (flow exactly
     * zero, never reopened within the slice), and it persists as an endpoint mode so the next slice's hysteresis reads it.
     * Endpoint modes are state, saved as ordinals in the checkpoint format only.
     *
     * <p>{@code PUMP_SUCTION_LIMIT} (owner decision D59 (4)) is a liquid pump that cavitates: its law would pull its suction
     * below the bubble pressure of the liquid it draws (water's saturation pressure; for a hydrocarbon liquid a two-phase
     * supplier's own pressure, owner decision K6 of 2026-10-18), so the row {@code P_suction = P_bubble} replaces the law's
     * row and the flow is what the suction line delivers at that pressure. It persists as an endpoint mode. The velocity
     * cap is no mode: it is part of every throttling line's law ({@code PassiveStepSolver.saturated}, 2026-10-18).
     *
     * <p>{@code MOVER_LAW} (batch 2026-11-03-rotating-machinery) is a running mover: one row, the rise falling with the
     * flow ({@link Mover#lawResidual}). It closes on a reverse drive and takes the suction limit. The set-flow pair it
     * replaced, a target row and a head-limit row, is gone (form F2b, 2026-11-04).
     */
    enum Mode { PASSIVE, VALVE_REGULATING, VALVE_OPEN, CLOSED, INLET_WRONG_PHASE, PUMP_SUCTION_LIMIT, VALVE_CONFLICT, MOVER_LAW }
}
