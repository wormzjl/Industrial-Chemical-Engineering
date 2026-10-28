package com.wormzjl.icheme.client.gui.common;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.PhaseRatioBasis;

import com.wormzjl.icheme.client.gui.common.ProcessUi.NumericDraft;
import com.wormzjl.icheme.client.gui.common.ProcessUi.TemperatureUnit;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidGuiConsistencyTest {
    @Test void switchingUnitsPreservesUntouchedSiAndConvertsEditedValues() {
        double exact=101325.123456789;var pressure=new NumericDraft(exact,PressureUnit.KILOPASCAL);
        for(int cycle=0;cycle<100;cycle++)for(var unit:PressureUnit.values()){pressure=pressure.in(unit);assertEquals(Double.doubleToRawLongBits(exact),Double.doubleToRawLongBits(pressure.si()));}
        pressure=pressure.in(PressureUnit.BAR);pressure.text("2.5");assertEquals(250000,pressure.si());assertEquals(.25,pressure.in(PressureUnit.MEGAPASCAL).value());
        var flow=new NumericDraft(.0123456789,FlowRateUnit.PER_SECOND);
        for(int i=0;i<100;i++){flow=flow.in(FlowRateUnit.PER_HOUR).in(FlowRateUnit.PER_SECOND);assertEquals(.0123456789,flow.si());}
        var temp=new NumericDraft(298.123456789,TemperatureUnit.CELSIUS);assertEquals(298.123456789,temp.in(TemperatureUnit.KELVIN).in(TemperatureUnit.CELSIUS).si());
        flow.text("NaN");var invalid=flow;assertThrows(IllegalArgumentException.class,()->invalid.in(FlowRateUnit.PER_HOUR));assertEquals("NaN",flow.text());
    }
    @Test void everyPressureUnitRetainsGaugeResolutionAndItsOwnLabel() {
        assertEquals("101325.0 Pa",PressureUnit.PASCAL.format(101325));assertEquals("101.3250 kPa",PressureUnit.KILOPASCAL.format(101325));
        assertEquals("1.013250 bar",PressureUnit.BAR.format(101325));assertEquals("0.1013250 MPa",PressureUnit.MEGAPASCAL.format(101325));
        assertEquals("0.0 Pa",PressureUnit.PASCAL.format(-.04));assertEquals("—",PressureUnit.BAR.format(Double.NaN));
        assertEquals(PressureUnit.PASCAL,PressureUnit.PASCAL.next().next().next().next());
    }
    @Test void phaseBasesUseActualPhaseAmountsAndOnlyDefinedCrystalMoles() {
        var ice=new SolidMaterial("icheme:ice","1",900,2000,new SolidMaterial.Crystal("Water",0));
        var sand=new SolidMaterial("icheme:sand","1",2000,1000);
        var solids=new SolidInventory(List.of(new SolidInventory.Population(ice,new ParticleSize(".001"),.018),new SolidInventory.Population(sand,new ParticleSize(".001"),.2)));
        var components=List.of("Nitrogen","Water");var weights=List.of(.028,.018);double[][] moles={{1,0},{0,2},{3,1}};double[] volumes={.1,.2,.7};
        assertArrayEquals(new double[]{.1,.2,.7,.00012},PhaseRatioBasis.VOLUME.amounts(volumes,moles,solids,components,weights),1e-12);
        assertArrayEquals(new double[]{.028,.036,.102,.218},PhaseRatioBasis.MASS.amounts(volumes,moles,solids,components,weights),1e-12);
        assertArrayEquals(new double[]{1,2,4,1},PhaseRatioBasis.MOLAR.amounts(volumes,moles,solids,components,weights),1e-12);
        assertTrue(PhaseRatioBasis.MOLAR.excludesCustomSolids(solids));assertFalse(PhaseRatioBasis.MASS.excludesCustomSolids(solids));
        assertEquals(PhaseRatioBasis.VOLUME,PhaseRatioBasis.VOLUME.next().next().next());
        assertThrows(IllegalArgumentException.class,()->PhaseRatioBasis.MASS.amounts(volumes,moles,solids,components,List.of(.028,0.0)));
    }
    @Test void sharedControlsFitEverySupportedScreenWidth() {
        for(int width=300;width<=790;width++){
            var footer=FluidGuiLayout.footer(width);assertEquals(6,footer.size());int end=4;
            for(var slot:footer){assertTrue(slot.x()>=end+6);assertTrue(slot.width()>=32);end=slot.x()+slot.width();assertTrue(end<=width-10);}
            for(int count=1;count<=4;count++)for(var slot:FluidGuiLayout.tabs(width,count)){assertTrue(slot.x()>=10);assertTrue(slot.x()+slot.width()<=width-10);}
        }
    }
    /**
     * The Control tab's columns (HEAT_GUI_PLAN.md section 2; layout C, GUI_TOOLKIT_PLAN.md section 10) at every supported
     * width: the drawing keeps at least 100 px (a third of the design grid); from about 460 px two columns, each at least
     * 120 px with 12 px between them, else one column of 150 to 240 px; the scrollbar after them, inside the screen.
     */
    @Test void theControlTabsColumnsFitEverySupportedScreenWidth() {
        boolean split=false;
        for(int width=300;width<=790;width++){
            var c=FluidGuiLayout.control(width);
            assertTrue(c.drawingRight()-10>=100,"drawing at "+width);
            assertTrue(c.leftX()>=c.drawingRight()+10,"the columns right of the drawing at "+width);
            if(c.split()){
                split=true;
                assertTrue(c.leftWidth()>=120&&c.rightWidth()>=FluidGuiLayout.SPLIT_MIN,"two columns at "+width);
                assertEquals(c.leftX()+c.leftWidth()+12,c.rightX());
            }else{
                assertTrue(c.rightWidth()>=150&&c.rightWidth()<=240,"one column at "+width);
                assertEquals(c.leftX(),c.rightX());
                assertTrue(!split,"two columns from some width on, at "+width);
            }
            assertTrue(c.scrollX()>=c.rightX()+c.rightWidth()&&c.scrollX()+8<=width-6,"scrollbar at "+width);
        }
        assertTrue(FluidGuiLayout.control(464).split(),"two columns at 464 px: the 480 by 270 GUI");
        assertThrows(IllegalArgumentException.class,()->FluidGuiLayout.control(299));
    }
}
