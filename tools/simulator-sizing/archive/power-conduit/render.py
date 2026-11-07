from pathlib import Path
cp=Path('tmp/machine-rework/classpath.txt').read_text(encoding='utf-8').strip().replace('\\','/')
cp+=';build/classes/java/test;tmp/power-conduit/classes'
s=Path('tmp/conduit-rework/ConduitLookRender.java').read_text(encoding='utf-8').replace('DataCableGeometry','ConduitGeometry').replace('ConduitLookRender','PowerLookRender')
a=s.index('        var keys=');b=s.index('    static void mesh',a)
s=s[:a]+'''        for(var medium:com.wormzjl.icheme.science.equipment.CableMedium.values())for(int joints:new int[]{48,36,52}){
            var quads=new ArrayList<Quad>();var key=new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(joints,0,medium);
            texture=medium.texture();for(var p:com.wormzjl.icheme.science.equipment.ConduitGeometry.polygons(key))mesh(quads,p,0x376674);
            render(quads,new double[]{-1,-.8,1.3},args[0]+"/"+medium.name().toLowerCase()+"-"+joints+".png");
        }
    }
    static String texture="data_cable";
''' +s[b:]
s=s.replace('"data_cable.png"','texture+".png"')
Path('tmp/power-conduit/PowerLookRender.java').write_text(s,encoding='utf-8')
s=Path('tmp/conduit-rework/ConduitEquipmentRender.java').read_text(encoding='utf-8').replace('ConduitEquipmentRender','PowerEquipmentRender')
before='        new File(out).getAbsoluteFile().getParentFile().mkdirs();'
s=s.replace(before,'''        var power=DataSocketGeometry.power(shape);
        if(power!=null){
            for(var polygon:PowerPortGeometry.polygons(new double[]{power.x(),power.y(),power.z()},LineGeometry.step(power.face()),true))add(triangles,polygon,0,0,0);
            System.out.println(kind+" FE terminal: "+power);
        }
'''+before)
Path('tmp/power-conduit/PowerEquipmentRender.java').write_text(s,encoding='utf-8')
Path('tmp/power-conduit/javac.args').write_text('-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\ntmp/power-conduit/classes\ntmp/power-conduit/PowerLookRender.java\ntmp/power-conduit/PowerEquipmentRender.java\n',encoding='utf-8')
for name,argv in {'conduits':['PowerLookRender','tmp/power-conduit/renders'], 'motor':['com.wormzjl.icheme.science.equipment.PowerEquipmentRender','MOTOR','22','1','0','tmp/power-conduit/renders/motor','iso,iso3'], 'generator':['com.wormzjl.icheme.science.equipment.PowerEquipmentRender','GENERATOR','22','1','0','tmp/power-conduit/renders/generator','iso,iso3']}.items():
 Path('tmp/power-conduit/'+name+'.args').write_text('-Djava.awt.headless=true\n-Djava.io.tmpdir=tmp/power-conduit/java-temp\n-cp\n"'+cp+'"\n'+'\n'.join(argv),encoding='utf-8')
