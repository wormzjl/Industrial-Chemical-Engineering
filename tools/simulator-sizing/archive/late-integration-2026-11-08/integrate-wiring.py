from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/ShapeRasterizer.java');s=p.read_text(encoding='utf-8');at=s.index('    /** Per-shape tables')
s=s[:at]+'''    /** Lazily built visual finish, keyed by the installed mount set. Never participates in server collision/layout. */
    public static InstrumentWiring.Drawing instrumentWiring(EquipmentShape shape,Set<String> installed,RenderDetail detail){
        var g=detail==RenderDetail.NORMAL?geometry(shape):drawGeometry(shape,detail);var key=Set.copyOf(installed);
        if(g.wiring.size()>16)g.wiring.clear();
        return g.wiring.computeIfAbsent(key,k->{var carriers=new ArrayList<>(g.mesh.facets().stream().filter(f->f.part()<0).map(Mesh::whole).toList());for(int n=0;n<g.nozzles.size();n++)for(var f:g.nozzle(n,0).facets())carriers.add(Mesh.whole(f));return InstrumentWiring.build(shape,carriers,g.services,g.instruments,k,detail);});
    }
''' +s[at:]
# find actual drawGeometry signature
s=s.replace('drawGeometry(shape,detail)','drawing(shape,detail)') if 'private static Geometry drawing(' in s else s
s=s.replace('        private final EquipmentShape shape;','        private final Map<Set<String>,InstrumentWiring.Drawing> wiring=new ConcurrentHashMap<>();\n        private final EquipmentShape shape;',1)
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/runtime/instrument/InstrumentHosts.java');s=p.read_text(encoding='utf-8').replace('if(mounts.stream().anyMatch','if(h.entity() instanceof EquipmentShellBlockEntity shell&&shell.cell().equals(ShapeRasterizer.fluidCell(shell.shape()))&&EquipmentInstruments.of(shell.shape()).probes().stream().anyMatch(p->{var q=p.centre();return centre.distanceToSqr(q[0]+shell.origin().getX(),q[1]+shell.origin().getY(),q[2]+shell.origin().getZ())<=radius*radius;})||mounts.stream().anyMatch');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/InstrumentRenderer.java');s=p.read_text(encoding='utf-8').replace('import com.wormzjl.icheme.science.equipment.InstrumentGeometry;','import com.wormzjl.icheme.science.equipment.*;')
s=s.replace('        for(var host:hosts) {','''        for(var host:hosts) {
            drawWiring(pose,buffers,host,camera);''',1)
s=s.replace('host.metadata().sensorIds().getOrDefault(sensor.instance(),""));','host.metadata().sensorIds().getOrDefault(sensor.instance(),""),!host.pipe());',1)
s=s.replace('installed.settings(),ratio,0,255);','installed.settings(),ratio,0,255,!host.pipe());',1)
s=s.replace('kind,alpha,"");','kind,alpha,"",false);')
s=s.replace('Sensor.Kind kind,int alpha,String id){','Sensor.Kind kind,int alpha,String id,boolean equipment){')
s=s.replace('for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(tap,k.bottom(),k.temperature(),k.probeDepth())))','if(!equipment)for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(tap,k.bottom(),k.temperature(),k.probeDepth())))')
s=s.replace('private static void draw(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,GaugeSettings settings,double ratio,int tint,int alpha){','''private static void draw(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,GaugeSettings settings,double ratio,int tint,int alpha){draw(pose,buffers,mount,camera,settings,ratio,tint,alpha,false);}
    private static void draw(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,GaugeSettings settings,double ratio,int tint,int alpha,boolean equipment){''')
s=s.replace('for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(new double[]{tap[0],tap[1],tap[2]},k.bottom,k.temperature,k.probeDepth)))','if(!equipment)for(var q:CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(new double[]{tap[0],tap[1],tap[2]},k.bottom,k.temperature,k.probeDepth)))')
s=s.replace('float angle=switch(m.face()){case NORTH->180;case EAST->90;case WEST->-90;default->0;};','float angle=(float)Math.toDegrees(Math.atan2(m.normal().x,m.normal().z));')
at=s.index('    private static void outline(')
s=s[:at]+'''    private static final Map<InstrumentWiring.Drawing,List<net.minecraft.client.renderer.block.model.BakedQuad>> WIRING=new HashMap<>();
    private static void drawWiring(PoseStack pose,MultiBufferSource buffers,InstrumentHosts.Host host,Vec3 camera){
        if(!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell)||!shell.cell().equals(ShapeRasterizer.fluidCell(shell.shape())))return;
        var installed=new HashSet<String>();host.metadata().installed().forEach(i->installed.add(i.key().mount()));host.metadata().sensors().forEach(i->installed.add(i.key().mount()));
        var drawing=ShapeRasterizer.instrumentWiring(shell.shape(),installed,com.wormzjl.icheme.client.model.EquipmentShellModel.detail());if(drawing.polygons().isEmpty())return;
        var sprites=new net.minecraft.client.renderer.texture.TextureAtlasSprite[Mesh.Material.values().length];for(var m:Mesh.Material.values()){sprites[m.ordinal()]=com.wormzjl.icheme.client.model.EquipmentShellModel.sprite(m);if(sprites[m.ordinal()]==null)return;}
        if(WIRING.size()>128)WIRING.clear();var quads=WIRING.computeIfAbsent(drawing,d->com.wormzjl.icheme.client.model.EquipmentShellModel.bake(d.polygons(),sprites,true));
        var origin=shell.origin();pose.pushPose();pose.translate(origin.getX()-camera.x,origin.getY()-camera.y,origin.getZ()-camera.z);var consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS));int light=LevelRenderer.getLightColor(Minecraft.getInstance().level,shell.getBlockPos());
        for(var q:quads)consumer.putBulkData(pose.last(),q,1,1,1,1,light,OverlayTexture.NO_OVERLAY,true);pose.popPose();
    }
''' +s[at:]
s=s.replace('public static void clear(){CACHE.clear();','public static void clear(){WIRING.clear();CACHE.clear();');p.write_text(s,encoding='utf-8')
# Asset made procedurally, matching approved metallic shaded finish (not AI image editing).
from PIL import Image
import math
im=Image.new('RGB',(32,64));px=im.load()
for y in range(64):
 for x in range(32):
  v=int(84+91*math.sqrt(max(0,1-((x-15.5)/16)**2)))
  if y<2:v=int(v*.78)
  px[x,y]=(v-4,v,v+4)
im.save('src/main/resources/assets/icheme/textures/block/instrument_conduit.png')
