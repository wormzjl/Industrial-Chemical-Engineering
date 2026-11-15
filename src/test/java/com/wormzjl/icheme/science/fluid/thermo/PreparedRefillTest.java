package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Finding P-18 (batch 2026-11-13-codebase-review): a node's prepared bundle is refilled in place when its temperature
 * changes. A refilled bundle must give the bits of a new one at that temperature, whatever support its last use left in its
 * kernel workspace, and build no new workspace.
 */
class PreparedRefillTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    /** A gas of the first two compounds and a liquid of two heavier ones (water left out), {@code scale} mol each. */
    private double[][] slots(int light,int heavy,double scale) {
        int n=model.componentCount();double[] gas=new double[n],oil=new double[n];
        for(int c=0;c<light;c++)gas[c]=scale*(c+1);
        for(int c=0;c<heavy;c++)oil[n-2-c]=scale*(c+2);
        return new double[][]{gas,oil,new double[n]};
    }
    private static boolean[] mask(double[][] slots) {
        boolean[] mask=new boolean[slots[0].length];
        for(double[] slot:slots)for(int c=0;c<slot.length;c++)mask[c]|=slot[c]!=0;
        return mask;
    }
    private FluidThermodynamics.State evaluate(double t,double p,double[][] slots,FluidThermodynamics.Prepared prepared) {
        prepared.carry(mask(slots));
        return model.state(t,p,slots[0],slots[1],slots[2],prepared);
    }
    private static void assertSameBits(FluidThermodynamics.State fresh,FluidThermodynamics.State refilled) {
        for(var component:FluidThermodynamics.State.class.getRecordComponents())try {
            Object a=component.getAccessor().invoke(fresh),b=component.getAccessor().invoke(refilled);
            if(a instanceof Double x)assertEquals(Double.doubleToLongBits(x),Double.doubleToLongBits((Double)b),component.getName());
            else if(a instanceof double[] x)assertArrayEquals(x,(double[])b,0,component.getName());
            else if(a instanceof com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel.Phase x)assertSamePhase(x,(HydrocarbonModel.Phase)b,component.getName());
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void assertSamePhase(HydrocarbonModel.Phase a,HydrocarbonModel.Phase b,String name) {
        if(a==null||b==null){assertSame(a,b,name);return;}
        assertEquals(Double.doubleToLongBits(a.molarVolume()),Double.doubleToLongBits(b.molarVolume()),name);
        assertEquals(Double.doubleToLongBits(a.molarEnthalpy()),Double.doubleToLongBits(b.molarEnthalpy()),name);
        assertEquals(Double.doubleToLongBits(a.molarInternalEnergy()),Double.doubleToLongBits(b.molarInternalEnergy()),name);
        assertEquals(Double.doubleToLongBits(a.volumePressureDerivative()),Double.doubleToLongBits(b.volumePressureDerivative()),name);
        assertTrue(Arrays.equals(a.logFugacity(),b.logFugacity()),name+" ln phi");
        assertEquals(a.vaporLike(),b.vaporLike(),name);
    }

    @Test void aRefilledBundleGivesANewBundlesBitsOnAWiderAndANarrowerSupport() {
        double p=2e5;
        // First used on two compounds, then refilled for six; and the other way round.
        for(int[][] uses:new int[][][]{{{2,0},{3,3}},{{3,3},{1,0}}}) {
            var first=slots(uses[0][0],uses[0][1],.4);var second=slots(uses[1][0],uses[1][1],.7);
            var reused=model.prepare(320);
            evaluate(320,p,first,reused);
            long built=FluidThermodynamics.Prepared.workspacesBuilt();
            var refilled=model.prepare(345,reused);
            assertSame(reused,refilled,"refilled in place");
            assertEquals(345,refilled.temperature());
            var fromRefill=evaluate(345,p,second,refilled);
            assertEquals(built,FluidThermodynamics.Prepared.workspacesBuilt(),"the refill builds no kernel workspace");
            var fromNew=evaluate(345,p,second,model.prepare(345));
            assertSameBits(fromNew,fromRefill);
            // The same temperature keeps the bundle as it is.
            assertSame(refilled,model.prepare(345,refilled));
        }
    }
}
