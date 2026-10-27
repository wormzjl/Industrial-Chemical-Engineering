package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** W4's science (batch 2026-10-27-heat-exchanger-design, plan section 6): a side's controller on its own heat law. */
class HeatControlTest {
    /** A liquid of 4000 J/kgK from 250 to 450 K under a film of 1000 W/m²K. */
    private static HeatCurve liquid(){return HeatCurve.of(List.of(new double[]{0,250,1000},new double[]{800_000,450,1000}));}
    private static double h(double temperature){return 4000*(temperature-250);}
    /** 1 kg/s through 10 m² against a wall at {@code wall} K (UA 10 kW/K, NTU 2.5 at full flow), the effort the share
     * through the cells: a bypass of 1 − u that rejoins at the outlet. */
    private static DoubleUnaryOperator bypassed(HeatCurve curve,double inlet,double wall){return u->u*(curve.march(inlet,u,10,wall)-inlet);}
    private static HeatControl outlet(double temperature,double lowLimit){return new HeatControl(HeatControl.Target.OUTLET_TEMPERATURE,temperature,lowLimit,1);}
    private static double mixed(DoubleUnaryOperator heat,double inlet,HeatControl.Setting setting){return inlet+heat.applyAsDouble(setting.effort());}

    /** Plan section 6: the bypass share whose mixed outlet meets the target, else the actuator at the end it stops at. */
    @Test void theBypassMeetsAnOutletOrADutyAndStopsAtItsEnds() {
        var curve=liquid();double inlet=h(400);var heat=bypassed(curve,inlet,300);
        var held=outlet(350,0).setting(heat,curve,inlet,1,0);
        assertEquals(HeatControl.Limit.NONE,held.limit());
        assertEquals(h(350),mixed(heat,inlet,held),1e-6,"the mixed outlet at the set point");
        // Full flow through the cells leaves at 300 K + 100 K e^-2.5 = 308.2 K: 305 K is out of reach.
        var short_=outlet(305,0).setting(heat,curve,inlet,1,.3);
        assertEquals(1,short_.effort());assertEquals(HeatControl.Limit.HIGHEST,short_.limit());
        var wrong=outlet(410,0).setting(heat,curve,inlet,1,.3);
        assertEquals(0,wrong.effort());assertEquals(HeatControl.Limit.LOWEST,wrong.limit(),"a set point above a cooled stream's inlet opens the bypass");
        var duty=new HeatControl(HeatControl.Target.DUTY,100_000,0,1).setting(heat,curve,inlet,1,0);
        assertEquals(-100_000,heat.applyAsDouble(duty.effort()),1e-4,"the heat moved, whichever way");
        assertEquals(HeatControl.Limit.HIGHEST,new HeatControl(HeatControl.Target.DUTY,1e6,0,1).setting(heat,curve,inlet,1,0).limit(),"367 kW at most");
        var still=outlet(350,0).setting(heat,curve,inlet,0,.4);
        assertEquals(.4,still.effort(),"without flow the bypass stands");
        assertEquals(.7,new HeatControl(HeatControl.Target.MANUAL,0,0,.7).setting(heat,curve,inlet,1,.4).effort(),"manual holds the player's share");
    }
    /** Plan section 6, the freeze guard: a limit in every mode on a cooled outlet, none on a heated one. */
    @Test void theLowLimitHoldsACooledOutletInEveryMode() {
        var curve=liquid();double inlet=h(400);var heat=bypassed(curve,inlet,300);
        var below=outlet(320,340).setting(heat,curve,inlet,1,0);
        assertEquals(HeatControl.Limit.LOW_LIMIT,below.limit());assertEquals(h(340),mixed(heat,inlet,below),1e-6);
        var manual=new HeatControl(HeatControl.Target.MANUAL,0,340,1).setting(heat,curve,inlet,1,1);
        assertEquals(HeatControl.Limit.LOW_LIMIT,manual.limit());assertEquals(h(340),mixed(heat,inlet,manual),1e-6);
        var above=outlet(360,340).setting(heat,curve,inlet,1,0);
        assertEquals(HeatControl.Limit.NONE,above.limit(),"a set point above the limit is the controller's alone");
        double cold=h(330);var arrives=outlet(320,340).setting(bypassed(curve,cold,300),curve,cold,1,.5);
        assertEquals(0,arrives.effort());assertEquals(HeatControl.Limit.LOW_LIMIT,arrives.limit(),"a stream that arrives below the limit is not cooled");
        double heated=h(280);var up=bypassed(curve,heated,380);
        var warmed=outlet(330,340).setting(up,curve,heated,1,0);
        assertEquals(h(330),mixed(up,heated,warmed),1e-6,"a heated outlet has no low limit");
    }
    /** An air cooler's still fans still cool by natural draught: a duty below that takes the lowest effort, short of it. */
    @Test void naturalDraughtIsTheLeastAFanControllerMoves() {
        var curve=liquid();DoubleUnaryOperator fans=u->-(100_000+300_000*u);
        var least=new HeatControl(HeatControl.Target.DUTY,50_000,0,1).setting(fans,curve,h(360),1,.5);
        assertEquals(0,least.effort());assertEquals(HeatControl.Limit.LOWEST,least.limit());
        var mid=new HeatControl(HeatControl.Target.DUTY,250_000,0,1).setting(fans,curve,h(360),1,.5);
        assertEquals(.5,mid.effort(),1e-9);assertEquals(HeatControl.Limit.NONE,mid.limit());
    }
    /** Plan section 6: a set point at a pure stream's saturation temperature names the plateau's liquid end when the side
     * cools it (all condensed) and its vapour end when the side heats it (all boiled). */
    @Test void aSetPointOnAPlateauNamesTheEndTheSideReaches() {
        var plateau=HeatCurve.of(List.of(new double[]{0,300,2000},new double[]{300_000,373,5000},new double[]{2_300_000,373,5000},new double[]{2_350_000,400,100}));
        assertEquals(2_300_000,plateau.enthalpyAt(373));assertEquals(300_000,plateau.lowestEnthalpyAt(373));
        assertEquals(plateau.enthalpyAt(340),plateau.lowestEnthalpyAt(340),"off the plateau both are one");
        assertEquals(plateau.enthalpyAt(250),plateau.lowestEnthalpyAt(250));assertEquals(plateau.enthalpyAt(420),plateau.lowestEnthalpyAt(420));
        double steam=2_350_000;DoubleUnaryOperator condense=u->u*(plateau.march(steam,u,50,320)-steam);
        var condensed=outlet(373,0).setting(condense,plateau,steam,1,0);
        assertEquals(300_000,mixed(condense,steam,condensed),1e-3,"cooled to its saturation temperature: all liquid");
        double water=100_000;DoubleUnaryOperator boil=u->u*(plateau.march(water,u,50,450)-water);
        var boiled=outlet(373,0).setting(boil,plateau,water,1,0);
        assertEquals(2_300_000,mixed(boil,water,boiled),1e-3,"heated to it: all vapour");
    }
    /** W4: a sampled curve keeps its knots' vapour fractions, so new films need no flash (an air cooler's fans). */
    @Test void aSampledCurveTakesNewFilmsWithoutAFlash() {
        var model=FluidTestSupport.networkModel();
        double[] water=new double[model.componentCount()];water[water.length-1]=1;
        var films=new HeatCurve.Films(430,487,85);
        var direct=HeatCurve.sample(model,water,101_325,355,390,films,()->{});
        var refilmed=HeatCurve.sample(model,water,101_325,355,390,HeatCurve.Films.DEFAULT,()->{}).withFilms(films);
        assertEquals(direct.knots(),refilmed.knots());
        for(int k=0;k<direct.knots();k++){assertEquals(direct.enthalpy(k),refilmed.enthalpy(k));assertEquals(direct.temperature(k),refilmed.temperature(k));assertEquals(direct.film(k),refilmed.film(k));}
        assertThrows(IllegalStateException.class,()->liquid().withFilms(films),"a curve given its films has no vapour fractions");
    }
}
