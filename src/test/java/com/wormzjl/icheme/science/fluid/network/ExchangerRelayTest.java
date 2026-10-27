package com.wormzjl.icheme.science.fluid.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The relay's science (batch 2026-10-27-heat-exchanger-design, plan section 5.2, W3): the quasi-steady wall and the plan. */
class ExchangerRelayTest {
    /** A stream of constant heat capacity 4180 J/kgK and film {@code film} from 250 to 450 K. */
    private static HeatCurve liquid(double film){return HeatCurve.of(List.of(new double[]{0,250,film},new double[]{4180*200,450,film}));}
    private static HeatSide side(double area,double film){var wall=new double[8];java.util.Arrays.fill(wall,300);return new HeatSide(area,new HeatCurve.Films(film,film,film),wall,0,0,1,1);}
    private static double h(double temperature){return 4180*(temperature-250);}

    /**
     * Plan section 5.1: N = 8 cells is within 0.9 % of exact counterflow up to NTU 5 at Cr 0.5. Two liquids of constant heat
     * capacity, films 2000 W/m²K on 10 m² each (UA 10 kW/K), 2 kg/s at 400 K against 4 kg/s at 300 K: NTU 1.2, Cr 0.5.
     */
    @Test void theWallOfTwoLiquidsGivesCounterflowWithinTheCellsError() {
        var hot=new ExchangerRelay.Stream(liquid(2000),h(400),2,side(10,2000),true);
        var cold=new ExchangerRelay.Stream(liquid(2000),h(300),4,side(10,2000),false);
        var wall=ExchangerRelay.wall(hot,cold,null);
        assertTrue(wall.converged(),"converged in "+wall.sweeps()+" sweeps");
        double[] reversed=new double[8];for(int k=0;k<8;k++)reversed[k]=wall.temperatures()[7-k];
        double given=2*(h(400)-ExchangerRelay.outlet(hot,wall.temperatures()));
        double taken=4*(ExchangerRelay.outlet(new ExchangerRelay.Stream(cold.curve(),cold.inlet(),cold.massFlow(),cold.law(),true),reversed)-h(300));
        assertEquals(given,taken,1e-3*given,"what the hot side gives the cold side takes");
        double ntu=10_000/(2*4180.),cr=.5,e=Math.exp(-ntu*(1-cr)),exact=(1-e)/(1-cr*e)*2*4180*100;
        assertEquals(exact,given,.009*exact,"within the 8 cells' error of exact counterflow");
        for(int k=1;k<8;k++)assertTrue(wall.temperatures()[k]<wall.temperatures()[k-1],"the wall falls along the hot stream");
        assertEquals(1,ExchangerRelay.wall(hot,cold,wall.temperatures()).sweeps(),"a converged wall is kept as it is");
    }
    /**
     * W4 (plan section 6): a controlled side's bypass and wall are solved together at the slice start, so the quasi-steady
     * exchanger as planned meets the set point: the cold stream of the counterflow case held at 315 K, where it reaches
     * 331 K without a bypass (NTU 1.2, Cr 0.5). The plan's cost is printed.
     */
    @Test void theBypassAndTheWallMeetTheSetPointTogether() {
        var control=new HeatControl(HeatControl.Target.OUTLET_TEMPERATURE,315,0,1);
        var own=new HeatSide(10,new HeatCurve.Films(2000,2000,2000),side(10,2000).wall(),0,0,1,1,control,HeatControl.Limit.NONE);
        var cold=new ExchangerRelay.Stream(liquid(2000),h(300),4,own,true);
        var hot=new ExchangerRelay.Stream(liquid(2000),h(400),2,side(10,2000),false);
        var read=new ExchangerRelay.Read(null,false,0,5,0);
        var planned=ExchangerRelay.plan(own,cold,hot,read,5);
        assertTrue(planned.bypass()>0&&planned.bypass()<1,"bypass "+planned.bypass());assertEquals(HeatControl.Limit.NONE,planned.limit());
        var through=new ExchangerRelay.Stream(cold.curve(),cold.inlet(),cold.massFlow(),planned,true);
        var wall=ExchangerRelay.wall(through,hot,null);
        double mixed=h(300)+planned.withInputs(wall.temperatures(),planned.bypass(),0,1).heat(cold.curve(),cold.inlet(),4)/4;
        assertEquals(h(315),mixed,.02*4180,"the quasi-steady exchanger at the planned bypass meets 315 K within the wall's tolerance");
        long start=System.nanoTime();int runs=50;for(int i=0;i<runs;i++)ExchangerRelay.plan(own,cold,hot,read,5);
        System.out.printf(java.util.Locale.ROOT,"controlled plan from a cold wall: %.3f ms; again from its own wall: same bypass %b%n",(System.nanoTime()-start)/1e6/runs,
                ExchangerRelay.plan(planned,cold,hot,read,5).bypass()==planned.bypass());
    }
    /** Plan section 5.2: a side without flow takes no give-back; a side whose partner is still moves no heat in its cells,
     * takes the whole due and keeps its outlet between the two inlet temperatures. */
    @Test void thePlanMovesNothingWithoutFlowAndGivesBackWithinTheInlets() {
        var own=side(10,2000).withInputs(new double[]{350,350,350,350,350,350,350,350},0,0);
        var partner=new ExchangerRelay.Stream(liquid(2000),h(300),4,side(10,2000),false);
        var still=new ExchangerRelay.Stream(liquid(2000),h(400),0,own,true);
        var planned=ExchangerRelay.plan(own,still,partner,new ExchangerRelay.Read(null,false,1e6,5,0),5);
        assertEquals(0,planned.giveBack(),"no flow, no give-back");
        assertEquals(300,planned.wallTemperature(0),1e-9,"its wall stands at the partner's inlet");
        var flowing=new ExchangerRelay.Stream(liquid(2000),h(400),2,own,true);
        var stopped=ExchangerRelay.plan(own,flowing,partner,new ExchangerRelay.Read(null,true,-1e5,5,0),5);
        assertEquals(0,stopped.active(),"a stopped partner: its cells move nothing");
        assertEquals(-1e5/5,stopped.heat(flowing.curve(),flowing.inlet(),2),1e-6*1e5,"the whole due given back over the slice: "+stopped);
        var clamped=ExchangerRelay.plan(own,flowing,partner,new ExchangerRelay.Read(null,true,-1e9,5,0),5);
        assertEquals(h(300),flowing.inlet()+clamped.heat(flowing.curve(),flowing.inlet(),2)/2,1e-6,"a give-back stops at the partner's inlet temperature");
        var owed=ExchangerRelay.plan(own,flowing,partner,new ExchangerRelay.Read(null,true,1e6,5,0),5);
        assertEquals(0,owed.giveBack(),"a hot stream is not heated past its own inlet");
    }
}
