import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.*;
import java.nio.file.*;
import java.util.*;
public class ToolRender {
    public static void main(String[] args)throws Exception {
        HeatDiagramRender.lang=HeatDiagramRender.lang(Path.of(args[0]));
        HeatDiagramRender.font=HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));
        for(int mode=0;mode<3;mode++){
            var input=new DialogColumns.PipelineTool(true,0,false,true,mode==1,mode==2);
            var rows=DialogColumns.pipelineTool(input,GuiPrototypes.DIALOG);
            var prototype=new GuiPrototypes.Prototype("laying-"+mode,230,GuiPrototypes.dialogHeight(rows,230),(d,s)->{
                d.skin.title(d.c,10,10,"Pipe laying tool");d.column(rows,10,DialogColumns.TOP,d.w-20,Map.of("bore","0.150","custom","A1A1A0"));GuiPrototypes.dialogFooter(d,"Cancel","Save");
            });
            HeatDiagramRender.grid(Path.of(args[1],"laying-"+mode+".png"),List.of("Light","Dark"),List.of(GuiPrototypes.render(prototype,PanelSkin.LIGHT,false),GuiPrototypes.render(prototype,PanelSkin.DARK,false)));
        }
    }
}
