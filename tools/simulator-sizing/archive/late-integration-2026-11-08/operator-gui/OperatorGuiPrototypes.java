import java.nio.file.*;
import java.util.*;
import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.*;
import com.wormzjl.icheme.science.equipment.*;
public class OperatorGuiPrototypes {
 public static void main(String[] args)throws Exception{
  HeatDiagramRender.lang=HeatDiagramRender.lang(Path.of(args[0]));HeatDiagramRender.font=HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));Files.createDirectories(Path.of(args[1]));
  var t=GuiPrototypes.DIALOG;var words=List.of("front","right","back","left");var outlet=new ConstructorColumn.Outlet(11,0,words);
  var connections=new ConstructorColumn.Connections(2,1,1,words,"4.5",false,-1,"back",true,false,false,outlet);
  var nozzles=new ConstructorColumn.Nozzles(7,2,new ConstructorColumn.Nozzle("pumparound_draw",1,true,"18.5",new UnitDesign.Line("nozzle.under_out",12),false,3,1),words,0);
  var forms=List.of(VesselPlacement.Form.VERTICAL,VesselPlacement.Form.COLUMN,VesselPlacement.Form.PUMP,VesselPlacement.Form.GENERATOR);
  for(var form:forms){boolean drum=form==VesselPlacement.Form.VERTICAL,col=form==VesselPlacement.Form.COLUMN;double size=drum?2:col?8:form==VesselPlacement.Form.PUMP?.1:250;int length=drum?6:col?20:1;
   var design=new ConstructorColumn.Design(form,size,length,drum?3:col?10:0,3,0,0,new ProcessUnit.Bank(1,1),col?new ConstructorColumn.Imported(8,42,38):null,form.designed()?new ConstructorColumn.Outlet(1,0,words):outlet,16,TowerBank.ONE,form.designed()?UnitDesign.of(form.kind()).duty(size,length):MachineDuty.NONE);
   var page=ConstructorColumn.design(design,drum?connections:null,col?nozzles:null,t);String name=form.name();
   var proto=new GuiPrototypes.Prototype(name,464,254,(d,skin)->GuiPrototypes.constructorDoor(d,page,name,"change","Ready",Palette.ACCENT,Map.of()),true);
   var pictures=List.of(GuiPrototypes.render(proto,PanelSkin.LIGHT,false),GuiPrototypes.render(proto,PanelSkin.DARK,false),GuiPrototypes.render(proto,PanelSkin.LIGHT,true),GuiPrototypes.render(proto,PanelSkin.DARK,true));
   HeatDiagramRender.grid(Path.of(args[1],name.toLowerCase()+".png"),List.of("Light","Dark","Light, end scroll","Dark, end scroll"),pictures);
  }
  var hint=ControlColumns.sensorHint(new ControlColumns.SensorLocation(true,true,12,64,-30,7),(k,a)->HeatDiagramRender.tr("gui.icheme."+k,a));
  var rows=ControlColumns.choices("Input",List.of(new ControlColumns.Choice("sensor:1","LT-0012 / LEVEL",true,hint),new ControlColumns.Choice("sensor:2","PT-0013 / PRESSURE",false,hint)),(k,a)->HeatDiagramRender.tr("gui.icheme."+k,a));
  var proto=new GuiPrototypes.Prototype("sensor-locations",464,254,(d,skin)->{skin.title(d.c,10,10,"Sensor selection");d.column(rows,10,30,220,Map.of());skin.well(d.c,new Rect(244,36,208,80));d.c.text("Hover: LT-0012",250,42,Palette.TEXT);int y=60;for(var line:hint.split("\n")){d.c.text(line,250,y,Palette.TEXT);y+=12;}},false);
  HeatDiagramRender.grid(Path.of(args[1],"sensor-locations.png"),List.of("Light","Dark"),List.of(GuiPrototypes.render(proto,PanelSkin.LIGHT,false),GuiPrototypes.render(proto,PanelSkin.DARK,false)));
 }
}
