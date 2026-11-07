import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.*;
import com.wormzjl.icheme.science.equipment.*;
import java.nio.file.*;
import java.util.*;
public class DriveRender {
    public static void main(String[] args)throws Exception {
        HeatDiagramRender.lang=HeatDiagramRender.lang(Path.of(args[0]));
        HeatDiagramRender.font=HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));
        var out=Path.of(args[1]);Files.createDirectories(out);
        for(var mode:List.of(MachineColumn.Mode.SPEED,MachineColumn.Mode.FLOW)) {
            var input=new MachineColumn.Input(MachineColumn.Kind.MOTOR,true,true,true,mode,false,null,List.of(new MachineColumn.Meter("speed","2400","rpm",null),new MachineColumn.Meter("power","9.0","kW",null)),List.of(new MachineColumn.Meter("electric","10.0","kW",null),new MachineColumn.Meter("draw","200","FE/t",null),new MachineColumn.Meter("store","72","%",null)),"1 min 10 s");
            var rows=MachineColumn.rows(input,GuiPrototypes.DIALOG);
            var prototype=GuiPrototypes.devicePage("vfd-"+mode,1920,1080,rows,(d,s)->{
                d.title("VFD motor");d.tabs(0,"Overview");d.status("RUNNING",Palette.MUTED);
                var box=GuiPrototypes.deviceDoor(d,rows,Map.of("speed","2400","powerLimit","22"),"vfd");
                MotorCurve.draw(d.c,box,22000,3000,2400,9000,GuiPrototypes.DIALOG);
                GuiPrototypes.pushFooter(d,1,"Close","Apply");
            });
            for(boolean end:List.of(false,true))HeatDiagramRender.grid(out.resolve("vfd-"+mode.name().toLowerCase()+"-"+(end?"end":"start")+".png"),List.of("Light","Dark"),List.of(GuiPrototypes.render(prototype,PanelSkin.LIGHT,end),GuiPrototypes.render(prototype,PanelSkin.DARK,end)));
        }
        var unit=UnitDesign.of(EquipmentShape.Kind.MOTOR);var duty=MachineDuty.motor(22,3000.0);
        var input=new ConstructorColumn.Design(VesselPlacement.Form.MOTOR,22,1,0,3,0,0,ProcessUnit.Bank.ONE,null,new ConstructorColumn.Outlet(1,0,GuiPrototypes.STANDING),16,TowerBank.ONE,duty);
        var page=ConstructorColumn.design(input,GuiPrototypes.DIALOG);
        var prototype=new GuiPrototypes.Prototype("motor-constructor",464,254,(d,s)->GuiPrototypes.constructorDoor(d,page,"Motor","change","",Palette.ACCENT,Map.of()));
        for(boolean end:List.of(false,true))HeatDiagramRender.grid(out.resolve(end?"motor-constructor-end.png":"motor-constructor.png"),List.of("Light","Dark"),List.of(GuiPrototypes.render(prototype,PanelSkin.LIGHT,end),GuiPrototypes.render(prototype,PanelSkin.DARK,end)));
    }
}
