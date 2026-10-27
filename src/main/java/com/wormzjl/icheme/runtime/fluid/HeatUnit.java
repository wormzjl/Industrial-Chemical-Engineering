package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.HeatSides;
import java.util.Objects;

/**
 * The facts of one side of a heat-exchanging unit (batch 2026-10-27-heat-exchanger-design, plan sections 5.1, 6 and 9),
 * carried by that side's registration ({@link PhysicalFluidTopology.Device#heat}). The unit's sides are placed, edited
 * and removed in one topology event, so the pair stays whole; registrations are the topology unit's facts, so a pair
 * whose sides sit in two islands is recorded once, world-wide.
 *
 * @param unit         what the side belongs to
 * @param side         which passage of the unit it is
 * @param area         its heat-transfer surface, m² (the unit's tube surface; the drawn datasheet's)
 * @param partner      the other side's registration, or 0 for a one-sided unit (an air cooler, a fired heater's coil)
 * @param control      the side's built-in control (plan section 6)
 * @param transitJoule the heat this side's unit holds in transit between its two sides (plan section 5.2), J
 */
public record HeatUnit(HeatSides.Unit unit,HeatSides.Side side,double area,long partner,Control control,double transitJoule) {
    /** The control modes of plan section 6. {@code OFF}: no bypass, no target. */
    public enum Mode { OFF, BYPASS, OUTLET_TEMPERATURE, DUTY, FAN, FIRING }
    /**
     * @param mode     what the controller holds
     * @param target   the held value: K for an outlet or set temperature, W for a duty, the share for a manual bypass
     * @param lowLimit the cooled outlet's lowest temperature, K, or 0 for none (plan section 6, the freeze guard)
     * @param share    the manual bypass share, 0 to 1
     */
    public record Control(Mode mode,double target,double lowLimit,double share) {
        public static final Control OFF=new Control(Mode.OFF,0,0,0);
        public Control {
            Objects.requireNonNull(mode);
            if(!Double.isFinite(target)||!Double.isFinite(lowLimit)||lowLimit<0||!Double.isFinite(share)||share<0||share>1)throw new IllegalArgumentException("Invalid heat unit control");
        }
    }
    public HeatUnit {
        Objects.requireNonNull(unit);Objects.requireNonNull(side);Objects.requireNonNull(control);
        if(!Double.isFinite(area)||area<=0||partner<0||!Double.isFinite(transitJoule))throw new IllegalArgumentException("Invalid heat unit side");
    }
    /** A newly built side: control off, nothing in transit. */
    public HeatUnit(HeatSides.Unit unit,HeatSides.Side side,double area,long partner){this(unit,side,area,partner,Control.OFF,0);}
    public HeatUnit withPartner(long id){return new HeatUnit(unit,side,area,id,control,transitJoule);}
    /** The same side over {@code value} m²: a datasheet whose surface law changed. */
    public HeatUnit withArea(double value){return new HeatUnit(unit,side,value,partner,control,transitJoule);}
}
