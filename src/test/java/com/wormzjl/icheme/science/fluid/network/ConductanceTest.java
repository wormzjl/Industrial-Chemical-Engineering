package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The unit's overall UA on its heat page (decision D16 of batch 2026-11-16-fouling-corrosion): over the wall's cells,
 * {@code Σ A_k / (1/h_own,k + 1/h_other,k)}, each film the one that side's march meets in the cell, deposits included; the
 * clean UA the same on films without the deposits. A kettle's tubes against its pool's film, an air cooler's bank over its
 * surface. None where a side has no partner read, counts it stopped, or draws nothing.
 */
class ConductanceTest {
    private static final FluidThermodynamics MODEL=FluidTestSupport.networkModel();
    private static final double AREA=10,PRESSURE=1_000_000;

    private static double[] water(){double[] n=new double[MODEL.componentCount()];n[n.length-1]=1;return n;}
    private static double[] oil() {
        double[] n=new double[MODEL.componentCount()];
        int heavy=-1;for(int c=0;c<MODEL.components().size();c++)if(MODEL.components().get(c).toLowerCase(Locale.ROOT).contains("pc10"))heavy=c;
        assertTrue(heavy>=0,"a crude cut pc10 in "+MODEL.components());
        n[heavy]=1;return n;
    }
    private static HeatCurve curve(double[] amounts,double low,double high,HeatCurve.Films films){return HeatCurve.sample(MODEL,amounts,PRESSURE,low,high,films,()->{});}
    private static HeatSide side(HeatCurve.Films films){var wall=new double[8];Arrays.fill(wall,300);return new HeatSide(AREA,films,wall,0,0,1,1);}
    private static ExchangerRelay.Stream stream(double[] amounts,double inlet,double massFlow,HeatCurve.Films films,boolean forward,double low,double high) {
        var curve=curve(amounts,low,high,films);
        return new ExchangerRelay.Stream(curve,curve.enthalpyAt(inlet),massFlow,side(films),forward);
    }
    private static final ExchangerRelay.Read READ=new ExchangerRelay.Read(null,false,0,5,0);

    /** Two clean water streams at 20 to 60 °C: warm water's film is the reference's, 5000 W/m²K, so U = 2500 W/m²K; the
     * clean UA is the UA itself. */
    @Test void twoCleanWaterStreamsGiveHalfTheReferenceFilm() {
        var hot=stream(water(),333.15,2,HeatCurve.Films.DEFAULT,true,283.15,343.15);
        var cold=stream(water(),293.15,2,HeatCurve.Films.DEFAULT,false,283.15,343.15);
        var wall=ExchangerRelay.wall(hot,cold,null).temperatures();
        var c=ExchangerRelay.conductance(hot,cold,wall);
        assertEquals(2500*AREA,c.ua(),.01*2500*AREA,"U = 1/(1/5000 + 1/5000)");
        assertEquals(c.ua(),c.clean(),0.,"no deposit: the clean UA is the UA");
        assertEquals(c.ua(),ExchangerRelay.conductance(cold,hot,wall).ua(),1e-9*c.ua(),"symmetric in the two streams");
    }

    /** A deposit rf on each side: U = 1/(2/5000 + 2 rf); the clean UA stays 2500 A. */
    @Test void aDepositOnEachSideLowersTheUaAndNotTheCleanOne() {
        double rf=2e-4;var films=HeatCurve.Films.DEFAULT.fouled(rf);
        var hot=stream(water(),333.15,2,films,true,283.15,343.15);
        var cold=stream(water(),293.15,2,films,false,283.15,343.15);
        var wall=ExchangerRelay.wall(hot,cold,null).temperatures();
        var c=ExchangerRelay.conductance(hot,cold,wall);
        double u=1/(2/5000.+2*rf);
        assertEquals(u*AREA,c.ua(),.01*u*AREA,"the deposits in series");
        assertEquals(2500*AREA,c.clean(),.01*2500*AREA,"the clean films");
        assertEquals(.5,c.ua()/c.clean(),.005,"half the clean UA is left");
    }

    /** The planned side carries the UA on its planned wall; the bypass changes the flow through the cells, not the surface:
     * a side with its bypass wide open still reads a UA. */
    @Test void thePlanCarriesTheUaWhateverTheBypass() {
        var hot=stream(water(),333.15,2,HeatCurve.Films.DEFAULT,true,283.15,343.15);
        var cold=stream(water(),293.15,2,HeatCurve.Films.DEFAULT,false,283.15,343.15);
        var planned=ExchangerRelay.plan(hot.law(),hot,cold,READ,5);
        assertEquals(ExchangerRelay.conductance(hot,cold,planned.wall()),planned.conductance());
        var open=hot.law().withInputs(hot.law().wall(),1,0);
        var bypassed=ExchangerRelay.plan(open,new ExchangerRelay.Stream(hot.curve(),hot.inlet(),hot.massFlow(),open,true),cold,READ,5);
        assertNotNull(bypassed.conductance(),"a wide-open bypass keeps the surface");
        assertEquals(2500*AREA,bypassed.conductance().ua(),.01*2500*AREA);
        var sitting=ExchangerRelay.plan(hot.law(),hot,cold,new ExchangerRelay.Read(null,false,0,5,.6),5);
        assertEquals(planned.conductance().ua(),sitting.conductance().ua(),1e-9*planned.conductance().ua(),"the sit-out scales the slice's heat, not the UA");
    }

    /** No UA without a partner read, with the partner counted stopped, against a column, or without a stream. */
    @Test void noUaWhereTheOtherSideIsNotReadOrNothingFlows() {
        var hot=stream(water(),333.15,2,HeatCurve.Films.DEFAULT,true,283.15,343.15);
        var cold=stream(water(),293.15,2,HeatCurve.Films.DEFAULT,false,283.15,343.15);
        var known=hot.law().withConductance(new ExchangerRelay.Conductance(1,1));
        var own=new ExchangerRelay.Stream(hot.curve(),hot.inlet(),2,known,true);
        assertNull(ExchangerRelay.plan(known,own,null,ExchangerRelay.Read.NONE,5).conductance(),"no partner read");
        assertNull(ExchangerRelay.plan(known,own,cold,new ExchangerRelay.Read(null,true,0,5,0),5).conductance(),"the partner counted stopped");
        assertNull(ExchangerRelay.plan(known,own,null,ExchangerRelay.Read.column(1e5,5,330,1e4),5).conductance(),"a column's condenser");
        assertNull(ExchangerRelay.plan(known,own,null,ExchangerRelay.Read.heating(-1e5,5,300,1e4),5).conductance(),"a column's reboiler");
        var still=new ExchangerRelay.Stream(hot.curve(),hot.inlet(),0,known,true);
        assertNull(ExchangerRelay.plan(known,still,cold,READ,5).conductance(),"no stream of its own");
        var stillOther=new ExchangerRelay.Stream(cold.curve(),cold.inlet(),0,cold.law(),false);
        assertNull(ExchangerRelay.plan(known,own,stillOther,READ,5).conductance(),"no stream on the other side");
    }

    /**
     * Each side plans from the other as read; at a steady state both read the same UA within 0.5 %. A hot oil against
     * water: the oil's film changes along the cells, and the two sides meet the cells in opposite orders.
     */
    @Test void bothSidesReadTheSameUaAtSteadyState() {
        var films=HeatCurve.Films.DEFAULT.fouled(1e-4);
        double low=283.15,high=430.15;
        var oil=curve(oil(),low,high,films);var water=curve(water(),low,high,HeatCurve.Films.DEFAULT);
        double oilIn=oil.enthalpyAt(420.15),waterIn=water.enthalpyAt(293.15);
        var oilSide=side(films);var waterSide=side(HeatCurve.Films.DEFAULT);
        var a=ExchangerRelay.plan(oilSide,new ExchangerRelay.Stream(oil,oilIn,1.5,oilSide,true),new ExchangerRelay.Stream(water,waterIn,1,waterSide,false),READ,5);
        var b=ExchangerRelay.plan(waterSide,new ExchangerRelay.Stream(water,waterIn,1,waterSide,true),new ExchangerRelay.Stream(oil,oilIn,1.5,oilSide,false),READ,5);
        assertNotNull(a.conductance());assertNotNull(b.conductance());
        assertEquals(a.conductance().ua(),b.conductance().ua(),.005*a.conductance().ua(),"the two pages agree");
        assertEquals(a.conductance().clean(),b.conductance().clean(),.005*a.conductance().clean());
        assertTrue(a.conductance().ua()<a.conductance().clean(),"the oil's deposit lowers it");
        // The oil's film is below the reference's: the UA lies under the water-water one.
        assertTrue(a.conductance().ua()<2500*AREA);
    }

    /** A kettle: the tubes' film against the pool's, cell by cell. Warm water's film is 5000, the bath's 2000. */
    @Test void aKettlesUaIsItsTubesAgainstItsPool() {
        double rf=3e-4;var films=HeatCurve.Films.DEFAULT.fouled(rf);
        var tube=stream(water(),353.15,2,films,true,303.15,363.15);
        var bath=new ExchangerRelay.Bath(313.15,2000);
        var planned=ExchangerRelay.plan(tube.law(),tube,bath,READ);
        var c=planned.conductance();
        assertEquals(AREA/(1/5000.+rf+1/2000.),c.ua(),1e-6*c.ua());
        assertEquals(AREA/(1/5000.+1/2000.),c.clean(),1e-6*c.clean());
        assertEquals(ExchangerRelay.conductance(tube,bath,planned.wall()),c);
        assertNull(ExchangerRelay.plan(tube.law(),tube,null,READ).conductance(),"an empty pool");
        assertNull(ExchangerRelay.plan(tube.law(),tube,bath,new ExchangerRelay.Read(null,true,0,5,0)).conductance(),"a pool counted stopped");
    }

    /** An air cooler: the process film in series with the air over its whole surface; warm water's film is the reference's
     * everywhere, so the bank's UA is its surface times the effective liquid film. */
    @Test void anAirCoolersUaIsItsSurfaceTimesItsEffectiveFilm() {
        double rf=4e-4;var air=HeatSides.AIR.fouled(rf);
        var cooled=new AirCooled(90,air,air.draught(.5),null,HeatControl.Limit.NONE);
        var curve=curve(water(),299.15,363.15,cooled.films());
        var c=cooled.conductance(curve,curve.enthalpyAt(353.15),5);
        assertEquals(90*cooled.films().wallLiquid(),c.ua(),1e-9*c.ua());
        var clean=new AirCooled(90,HeatSides.AIR,HeatSides.AIR.draught(.5),null,HeatControl.Limit.NONE);
        assertEquals(90*clean.films().wallLiquid(),c.clean(),1e-9*c.clean(),"the clean bank's");
        assertTrue(c.ua()<c.clean());
        assertNull(cooled.conductance(curve,curve.enthalpyAt(353.15),0),"no flow");
        assertEquals(cooled,cooled.withConductance(c),"a reading, not an input");
    }

    /** The films record their deposit and give themselves back clean without it. */
    @Test void cleanFilmsUndoTheDeposit() {
        var films=HeatCurve.Films.DEFAULT.fouled(2e-4).fouled(1e-4);
        assertEquals(3e-4,films.deposit(),1e-18);
        var clean=films.clean();
        assertEquals(5000,clean.liquid(),1e-9);assertEquals(5000,clean.twoPhase(),1e-9);assertEquals(100,clean.gas(),1e-12);assertEquals(0,clean.deposit());
        assertSame(HeatCurve.Films.DEFAULT,HeatCurve.Films.DEFAULT.clean());
    }
}
