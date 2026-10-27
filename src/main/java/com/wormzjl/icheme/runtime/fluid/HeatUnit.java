package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.HeatLaw;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The facts of one side of a heat-exchanging unit (batch 2026-10-27-heat-exchanger-design, plan sections 5.1, 6 and 9),
 * carried by that side's registration ({@link PhysicalFluidTopology.Device#heat}). The unit's sides are placed, edited
 * and removed in one topology event, so the pair stays whole; registrations are the topology unit's facts, so a pair
 * whose sides sit in two islands is recorded once, world-wide.
 *
 * @param unit         what the side belongs to
 * @param side         which passage of the unit it is
 * @param area         its heat-transfer surface, m² (the unit's tube surface; the drawn datasheet's)
 * @param partner      the other side's registration (an exchanger's other side, a fired heater's other coil), or 0 for a
 *                     one-sided unit (an air cooler)
 * @param control      the side's built-in control (plan section 6)
 * @param transitJoule the heat this side's unit holds in transit between its two sides (plan section 5.2), J
 */
public record HeatUnit(HeatSides.Unit unit,HeatSides.Side side,double area,long partner,Control control,double transitJoule) {
    /** The control modes of plan section 6. {@code OFF}: no controller, so the manual {@link Control#share} holds. */
    public enum Mode { OFF, OUTLET_TEMPERATURE, DUTY, FAN, FIRING }
    /**
     * @param mode     what the controller holds
     * @param target   the held value: K for an outlet or set temperature, W for a duty
     * @param lowLimit the cooled outlet's lowest temperature, K, or 0 for none (plan section 6, the freeze guard)
     * @param share    the manual setting of the side's actuator, 0 to 1, which holds while no controller acts: an
     *                 exchanger side's bypass share, an air cooler's fans (0 standing still, 1 at full speed), a fired
     *                 heater's burners (0 out, 1 firing up to the duty)
     */
    public record Control(Mode mode,double target,double lowLimit,double share) {
        /** No controller, no bypass, no fans. */
        public static final Control OFF=new Control(Mode.OFF,0,0,0);
        public Control {
            Objects.requireNonNull(mode);
            if(!Double.isFinite(target)||!Double.isFinite(lowLimit)||lowLimit<0||!Double.isFinite(share)||share<0||share>1)throw new IllegalArgumentException("Invalid heat unit control");
        }
        public Control withShare(double value){return new Control(mode,target,lowLimit,value);}
    }
    public HeatUnit {
        Objects.requireNonNull(unit);Objects.requireNonNull(side);Objects.requireNonNull(control);
        if(!Double.isFinite(area)||area<=0||partner<0||!Double.isFinite(transitJoule))throw new IllegalArgumentException("Invalid heat unit side");
        // A fired heater's coil fires towards its set point (owner Q11): its law needs one.
        if(unit==HeatSides.Unit.FIRED_HEATER&&(control.mode()!=Mode.FIRING||!(control.target()>0)))throw new IllegalArgumentException("A fired heater's coil fires to a set point");
    }
    /** A fired heater's set point when built, K: 300 °C, its burners lit. */
    public static final Control FIRING=new Control(Mode.FIRING,573.15,0,1);
    /** A newly built side: control off, an air cooler's fans running, a fired heater firing to {@link #FIRING}, nothing in transit. */
    public HeatUnit(HeatSides.Unit unit,HeatSides.Side side,double area,long partner){this(unit,side,area,partner,switch(unit){case AIR_COOLER->Control.OFF.withShare(1);case FIRED_HEATER->FIRING;default->Control.OFF;},0);}
    public HeatUnit withPartner(long id){return new HeatUnit(unit,side,area,id,control,transitJoule);}
    /** The same side over {@code value} m²: a datasheet whose surface law changed. */
    public HeatUnit withArea(double value){return new HeatUnit(unit,side,value,partner,control,transitJoule);}
    public HeatUnit withControl(Control value){return new HeatUnit(unit,side,area,partner,value,transitJoule);}
    /** Whether the side has fans the player switches (an air cooler's process side). */
    public boolean fans(){return unit==HeatSides.Unit.AIR_COOLER;}
    /** Whether the side is a fired heater's coil, whose burners and set point the player sets for both coils at once. */
    public boolean burners(){return unit==HeatSides.Unit.FIRED_HEATER;}
    /** The heat law this side moves heat through on its own ({@link HeatSides#heatSide}), or null where it waits for its partner. */
    public @Nullable HeatLaw heatSide(){return HeatSides.heatSide(unit,side,area,control.share(),control.target());}
}
