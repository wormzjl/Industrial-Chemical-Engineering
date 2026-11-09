from pathlib import Path
p=Path('src/test/java/com/wormzjl/icheme/client/gui/common/ConstructorColumnTest.java');s=p.read_text(encoding='utf-8').replace('List.of("nozzles", "paint", "build"), drum.footer()', 'List.of("paint", "build"), drum.footer()');s=s.replace('assertEquals(List.of("flow", "flow-", "flow+", "head", "head-", "head+", "size", "poles:2", "poles:4"), slots(pump).subList(0, 9));','assertEquals(List.of("flow", "flow-", "flow+", "head", "head-", "head+", "size", "base_speed", "base_speed-", "base_speed+"), slots(pump).subList(0, 10));').replace('assertTrue(buttons(pump).get("poles:2").latched() && buttons(pump).get("poles:4").active());','assertFalse(slots(pump).stream().anyMatch(k->k.startsWith("poles:")));');i=s.index('    private static Map<String, Rect> widgets');s=s[:i]+'''    @Test void nozzleControlsStayOnTheMainDesignDoorWithOneFooter(){
        var t=new HeatControlColumnTest.Recording("gui.icheme.");
        for(var form:List.of(Form.VERTICAL,Form.CROSSWISE,Form.COLUMN)){
            var in=design(form,2,6,3,3,0,0);
            var page=ConstructorColumn.design(in,form.column()?null:connections(2,1,form.orientation().horizontal(),0,null,true,false,11),form.column()?nozzles("draw",true,true,14):null,t);
            var keys=slots(page);assertTrue(keys.containsAll(List.of("forward","right","up",form.column()?"nozzle":"connection")));
            assertEquals(List.of("paint","build"),page.footer().stream().map(Column.Button::key).toList());
            assertFalse(buttons(page).containsKey("nozzles"));if(form.column())assertTrue(buttons(page).containsKey("reset"));
            for(int width:new int[]{ConstructorColumn.WIDTH,ConstructorColumn.WIDTH-BAR}){
                var column=Column.place(page.rows(),0,0,width,M,Column.Metrics.COMPACT);column.slots();DeviceColumnTest.assertInLine(column,"inline nozzles "+form);
                for(var row:column.rows())for(var line:row.texts())assertTrue(M.width(line)<=row.rect().w(),line);
            }
        }
        assertEquals(Set.of(),t.missing);
    }
    @Test void pumpMotorAndGeneratorUseDirectRpmWithoutPoleButtons(){
        var t=new HeatControlColumnTest.Recording("gui.icheme.");
        for(var form:List.of(Form.PUMP,Form.MOTOR,Form.GENERATOR)){
            var d=UnitDesign.of(form.kind());var page=ConstructorColumn.design(design(form,d.minSize(),d.minLength(d.minSize()),0,3,0,0),t);
            assertTrue(slots(page).containsAll(List.of("base_speed","base_speed-","base_speed+")));
            assertFalse(slots(page).stream().anyMatch(k->k.startsWith("poles:")));
        }
    }

'''+s[i:];p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/wormzjl/icheme/client/gui/common/ControlColumnsTest.java');s=p.read_text(encoding='utf-8');i=s.rindex('}');s=s[:i]+'''    @Test void sensorHoversShowCoordinatesAndEquipmentIdWithoutChangingSelectionLabels(){
        var text=new HeatControlColumnTest.Recording("gui.icheme.");
        var hint=ControlColumns.sensorHint(new ControlColumns.SensorLocation(true,true,12,64,-30,7),text::get);
        assertTrue(hint.contains("12, 64, -30"));assertTrue(hint.contains("#0007"));assertFalse(hint.contains("Dimension"));
        var rows=ControlColumns.choices("Input",List.of(new ControlColumns.Choice("sensor:id","LT-0012 / LEVEL",true,hint)),text::get);
        var list=Column.place(rows,10,30,210,Measure.fixed(6),Column.Metrics.COMPACT);
        var choice=list.rows().stream().filter(p->p.row() instanceof Column.Choice).findFirst().orElseThrow();
        assertEquals(hint,list.hover(choice.rect().x()+5,choice.rect().y()+3));assertTrue(choice.texts().contains("LT-0012 / LEVEL"));
        var loop=Column.place(ControlColumns.loop(new ControlColumns.View(true,"m","%",true,"LT-0012 / LEVEL","25.0","CV-0013","CONTROLLING",true,ControlColumns.Variable.LEVEL),hint,text::get),10,30,210,Measure.fixed(6),Column.Metrics.COMPACT);
        var label=loop.rows().stream().filter(p->p.row() instanceof Column.Paragraph para&&para.text().equals("LT-0012 / LEVEL")).findFirst().orElseThrow();
        assertEquals(hint,loop.hover(label.rect().x()+5,label.rect().y()+3));
        var pipe=ControlColumns.sensorHint(new ControlColumns.SensorLocation(true,false,1,2,3,0),text::get);assertFalse(pipe.contains("Equipment:"));
        assertEquals(Set.of(),text.missing);
    }
'''+s[i:];p.write_text(s,encoding='utf-8')
