from pathlib import Path
p=Path('tmp/process-control/ControlPrototypes.java');s=p.read_text(encoding='utf-8')
a=s.index('    public static void main(')
helper='''    static void columns(GuiPrototypes.Door d,List<List<Column.Row>> rows,boolean dcs,Map<String,String> values){
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
'''
s=s[:a]+helper+s[a:]
s=s.replace('d.column(loop(),10,28,226,Map.of("name","LIC-101","target","6.50"));','columns(d,List.of(loop()),false,Map.of("name","LIC-101","target","6.50"));')
a=s.index('d.column(ControlColumns.controllers(');b=s.index('d.footer(2,"Close","Apply");},true),',a)
s=s[:a]+'''columns(d,List.of(ControlColumns.controllers(List.of(new ControlColumns.Choice("loop1","LIC-101 / Tank level",true),new ControlColumns.Choice("loop2","FIC-102 / Feed flow",false),new ControlColumns.Choice("loop3","PIC-201 / Pressure",false)),ControlPrototypes::tr),loop()),true,Map.of("name","LIC-101","target","6.50"));'''+s[b:]
s=s.replace('d.column(ControlColumns.choices("Input sensor",choices,ControlPrototypes::tr),10,28,226,Map.of());','columns(d,List.of(ControlColumns.choices("Input sensor",choices,ControlPrototypes::tr)),false,Map.of());')
s=s.replace('230,114,','230,GuiPrototypes.dialogHeight(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true,0,false,true,true),ControlPrototypes::tr),230),')
p.write_text(s,encoding='utf-8')
# Resolve inherited vanilla models when reviewing their actual production geometry.
p=Path('tmp/process-control/model-render.py');s=p.read_text(encoding='utf-8');s=s.replace("model=json.loads((assets/f'models/{folder}/{name}.json').read_text());textures=model['textures']", "model=json.loads((assets/f'models/{folder}/{name}.json').read_text(encoding='utf-8'))\n if model.get('parent','').startswith('icheme:'):\n  parent=json.loads((assets/('models/'+model['parent'].split(':')[1]+'.json')).read_text(encoding='utf-8'));parent['textures'].update(model.get('textures',{}));model={**parent,**model,'textures':parent['textures']}\n textures=model['textures']")
p.write_text(s,encoding='utf-8')
