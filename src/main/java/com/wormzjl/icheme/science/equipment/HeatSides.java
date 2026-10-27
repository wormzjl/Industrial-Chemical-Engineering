package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The heat-exchanging sides of a unit (batch 2026-10-27-heat-exchanger-design, plan sections 5.1 and 10): each a passage
 * between its inlet and outlet nozzles, registered as one fluid device of its own. An exchanger has a tube and a shell
 * side; the air cooler (W5), the fired heaters (W6) and the kettle (W7) add theirs.
 */
public final class HeatSides {
    private HeatSides() {}
    public enum Unit { EXCHANGER, KETTLE, AIR_COOLER, FIRED_HEATER }
    public enum Side { TUBE, SHELL, PROCESS, CONVECTION, RADIANT }
    /**
     * One side: its nozzles of role {@code inlet} and {@code outlet}, its heat-transfer surface (m²) and its passage.
     */
    public record Definition(Side side,EquipmentShape.Role inlet,EquipmentShape.Role outlet,double area,PipeResistance.Geometry passage) {}
    /** Drawn tubing's roughness, m: an exchanger's tubes and the passages between them. */
    public static final double TUBE_ROUGHNESS=1.5e-6;

    /** The unit a shape kind's sides belong to, or null for a kind without heat sides. */
    public static @Nullable Unit unit(EquipmentShape.Kind kind) {
        return kind==EquipmentShape.Kind.EXCHANGER?Unit.EXCHANGER:null;
    }
    /** {@code shape}'s sides in registration order (the first one's node is the structure's fluid cell); none for other kinds. */
    public static List<Definition> of(EquipmentShape shape) {
        if(unit(shape.kind())!=Unit.EXCHANGER)return List.of();
        double d=shape.diameter(),l=shape.tangentLength(),area=ProcessUnit.tubeSurface(d,l);
        return List.of(new Definition(Side.TUBE,EquipmentShape.Role.TUBE_INLET,EquipmentShape.Role.TUBE_OUTLET,area,tubePassage(d,l)),
                new Definition(Side.SHELL,EquipmentShape.Role.SHELL_INLET,EquipmentShape.Role.SHELL_OUTLET,area,shellPassage(d,l)));
    }
    /**
     * An exchanger's tube side, shell {@code shell} and tubes {@code tubes} m: 630 D² tubes per pass of 14.8 mm bore (the
     * tube surface 75 D² L on 19.05 mm tubes, two passes), the two passes' length, and 4 velocity heads per pass for
     * entry, return and exit. The plan's example (1 m, 6 m) loses 3.8 kPa at 50 kg/s of water, 33 kPa at 1.5 m/s.
     */
    public static PipeResistance.Geometry tubePassage(double shell,double tubes) {
        return new PipeResistance.Geometry(2*tubes,.0148,TUBE_ROUGHNESS,8,channels(630*shell*shell));
    }
    /**
     * An exchanger's shell side: an equivalent passage of 18 mm hydraulic diameter and 0.1 D² crossflow area, one shell
     * diameter per baffle crossing with baffles 0.4 D apart, so 2.5 L long.
     */
    public static PipeResistance.Geometry shellPassage(double shell,double tubes) {
        return new PipeResistance.Geometry(2.5*tubes,.018,TUBE_ROUGHNESS,0,channels(.1*shell*shell/(Math.PI*.018*.018/4)));
    }
    private static int channels(double count){return (int)Math.max(1,Math.round(count));}
}
