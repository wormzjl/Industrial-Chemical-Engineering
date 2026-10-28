package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.AirCooled;
import com.wormzjl.icheme.science.fluid.network.ConvectionCoil;
import com.wormzjl.icheme.science.fluid.network.FiredCoil;
import com.wormzjl.icheme.science.fluid.network.HeatControl;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.network.HeatLaw;
import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The heat-exchanging sides of a unit (batch 2026-10-27-heat-exchanger-design, plan sections 4, 5.1 and 10): each a
 * passage between its inlet and outlet nozzles, registered as one fluid device of its own. An exchanger has a tube and a
 * shell side, an air cooler its process side, a fired heater its two coils (W6), a kettle its tubes (W7). A kettle's shell is
 * no passage: it registers as a vessel after the sides ({@link #pool}), its pool the tubes' other side.
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
    /** The air an air cooler's fans blow through its bundle, K: a constant 25 °C (owner Q11), no weather, biome or altitude. */
    public static final double AIR_TEMPERATURE=298.15;
    /**
     * An air cooler per m² of bundle face (plan section 4): 5 m² of bare tube (four rows of 1" finned tubes), 50 m² of
     * finned surface (fin ratio 10) under a 100 W/m²K gas film, and at full fans air at 3 m/s, whose heat capacity rate is
     * 3.5 kW/K and which 1 kW of fan power drives (3 m³/s through 200 Pa at 60 %).
     */
    static final double BARE_PER_FACE=5,FINNED_PER_FACE=50,AIR_FILM=100,AIR_CAPACITY=3500,FAN_POWER=1000;
    /** An air cooler's tubes, m: 1" outside, 2.1 mm wall (BWG 14). */
    static final double AIR_COOLER_TUBE=.0254,AIR_COOLER_BORE=.0212;
    /** Natural draught through a bay whose fans stand still: the air's capacity and film of full fans times this. */
    static final double NATURAL_DRAUGHT=.25;
    /** An air cooler's air per m² of bundle face, as its process side's law reads it ({@link AirCooled}). */
    public static final AirCooled.Air AIR=new AirCooled.Air(AIR_TEMPERATURE,BARE_PER_FACE,AIR_FILM*FINNED_PER_FACE,AIR_CAPACITY,NATURAL_DRAUGHT);
    /**
     * A fired heater's convection coil's mean flux, kW/m² of bare tube (API 560 practice: 10 to 20 for finned and shock
     * tubes), against the radiant coil's {@link ProcessUnit#RADIANT_FLUX}: the coils take 30 % and 70 % of the duty
     * ({@link ProcessUnit#RADIANT_SHARE}).
     */
    static final double CONVECTION_FLUX=15;
    /**
     * A convection coil's overall coefficient against the flue gas, W/m²K of bare tube ({@link ConvectionCoil}): it gives the
     * coils' 70/30 design split at a 250 °C crude inlet (about 15 kW/m² over a 270 K mean difference, as studded and
     * finned convection tubes run; the owner's test of 2026-10-28, OWNER_TEST_REVIEW.md section 1).
     */
    static final double CONVECTION_U=54.9;
    /** A fired heater's coil tubes, m: 6" schedule 80, the tubes {@link ProcessUnit#heaterDuty} counts. */
    static final double HEATER_TUBE=.168,HEATER_BORE=.146;
    /** A coil's duty per parallel pass, MW (about 2 m/s of oil per pass), its straight tube between return bends, m, and a bend's velocity heads. */
    static final double PASS_DUTY=8,HEATER_STRAIGHT=10,RETURN_BEND=.5;

    /** The unit a shape kind's sides belong to, or null for a kind without heat sides. */
    public static @Nullable Unit unit(EquipmentShape.Kind kind) {
        return switch(kind){case EXCHANGER->Unit.EXCHANGER;case KETTLE->Unit.KETTLE;case AIR_COOLER->Unit.AIR_COOLER;case VERTICAL_HEATER,CABIN_HEATER->Unit.FIRED_HEATER;default->null;};
    }
    /**
     * Whether {@code kind}'s unit also registers a vessel, after its sides (W7, plan section 8): a kettle's shell, a reservoir
     * whose FEED, VAPOUR_OUTLET and LIQUID_OUTLET nozzles are its ports, as a drum's are, and whose pool is its tubes' other side.
     */
    public static boolean pool(EquipmentShape.Kind kind){return kind==EquipmentShape.Kind.KETTLE;}
    /**
     * A kettle's bundle across, m, in its shell of diameter {@code shell} (W7): the drawn bundle, centred on the shell's axis,
     * 1/1.6 of it ({@link ProcessUnit#kettleShell}, within the shell's rounding to 0.25 m). Its lowest tubes stand half the
     * difference over the shell's bottom; the pool's level wets it from there ({@link com.wormzjl.icheme.science.fluid.network.Pool#wetted}).
     */
    public static double kettleBundle(double shell){return shell/1.6;}
    /**
     * A kettle's shell as a vessel (W7): a lying drum of the kettle's diameter ({@link ProcessUnit#kettleShell}, 1.6 bundles)
     * over the drawn shell's cylinder, 0.9 L + 0.3 m ({@link ProcessUnit#kettle}), with two heads' volume for its back head
     * and front cone.
     */
    public static com.wormzjl.icheme.science.fluid.network.VesselGeometry kettleShell(double bundle,double tubes) {
        return com.wormzjl.icheme.science.fluid.network.VesselGeometry.round(com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.HORIZONTAL_DRUM,ProcessUnit.kettleShell(bundle),.9*tubes+.3,0,0);
    }
    /** {@code shape}'s sides in registration order (the first one's node is the structure's fluid cell); none for other kinds. */
    public static List<Definition> of(EquipmentShape shape) {
        var unit=unit(shape.kind());if(unit==null)return List.of();
        double d=shape.diameter(),l=shape.tangentLength();
        return switch(unit) {
            case EXCHANGER->{double area=ProcessUnit.tubeSurface(d,l);
                yield List.of(new Definition(Side.TUBE,EquipmentShape.Role.TUBE_INLET,EquipmentShape.Role.TUBE_OUTLET,area,tubePassage(d,l)),
                        new Definition(Side.SHELL,EquipmentShape.Role.SHELL_INLET,EquipmentShape.Role.SHELL_OUTLET,area,shellPassage(d,l)));}
            // A kettle's bundle (TEMA AKT): two tube passes as an exchanger's; its shell is a vessel ({@link #pool}).
            case KETTLE->List.of(new Definition(Side.TUBE,EquipmentShape.Role.TUBE_INLET,EquipmentShape.Role.TUBE_OUTLET,ProcessUnit.tubeSurface(d,l),tubePassage(d,l)));
            case AIR_COOLER->List.of(new Definition(Side.PROCESS,EquipmentShape.Role.PROCESS_INLET,EquipmentShape.Role.PROCESS_OUTLET,
                    BARE_PER_FACE*shape.bank().bays()*ProcessUnit.bundleFace(d,l),airCoolerPassage(d,l,shape.bank())));
            case FIRED_HEATER->{double duty=ProcessUnit.heaterDuty(shape.kind(),d,l);
                double convection=coilArea(Side.CONVECTION,duty),radiant=coilArea(Side.RADIANT,duty);
                yield List.of(new Definition(Side.CONVECTION,EquipmentShape.Role.CONVECTION_INLET,EquipmentShape.Role.CONVECTION_OUTLET,convection,heaterPassage(convection,duty)),
                        new Definition(Side.RADIANT,EquipmentShape.Role.RADIANT_INLET,EquipmentShape.Role.RADIANT_OUTLET,radiant,heaterPassage(radiant,duty)));}
            default->List.of();
        };
    }
    /** A fired heater coil's bare tube, m², for the heater's duty {@code duty} (MW): its share of the duty at its mean flux. */
    static double coilArea(Side coil,double duty) {
        return coil==Side.RADIANT?ProcessUnit.RADIANT_SHARE*duty*1000/ProcessUnit.RADIANT_FLUX:(1-ProcessUnit.RADIANT_SHARE)*duty*1000/CONVECTION_FLUX;
    }
    /** A coil's most heat, W, over its bare tube {@code area} (m²) at its mean flux, with the burners at {@code burners} of full firing. */
    public static double coilDuty(Side coil,double area,double burners) {
        return burners*area*1000*(coil==Side.RADIANT?ProcessUnit.RADIANT_FLUX:CONVECTION_FLUX);
    }
    /**
     * A fired heater coil's passage, over bare tube {@code area} m² of a heater of duty {@code duty} MW: 6" tubes in
     * parallel passes of {@link #PASS_DUTY} each, a return bend every {@link #HEATER_STRAIGHT} m of a pass. A 10 MW
     * heater's radiant coil, one pass of 380 m, loses about 1 bar of oil at 2 m/s.
     */
    public static PipeResistance.Geometry heaterPassage(double area,double duty) {
        int passes=channels(duty/PASS_DUTY);double length=area/(Math.PI*HEATER_TUBE)/passes;
        return new PipeResistance.Geometry(length,HEATER_BORE,PipeResistance.DEFAULT_ROUGHNESS_METRES,RETURN_BEND*Math.floor(length/HEATER_STRAIGHT),passes);
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
    /**
     * An air cooler bank's process side, bays {@code width} by {@code length} m: each bay's tubes in one pass (as many as
     * carry its 5 m² of bare tube per m² of face), the trains in parallel, the bays of a train in series, 1.5 velocity
     * heads per bay for its headers.
     */
    public static PipeResistance.Geometry airCoolerPassage(double width,double length,ProcessUnit.Bank bank) {
        double tubes=BARE_PER_FACE*(width-.22)/(Math.PI*AIR_COOLER_TUBE);
        return new PipeResistance.Geometry(bank.series()*length,AIR_COOLER_BORE,TUBE_ROUGHNESS,1.5*bank.series(),channels(bank.parallel()*Math.round(tubes)));
    }
    private static int channels(double count){return (int)Math.max(1,Math.round(count));}

    /** An exchanger's wall cells in counterflow (plan section 5.1: within 0.9 % of exact counterflow up to NTU 5 at Cr 0.5). */
    public static final int EXCHANGER_CELLS=8;
    /** {@link #heatSide(Unit,Side,double,double,double,long,HeatControl)} of a side without a partner or controller. */
    public static @Nullable HeatLaw heatSide(Unit unit,Side side,double area,double share,double target){return heatSide(unit,side,area,share,target,0,null);}
    /**
     * The heat law a side moves heat through, or null where it has none (an exchanger side without a partner, a kettle's
     * shell, whose pool rides on its vessel), with its actuator at {@code share}, its set point {@code target} (K) and its
     * built-in {@code control} (W4, plan section 6; null for none), which sets an exchanger side's bypass and an air cooler's
     * fans:
     * <ul><li>an exchanger's side over its tube surface {@code area} (m²) in {@link #EXCHANGER_CELLS} cells, with the films
     * per phase of plan section 4 and its bypass at {@code share}, against the wall its other side's stream makes:
     * {@code partner} is that side's connection id, and each slice plans the wall from it (plan section 5.2; the cells' 25 °C
     * here is never moved against); a kettle's tubes the same against the wall their pool makes (W7);</li>
     * <li>an air cooler's process side against air at {@link #AIR_TEMPERATURE} over its bare tube surface {@code area}
     * (m²), with its fans at {@code share} of full speed and its louvers open ({@link #AIR}); a controller works both;</li>
     * <li>a fired heater's radiant coil towards the set point {@code target}, at most its {@link #coilDuty} with the burners
     * at {@code share}; its convection coil against the flue gas the radiant coil's heat leaves ({@link ConvectionCoil}, over
     * {@link #CONVECTION_U}). Each names the other as its {@code partner}.</li></ul>
     */
    public static @Nullable HeatLaw heatSide(Unit unit,Side side,double area,double share,double target,long partner,@Nullable HeatControl control) {
        return switch(unit) {
            case KETTLE,EXCHANGER->{if(partner==0||unit==Unit.KETTLE&&side!=Side.TUBE)yield null;var wall=new double[EXCHANGER_CELLS];java.util.Arrays.fill(wall,AIR_TEMPERATURE);
                yield new HeatSide(area,HeatCurve.Films.DEFAULT,wall,share,0,partner,1,control,HeatControl.Limit.NONE);}
            case AIR_COOLER->new AirCooled(area,AIR,AIR.draught(share),control,HeatControl.Limit.NONE);
            case FIRED_HEATER->side==Side.RADIANT?new FiredCoil(target,coilDuty(side,area,share),1,partner):new ConvectionCoil(CONVECTION_U*area,partner,0);
            default->null;
        };
    }
    /**
     * An air cooler's films on its bare tube surface, per phase (plan section 4), with its fans at {@code fans} of full
     * speed: natural draught at 0. Every tube row meets fresh air at 25 °C, so the process stream sees the air through
     * UA_eff = C (1 − e^(−UA/C)) per m² of face, with UA the process film on the bare tube in series with the air film on
     * the fins, and C the air's capacity rate; the air's film and capacity scale with its flow, a quarter of full by
     * natural draught. At full fans 2.15 (liquid), 2.44 (condensing) and 0.43 (gas) kW/K per m² of face; with the fans
     * still, 0.63, 0.65 and 0.29.
     */
    public static HeatCurve.Films airFilms(double fans){return AIR.films(AIR.draught(fans));}
    /** An air cooler's fan power, W, over bare tube surface {@code area} (m²) with its fans at {@code fans} of full speed (the fan laws: the cube of the speed). */
    public static double fanPower(double area,double fans){return FAN_POWER*area/BARE_PER_FACE*fans*fans*fans;}
}
