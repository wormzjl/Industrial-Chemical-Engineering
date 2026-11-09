from pathlib import Path
import json
assets=Path('src/main/resources/assets/icheme');models=[(n,'block') for n in ['local_controller','dcs']]+[(n,'item') for n in ['pressure_probe','temperature_probe','flowmeter','digital_level_meter','speed_sensor']]
rows=[]
for name,folder in models:
 model=json.loads((assets/f'models/{folder}/{name}.json').read_text(encoding='utf-8'))
 if model.get('parent','').startswith('icheme:'):
  parent=json.loads((assets/('models/'+model['parent'].split(':')[1]+'.json')).read_text(encoding='utf-8'));parent['textures'].update(model.get('textures',{}));model={**parent,**model,'textures':parent['textures']}
 textures=model['textures']
 for element in model['elements']:
  x,y,z=element['from'];X,Y,Z=element['to']
  for face,defn in element['faces'].items():
   p,n={'north':([(X,Y,z),(X,y,z),(x,y,z),(x,Y,z)],(0,0,-1)),'south':([(x,Y,Z),(x,y,Z),(X,y,Z),(X,Y,Z)],(0,0,1)),'east':([(X,Y,Z),(X,y,Z),(X,y,z),(X,Y,z)],(1,0,0)),'west':([(x,Y,z),(x,y,z),(x,y,Z),(x,Y,Z)],(-1,0,0)),'up':([(x,Y,z),(x,Y,Z),(X,Y,Z),(X,Y,z)],(0,1,0)),'down':([(x,y,Z),(x,y,z),(X,y,z),(X,y,Z)],(0,-1,0))}[face]
   tex=defn['texture']
   while tex.startswith('#'):tex=textures[tex[1:]]
   tex=assets/'textures'/f'{tex.split(":")[1]}.png';uv=defn.get('uv',[0,0,16,16]);uvs=[uv[0]/16,uv[1]/16,uv[0]/16,uv[3]/16,uv[2]/16,uv[3]/16,uv[2]/16,uv[1]/16]
   rows.append('\t'.join(map(str,[name,tex.as_posix(),*[v for point in p for v in point],*uvs,*n])))
Path('tmp/process-control/models.tsv').write_text('\n'.join(rows),encoding='utf-8')
old=Path('tmp/constructor-model/ConstructorRender.java').read_text(encoding='utf-8');helpers=old[old.index('    static double[] cross'):old.index('    public static void main')];render=old[old.index('    static void render'):]
main='''    public static void main(String[] args) throws Exception {
        var models=new LinkedHashMap<String,List<Quad>>();
        for(var line:java.nio.file.Files.readAllLines(Path.of(args[0]))){var c=line.split("\\t");var p=new double[4][3];var uv=new double[4][2];var normal=new double[3];int at=2;for(int i=0;i<4;i++)for(int k=0;k<3;k++)p[i][k]=Double.parseDouble(c[at++]);for(int i=0;i<4;i++)for(int k=0;k<2;k++)uv[i][k]=Double.parseDouble(c[at++]);for(int k=0;k<3;k++)normal[k]=Double.parseDouble(c[at++]);models.computeIfAbsent(c[0],x->new ArrayList<>()).add(new Quad(p,uv,normal,ImageIO.read(Path.of(c[1]).toFile())));}
        var port=new ArrayList<Quad>();for(var p:com.wormzjl.icheme.science.equipment.DataPortGeometry.polygons(new double[]{.5,.5,.5},new double[]{0,0,-1}))mesh(port,p);models.put("data-port",port);
        var wire=new ArrayList<Quad>();for(var p:com.wormzjl.icheme.science.equipment.DataCableGeometry.polygons(new com.wormzjl.icheme.science.equipment.DataCableGeometry.Key(0,0).connect(3,0,0).connect(4,0,0).connect(5,0,0)))mesh(wire,p);models.put("cable-junction",wire);
        var sensor=new ArrayList<Quad>();for(var q:com.wormzjl.icheme.science.equipment.InstrumentGeometry.transmitter(false)){var p=new double[4][3];var uv=new double[4][2];var v=q.vertices();for(int i=0;i<4;i++)for(int k=0;k<3;k++)p[i][k]=8+v[3*i+k]*40;var n=unit(cross(new double[]{p[1][0]-p[0][0],p[1][1]-p[0][1],p[1][2]-p[0][2]},new double[]{p[2][0]-p[0][0],p[2][1]-p[0][1],p[2][2]-p[0][2]}));if(Double.isNaN(n[0]))continue;var t=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);t.setRGB(0,0,q.colour());sensor.add(new Quad(p,uv,n,t));}models.put("world-sensor",sensor);
        for(var entry:models.entrySet()){render(entry.getValue(),new double[]{-1,-.8,1.3},args[1]+"/"+entry.getKey()+"-front.png");if(entry.getKey().equals("local_controller")||entry.getKey().equals("dcs"))render(entry.getValue(),new double[]{1,-.8,-1.3},args[1]+"/"+entry.getKey()+"-rear.png");}
    }
    static void mesh(List<Quad> quads,com.wormzjl.icheme.science.equipment.Mesh.Polygon q){var v=q.positions();var ns=q.normals();for(int first=1;first+1<q.size();first+=2){var corners=new int[]{0,first,first+1,Math.min(first+2,q.size()-1)};var p=new double[4][3];for(int i=0;i<4;i++)for(int k=0;k<3;k++)p[i][k]=16*v[corners[i]*3+k];var t=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);t.setRGB(0,0,switch(q.material()){case OPENING->0x17282d;case GUARD->0x39819a;case FLANGE->0x747a7d;default->0x72929a;});quads.add(new Quad(p,new double[4][2],new double[]{ns[0],ns[1],ns[2]},t));}}
'''
header='import java.awt.image.BufferedImage;\nimport java.nio.file.Path;\nimport java.util.*;\nimport javax.imageio.ImageIO;\npublic class ControlAssetRender {\n    record Quad(double[][] points,double[][] uv,double[] normal,BufferedImage texture) {}\n'
Path('tmp/process-control/ControlAssetRender.java').write_text(header+helpers+main+render,encoding='utf-8')
