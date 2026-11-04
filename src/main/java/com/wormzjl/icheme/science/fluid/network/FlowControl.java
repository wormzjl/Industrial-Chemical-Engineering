package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/** Immutable actuator settings; the active regime belongs to a solve attempt, never to this shared value. */
public sealed interface FlowControl permits FlowControl.Passive,FlowControl.Mover,FlowControl.Valve {
    record Passive() implements FlowControl {}
    /**
     * A device that moves fluid from its connection's first end (its suction) to its second at a set suction volume flow,
     * up to a pressure-rise limit: the liquid pump and the gas compressor (decisions D1, D2, D7, D8 of
     * documentation/2026-09-26-phase-ports-and-compressor). The solver runs both on one set of modes
     * ({@code PUMP_TARGET}, {@code PUMP_HEAD_LIMIT}, {@code CLOSED},
     * {@code INLET_WRONG_PHASE}), rows, shutoff band and carry; they differ only in the rise limit, the shaft work and the
     * inlet phase they admit. A pump has one more, {@code PUMP_SUCTION_LIMIT} (cavitation); a compressor draws gas, which
     * has no vapour pressure to fall below, and never takes it. Every quantity reads the suction only (decision D4): the stream the first end draws, and the
     * first end's pressure.
     */
    sealed interface Mover extends FlowControl permits Pump,Compressor {
        /** The largest suction volume flow a device's controls take (m3/s). */
        double MAXIMUM_VOLUME_FLOW=10;
        /** Configured suction volume flow (m3/s), retained while switched off. */
        double requestedVolumeFlow();
        /** Whether the constructed mover is commanded on; one-block devices retain their enabled behavior. */
        boolean enabled();
        /** Effective target: Off uses the existing zero-flow closed regime. */
        default double targetVolumeFlow(){return enabled()?requestedVolumeFlow():0;}
        /**
         * The largest pressure rise (Pa) the device can add over what its suction draws: {@code suctionDensity} (kg/m3) is
         * the density of the stream the first end draws, {@code suctionPressure} (Pa) the first end's driving pressure,
         * {@code referenceDensity} the pump setting's reference fluid density
         * ({@link com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics#pumpReferenceDensity()}).
         */
        double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity);
        /**
         * The shaft power (W) the device puts into what it moves, booked as heat into the discharge: {@code massFlow}
         * (kg/s, forward), {@code head} (Pa, the rise the device holds), both clipped at zero, and the suction's density
         * and pressure only. It is the ideal work: a mover has no efficiency (owner decision D37, simplification S6; the
         * GUI and the world default always set 1).
         */
        double power(double massFlow,double head,double suctionDensity,double suctionPressure);
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
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity) {
            return maximumAddedPressure*suctionDensity/referenceDensity;
        }
        /** {@code q v_s head}: the hydraulic power on the suction's specific volume. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure) {
            return Math.max(0,massFlow)/suctionDensity*Math.max(0,head);
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused) {
            if(supply.dry(refused))return true;
            double share=supply.vapourVolumeShare();
            return share>VAPOUR_REFUSE||refused&&!(share<VAPOUR_RESUME);
        }
        @Override public String deviceName(){return "pump";}
    }
    /**
     * A gas compressor (decisions D2, D7, D8): suction volume flow target (m3/s at inlet conditions) and maximum pressure
     * ratio {@code r_max} (discharge over suction). Its rise limit is {@code (r_max - 1) P_suction}; its shaft work the
     * ideal isothermal work on suction properties, booked as heat into the discharge. It
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
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity) {
            return (maximumPressureRatio-1)*suctionPressure;
        }
        /** {@code q (P_s / rho_s) ln(1 + head / P_s)} (decision D8): the ideal isothermal work of raising the suction stream
         * by {@code head}, on suction properties only. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure) {
            return Math.max(0,massFlow)*(suctionPressure/suctionDensity)*DeterministicMath.log1p(Math.max(0,head)/suctionPressure);
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused) {
            if(supply.dry(refused))return true;
            if(supply.largestSolidVolumeFraction()>com.wormzjl.icheme.science.fluid.transport.SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION)return true;
            double share=supply.condensedMassShare();
            return share>CONDENSED_REFUSE||refused&&!(share<CONDENSED_RESUME);
        }
        @Override public String deviceName(){return "compressor";}
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
     * Endpoint modes are saved as ordinals (checkpoint format 11, unit format 4).
     *
     * <p>{@code PUMP_SUCTION_LIMIT} (owner decision D59 (4)) is a liquid pump that cavitates: its target flow would pull its
     * suction below the bubble pressure of the liquid it draws (water's saturation pressure; for a hydrocarbon liquid a
     * two-phase supplier's own pressure, owner decision K6 of 2026-10-18), so the row {@code P_suction = P_bubble} replaces
     * the target-flow row, as the head limit does, and the flow is what the suction line delivers at that pressure. It
     * persists as an endpoint mode like the head limit. The velocity cap is no mode: it is part of every throttling
     * line's law ({@code PassiveStepSolver.saturated}, 2026-10-18).
     */
    enum Mode { PASSIVE, PUMP_TARGET, PUMP_HEAD_LIMIT, VALVE_REGULATING, VALVE_OPEN, CLOSED, INLET_WRONG_PHASE, PUMP_SUCTION_LIMIT, VALVE_CONFLICT }
}
