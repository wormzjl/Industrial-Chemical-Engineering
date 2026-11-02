package com.wormzjl.icheme.science.fluid.network;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VesselGeometryTest {
    @Test void independentTenPercentHeightReferences(){
        var drum=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,0,0);
        assertEquals(.32041532283655283,drum.heightAt(.1*drum.capacity()),1e-12);
        var sphere=VesselGeometry.round(VesselGeometry.Form.SPHERE,4,0,0,0);
        assertEquals(.7832004226363669,sphere.heightAt(.1*sphere.capacity()),1e-12);
    }
    @Test void everyProfileIsMonotoneAndRoundTripsIncludingBootAndEndpoints(){
        for(var g:List.of(VesselGeometry.prism(3,2),VesselGeometry.round(VesselGeometry.Form.CYLINDER,4,3,0,0),
                VesselGeometry.round(VesselGeometry.Form.SPHERE,4,0,0,0),
                VesselGeometry.round(VesselGeometry.Form.VERTICAL_DRUM,2,6,0,0),
                VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,0,0),
                VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7))){
            double previous=-Double.MAX_VALUE;
            for(int i=0;i<=1000;i++){double volume=g.capacity()*i/1000,h=g.heightAt(volume);assertTrue(h>=previous);assertEquals(volume,g.volumeAt(h),1e-12*g.capacity());previous=h;}
            assertEquals(g.capacity(),g.volumeAt(g.height()),1e-12*g.capacity());
        }
    }
    @Test void bootWeirIsAHeightThresholdAndIncludesBootVolume(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        assertEquals(0,g.heightAt(g.bootCapacity()),1e-12);
        assertEquals(2.0/3,g.heightAt(g.weirVolume()),1e-12);
        assertTrue(g.weirVolume()>g.bootCapacity());
        assertNotEquals(g.capacity()/3,g.weirVolume(),1e-3);
        assertTrue(g.heightAt(.5*g.bootCapacity())<0);
    }
    @Test void hydrostaticHeadUsesLayerHeightsAndDensities(){
        var g=VesselGeometry.round(VesselGeometry.Form.SPHERE,4,0,0,0);
        double water=g.volumeAt(1),total=g.volumeAt(2.5);
        assertEquals(PassiveStepSolver.GRAVITY*(1000+800*1.5),g.head(water,1000*water,total-water,800*(total-water)),1e-9);
    }
    /** The head stands over the body bottom, the node's elevation; the boot below is the line below its nozzle. */
    @Test void bootHeadStandsOverTheBodyBottom(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        assertEquals(0,g.head(g.bootCapacity(),1000*g.bootCapacity(),0,0),1e-9,"a full boot adds nothing over the body bottom");
        double weir=g.weirVolume();
        assertEquals(PassiveStepSolver.GRAVITY*1000*g.weirHeight(),g.head(weir,1000*weir,0,0),1e-6);
        double water=g.volumeAt(.5),top=g.volumeAt(1.2);
        assertEquals(PassiveStepSolver.GRAVITY*(1000*.5+800*.7),g.head(water,1000*water,top-water,800*(top-water)),1e-6);
        double inBoot=g.volumeAt(-.2),oil=g.volumeAt(.4);
        assertEquals(PassiveStepSolver.GRAVITY*800*.4,g.head(inBoot,1000*inBoot,oil-inBoot,800*(oil-inBoot)),1e-6,"only the layer above the body bottom counts");
    }
    @Test void invalidAndDamagedGeometryRefuses(){
        assertThrows(IllegalArgumentException.class,()->new VesselGeometry(VesselGeometry.Form.SPHERE,1,2,2,0,0,0));
        assertThrows(IllegalArgumentException.class,()->VesselGeometry.prism(Double.NaN,1));
    }
}
