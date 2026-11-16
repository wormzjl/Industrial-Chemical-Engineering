package com.wormzjl.icheme.science.fluid.network;

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
 * @param fanLimit the most of full speed the fans may turn at this slice (batch 2026-11-03-rotating-machinery W5: 0 when
 *                 the cooler's electric store runs empty, 1 otherwise): a dispatch input ({@link ShaftPlan}), not a fact; the
 *                 louvers stay open, so the air falls to natural draught, and a controller short of its target reports it
 * @param conductance the bank's UA as the step's plan read it ({@link #conductance(HeatCurve,double,double)}, decision D16 of
 *                    batch 2026-11-16-fouling-corrosion), null where none was planned: a reading for the heat page, not an
 *                    input to the side's equations, so it takes no part in {@link #equals}
 */
public record AirCooled(double area,Air air,double draught,@Nullable HeatControl control,HeatControl.Limit limit,double fanLimit,@Nullable ExchangerRelay.Conductance conductance) implements HeatLaw {
    /**
     * The air of an air cooler's bank, per m² of its bundle face: at {@code temperature} K, over {@code barePerFace} m² of
     * bare tube; at full fans the air's film on the fins ({@code film}, W/K) and its heat capacity rate ({@code capacity},
     * W/K); natural draught moves the share {@code natural} of full air. {@code process} are the films inside the tubes
     * ({@link HeatCurve.Films#DEFAULT} for a clean bundle; batch 2026-11-16-fouling-corrosion: behind its deposit,
     * {@link #fouled}).
     */
    public record Air(double temperature,double barePerFace,double film,double capacity,double natural,HeatCurve.Films process) {
        public Air(double temperature,double barePerFace,double film,double capacity,double natural){this(temperature,barePerFace,film,capacity,natural,HeatCurve.Films.DEFAULT);}
        public Air {
            Objects.requireNonNull(process);
            if(process.outer()!=null)throw new IllegalArgumentException("An air cooler's process films have no outer side");
            if(!(temperature>0)||!(barePerFace>0)||!(film>0)||!(capacity>0)||!(natural>0&&natural<1)||!Double.isFinite(temperature+barePerFace+film+capacity))
                throw new IllegalArgumentException("Invalid air side");
        }
        /** This air against tubes fouled by a deposit of {@code rf} m²K/W inside ({@link HeatCurve.Films#fouled}); itself at 0. */
        public Air fouled(double rf){var films=process.fouled(rf);return films==process?this:new Air(temperature,barePerFace,film,capacity,natural,films);}
        /** The air with the louvers open and the fans at {@code fans} of full speed, a share of full air: natural draught at 0. */
        public double draught(double fans) {
            if(!(fans>=0&&fans<=1))throw new IllegalArgumentException("Invalid fan share "+fans);
            return natural+(1-natural)*fans;
        }
        /**
         * The films on the bare tube, per phase, with {@code draught} of full air through the bank (plan section 4): the
         * stream sees the air through UA_eff = C (1 − e^(−UA/C)) per m² of face, with UA the process film
         * ({@link #process}) on the bare tube in series with the air's film on the fins, and C the air's
         * capacity rate; the air's film and capacity scale with its flow. Shut louvers leave the films' limit at no air,
         * which is zero: they are kept just above it, as a curve's films are positive, and move no heat a march can see.
         * The films returned are the {@link #process} films with this air as their {@link HeatCurve.Films.Outer}, so a
         * liquid film's resistance (decisions D13 and D15 of batch 2026-11-16-fouling-corrosion) lies inside the tubes; the wall's
         * effective films are {@link HeatCurve.Films#wallLiquid} and its siblings.
         */
        public HeatCurve.Films films(double draught) {
            if(!(draught>=0&&draught<=1))throw new IllegalArgumentException("Invalid air share "+draught);
            double share=Math.max(draught,SHUT),air=film*share,rate=capacity*share;
            return process.withOuter(new HeatCurve.Films.Outer(barePerFace,air,rate));
        }
    }
    /** The air share the films take for shut louvers: a curve's films are positive. */
    private static final double SHUT=1e-12;
    public AirCooled(double area,Air air,double draught,@Nullable HeatControl control,HeatControl.Limit limit){this(area,air,draught,control,limit,1);}
    public AirCooled(double area,Air air,double draught,@Nullable HeatControl control,HeatControl.Limit limit,double fanLimit){this(area,air,draught,control,limit,fanLimit,null);}
    public AirCooled {
        Objects.requireNonNull(air);Objects.requireNonNull(limit);
        if(!(area>0)||!Double.isFinite(area)||!(draught>=0&&draught<=1)||!(fanLimit>=0&&fanLimit<=1))throw new IllegalArgumentException("Invalid air cooler side");
    }
    /** The air share the bank moves: its draught, at most what fans at {@link #fanLimit} with the louvers open give. */
    public double airShare(){return Math.min(draught,air.draught(fanLimit));}
    /** The facts that shape the side's equations, without its actuator. */
    public record Shape(double area,Air air,HeatControl control) {}
    @Override public Shape shape(){return new Shape(area,air,control);}
    @Override public double coldest(){return air.temperature();}
    @Override public double hottest(){return air.temperature();}
    @Override public HeatCurve.Films films(){return air.films(airShare());}
    @Override public double heat(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);
        if(mass==0||airShare()==0)return 0;
        return mass*(curve.march(inlet,mass,area,air.temperature())-inlet);
    }
    /** The fans' share of full speed: still until the louvers stand open. */
    public double fans(){return Math.max(0,(airShare()-air.natural())/(1-air.natural()));}
    /** The louvers' opening, 0 shut to 1 open: open once the fans turn. */
    public double louvers(){return Math.min(1,airShare()/air.natural());}
    public AirCooled withDraught(double value,HeatControl.Limit why){return new AirCooled(area,air,value,control,why,fanLimit,conductance);}
    /** This side with its fans held at most at {@code value} of full speed (W5's dispatch input). */
    public AirCooled withFanLimit(double value){return value==fanLimit?this:new AirCooled(area,air,draught,control,limit,value,conductance);}
    /** This side with the bank's UA as planned, {@code value} (null for none). */
    public AirCooled withConductance(@Nullable ExchangerRelay.Conductance value){return value==conductance?this:new AirCooled(area,air,draught,control,limit,fanLimit,value);}
    /** The pieces of the bank's surface its UA is summed over ({@link #conductance(HeatCurve,double,double)}). */
    public static final int PIECES=8;
    /**
     * The bank's UA, W/K, for a stream drawing {@code massFlow} (kg/s) at {@code inlet} (J/kg) on {@code curve}, a sampled
     * curve of its stream (decision D16 of batch 2026-11-16-fouling-corrosion): the films its march uses, the process film
     * inside the tubes in series with the air at the bank's air share ({@link #films}, {@link HeatCurve.Films.Outer}), summed
     * over {@link #PIECES} equal pieces of its surface, each at the film of the mean of the specific enthalpies the stream
     * enters and leaves it with; its clean UA the same with the clean process films ({@link HeatCurve.Films#clean}). No
     * flash: the curve is re-filmed. Null without flow.
     */
    public @Nullable ExchangerRelay.Conductance conductance(HeatCurve curve,double inlet,double massFlow) {
        double mass=Math.abs(massFlow);if(mass==0)return null;
        var films=films();
        return new ExchangerRelay.Conductance(bank(curve.withFilms(films),inlet,mass),bank(curve.withFilms(films.clean()),inlet,mass));
    }
    private double bank(HeatCurve curve,double inlet,double mass) {
        double piece=area/PIECES,h=inlet,ua=0;
        for(int k=0;k<PIECES;k++){double out=curve.march(h,mass,piece,air.temperature());ua+=piece*curve.filmAt((h+out)/2);h=out;}
        return ua;
    }
    @Override public boolean equals(Object other) {
        return other instanceof AirCooled c&&Double.compare(area,c.area)==0&&air.equals(c.air)&&Double.compare(draught,c.draught)==0&&java.util.Objects.equals(control,c.control)
                &&limit==c.limit&&Double.compare(fanLimit,c.fanLimit)==0;
    }
    @Override public int hashCode(){return Objects.hash(area,air,draught,control,limit,fanLimit);}
    /**
     * This side with its louvers and fans planned for a step that starts drawing {@code massFlow} (kg/s) at {@code inlet}
     * (J/kg) on {@code curve}, a sampled curve with this side's films ({@link HeatControl#setting}: each trial share re-films
     * the curve without a flash). In manual the player's fans hold with the louvers open. Itself without a controller.
     */
    public AirCooled planned(HeatCurve curve,double inlet,double massFlow) {
        if(control==null)return this;
        double mass=Math.abs(massFlow);
        // Fans without power (W5) cap the effort at the louvers' natural draught: a target past it stands at its highest.
        double most=air.draught(fanLimit);
        var setting=control.setting(u->{double a=Math.min(u,most);return a==0?0:mass*(curve.withFilms(air.films(a)).march(inlet,mass,area,air.temperature())-inlet);},curve,inlet,mass,draught);
        double effort=control.target()==HeatControl.Target.MANUAL?air.draught(setting.effort()):setting.effort();
        var why=setting.limit();
        if(effort>most){effort=most;if(why==HeatControl.Limit.NONE&&control.target()!=HeatControl.Target.MANUAL)why=HeatControl.Limit.HIGHEST;}
        return withDraught(effort,why);
    }
}
