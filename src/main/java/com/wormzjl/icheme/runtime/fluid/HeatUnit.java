package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.HeatControl;
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
 * @param partner      the other side's registration (an exchanger's other side, a fired heater's other coil, a kettle's
 *                     tubes and shell, W7), or 0 for a one-sided unit (an air cooler)
 * @param control      the side's built-in control (plan section 6)
 * @param transitJoule the heat this side's unit holds in transit between its two sides (plan section 5.2), J
 */
public record HeatUnit(HeatSides.Unit unit,HeatSides.Side side,double area,long partner,Control control,double transitJoule) {
    /**
     * The control modes of plan section 6. {@code OFF}: no controller, so the manual {@link Control#share} holds.
     * {@code OUTLET_TEMPERATURE} and {@code DUTY} act on the unit's own actuator (W4): an exchanger side's or a kettle's tubes'
     * bypass (steam that leaves uncondensed), an air cooler's fans. {@code FIRING} is a fired heater's coil, firing to its set point (W6).
     * {@code RATIO} is an exchanger side's feed held at its target times the other side's feed, by a valve on its feed (W8):
     * it needs the exchanger's lines ({@link #lines}), and holds nothing until they are complete.
     */
    public enum Mode { OFF, OUTLET_TEMPERATURE, DUTY, FIRING, RATIO }
    /**
     * @param mode     what the controller holds
     * @param target   the held value: K for an outlet or set temperature, W for a duty, the feed's share of the other side's for a ratio
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
            if(!Double.isFinite(target)||!Double.isFinite(lowLimit)||lowLimit<0||!Double.isFinite(share)||share<0||share>1
                    ||mode==Mode.OUTLET_TEMPERATURE&&!(target>0)||mode==Mode.DUTY&&target<0||mode==Mode.RATIO&&!(target>0))throw new IllegalArgumentException("Invalid heat unit control");
        }
        public Control withShare(double value){return new Control(mode,target,lowLimit,value);}
    }
    public HeatUnit {
        Objects.requireNonNull(unit);Objects.requireNonNull(side);Objects.requireNonNull(control);
        if(!Double.isFinite(area)||area<=0||partner<0||!Double.isFinite(transitJoule))throw new IllegalArgumentException("Invalid heat unit side");
        // A fired heater's coil fires towards its set point (owner Q11): its law needs one.
        if(unit==HeatSides.Unit.FIRED_HEATER&&(control.mode()!=Mode.FIRING||!(control.target()>0)))throw new IllegalArgumentException("A fired heater's coil fires to a set point");
        if(unit!=HeatSides.Unit.FIRED_HEATER&&control.mode()==Mode.FIRING)throw new IllegalArgumentException("Only a fired heater's coil fires");
        if(unit!=HeatSides.Unit.EXCHANGER&&control.mode()==Mode.RATIO)throw new IllegalArgumentException("Only an exchanger's side holds a ratio");
    }
    /** A fired heater's set point when built, K: 300 °C, its burners lit. */
    public static final Control FIRING=new Control(Mode.FIRING,573.15,0,1);
    /** A newly built side: control off, an air cooler's fans running, a fired heater firing to {@link #FIRING}, nothing in transit. */
    public HeatUnit(HeatSides.Unit unit,HeatSides.Side side,double area,long partner){this(unit,side,area,partner,switch(unit){case AIR_COOLER->Control.OFF.withShare(1);case FIRED_HEATER->FIRING;default->Control.OFF;},0);}
    public HeatUnit withPartner(long id){return new HeatUnit(unit,side,area,id,control,transitJoule);}
    /** The same side over {@code value} m²: a datasheet whose surface law changed. */
    public HeatUnit withArea(double value){return new HeatUnit(unit,side,value,partner,control,transitJoule);}
    public HeatUnit withControl(Control value){return new HeatUnit(unit,side,area,partner,value,transitJoule);}
    public HeatUnit withTransit(double joule){return new HeatUnit(unit,side,area,partner,control,joule);}
    /** Whether the side has fans the player switches (an air cooler's process side). */
    public boolean fans(){return unit==HeatSides.Unit.AIR_COOLER;}
    /** Whether the side is a fired heater's coil, whose burners and set point the player sets for both coils at once. */
    public boolean burners(){return unit==HeatSides.Unit.FIRED_HEATER;}
    /** The heat law this side moves heat through ({@link HeatSides#heatSide}; an exchanger's against its partner's connection), or null where it has none. */
    public @Nullable HeatLaw heatSide(){return HeatSides.heatSide(unit,side,area,control.share(),control.target(),partner==0?0:PhysicalFluidTopology.sideIdentity(partner),controller());}
    /**
     * The side's built-in controller as its heat law reads it (W4, plan section 6), or null for none: an exchanger side's or
     * an air cooler's control with a target or a low limit. Its effort is the air cooler's fans, or an exchanger side's
     * share through its cells (1 − the bypass share).
     */
    public @Nullable HeatControl controller() {
        if(!controllable())return null;
        double manual=unit==HeatSides.Unit.AIR_COOLER?control.share():1-control.share();
        return switch(control.mode()) {
            // A ratio works the feed valve, not the heat: the side's bypass holds its manual share.
            case OFF,RATIO->control.lowLimit()>0?new HeatControl(HeatControl.Target.MANUAL,0,control.lowLimit(),manual):null;
            case OUTLET_TEMPERATURE->new HeatControl(HeatControl.Target.OUTLET_TEMPERATURE,control.target(),control.lowLimit(),manual);
            case DUTY->new HeatControl(HeatControl.Target.DUTY,control.target(),control.lowLimit(),manual);
            case FIRING->null;
        };
    }
    /** Whether the player sets this side's control (W4): an exchanger side's, an air cooler's and a kettle's tubes' (W7). */
    public boolean controllable(){return unit==HeatSides.Unit.EXCHANGER||unit==HeatSides.Unit.AIR_COOLER||unit==HeatSides.Unit.KETTLE&&side==HeatSides.Side.TUBE;}
    /**
     * The real lines this side's control asks for (W8, plan section 7): an exchanger side held at an outlet temperature or
     * a duty has a bypass, one held at a ratio a valve on its feed; every other side none. Its exchanger validates them as
     * an assembly; until they are complete, a bypass control works the side's internal bypass and a ratio holds nothing.
     */
    public com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines lines() {
        if(unit!=HeatSides.Unit.EXCHANGER)return com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.NONE;
        return switch(control.mode()) {
            case OUTLET_TEMPERATURE,DUTY->com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.BYPASS;
            case RATIO->com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.RATIO;
            default->com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.NONE;
        };
    }
    /** Whether this is a kettle's shell (W7): a vessel whose pool is its tubes' other side, no passage of its own. */
    public boolean pool(){return unit==HeatSides.Unit.KETTLE&&side==HeatSides.Side.SHELL;}
}
