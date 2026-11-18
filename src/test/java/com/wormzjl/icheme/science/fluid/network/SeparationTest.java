package com.wormzjl.icheme.science.fluid.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Separation} on the numbers of batch 2026-11-17-three-phase-separator's plan, section 3: the review plant's
 * receiver (oil 666 kg/m³ and 0.30 mPa s, water 984 kg/m³ and 0.47 mPa s, gas 5.2 kg/m³).
 */
class SeparationTest {
    private static final double OIL=666.35,WATER=983.79,OIL_VISCOSITY=2.999e-4,WATER_VISCOSITY=4.732e-4;
    private static final double OIL_FLOW=.0042,WATER_FLOW=3.74e-4,GAS_FLOW=.0343,GAS=5.22;

    @Test void theDesignDropsSettleAtFortyFourAndTenMillimetresASecond() {
        // Owner, 2026-11-17 (option B): a water drop of 0.5 mm in the oil, an oil drop of 0.2 mm in the water.
        assertEquals(5e-4,Separation.DROPS.water());
        assertEquals(2e-4,Separation.DROPS.oil());
        assertEquals(43.7e-3,Separation.settling(Separation.DROPS.water(),WATER-OIL,OIL_VISCOSITY,OIL),.5e-3);
        assertEquals(10.4e-3,Separation.settling(Separation.DROPS.oil(),WATER-OIL,WATER_VISCOSITY,WATER),.2e-3);
        // A tenth of a millimetre: 5.0 mm/s in the oil and 3.3 mm/s in the water.
        assertEquals(5.0e-3,Separation.settling(1e-4,WATER-OIL,OIL_VISCOSITY,OIL),.15e-3);
        assertEquals(3.3e-3,Separation.settling(1e-4,WATER-OIL,WATER_VISCOSITY,WATER),.15e-3);
        // A small drop in a thick oil is Stokes': the correction vanishes with the Reynolds number.
        double stokes=PassiveStepSolver.GRAVITY*1e-10*90/(18*.01);
        assertEquals(stokes,Separation.settling(1e-5,90,.01,900),stokes*1e-3);
        assertEquals(Separation.settling(1e-4,318,OIL_VISCOSITY,OIL),Separation.settling(1e-4,-318,OIL_VISCOSITY,OIL));
        assertEquals(0,Separation.settling(1e-4,0,OIL_VISCOSITY,OIL));
    }

    @Test void aLayerIsCleanFromASettlingNumberOfOne() {
        assertEquals(0,Separation.carried(1,1,10));
        assertEquals(0,Separation.carried(11.9,1,10));
        assertEquals(0,Separation.carried(Double.POSITIVE_INFINITY,1,10));
        // Under one, the share that did not settle of the other liquid that entered per volume of its own.
        assertEquals(.26*.1,Separation.carried(.74,1,10),1e-12);
        // At most volume for volume.
        assertEquals(.57,Separation.carried(.43,11,1),1e-12);
        // Continuous at one, and nothing where nothing of the other liquid entered.
        assertEquals(0,Separation.carried(1-1e-12,1,1),1e-11);
        assertEquals(0,Separation.carried(.2,0,1));
        assertEquals(12.5,Separation.number(5e-3,10,.004),1e-12);
        assertEquals(Double.POSITIVE_INFINITY,Separation.number(5e-3,10,0));
    }

    @Test void theGasLawTakesAStandingAndALyingVessel() {
        double standing=Separation.gasLimit(false,OIL,5.12),lying=Separation.gasLimit(true,OIL,5.12);
        assertEquals(.608,standing,.002);
        assertEquals(1.25*standing,lying,1e-12);
        assertEquals(0,Separation.entrained(.37,standing));
        assertEquals(1-standing/.825,Separation.entrained(.825,standing),1e-12);
        assertEquals(0,Separation.entrained(standing,standing));
        assertEquals(Double.POSITIVE_INFINITY,Separation.gasLimit(false,5,5));
        // The plan's table: the review plant's whole overhead, 0.648 m3/s.
        var none=new Separation.Layer(0,0,0,0,0);
        for(double[] row:new double[][]{{.5,.82},{1,.26},{1.5,0}}) {
            var drum=VesselGeometry.round(VesselGeometry.Form.VERTICAL_DRUM,row[0],3,0,0);
            var oil=new Separation.Layer(drum.capacity()/2,OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
            assertEquals(row[1],Separation.of(drum,Separation.DROPS,oil,none,false,.648,5.12).liquidInGas(),.01);
        }
        // A lying drum's vapour space is the segment above its level: half full, then three quarters.
        var lyingDrum=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1.5,5,0,0);
        var half=new Separation.Layer(lyingDrum.volumeAt(.75),OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertEquals(0,Separation.of(lyingDrum,Separation.DROPS,half,none,false,.648,5.12).liquidInGas());
        var high=new Separation.Layer(lyingDrum.volumeAt(1.125),OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertTrue(Separation.of(lyingDrum,Separation.DROPS,high,none,false,.648,5.12).liquidInGas()>.4);
        // No gas leaving, or no liquid to lift: clean.
        assertEquals(0,Separation.of(lyingDrum,Separation.DROPS,half,none,false,0,5.12).liquidInGas());
        assertEquals(0,Separation.of(lyingDrum,Separation.DROPS,none,none,false,.648,5.12).liquidInGas());
    }

    @Test void aStandingVesselsGasLimitFallsWithItsGasSpace() {
        // API 12J's table: 0.24 ft/s at 5 ft against 0.35 at 10 ft is 0.686; the exponent 0.56 gives 0.678.
        assertEquals(0,Separation.gasSpaceFactor(0));
        assertEquals(0,Separation.gasSpaceFactor(-.2));
        assertEquals(.678,Separation.gasSpaceFactor(.762),1e-3);
        assertEquals(1,Separation.gasSpaceFactor(1.524));
        assertEquals(1,Separation.gasSpaceFactor(7));
        // A standing drum 1 m by 4 m (4.5 m high) at 0.4 m/s, between its limit 0.3 m under the top and its full limit.
        var drum=VesselGeometry.round(VesselGeometry.Form.VERTICAL_DRUM,1,4,0,0);
        var none=new Separation.Layer(0,0,0,0,0);
        double full=Separation.gasLimit(false,OIL,5.12),reduced=full*Separation.gasSpaceFactor(.3),flow=.4*Math.PI/4;
        assertTrue(reduced<.4&&.4<full,reduced+" "+full);
        var half=new Separation.Layer(drum.capacity()/2,OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertEquals(0,Separation.of(drum,Separation.DROPS,half,none,false,flow,5.12).liquidInGas());
        var high=new Separation.Layer(drum.volumeAt(drum.height()-.3),OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertEquals(1-reduced/.4,Separation.of(drum,Separation.DROPS,high,none,false,flow,5.12).liquidInGas(),1e-6);
        assertEquals(reduced,Separation.gasLimit(drum,drum.height()-.3,OIL,5.12),1e-12);
        // Liquid-full with gas leaving: the gas outlet draws the bulk, as in a lying drum.
        var brim=new Separation.Layer(drum.capacity(),OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertEquals(1,Separation.of(drum,Separation.DROPS,brim,none,false,flow,5.12).liquidInGas());
        assertEquals(0,Separation.gasLimit(drum,drum.height(),OIL,5.12));
        // A lying drum does not read its gas space: its result is the full-height law's, bit for bit.
        var lying=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1,3,0,0);
        for(double level:new double[]{.3,.5,.8}) {
            var oil=new Separation.Layer(lying.volumeAt(level),OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
            double top=lying.heightAt(oil.volume()),expected=Separation.entrained(.648/lying.vapourSection(top),Separation.gasLimit(true,OIL,5.12));
            assertEquals(expected,Separation.of(lying,Separation.DROPS,oil,none,false,.648,5.12).liquidInGas());
            assertEquals(Separation.gasLimit(true,OIL,5.12),Separation.gasLimit(lying,top,OIL,5.12));
        }
    }

    @Test void aDemisterDoublesTheGasLimitAndTheGasSpaceFactorStillApplies() {
        assertEquals(2,Separation.DEMISTER);
        var bare=VesselGeometry.round(VesselGeometry.Form.VERTICAL_DRUM,1,4,0,0);var padded=bare.withDemister(true);
        var none=new Separation.Layer(0,0,0,0,0);
        double full=Separation.gasLimit(false,OIL,5.12),speed=1.5*full,flow=speed*Math.PI/4;
        // Half full (2.25 m of gas space): between the bare and the padded limit, the bare drum carries and the padded one does not.
        var half=new Separation.Layer(bare.capacity()/2,OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertEquals(1-full/speed,Separation.of(bare,Separation.DROPS,half,none,false,flow,5.12).liquidInGas(),1e-12);
        assertEquals(0,Separation.of(padded,Separation.DROPS,half,none,false,flow,5.12).liquidInGas());
        assertEquals(2*full,Separation.gasLimit(true,true,OIL,5.12)/1.25,1e-12);
        // 0.3 m under the top both limits take the gas space factor.
        double factor=Separation.gasSpaceFactor(.3),level=bare.height()-.3;
        assertEquals(full*factor,Separation.gasLimit(bare,level,OIL,5.12),1e-12);
        assertEquals(2*full*factor,Separation.gasLimit(padded,level,OIL,5.12),1e-12);
        var high=new Separation.Layer(bare.volumeAt(level),OIL,OIL_VISCOSITY,OIL_FLOW,OIL_FLOW);
        assertEquals(1-2*full*factor/speed,Separation.of(padded,Separation.DROPS,high,none,false,flow,5.12).liquidInGas(),1e-6);
        // A lying drum's pad doubles its limit too.
        var lying=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,1,3,0,0);
        assertEquals(2*Separation.gasLimit(true,OIL,5.12),Separation.gasLimit(lying.withDemister(true),.5,OIL,5.12),1e-12);
    }

    /** A lying drum with a boot, half full of oil, its boot full of water, at {@code scale} times the review plant's flows. */
    private static Separation.Carried receiver(double diameter,double length,double bootDiameter,double bootLength,double scale) {
        var drum=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,diameter,length,bootDiameter,bootLength);
        var oil=new Separation.Layer(drum.volumeAt(diameter/2)-drum.bootCapacity(),OIL,OIL_VISCOSITY,scale*OIL_FLOW,scale*OIL_FLOW);
        var water=new Separation.Layer(drum.bootCapacity(),WATER,WATER_VISCOSITY,scale*WATER_FLOW,scale*WATER_FLOW);
        return Separation.of(drum,Separation.DROPS,oil,water,true,scale*GAS_FLOW,GAS);
    }

    @Test void aProperlySizedReceiverIsCleanAndASmallOneCarries() {
        assertTrue(receiver(2,5,2.0/3,.7,1).none());
        assertTrue(receiver(1,6,.4,.5,1).none());
        assertTrue(receiver(.5,9,.25,.4,1).none());
        // A 1 m drum at four times the flow: its water leaves the boot faster than the oil drops rise; its oil layer
        // is wide enough, and its gas is under its limit.
        var small=receiver(1,6,.4,.5,4);
        assertEquals(0,small.waterInOil());
        assertEquals(.13,small.oilInWater(),.01);
        assertEquals(0,small.liquidInGas());
        // The sized drum at sixteen times the flow: the boot under one, the oil layer still over six.
        var loaded=receiver(2,5,2.0/3,.7,16);
        assertEquals(0,loaded.waterInOil());
        assertEquals(.395,loaded.oilInWater(),.02);
        assertEquals(0,loaded.liquidInGas());
        // At 128 times both layers are under one, and the gas is over its limit.
        var flooded=receiver(2,5,2.0/3,.7,128);
        assertTrue(flooded.waterInOil()>.005&&flooded.waterInOil()<.03,"water in oil "+flooded.waterInOil());
        assertEquals(.92,flooded.oilInWater(),.02);
        assertTrue(flooded.liquidInGas()>.5,"liquid in gas "+flooded.liquidInGas());
    }

    @Test void oneLiquidOutletKeepsNothingApartAndAStillVesselSettles() {
        var drum=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,.5,9,.25,.4);
        // At 64 times the review plant's flows both of its layers are under one.
        var oil=new Separation.Layer(drum.volumeAt(.25)-drum.bootCapacity(),OIL,OIL_VISCOSITY,64*OIL_FLOW,64*OIL_FLOW);
        var water=new Separation.Layer(drum.bootCapacity(),WATER,WATER_VISCOSITY,64*WATER_FLOW,64*WATER_FLOW);
        var carried=Separation.of(drum,Separation.DROPS,oil,water,true,0,0);
        assertTrue(carried.waterInOil()>.02&&carried.oilInWater()>.9,carried.toString());
        assertTrue(Separation.of(drum,Separation.DROPS,oil,water,false,0,0).none());
        var stillOil=new Separation.Layer(oil.volume(),OIL,OIL_VISCOSITY,64*OIL_FLOW,0);
        var stillWater=new Separation.Layer(water.volume(),WATER,WATER_VISCOSITY,64*WATER_FLOW,0);
        assertTrue(Separation.of(drum,Separation.DROPS,stillOil,stillWater,true,0,0).none());
        // A larger water drop settles what a small one leaves: the desalter's field. The oil drops in its water stay.
        var field=Separation.of(drum,new Separation.Drops(1.3e-3,Separation.DROPS.oil()),oil,water,true,0,0);
        assertEquals(0,field.waterInOil());
        assertEquals(carried.oilInWater(),field.oilInWater());
    }

    @Test void aPlanAreaIsTheSlopeOfTheVolume() {
        for(var g:List.of(VesselGeometry.prism(3,2),VesselGeometry.round(VesselGeometry.Form.CYLINDER,4,3,0,0),
                VesselGeometry.round(VesselGeometry.Form.SPHERE,4,0,0,0),VesselGeometry.round(VesselGeometry.Form.VERTICAL_DRUM,2,6,0,0),
                VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,0,0),VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7))) {
            double low=-g.bootDepth(),step=1e-5;
            for(int i=1;i<40;i++) {
                double h=low+(g.height()-low)*i/40;
                double slope=(g.volumeAt(h+step)-g.volumeAt(h-step))/(2*step);
                assertEquals(slope,g.planArea(h),1e-5*Math.max(1,slope),g.form()+" at "+h);
            }
        }
        var lying=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,0,0);
        assertEquals(Math.PI/2,lying.vapourSection(1),1e-12);
        assertEquals(Math.PI,lying.vapourSection(0),1e-12);
        assertEquals(0,lying.vapourSection(2),1e-12);
        var standing=VesselGeometry.round(VesselGeometry.Form.VERTICAL_DRUM,2,6,0,0);
        assertEquals(Math.PI,standing.vapourSection(.3),1e-12);
        assertEquals(Math.PI,standing.vapourSection(5),1e-12);
        assertTrue(lying.lying()&&!standing.lying());
    }
}
