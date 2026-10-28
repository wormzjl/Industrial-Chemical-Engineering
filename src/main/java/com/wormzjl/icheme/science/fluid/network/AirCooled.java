package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * An air cooler's process side (batch 2026-10-27-heat-exchanger-design, plan section 4, W5; its own law since W4): the
 * stream passes {@code area} m² of bare tube against air at a constant temperature, every tube row meeting fresh air, under
 * the effective films the {@code air} gives at the {@code draught}, the air through the bank as a share of full fans' air.
 * The louvers and the fans are its actuator, one effort (the owner's test, 2026-10-28: "AC should be able to adjust its duty
 * to match target T as well as furnace"): 0 with the louvers shut (no air, no heat), {@link Air#natural} with the louvers
 * open and the fans still, 1 at full fans. Without a controller, or in manual, the player sets the fans and the louvers
 * stand open ({@link Air#draught}); a controller plans the effort for each step ({@link #planned}), as a fired coil's firing
 * is. Its heat is one exact march against the flat air temperature: no unknown, zero at no flow.
 *
 * @param limit why the planned effort stands where it does ({@link HeatControl.Limit#NONE} unplanned)
 */
public record AirCooled(double area,Air air,double draught,@Nullable HeatControl control,HeatControl.Limit limit) implements HeatLaw {
    /**
     * The air of an air cooler's bank, per m² of its bundle face: at {@code temperature} K, over {@code barePerFace} m² of
     * bare tube; at full fans the air's film on the fins ({@code film}, W/K) and its heat capacity rate ({@code capacity},
     * W/K); natural draught moves the share {@code natural} of full air.
     */
    public record Air(double temperature,double barePerFace,double film,double capacity,double natural) {
        public Air {
            if(!(temperature>0)||!(barePerFace>0)||!(film>0)||!(capacity>0)||!(natural>0&&natural<1)||!Double.isFinite(temperature+barePerFace+film+capacity))
                throw new IllegalArgumentException("Invalid air side");
        }
        /** The air with the louvers open and the fans at {@code fans} of full speed, a share of full air: natural draught at 0. */
        public double draught(double fans) {
            if(!(fans>=0&&fans<=1))throw new IllegalArgumentException("Invalid fan share "+fans);
            return natural+(1-natural)*fans;
        }
        /**
         * The films on the bare tube, per phase, with {@code draught} of full air through the bank (plan section 4): the
         * stream sees the air through UA_eff = C (1 − e^(−UA/C)) per m² of face, with UA the process film
         * ({@link HeatCurve.Films#DEFAULT}) on the bare tube in series with the air's film on the fins, and C the air's
         * capacity rate; the air's film and capacity scale with its flow. Shut louvers leave the films' limit at no air,
         * which is zero: they are kept just above it, as a curve's films are positive, and move no heat a march can see.
         */
        public HeatCurve.Films films(double draught) {
            if(!(draught>=0&&draught<=1))throw new IllegalArgumentException("Invalid air share "+draught);
            double share=Math.max(draught,SHUT),air=film*share,rate=capacity*share;
            var process=HeatCurve.Films.DEFAULT;
            return new HeatCurve.Films(effective(process.liquid(),air,rate),effective(process.twoPhase(),air,rate),effective(process.gas(),air,rate));
        }
        private double effective(double process,double air,double rate) {
            double conductance=1/(1/(barePerFace*process)+1/air);
            return -rate*DeterministicMath.expm1(-conductance/rate)/barePerFace;
        }
    }
    /** The air share the films take for shut louvers: a curve's films are positive. */
    private static final double SHUT=1e-12;
    public AirCooled {
        Objects.requireNonNull(air);Objects.requireNonNull(limit);
        if(!(area>0)||!Double.isFinite(area)||!(draught>=0&&draught<=1))throw new IllegalArgumentException("Invalid air cooler side");
    }
    /** The facts that shape the side's equations, without its actuator. */
    public record Shape(double area,Air air,HeatControl control) {}
    @Override public Shape shape(){return new Shape(area,air,control);}
    @Override public double coldest(){return air.temperature();}
    @Override public double hottest(){return air.temperature();}
    @Override public HeatCurve.Films films(){return air.films(draught);}
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0||draught==0)return 0;
        return mass*(curve.march(inlet,mass,area,air.temperature())-inlet);
    }
    /** The fans' share of full speed: still until the louvers stand open. */
    public double fans(){return Math.max(0,(draught-air.natural())/(1-air.natural()));}
    /** The louvers' opening, 0 shut to 1 open: open once the fans turn. */
    public double louvers(){return Math.min(1,draught/air.natural());}
    public AirCooled withDraught(double value,HeatControl.Limit why){return new AirCooled(area,air,value,control,why);}
    /**
     * This side with its louvers and fans planned for a step that starts drawing {@code massFlow} (kg/s) at {@code inlet}
     * (J/kg) on {@code curve}, a sampled curve with this side's films ({@link HeatControl#setting}: each trial share re-films
     * the curve without a flash). In manual the player's fans hold with the louvers open. Itself without a controller.
     */
    public AirCooled planned(HeatCurve curve,double inlet,double massFlow) {
        if(control==null)return this;
        double mass=Math.abs(massFlow);
        var setting=control.setting(u->u==0?0:mass*(curve.withFilms(air.films(u)).march(inlet,mass,area,air.temperature())-inlet),curve,inlet,mass,draught);
        return withDraught(control.target()==HeatControl.Target.MANUAL?air.draught(setting.effort()):setting.effort(),setting.limit());
    }
}
