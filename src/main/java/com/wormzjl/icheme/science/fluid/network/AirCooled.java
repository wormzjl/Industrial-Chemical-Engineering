package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * An air cooler's process side (batch 2026-10-27-heat-exchanger-design, plan section 4, W5; its own law since W4): the
 * stream passes {@code area} m² of bare tube against air at a constant temperature, every tube row meeting fresh air, under
 * the effective films the {@code air} gives at the {@code fans} share of full speed. The fans are its actuator: a slice
 * input the player sets ({@code control} null or manual) or its controller plans for each step ({@link #planned}), as a
 * fired coil's firing is. Its heat is one exact march against the flat air temperature: no unknown, zero at no flow.
 *
 * @param limit why the planned fans stand where they do ({@link HeatControl.Limit#NONE} unplanned)
 */
public record AirCooled(double area,Air air,double fans,@Nullable HeatControl control,HeatControl.Limit limit) implements HeatLaw {
    /**
     * The air of an air cooler's bank, per m² of its bundle face: at {@code temperature} K, over {@code barePerFace} m² of
     * bare tube; at full fans the air's film on the fins ({@code film}, W/K) and its heat capacity rate ({@code capacity},
     * W/K); natural draught moves the share {@code natural} of full air.
     */
    public record Air(double temperature,double barePerFace,double film,double capacity,double natural) {
        public Air {
            if(!(temperature>0)||!(barePerFace>0)||!(film>0)||!(capacity>0)||!(natural>0&&natural<=1)||!Double.isFinite(temperature+barePerFace+film+capacity))
                throw new IllegalArgumentException("Invalid air side");
        }
        /**
         * The films on the bare tube, per phase, with the fans at {@code fans} of full speed (plan section 4): the stream
         * sees the air through UA_eff = C (1 − e^(−UA/C)) per m² of face, with UA the process film ({@link HeatCurve.Films#DEFAULT})
         * on the bare tube in series with the air's film on the fins, and C the air's capacity rate; the air's film and
         * capacity scale with its flow, the share {@link #natural} of full with the fans still.
         */
        public HeatCurve.Films films(double fans) {
            if(!(fans>=0&&fans<=1))throw new IllegalArgumentException("Invalid fan share "+fans);
            double draught=natural+(1-natural)*fans,air=film*draught,rate=capacity*draught;
            var process=HeatCurve.Films.DEFAULT;
            return new HeatCurve.Films(effective(process.liquid(),air,rate),effective(process.twoPhase(),air,rate),effective(process.gas(),air,rate));
        }
        private double effective(double process,double air,double rate) {
            double conductance=1/(1/(barePerFace*process)+1/air);
            return -rate*DeterministicMath.expm1(-conductance/rate)/barePerFace;
        }
    }
    public AirCooled {
        Objects.requireNonNull(air);Objects.requireNonNull(limit);
        if(!(area>0)||!Double.isFinite(area)||!(fans>=0&&fans<=1))throw new IllegalArgumentException("Invalid air cooler side");
    }
    /** The facts that shape the side's equations, without its fans. */
    public record Shape(double area,Air air,HeatControl control) {}
    @Override public Shape shape(){return new Shape(area,air,control);}
    @Override public double coldest(){return air.temperature();}
    @Override public double hottest(){return air.temperature();}
    @Override public HeatCurve.Films films(){return air.films(fans);}
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0)return 0;
        return mass*(curve.march(inlet,mass,area,air.temperature())-inlet);
    }
    public AirCooled withFans(double value,HeatControl.Limit why){return new AirCooled(area,air,value,control,why);}
    /**
     * This side with its fans planned for a step that starts drawing {@code massFlow} (kg/s) at {@code inlet} (J/kg) on
     * {@code curve}, a sampled curve with this side's films ({@link HeatControl#setting}: each trial share re-films the curve
     * without a flash). Itself without a controller.
     */
    public AirCooled planned(HeatCurve curve,double inlet,double massFlow) {
        if(control==null)return this;
        double mass=Math.abs(massFlow);
        var setting=control.setting(u->mass*(curve.withFilms(air.films(u)).march(inlet,mass,area,air.temperature())-inlet),curve,inlet,mass,fans);
        return withFans(setting.effort(),setting.limit());
    }
}
