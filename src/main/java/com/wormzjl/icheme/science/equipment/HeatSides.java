package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.FiredCoil;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.network.HeatLaw;
import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The heat-exchanging sides of a unit (batch 2026-10-27-heat-exchanger-design, plan sections 4, 5.1 and 10): each a
 * passage between its inlet and outlet nozzles, registered as one fluid device of its own. An exchanger has a tube and a
 * shell side, an air cooler its process side; the fired heaters (W6) and the kettle (W7) add theirs.
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
    /**
     * A fired heater's convection coil's mean flux, kW/m² of bare tube (API 560 practice: 10 to 20 for finned and shock
     * tubes), against the radiant coil's {@link ProcessUnit#RADIANT_FLUX}: the coils take 30 % and 70 % of the duty
     * ({@link ProcessUnit#RADIANT_SHARE}).
     */
    static final double CONVECTION_FLUX=15;
    /** A fired heater's coil tubes, m: 6" schedule 80, the tubes {@link ProcessUnit#heaterDuty} counts. */
    static final double HEATER_TUBE=.168,HEATER_BORE=.146;
    /** A coil's duty per parallel pass, MW (about 2 m/s of oil per pass), its straight tube between return bends, m, and a bend's velocity heads. */
    static final double PASS_DUTY=8,HEATER_STRAIGHT=10,RETURN_BEND=.5;

    /** The unit a shape kind's sides belong to, or null for a kind without heat sides. */
    public static @Nullable Unit unit(EquipmentShape.Kind kind) {
        return switch(kind){case EXCHANGER->Unit.EXCHANGER;case AIR_COOLER->Unit.AIR_COOLER;case VERTICAL_HEATER,CABIN_HEATER->Unit.FIRED_HEATER;default->null;};
    }
    /** {@code shape}'s sides in registration order (the first one's node is the structure's fluid cell); none for other kinds. */
    public static List<Definition> of(EquipmentShape shape) {
        var unit=unit(shape.kind());if(unit==null)return List.of();
        double d=shape.diameter(),l=shape.tangentLength();
        return switch(unit) {
            case EXCHANGER->{double area=ProcessUnit.tubeSurface(d,l);
                yield List.of(new Definition(Side.TUBE,EquipmentShape.Role.TUBE_INLET,EquipmentShape.Role.TUBE_OUTLET,area,tubePassage(d,l)),
                        new Definition(Side.SHELL,EquipmentShape.Role.SHELL_INLET,EquipmentShape.Role.SHELL_OUTLET,area,shellPassage(d,l)));}
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

    /**
     * The heat law a side moves heat through on its own, or null where it waits for its partner (W3) or its package (W7),
     * with its actuator at {@code share} and its set point {@code target} (K):
     * <ul><li>an air cooler's process side against air at {@link #AIR_TEMPERATURE} over its bare tube surface {@code area}
     * (m²), with its fans at {@code share} of full speed ({@link #airFilms});</li>
     * <li>a fired heater's coil towards the set point {@code target}, the radiant coil all the way and the convection coil
     * its 30 % share of it, at most its {@link #coilDuty} with the burners at {@code share}.</li></ul>
     */
    public static @Nullable HeatLaw heatSide(Unit unit,Side side,double area,double share,double target) {
        return switch(unit) {
            case AIR_COOLER->new HeatSide(area,airFilms(share),new double[]{AIR_TEMPERATURE},0,0);
            case FIRED_HEATER->new FiredCoil(target,side==Side.RADIANT?1:1-ProcessUnit.RADIANT_SHARE,coilDuty(side,area,share),1);
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
    public static HeatCurve.Films airFilms(double fans) {
        if(!(fans>=0&&fans<=1))throw new IllegalArgumentException("Invalid fan share "+fans);
        double draught=NATURAL_DRAUGHT+(1-NATURAL_DRAUGHT)*fans,air=AIR_FILM*FINNED_PER_FACE*draught,capacity=AIR_CAPACITY*draught;
        var process=HeatCurve.Films.DEFAULT;
        return new HeatCurve.Films(airFilm(process.liquid(),air,capacity),airFilm(process.twoPhase(),air,capacity),airFilm(process.gas(),air,capacity));
    }
    private static double airFilm(double process,double air,double capacity) {
        double conductance=1/(1/(BARE_PER_FACE*process)+1/air);
        return -capacity*DeterministicMath.expm1(-conductance/capacity)/BARE_PER_FACE;
    }
    /** An air cooler's fan power, W, over bare tube surface {@code area} (m²) with its fans at {@code fans} of full speed (the fan laws: the cube of the speed). */
    public static double fanPower(double area,double fans){return FAN_POWER*area/BARE_PER_FACE*fans*fans*fans;}
}
