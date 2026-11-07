from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/DataPorts.java');s=p.read_text(encoding='utf-8')
s=s.replace('import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;','import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;\nimport com.wormzjl.icheme.runtime.instrument.Sensor;\nimport com.wormzjl.icheme.science.equipment.*;\nimport com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;\nimport com.wormzjl.icheme.world.level.block.FluidDeviceBlock;\nimport com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;')
for prefix in ['com.wormzjl.icheme.science.equipment.','com.wormzjl.icheme.runtime.instrument.','com.wormzjl.icheme.world.level.block.entity.','com.wormzjl.icheme.world.level.block.','com.wormzjl.icheme.science.fluid.topology.']:
 s='\n'.join(line if line.startswith('import ') else line.replace(prefix,'') for line in s.split('\n'))
s=s.replace('java.util.List','List');p.write_text(s,encoding='utf-8')

source=Path('tmp/machine-rework/UnitRender.java').read_text(encoding='utf-8')
source=source.replace('public class UnitRender','public class ConduitEquipmentRender').replace('Mesh.Polygon polygon, int x, int y, int z','Mesh.Polygon polygon, double x, double y, double z')
at='        new File(out).getAbsoluteFile().getParentFile().mkdirs();'
insert='''        var socket=DataSocketGeometry.equipment(shape);
        if(socket!=null){
            var n=LineGeometry.step(socket.face());
            for(var polygon:DataPortGeometry.polygons(new double[]{socket.x(),socket.y(),socket.z()},n,true))add(triangles,polygon,0,0,0);
            System.out.println(kind+" casing data socket: "+socket);
        }else System.out.println(kind+" uses its service-tap mount");
'''
assert at in source;source=source.replace(at,insert+at)
Path('tmp/conduit-rework/ConduitEquipmentRender.java').write_text(source,encoding='utf-8')

source=Path('tmp/process-control/ControlAssetRender.java').read_text(encoding='utf-8').replace('public class ControlAssetRender','public class ConduitLookRender')
a=source.index('    public static void main');b=source.index('    static void mesh',a)
source=source[:a]+'''    public static void main(String[] args)throws Exception {
        var keys=new LinkedHashMap<String,com.wormzjl.icheme.science.equipment.DataCableGeometry.Key>();
        keys.put("straight",new com.wormzjl.icheme.science.equipment.DataCableGeometry.Key(48,0));
        keys.put("elbow",new com.wormzjl.icheme.science.equipment.DataCableGeometry.Key(36,0));
        keys.put("tee",new com.wormzjl.icheme.science.equipment.DataCableGeometry.Key(52,0));
        keys.put("off-centre",new com.wormzjl.icheme.science.equipment.DataCableGeometry.Key(0,0).connect(4,0,0).connect(5,8,-7));
        for(var entry:keys.entrySet()){
            var quads=new ArrayList<Quad>();for(var p:com.wormzjl.icheme.science.equipment.DataCableGeometry.polygons(entry.getValue()))mesh(quads,p,0x376674);
            render(quads,new double[]{-1,-.8,1.3},args[0]+"/"+entry.getKey()+".png");
        }
    }
''' +source[b:]
source=source.replace('Math.min(t.getWidth()-1,Math.max(0,(int)(u*t.getWidth())))','Math.floorMod((int)Math.floor(u*t.getWidth()),t.getWidth())').replace('Math.min(t.getHeight()-1,Math.max(0,(int)(v*t.getHeight())))','Math.floorMod((int)Math.floor(v*t.getHeight()),t.getHeight())')
Path('tmp/conduit-rework/ConduitLookRender.java').write_text(source,encoding='utf-8')
