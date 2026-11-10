package com.wormzjl.icheme.client.fx.compat.dh;

import com.wormzjl.icheme.client.fx.FxCompat;
import com.wormzjl.icheme.client.fx.FxGuard;
import com.wormzjl.icheme.client.fx.silhouette.FarRenderer;
import com.wormzjl.icheme.client.fx.silhouette.SilhouetteBox;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan section 4.8 (batch 2026-11-10-custom-shader-system, W10): a DH API of another major, an older minor, a missing method
 * or no API at all leaves the far silhouettes off, without an exception; the effects core loads without DH. The test
 * classpath holds no DH (it is {@code compileOnly}), so the bridge here meets the missing API itself.
 */
class DhBridgeTest {
    @BeforeAll static void noDhOnTheClasspath() {
        boolean absent;
        try{Class.forName("com.seibel.distanthorizons.api.DhApi",false,DhBridgeTest.class.getClassLoader());absent=false;}
        catch(ClassNotFoundException e){absent=true;}
        assumeTrue(absent,"these tests stand for a missing DH API");
    }

    @Test void theGateTakesApiSevenFromMinorTwo() {
        assertTrue(DhBridge.gate(()->7,()->2));
        assertTrue(DhBridge.gate(()->7,()->9));
        assertFalse(DhBridge.gate(()->7,()->1),"an older minor may lack a method we call");
        assertFalse(DhBridge.gate(()->6,()->9),"an older major");
        assertFalse(DhBridge.gate(()->4,()->1),"the API 4.1.0 this batch first named");
        assertFalse(DhBridge.gate(()->8,()->0),"a new major breaks the API");
    }

    @Test void aMissingVersionMethodFailsTheGateWithoutAnException() {
        assertFalse(assertDoesNotThrow(()->DhBridge.gate(()->{throw new NoSuchMethodError("getApiMajorVersion");},()->2)));
        assertFalse(assertDoesNotThrow(()->DhBridge.gate(()->7,()->{throw new NoSuchMethodError("getApiMinorVersion");})));
        assertFalse(assertDoesNotThrow(()->DhBridge.gate(()->{throw new NoClassDefFoundError("com/seibel/distanthorizons/api/DhApi");},()->2)));
        assertFalse(assertDoesNotThrow(()->DhBridge.gate(()->{throw new IllegalStateException("not ready");},()->2)));
    }

    @Test void withoutTheApiTheBridgeLoadsAndStaysOffLoggingOnce() {
        var logged=new ArrayList<String>();
        var bridge=assertDoesNotThrow(()->new DhBridge(new FxGuard("Distant Horizons",()->"stub",(m,e)->logged.add(m))));
        assertFalse(assertDoesNotThrow(bridge::compatible));
        assertDoesNotThrow(bridge::status);
        assertFalse(assertDoesNotThrow(bridge::ready));
        assertEquals(0,assertDoesNotThrow(bridge::radius));
        assertNull(assertDoesNotThrow(()->bridge.add(BlockPos.ZERO,List.of(new SilhouetteBox(0,0,0,1,1,1,0x808080,false)))));
        for(int k=0;k<2*DhBridge.POLL;k++)assertDoesNotThrow(bridge::tick);
        assertFalse(bridge.on(),"the first failed call turns the integration off");
        assertTrue(logged.size()<=1,"one warning at most: "+logged);
    }

    @Test void theEffectsCoreLoadsWithoutDhAndKeepsTheFallback() {
        for(var name:List.of("com.wormzjl.icheme.client.fx.silhouette.FarRenderer","com.wormzjl.icheme.client.fx.silhouette.SilhouetteShapes",
                "com.wormzjl.icheme.client.fx.silhouette.SilhouetteBudget","com.wormzjl.icheme.client.fx.silhouette.FarSilhouettes",DhBridge.class.getName()))
            assertDoesNotThrow(()->Class.forName(name,true,DhBridgeTest.class.getClassLoader()),name);
        // Outside a running game no mod is loaded: the fallback, with no attempt to load the bridge.
        assertSame(FarRenderer.NONE,FxCompat.load(DhBridge.MOD,DhBridge.class.getName(),FarRenderer.class,FarRenderer.NONE));
        assertFalse(FarRenderer.NONE.on());
        assertNull(FarRenderer.NONE.add(BlockPos.ZERO,List.of()));
    }
}
