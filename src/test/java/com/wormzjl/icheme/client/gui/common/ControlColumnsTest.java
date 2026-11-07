package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlColumnsTest {
    @Test void panelsFitTheSuppliedGuiDimensionsWithNoScaleOverride(){
        for(int width=320;width<=1280;width+=7)for(int height=240;height<=800;height+=53)for(boolean dcs:new boolean[]{false,true}){
            var frame=ControlColumns.layout(width,height,dcs);var panel=new Rect(0,0,frame.width(),frame.height());
            assertTrue(frame.width()<=width-8&&frame.height()<=height-8);
            for(var area:frame.areas())assertTrue(area.inside(panel));
            for(var footer:DialogColumns.footer(frame.width(),frame.height(),2)){
                assertTrue(footer.inside(panel));
                assertTrue(frame.areas().getFirst().bottom()<=footer.y());
            }
            if(frame.chooser()!=null)assertTrue(frame.chooser().inside(panel)&&frame.chooser().bottom()<frame.areas().getFirst().y());
        }
        assertFalse(ControlColumns.layout(320,240,true).split());
        assertNotNull(ControlColumns.layout(320,240,true).chooser());
        assertTrue(ControlColumns.layout(640,360,true).split());
    }
    @Test void levelSetupNamesTheValveLocationAndFitsBothPanels(){
        var text=new HeatControlColumnTest.Recording("gui.icheme.");
        var measure=Measure.fixed(6);
        for(var variable:ControlColumns.Variable.values())for(boolean outlet:new boolean[]{false,true})
            for(int width:new int[]{210,214,222,226}){
                var rows=ControlColumns.loop(new ControlColumns.View(true,"m","%",true,"LT-0012 / LEVEL","37.0","CV-0021","CONTROLLING",outlet,variable),text::get);
                var column=Column.place(rows,10,30,width,measure,Column.Metrics.COMPACT);
                for(var p:column.rows()){
                    assertTrue(p.rect().x()>=column.bounds().x()&&p.rect().right()<=column.bounds().right());
                    for(var line:p.texts())assertTrue(measure.width(line)<=p.rect().w(),line+" at "+width);
                    for(var slot:p.slots().values())assertTrue(slot.inside(p.rect())&&slot.w()>=40,slot.toString());
                    if(p.row() instanceof Column.Group)assertEquals(1,p.texts().size());
                }
                var slots=column.slots();
                assertEquals(variable==ControlColumns.Variable.LEVEL||variable==ControlColumns.Variable.PRESSURE,slots.containsKey("outlet"));
                DeviceColumnTest.assertInLine(column,"controller "+width);
            }
        assertEquals(Set.of(),text.missing);
    }
    @Test void accessoryIdsAreVisibleWithoutAProcessReading(){
        var text=new HeatControlColumnTest.Recording();
        assertTrue(DeviceColumn.instrumentIds(List.of(),text).isEmpty());
        var column=Column.place(DeviceColumn.instrumentIds(List.of("LT-0012","PT-0013","CV-0021"),text),10,30,140,Measure.fixed(6),Column.Metrics.COMPACT);
        var lines=column.rows().stream().flatMap(p->p.texts().stream()).toList();
        assertTrue(lines.containsAll(List.of("LT-0012","PT-0013","CV-0021")));
        assertEquals(Set.of(),text.missing);
    }
}
