package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Separation}'s band law on the tables of batch 2026-11-17-dynamic-settling's plan, section 3 (reference:
 * {@code tools/dynamic-settling/band.mjs}): the review plant's receiver, oil 666 kg/m³ and 0.30 mPa s, water 984 kg/m³
 * and 0.47 mPa s.
 */
class SettlingBandTest {
    private static final double OIL=666.35,WATER=983.79,OIL_VISCOSITY=2.999e-4,WATER_VISCOSITY=4.732e-4;
    /** Water drops in the oil (43.7 mm/s) and oil drops in the water (10.4 mm/s). */
    private static final double WATER_DROP=Separation.settling(Separation.DROPS.water(),WATER-OIL,OIL_VISCOSITY,OIL),
            OIL_DROP=Separation.settling(Separation.DROPS.oil(),WATER-OIL,WATER_VISCOSITY,WATER);
    /** The heavy crude of the plan's second table: 900 kg/m³, 10 mPa s, 90 kg/m³ lighter than its water. */
    private static final double CRUDE=900,CRUDE_VISCOSITY=.01,CRUDE_DIFFERENCE=90;

    /** A layer at steady flow: volume, plan area, drop speed, flow in and out, feed ratio, the plan's carried share. */
    private record Steady(String name,double volume,double area,double speed,double flow,double ratio,double expected) {
        double number(){return speed*area/flow;}
    }
    private static final Steady[] STEADY={
            new Steady("1 x 6 m drum at 4 times, boot",.0628,.126,OIL_DROP,4*3.74e-4,1,.1265),
            new Steady("2 x 5 m drum at 16 times, boot",.244,.349,OIL_DROP,16*3.74e-4,1,.3951),
            new Steady("2 x 5 m drum at 128 times, oil layer",7.6,9.84,WATER_DROP,128*.0042,.089,.0178),
            new Steady("2 x 5 m drum at design flow, oil layer",7.6,9.84,WATER_DROP,.0042,.089,0)};

    /** The mean carried ratio of the last 20 of 4000 slices, and the end time of the first slice that carries (-1: none). */
    private static double[] steady(Steady layer,double seconds,boolean zeroOnEverySlice) {
        var band=Separation.Band.NONE;double volume=layer.volume(),sum=0,first=-1;
        for(int k=0;k<4000;k++) {
            var slice=Separation.advance(band,volume,layer.area(),layer.speed(),0,layer.flow(),layer.ratio(),layer.flow(),seconds);
            band=slice.next();
            if(zeroOnEverySlice)assertEquals(0,slice.carried(),layer.name()+" slice "+k);
            if(slice.carried()>0&&first<0)first=(k+1)*seconds;
            if(k>=3980)sum+=slice.carried();
        }
        return new double[]{sum/20,first};
    }

    @Test void atSteadyFlowTheBandGivesHazensShareToFourDigits() {
        for(var layer:STEADY)for(double seconds:new double[]{5,20}) {
            double[] run=steady(layer,seconds,layer.expected()==0);
            assertEquals(layer.expected(),run[0],1e-4,layer.name()+" at slices of "+seconds+" s");
            assertEquals(Math.max(0,1-layer.number())*layer.ratio(),run[0],1e-4,layer.name()+" against (1 - N) r");
        }
        assertEquals(0,steady(STEADY[3],5,true)[0]);
        assertEquals(-1,steady(STEADY[3],5,true)[1]);
    }

    @Test void anOverloadedLayerIsCleanUntilTheBandFillsIt() {
        // V / (Q - v A): 332 s, 103 s and 71 s.
        double[] expected={332,103,71};
        for(int i=0;i<3;i++) {
            var layer=STEADY[i];double period=layer.volume()/(layer.flow()-layer.speed()*layer.area());
            assertEquals(expected[i],period,1,layer.name());
            assertEquals(period,steady(layer,5,false)[1],.05*period,layer.name()+" first slice that carries");
        }
    }

    /** The plan's second table: a 2 m layer filled turbid and left alone. */
    private record Batch(String name,double drop,double difference,double viscosity,double density,double fraction,double clear,double swarm) {
        double speed(){return Separation.settling(drop,difference,viscosity,density);}
    }
    private static final Batch[] BATCHES={
            new Batch("9 % water in naphtha, 0.5 mm",5e-4,WATER-OIL,OIL_VISCOSITY,OIL,.082,46,59),
            new Batch("9 % oil in water, 0.2 mm",2e-4,WATER-OIL,WATER_VISCOSITY,WATER,.082,193,268),
            new Batch("10 % water in a heavy crude, 0.5 mm",5e-4,CRUDE_DIFFERENCE,CRUDE_VISCOSITY,CRUDE,.1,1665,2717),
            new Batch("30 % water in a heavy crude, 0.5 mm",5e-4,CRUDE_DIFFERENCE,CRUDE_VISCOSITY,CRUDE,.3,1665,8742),
            new Batch("30 % water in a heavy crude, 0.1 mm",1e-4,CRUDE_DIFFERENCE,CRUDE_VISCOSITY,CRUDE,.3,40819,-1)};

    /** Seconds until a 2 m layer of plan area 1 filled turbid at {@code fraction} is settled, at slices of 1 s. */
    private static int clearAfter(double speed,double exponent,double fraction,int limit) {
        double volume=2,ratio=fraction/(1-fraction);var band=new Separation.Band(volume,volume*ratio);
        for(int k=0;k<limit;k++) {
            var slice=Separation.advance(band,volume,1,speed,exponent,0,0,0,1);
            assertEquals(0,slice.carried());
            band=slice.next();
            if(band.none())return k+1;
        }
        return -1;
    }

    @Test void aStillLayerClearsAfterItsHeightOverTheDropSpeed() {
        // Kynch: the front crosses the layer at the drops' speed.
        double crude=Separation.settling(5e-4,CRUDE_DIFFERENCE,CRUDE_VISCOSITY,CRUDE);
        assertEquals(1.20e-3,crude,.01e-3);
        for(var batch:BATCHES) {
            double h=2/batch.speed();int seconds=clearAfter(batch.speed(),0,batch.fraction(),100000);
            assertEquals(batch.clear(),h,batch.clear()>1e4?.001*batch.clear():1,batch.name()+" h / v");
            assertEquals(h,seconds,batch.clear()>1e4?.001*h:1,batch.name()+" settled after");
        }
    }

    @Test void theSwarmLawSlowsAConcentratedDispersion() {
        // Richardson and Zaki: 59 s, 268 s, 45 min and 2.4 h.
        for(var batch:BATCHES) {
            if(batch.swarm()<0)continue;
            double n=Separation.swarmExponent(batch.speed(),batch.drop(),batch.viscosity(),batch.density());
            double hindered=2/(batch.speed()*DeterministicMath.pow(1-batch.fraction(),n));
            assertEquals(batch.swarm(),hindered,.01*batch.swarm(),batch.name()+" h / v (1 - phi)^n");
            // Within 1 %, or the one slice of 1 s a 59 s clearing time cannot resolve to 1 %.
            assertEquals(hindered,clearAfter(batch.speed(),n,batch.fraction(),100000),Math.max(1,.01*hindered),batch.name());
        }
    }

    @Test void theSwarmExponentFollowsRichardsonAndZakisSteps() {
        double naphtha=Separation.swarmExponent(WATER_DROP,5e-4,OIL_VISCOSITY,OIL);
        assertEquals(48.6,OIL*WATER_DROP*5e-4/OIL_VISCOSITY,.1);
        assertEquals(3.02,naphtha,.005);
        assertEquals(.77,DeterministicMath.pow(1-.082,naphtha),.005);
        double water=Separation.swarmExponent(OIL_DROP,2e-4,WATER_VISCOSITY,WATER);
        assertEquals(4.31,WATER*OIL_DROP*2e-4/WATER_VISCOSITY,.01);
        assertEquals(3.84,water,.005);
        assertEquals(.72,DeterministicMath.pow(1-.082,water),.005);
        double crude=Separation.swarmExponent(Separation.settling(5e-4,CRUDE_DIFFERENCE,CRUDE_VISCOSITY,CRUDE),5e-4,CRUDE_VISCOSITY,CRUDE);
        assertEquals(4.65,crude);
        assertEquals(.61,DeterministicMath.pow(1-.1,crude),.005);
        assertEquals(.19,DeterministicMath.pow(1-.3,crude),.005);
        // The four ranges and both sides of each step (drop 1 m, viscosity and density 1: the speed is Re).
        assertEquals(4.65,exponentAt(0));
        assertEquals(4.65,exponentAt(.1999));
        assertEquals(4.35*DeterministicMath.pow(.2,-.03),exponentAt(.2),1e-12);
        assertEquals(4.565,exponentAt(.2),.001);
        assertEquals(4.35,exponentAt(.99999),1e-4);
        assertEquals(4.45,exponentAt(1),1e-12);
        assertEquals(4.45*DeterministicMath.pow(48.6,-.1),exponentAt(48.6),1e-12);
        assertEquals(2.3904,exponentAt(499.999),1e-4);
        assertEquals(2.39,exponentAt(500));
        assertEquals(2.39,exponentAt(1e6));
        assertThrows(IllegalArgumentException.class,()->Separation.swarmExponent(1,0,1,1));
        assertThrows(IllegalArgumentException.class,()->Separation.swarmExponent(-1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->Separation.swarmExponent(Double.NaN,1,1,1));
    }
    private static double exponentAt(double re){return Separation.swarmExponent(re,1,1,1);}

    @Test void aBootWhoseFeedStopsSettlesWithinItsHeightOverTheDropSpeed() {
        var boot=STEADY[0];var band=Separation.Band.NONE;
        for(int k=0;k<240;k++)band=Separation.advance(band,boot.volume(),boot.area(),boot.speed(),0,boot.flow(),1,boot.flow(),5).next();
        // After 20 min at 4 times the flow the boot is turbid throughout.
        assertEquals(boot.volume(),band.turbid(),.01*boot.volume());
        // No flow: settled after 50 s (h / v 48 s), still turbid at 45 s.
        for(int k=0;k<10;k++) {
            assertFalse(band.none(),"slice "+k);
            band=Separation.advance(band,boot.volume(),boot.area(),boot.speed(),0,0,0,0,5).next();
        }
        assertSame(Separation.Band.NONE,band);
    }

    @Test void aBandStaysWithinWhatTheVesselHolds() {
        var band=new Separation.Band(2,.2);
        assertEquals(new Separation.Band(1,.1),band.within(1,1));
        assertEquals(new Separation.Band(2,.05),band.within(3,.05));
        assertEquals(new Separation.Band(1,.05),band.within(1,.05));
        assertSame(band,band.within(2,.2));
        assertSame(Separation.Band.NONE,band.within(0,1));
        assertTrue(Separation.Band.NONE.none());
        assertFalse(band.none());
        assertFalse(new Separation.Band(0,1e-9).none());
        assertThrows(IllegalArgumentException.class,()->band.within(-1,1));
        assertThrows(IllegalArgumentException.class,()->band.within(1,Double.NaN));
    }

    @Test void theMinorityLiquidEntersAsDrops() {
        assertArrayEquals(new double[]{.1,0},Separation.dispersed(1,.1));
        assertArrayEquals(new double[]{0,.1},Separation.dispersed(.1,1));
        assertArrayEquals(new double[]{1,0},Separation.dispersed(1,1));
        assertArrayEquals(new double[]{0,0},Separation.dispersed(0,0));
        assertArrayEquals(new double[]{0,0},Separation.dispersed(1,0));
        assertArrayEquals(new double[]{0,0},Separation.dispersed(0,1));
        assertThrows(IllegalArgumentException.class,()->Separation.dispersed(-1,1));
        assertThrows(IllegalArgumentException.class,()->Separation.dispersed(1,Double.POSITIVE_INFINITY));
    }

    @Test void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new Separation.Band(-1e-12,0));
        assertThrows(IllegalArgumentException.class,()->new Separation.Band(0,-1));
        assertThrows(IllegalArgumentException.class,()->new Separation.Band(Double.NaN,0));
        assertThrows(IllegalArgumentException.class,()->new Separation.Band(Double.POSITIVE_INFINITY,0));
        var none=Separation.Band.NONE;
        assertThrows(IllegalArgumentException.class,()->Separation.advance(null,1,1,1,0,0,0,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,-1,1,1,0,0,0,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,-1,1,0,0,0,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,Double.NaN,0,0,0,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,-1,0,0,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,0,-1,0,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,0,1,1.5,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,0,1,-.1,0,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,0,1,0,-1,5));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,0,1,0,0,Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,()->Separation.advance(none,1,1,1,0,1,0,0,-5));
        // A still, empty slice is settled and carries nothing.
        var slice=Separation.advance(none,1,1,1,0,0,0,0,5);
        assertSame(Separation.Band.NONE,slice.next());
        assertEquals(0,slice.carried());
    }

    @Test void noSliceLeavesANegativeOrNonFiniteBand() {
        // A fixed grid of layers, flows and starting bands, from still to turning over many times a slice.
        double[] volumes={0,1e-6,.06,7.6},areas={0,.126,9.84},speeds={0,1e-5,.01,.05},flows={0,1e-4,.005,.5},ratios={0,.089,1},
                exponents={0,2.39,4.65},seconds={.05,5,20};
        int checked=0;
        for(double layer:volumes)for(double area:areas)for(double speed:speeds)for(double in:flows)for(double out:flows)for(double ratio:ratios)
            for(double exponent:exponents)for(double dt:seconds)for(int start=0;start<3;start++) {
                double volume=layer;
                var band=start==0?Separation.Band.NONE:new Separation.Band(volume*(start==1?.5:1),volume*(start==1?.05:1));
                for(int k=0;k<3;k++) {
                    var slice=Separation.advance(band,volume,area,speed,exponent,in,ratio,out,dt);
                    var next=slice.next();
                    assertTrue(next.turbid()>=0&&next.held()>=0&&Double.isFinite(next.turbid()+next.held()));
                    assertTrue(slice.carried()>=0&&Double.isFinite(slice.carried()));
                    assertTrue(next.turbid()<=Math.max(volume,0)+in*dt+1e-12,"turbid within the layer");
                    if(out==0)assertEquals(0,slice.carried());
                    band=next;volume=Math.max(0,volume+(in-out)*dt);checked++;
                }
            }
        assertTrue(checked>10000);
    }
}
