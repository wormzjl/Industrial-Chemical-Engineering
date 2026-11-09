import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.*;
import java.nio.file.*;
import java.util.*;
public class ControlPrototypes {
    static String tr(String key,Object...args){return GuiPrototypes.Door.tr("gui.icheme."+key,args);}
    static List<Column.Row> loop(){return ControlColumns.loop(new ControlColumns.View(true,"m","%",false,"LT-0012 / LEVEL","6.500","CV-0021","CONTROLLING",true),ControlPrototypes::tr);}
    static void columns(GuiPrototypes.Door d,List<List<Column.Row>> rows,boolean dcs,Map<String,String> values){
        int height=d.h-DialogColumns.TOP-DialogColumns.FOOTER;List<Rect> areas;
        if(dcs)areas=List.of(new Rect(10,28,194,height),new Rect(218,28,222,height));
        else {int full=d.w-20;int tall=Column.place(rows.getFirst(),10,28,full,d.m,Column.Metrics.COMPACT).bounds().h();areas=List.of(new Rect(10,28,tall>height?full-ScrollView.BAR-4:full,height));}
        var placed=new ArrayList<Column>();int tall=0;for(int i=0;i<rows.size();i++){var area=areas.get(i);var c=Column.place(rows.get(i),area.x(),area.y(),area.w(),d.m,Column.Metrics.COMPACT);placed.add(c);tall=Math.max(tall,c.bounds().h());}
        var first=areas.getFirst();var view=new ScrollView();view.layout(new Rect(first.x()-2,28,areas.getLast().right()-first.x()+4,height),dcs?450:first.right()+3,tall);
        while(d.end&&view.offset()<view.max())view.wheel(10,30,-1);
        d.c.clip(view.viewport());for(var c:placed){c.draw(d.c,-view.offset(),d.skin);for(var p:c.rows()){
            String key=p.row() instanceof Column.Field f?f.key():p.row() instanceof Column.Wide w?w.key():null;
            if(key!=null){var r=p.slots().get(key);if(view.visible(r))d.skin.field(d.c,view.onScreen(r),values.getOrDefault(key,""),"",false);}
            if(p.row() instanceof Column.Buttons b)for(var button:b.buttons()){var r=p.slots().get(button.key());if(!view.visible(r))continue;if(button.soft())d.skin.softKey(d.c,view.onScreen(r),button.text(),false);else d.skin.button(d.c,view.onScreen(r),button.text(),button.latched()?Skin.Press.CURRENT:button.active()?Skin.Press.REST:Skin.Press.OFF,false);}
        }}d.c.unclip();view.drawBar(d.c,d.skin);
    }
    public static void main(String[] args)throws Exception {
        HeatDiagramRender.lang=HeatDiagramRender.lang(Path.of(args[0]));HeatDiagramRender.font=HeatDiagramRender.GameFont.load(Path.of("build/moddev/artifacts/neoforge-21.1.219-client-extra-aka-minecraft-resources.jar"));var out=Path.of(args[1]);
        var pages=List.of(
            new GuiPrototypes.Prototype("controller-box",246,390,(d,s)->{d.skin.title(d.c,10,10,"Controller box");columns(d,List.of(loop()),false,Map.of("name","LIC-101","target","6.50"));d.footer(2,"Close","Apply");},true),
            new GuiPrototypes.Prototype("dcs-console",464,390,(d,s)->{d.skin.title(d.c,10,10,"Control station");columns(d,List.of(ControlColumns.controllers(List.of(new ControlColumns.Choice("loop1","LIC-101 / Tank level",true),new ControlColumns.Choice("loop2","FIC-102 / Feed flow",false),new ControlColumns.Choice("loop3","PIC-201 / Pressure",false)),ControlPrototypes::tr),loop()),true,Map.of("name","LIC-101","target","6.50"));d.footer(2,"Close","Apply");},true),
            new GuiPrototypes.Prototype("input-choices",246,390,(d,s)->{d.skin.title(d.c,10,10,"Controller box");var choices=new ArrayList<ControlColumns.Choice>();for(int i=0;i<32;i++)choices.add(new ControlColumns.Choice("sensor"+i,String.format(java.util.Locale.ROOT,"%s-%04d / %s",i%2==0?"LT":"TT",i+1,i%2==0?"LEVEL":"TEMPERATURE"),i==0));columns(d,List.of(ControlColumns.choices("Input sensor",choices,ControlPrototypes::tr)),false,Map.of());d.footer(2,"Close","Apply");},true),
            new GuiPrototypes.Prototype("cable-tool",230,GuiPrototypes.dialogHeight(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true,0,false,true,true),ControlPrototypes::tr),230),(d,s)->{d.skin.title(d.c,10,10,"Pipe laying tool");d.column(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true,0,false,true,true),ControlPrototypes::tr),10,28,210,Map.of());d.footer(2,"Cancel","Save");}));
        for(var p:pages){var names=new ArrayList<>(List.of("Light","Dark"));var images=new ArrayList<>(List.of(GuiPrototypes.render(p,PanelSkin.LIGHT,false),GuiPrototypes.render(p,PanelSkin.DARK,false)));if(p.scrolls()){names.addAll(List.of("Light end","Dark end"));images.addAll(List.of(GuiPrototypes.render(p,PanelSkin.LIGHT,true),GuiPrototypes.render(p,PanelSkin.DARK,true)));}HeatDiagramRender.grid(out.resolve(p.file()+".png"),names,images);}
    }
}
