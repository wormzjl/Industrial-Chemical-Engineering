package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.FluidNetwork;
import net.minecraft.core.BlockPos;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.*;
import com.wormzjl.icheme.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.*;

/** Shared metre-sized geometry for installed dials and placement ghosts. Only needles read live publications. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class InstrumentRenderer {
    private InstrumentRenderer(){}
    /** Synced structural labels, shared by the host's fluid and machinery GUIs; no process subscription or solve. */
    public static List<String> identities(net.minecraft.world.level.Level level,BlockPos position){
        if(level==null)return List.of();
        var host=InstrumentHosts.at(level,position);
        var metadata=host!=null?host.metadata():level.getBlockEntity(position) instanceof Instrument.Holder holder?holder.instruments():null;
        if(metadata==null)return List.of();
        return metadata.sensors().stream().filter(s->metadata.sensorIds().containsKey(s.instance())).map(s->{
            String label=s.kind()==Sensor.Kind.SPEED?"item.icheme.speed_sensor":"gui.icheme.fluid."+switch(s.kind()){
                case PRESSURE->"pressure_probe";case TEMPERATURE->"temperature_probe";case FLOW->"flowmeter";
                case LEVEL->s.key().mount().equals("level_boot")?"boot_level":"body_level";case SPEED,ALARM->throw new IllegalStateException();};
            return metadata.sensorIds().get(s.instance())+" / "+Component.translatable(label).getString();
        }).sorted().toList();
    }
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"textures/misc/instrument.png");
    private static final RenderType TYPE=RenderType.entitySolid(WHITE);
    private static final RenderType GHOST=RenderType.entityTranslucentCull(WHITE);
    private record Connector(List<Double> tap,boolean bottom,boolean temperature,double probeDepth){}
    private static final Map<Connector,List<InstrumentGeometry.Quad>> CONNECTORS=new HashMap<>();
    // ---- level of detail (batch 2026-11-09-lod-rendering, LOD_RENDERING_PLAN.md A2): sizes in metres, tiers in pixels ----
    /** A dial's face, a transmitter's head, a connector's stem and a wiring ribbon. */
    private static final double DIAL=2*InstrumentGeometry.RADIUS,HEAD=.23,STEM=.028,WIRE=.025;
    /** Dial: full (text, 48 sides), plain (24 sides, no text), far (12 sides, needle only), hidden. Connectors end with plain, where the stem reaches a pixel. */
    private static final int FULL=0,PLAIN=1,FAR=2;
    private static final RenderLod.Tiers<Seen> DIALS=new RenderLod.Tiers<>(48,12,3);
    /** Transmitter: symbol and ID (to 4 m at 1080 lines), symbol (to 13 m), head only (to 59 m), hidden. */
    private static final RenderLod.Tiers<Seen> HEADS=new RenderLod.Tiers<>(44,14,3);
    /** A structure's wiring while its ribbon covers a pixel. */
    private static final RenderLod.Tiers<Seen> WIRES=new RenderLod.Tiers<>(1);
    private record Seen(long host,String mount){}
    /** Hosts with installed instruments, gathered every {@link #HOST_TICKS} ticks while no placing item is held. */
    private static final int HOST_TICKS=5;
    private static List<InstrumentHosts.Host> shown=List.of();
    private static long shownAt=Long.MIN_VALUE;
    private static net.minecraft.world.level.Level shownIn;
    private static final net.minecraft.world.InteractionHand[] HANDS=net.minecraft.world.InteractionHand.values();
    private static final Mesh.Material[] MATERIALS=Mesh.Material.values();
    // ---- per-host plans (batch 2026-11-10-custom-shader-system, FX_BENCH_REVIEW.md: 0.79 ms and about 10 MB a frame) ----
    // A host's instruments, mounts, factory heads and wiring change only when its metadata, block state, shape or mounts do,
    // so their posed quads (world positions, normals, colours), frustum boxes, light positions, LOD keys and display targets
    // are built once per change (Plan), and a frame only tests the tiers and the frustum and writes the cached vertices.
    // A needle is posed again only when its reading moves. The placement ghosts (an item held) still pose as they draw.
    /** The plans by block entity; a removed one is pruned at the next host gathering. */
    private static final Map<net.minecraft.world.level.block.entity.BlockEntity,Plan> PLANS=new IdentityHashMap<>();
    private static final int MOST_PLANS=1024;
    /** Quads posed on a mount: world positions (12 a quad), the quad's normal (3), its colour; as {@code quad} drew them before. */
    private static final class Posed {
        double[] xyz=new double[48];
        float[] normals=new float[12];
        int[] colours=new int[4];
        int quads;
        Posed set(List<InstrumentGeometry.Quad> list,double[] centre,double[] normal){quads=0;room(list.size());for(int i=0;i<list.size();i++)put(list.get(i),centre,normal);return this;}
        Posed one(InstrumentGeometry.Quad quad,double[] centre,double[] normal){quads=0;room(1);put(quad,centre,normal);return this;}
        private void room(int n){if(colours.length<n){xyz=new double[12*n];normals=new float[3*n];colours=new int[n];}}
        /** As {@link InstrumentGeometry#pose} (the same sums in the same order), then the normal as {@code quad} took it. */
        private void put(InstrumentGeometry.Quad quad,double[] centre,double[] normal){
            var s=quad.vertices();int o=12*quads;double[] right={normal[2],0,-normal[0]};
            for(int i=0;i<12;i+=3)for(int a=0;a<3;a++)xyz[o+i+a]=centre[a]+right[a]*s[i]+(a==1?s[i+1]:0)+normal[a]*s[i+2];
            var v=xyz;
            double ax=v[o+3]-v[o],ay=v[o+4]-v[o+1],az=v[o+5]-v[o+2],bx=v[o+6]-v[o],by=v[o+7]-v[o+1],bz=v[o+8]-v[o+2];
            double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            // As Vec3.normalize: a degenerate normal is zero.
            if(length<1e-4){nx=0;ny=0;nz=0;}else{nx/=length;ny/=length;nz/=length;}
            normals[3*quads]=(float)nx;normals[3*quads+1]=(float)ny;normals[3*quads+2]=(float)nz;colours[quads]=quad.colour();quads++;
        }
    }
    /** The placement ghosts' posing space: reused, render thread only. */
    private static final Posed SCRATCH=new Posed();
    /** A transmitter head: an installed sensor's or an equipment's factory probe's. */
    private static final class Head {
        final Seen seen;final Instrument.Mount mount;final net.minecraft.world.phys.AABB box;final BlockPos light;final Sensor.Kind kind;final String id;final boolean equipment;final Posed body;
        private Posed connector;
        Head(Seen seen,Instrument.Mount mount,Sensor.Kind kind,String id,boolean equipment){
            this.seen=seen;this.mount=mount;this.kind=kind;this.id=id;this.equipment=equipment;box=box(mount);light=BlockPos.containing(mount.centre());
            var f=frame(mount);body=new Posed().set(InstrumentGeometry.transmitter(kind==Sensor.Kind.LEVEL),f.centre(),f.normal());
        }
        Posed connector(){if(connector==null){var f=frame(mount);connector=new Posed().set(connection(mount,kind==Sensor.Kind.TEMPERATURE),f.centre(),f.normal());}return connector;}
    }
    /** An installed dial: its housings by tier, connector and needle, posed on first use. */
    private static final class Dial {
        final Seen seen;final Instrument.Mount mount;final net.minecraft.world.phys.AABB box;final BlockPos light;final GaugeSettings settings;final FluidNetwork.InstrumentTarget target;final boolean equipment;final Frame frame;
        private final Posed[] housings=new Posed[3];
        private Posed connector,needle;
        private double ratio;
        private String[] labels;
        Dial(Seen seen,Instrument.Mount mount,GaugeSettings settings,FluidNetwork.InstrumentTarget target,boolean equipment){
            this.seen=seen;this.mount=mount;this.settings=settings;this.target=target;this.equipment=equipment;box=box(mount);light=BlockPos.containing(mount.centre());frame=frame(mount);
        }
        Posed housing(int tier){if(housings[tier]==null)housings[tier]=new Posed().set(tier==FULL?InstrumentGeometry.housing():tier==PLAIN?InstrumentGeometry.housingPlain():InstrumentGeometry.housingFar(),frame.centre(),frame.normal());return housings[tier];}
        Posed connector(){if(connector==null)connector=new Posed().set(connection(mount,settings.kind()==GaugeSettings.Kind.TEMPERATURE),frame.centre(),frame.normal());return connector;}
        Posed needle(double r){
            if(needle==null||Double.doubleToLongBits(r)!=Double.doubleToLongBits(ratio)){(needle==null?needle=new Posed():needle).one(InstrumentGeometry.needle(r),frame.centre(),frame.normal());ratio=r;}
            return needle;
        }
        /** The scale's five labels: fixed by the settings. */
        String[] labels(){
            if(labels==null){labels=new String[5];for(int i=0;i<=4;i++){double fraction=i/4.0;labels[i]=compact(settings.display(settings.minimum()+fraction*(settings.maximum()-settings.minimum())));}}
            return labels;
        }
    }
    /** What one host draws without a placing item: built when the host changes, valid while {@link #holds} says so. */
    private static final class Plan {
        final net.minecraft.world.level.block.entity.BlockEntity entity;final long identity;final boolean pipe;
        final Object state;final List<?> installed,sensorList;final Map<?,?> ids;
        final EquipmentShape shape;final Object key;
        InstrumentHosts.Host host;List<Instrument.Mount> mounts;
        /** The fluid cell of an equipment shell: it draws the structure's wiring and factory heads. */
        final boolean root;
        final BlockPos origin;final double x0,y0,z0,x1,y1,z1;final net.minecraft.world.phys.AABB wiringBox;final Seen wiringSeen;
        final Head[] factory,sensors;final Dial[] dials;
        /** An exchanger's ports (where a piece's nozzle code is read) and their nozzles; null otherwise. */
        final BlockPos[] portAt;final int[] portNozzle;final int[] codes;
        Wiring wiring;
        final Shown shown=new Shown();
        Plan(InstrumentHosts.Host host){
            this.host=host;entity=host.entity();identity=host.identity();pipe=host.pipe();mounts=host.mounts();
            var md=host.metadata();installed=md.installed();sensorList=md.sensors();ids=md.sensorIds();state=entity.getBlockState();
            EquipmentShape s=null;Object k=null;boolean r=false;BlockPos o=null;
            if(entity instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell){s=shell.shape();k=shell.key();r=s!=null&&shell.cell().equals(ShapeRasterizer.fluidCell(s));o=shell.origin();}
            shape=s;key=k;root=r;origin=o;
            var factoryHeads=new ArrayList<Head>();BlockPos[] at=null;int[] nozzles=null,c=null;
            if(root){
                x0=o.getX();y0=o.getY();z0=o.getZ();x1=x0+s.sizeX();y1=y0+s.sizeY();z1=z0+s.sizeZ();
                wiringBox=new net.minecraft.world.phys.AABB(x0,y0,z0,x1,y1,z1);wiringSeen=new Seen(identity,"");
                for(var p:EquipmentInstruments.of(s).probes())if(!p.manual()&&p.display())
                    factoryHeads.add(new Head(new Seen(identity,"factory:"+p.id()),InstrumentHosts.equipmentMount(p,o),Sensor.Kind.valueOf(p.quantity().name()),"EQ-"+identity,true));
                if(s.kind()==EquipmentShape.Kind.EXCHANGER){
                    var ports=ShapeRasterizer.ports(s);at=new BlockPos[ports.size()];nozzles=new int[ports.size()];c=new int[s.nozzles().size()];
                    for(int i=0;i<ports.size();i++){var cell=ports.get(i).cell();at[i]=o.offset(cell.x(),cell.y(),cell.z());nozzles[i]=ports.get(i).nozzle();}
                }
            }else{x0=y0=z0=x1=y1=z1=0;wiringBox=null;wiringSeen=null;}
            portAt=at;portNozzle=nozzles;codes=c;
            factory=factoryHeads.toArray(Head[]::new);
            var heads=new ArrayList<Head>();
            for(var sensor:md.sensors())for(var mount:Sensor.mounts(host,sensor.kind()))if(mount.id().equals(sensor.key().mount()))
                heads.add(new Head(new Seen(identity,mount.id()),mount,sensor.kind(),md.sensorIds().getOrDefault(sensor.instance(),""),!pipe));
            sensors=heads.toArray(Head[]::new);
            var gauges=new ArrayList<Dial>();
            for(var i:md.installed()){var mount=host.mount(i.key().mount());if(mount==null)continue;
                gauges.add(new Dial(new Seen(identity,mount.id()),mount,i.settings(),new FluidNetwork.InstrumentTarget(entity.getBlockPos(),i.key(),i.instance()),!pipe));}
            dials=gauges.toArray(Dial[]::new);
        }
        /** Whether this plan still draws {@code h}: the same entity, metadata, block state, shape and mounts. */
        boolean holds(InstrumentHosts.Host h){
            if(h.entity()!=entity||h.identity()!=identity||h.pipe()!=pipe)return false;
            var md=h.metadata();if(md.installed()!=installed||md.sensors()!=sensorList||md.sensorIds()!=ids||entity.getBlockState()!=state)return false;
            if(entity instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell&&(shell.shape()!=shape||shell.key()!=key))return false;
            if(h!=host){if(!h.mounts().equals(mounts))return false;host=h;mounts=h.mounts();}
            return true;
        }
    }
    private static Plan plan(InstrumentHosts.Host host){
        var plan=PLANS.get(host.entity());
        if(plan!=null&&plan.holds(host))return plan;
        if(PLANS.size()>=MOST_PLANS)PLANS.clear();
        plan=new Plan(host);PLANS.put(host.entity(),plan);
        return plan;
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;long started=System.nanoTime();
        var camera=event.getCamera().getPosition();var buffers=mc.renderBuffers().bufferSource();var pose=event.getPoseStack();
        var view=ClientConfig.lodView(event.getProjectionMatrix().m11(),mc.getWindow().getHeight());var frustum=event.getFrustum();
        GaugeSettings draft=null;Sensor.Kind sensorDraft=null;boolean wrench=false,levelGauge=false;
        for(var hand:HANDS) {
            var stack=mc.player.getItemInHand(hand);
            if(stack.is(ModItems.LIQUID_LEVEL_GAUGE.get()))levelGauge=true;
            if(stack.getItem() instanceof SensorItem sensorItem)sensorDraft=sensorItem.kind();
            if(stack.getItem() instanceof GaugeItem)draft=stack.get(ModItems.GAUGE_SETTINGS.get());
            if(stack.getItem() instanceof AccessoryWrenchItem&&mc.player.isShiftKeyDown())wrench=true;
        }
        boolean placing=draft!=null||sensorDraft!=null||wrench||levelGauge;
        var hosts=placing?InstrumentHosts.nearby(mc.level,mc.player.position(),120,false):shownHosts(mc);
        var selected=draft==null?null:InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),draft.kind(),false);
        boolean shadow=SHADOW.test(()->com.wormzjl.icheme.client.fx.FxCompat.iris().shadowPass(),false);
        SHOWN.clear();
        for(int h=0;h<hosts.size();h++) {
            var host=hosts.get(h);
            if(host.entity().isRemoved())continue;
            var plan=plan(host);
            drawWiring(pose,buffers,plan,camera,view,frustum,shadow);
            for(var head:plan.sensors){
                double d2=head.mount.centre().distanceToSqr(camera);int tier=HEADS.of(head.seen,view.pixels(HEAD,d2));
                if(tier==HEADS.hidden()||!frustum.isVisible(head.box))continue;
                drawHead(pose,buffers,head,camera,tier,view.pixels(STEM,d2)>=1);
            }
            if(sensorDraft!=null)for(var mount:Sensor.mounts(host,sensorDraft)){
                if(mount.centre().distanceToSqr(mc.player.getEyePosition())>64)continue;
                boolean occupied=host.metadata().sensors().stream().anyMatch(s->s.key().mount().equals(mount.id()))||host.metadata().installed().stream().anyMatch(s->s.key().mount().equals(mount.id()))||host.pipe()&&!mount.id().equals("valve_sensor")&&host.occupied(mount);
                boolean blocked=occupied||!InstrumentHosts.clear(mc.level,host,mount);
                if(!occupied){drawSensor(pose,buffers,mount,camera,sensorDraft,70,!host.pipe());drawRootGhost(pose,buffers,host,mount,camera,0x779ba9,70);}
                outline(pose,buffers,mount,camera,blocked?0xad7770:0x73a38e);
            }
            for(var dial:plan.dials) {
                int tier=DIALS.of(dial.seen,view.pixels(DIAL,dial.mount.centre().distanceToSqr(camera)));
                if(tier==DIALS.hidden()||!frustum.isVisible(dial.box))continue;
                var display=get(dial.target);
                double ratio=display==null||display.sample().availability()==Instrument.Availability.UNAVAILABLE?0:dial.settings.needle(display.sample().value());
                drawDial(pose,buffers,dial,camera,ratio,tier);
            }
            if(levelGauge){
                var mount=InstrumentHosts.LevelGauge.of(host);
                if(mount!=null&&mount.bounds().distanceToSqr(mc.player.getEyePosition())<=64&&!host.metadata().levelGauge()){
                    boolean clear=InstrumentHosts.clear(mc.level,host,mount);
                    var hit=mount.bounds().clip(mc.player.getEyePosition(),mc.player.getEyePosition().add(mc.player.getLookAngle().scale(mc.player.blockInteractionRange())));
                    boolean aimed=hit.isPresent()&&InstrumentHosts.reachable(mc.player,host,mount);
                    drawLevelGhost(pose,buffers,host,camera,clear?(aimed?0x73a38e:0x779ba9):0xad7770,aimed?64:40);
                }
            }
            if(draft!=null&&!host.pipe())for(var mount:host.mounts()) {
                if(!mount.kinds().contains(draft.kind())||mount.centre().distanceToSqr(mc.player.getEyePosition())>64)continue;
                boolean blocked=host.occupied(mount)||!InstrumentHosts.clear(mc.level,host,mount);
                boolean aimed=selected!=null&&selected.host().identity()==host.identity()&&selected.mount().id().equals(mount.id())&&InstrumentHosts.reachable(mc.player,host,mount);
                int colour=blocked?0xad7770:aimed?0x73a38e:0x779ba9;
                // Occupied instruments already supply the silhouette. An outline avoids coincident ghost/model planes.
                if(!blocked){drawGhostDial(pose,buffers,mount,camera,aimed?88:56);drawRootGhost(pose,buffers,host,mount,camera,colour,aimed?88:56);}
                outline(pose,buffers,mount,camera,colour);
            }
        }
        if(wrench) {
            var target=InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),null,true);
            if(target!=null&&!target.host().pipe()&&InstrumentHosts.reachable(mc.player,target.host(),target.mount()))outline(pose,buffers,target.mount(),camera,0x73a38e);

        }
        flushWiring(event,pose,buffers,camera);
        buffers.endBatch(TYPE);buffers.endBatch(GHOST);buffers.endBatch(RenderType.lines());
        com.wormzjl.icheme.client.fx.FxStats.add(com.wormzjl.icheme.client.fx.FxStats.Counter.WIRING_NS,System.nanoTime()-started);
    }
    private static void drawRootGhost(PoseStack pose,MultiBufferSource buffers,InstrumentHosts.Host host,Instrument.Mount mount,Vec3 camera,int colour,int alpha){
        if(!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell))return;
        EquipmentInstruments.of(shell.shape()).probes().stream().filter(p->p.id().equals(mount.id())).findFirst().ifPresent(p->DataControlRenderer.draw(pose,buffers,camera,Vec3.atLowerCornerOf(shell.origin()),InstrumentWiring.rootHardware(p,com.wormzjl.icheme.client.model.EquipmentShellModel.detail()),colour,alpha));
    }
    // ---- instrument wiring (batch 2026-11-10-custom-shader-system, CUSTOM_SHADER_SYSTEM_PLAN.md section 4.4, W2) ----
    // The steel hardware (roots, glands) stays on the block atlas. The cable finish takes the tiled textures under
    // textures/wiring/ by what it carries (InstrumentWiring.Finish), v in metres over InstrumentWiring.PERIOD. SHADER path
    // with pbr_textures: icheme:rendertype_wiring with the _n and _s maps, from one cached buffer per structure and texture,
    // built again only when that structure's wiring is. COMPATIBLE (a shader pack lights it from _n and _s), OFF or
    // pbr_textures off: entityCutoutNoCull on the same textures, written every frame from the cached vertices. Nothing in an
    // Iris shadow pass.
    /**
     * A structure's wiring, kept while its shape, installed mounts, nozzle codes, detail and atlas hold: steel as atlas quads,
     * the finish by texture ({@code names}, sorted) as vertices of 8 floats (x, y, z, u, v, normal) and its upload keys.
     */
    private record Wiring(EquipmentShape shape,Set<String> installed,List<Integer> codes,RenderDetail detail,Object atlas,List<net.minecraft.client.renderer.block.model.BakedQuad> hardware,String[] names,float[][] finish,Uploaded[] keys,long version){
        int index(String name){for(int i=0;i<names.length;i++)if(names[i].equals(name))return i;return -1;}
    }
    private static final Map<BlockPos,Wiring> WIRING=new HashMap<>();
    private static long wiringBuilds;
    /** A finish mesh on the GPU: one per structure, build and texture. */
    private record Uploaded(BlockPos at,long version,String texture){}
    private static final VertexBufferCache<Uploaded> UPLOADED=new VertexBufferCache<>(512,2048);
    /** Wiring that passed the level of detail and the frustum this frame, drawn after every host: one batch per texture. Its plan's, set each frame. */
    private static final class Shown {
        BlockPos at,origin;Wiring wiring;int light;
        void set(BlockPos at,BlockPos origin,Wiring wiring,int light){this.at=at;this.origin=origin;this.wiring=wiring;this.light=light;}
    }
    private static final List<Shown> SHOWN=new ArrayList<>();
    /** This frame's finish textures, sorted; the SHADER path's buffers by texture and structure. */
    private static final List<String> NAMES=new ArrayList<>();
    private static VertexBuffer[] litBuffers=new VertexBuffer[64];
    /** A wiring texture's locations and its flat render type. */
    private static final class Texture {
        final ResourceLocation base,normal,specular;RenderType flat;
        Texture(String name){base=location(name);normal=location(name+"_n");specular=location(name+"_s");}
        RenderType flat(){if(flat==null)flat=RenderType.entityCutoutNoCull(base);return flat;}
        private static ResourceLocation location(String name){return ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"textures/wiring/"+name+".png");}
    }
    private static final Map<String,Texture> TEXTURES=new HashMap<>();
    private static RenderType atlasType;
    private static final org.joml.Matrix4f BASE=new org.joml.Matrix4f();
    private static final com.wormzjl.icheme.client.fx.FxGuard LIT=com.wormzjl.icheme.client.fx.FxGuard.effect("instrument_wiring");
    private static final com.wormzjl.icheme.client.fx.FxGuard SHADOW=new com.wormzjl.icheme.client.fx.FxGuard("Iris shadow pass (wiring)",()->com.wormzjl.icheme.client.fx.FxCompat.version(com.wormzjl.icheme.client.fx.FxCompat.IRIS));
    private static Texture texture(String name){return TEXTURES.computeIfAbsent(name,Texture::new);}
    private static RenderLevelStageEvent litEvent;
    private static Vec3 litCamera;
    private static final Runnable DRAW_LIT=()->drawLit(litEvent,litCamera);
    private static void drawWiring(PoseStack pose,MultiBufferSource buffers,Plan plan,Vec3 camera,RenderLod.View view,net.minecraft.client.renderer.culling.Frustum frustum,boolean shadow){
        if(!plan.root)return;
        var shell=(com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity)plan.entity;
        if(!shadow&&WIRES.of(plan.wiringSeen,view.pixels(WIRE,camera.x,camera.y,camera.z,plan.x0,plan.y0,plan.z0,plan.x1,plan.y1,plan.z1))==0&&frustum.isVisible(plan.wiringBox)){
            var wiring=wiring(plan,shell);
            if(wiring!=null&&(!wiring.hardware().isEmpty()||wiring.names().length>0)){
                plan.shown.set(shell.getBlockPos(),plan.origin,wiring,LevelRenderer.getLightColor(Minecraft.getInstance().level,shell.getBlockPos()));SHOWN.add(plan.shown);
            }
        }
        for(var head:plan.factory){
            int tier=HEADS.of(head.seen,view.pixels(HEAD,head.mount.centre().distanceToSqr(camera)));
            if(tier==HEADS.hidden()||!frustum.isVisible(head.box))continue;
            drawHead(pose,buffers,head,camera,tier,false);
        }
    }
    /** This frame's wiring: the atlas hardware, then the finish on the frame's path; always empties {@link #SHOWN}. */
    private static void flushWiring(RenderLevelStageEvent event,PoseStack pose,MultiBufferSource.BufferSource buffers,Vec3 camera){
        if(SHOWN.isEmpty())return;
        try{
            if(atlasType==null)atlasType=RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
            var consumer=buffers.getBuffer(atlasType);
            // One pose for every structure: the frame's pose translated to each origin, as a push and translate made it.
            pose.pushPose();
            try{
                var mine=pose.last();BASE.set(mine.pose());
                for(int i=0;i<SHOWN.size();i++){
                    var s=SHOWN.get(i);var hardware=s.wiring.hardware();
                    if(hardware.isEmpty())continue;
                    at(mine,s.origin,camera);
                    for(int q=0;q<hardware.size();q++)consumer.putBulkData(mine,hardware.get(q),1,1,1,1,s.light,OverlayTexture.NO_OVERLAY,true);
                }
                mine.pose().set(BASE);
            }finally{pose.popPose();}
            buffers.endBatch(atlasType);
            for(int i=0;i<SHOWN.size();i++)for(var name:SHOWN.get(i).wiring.names())if(!NAMES.contains(name))NAMES.add(name);
            NAMES.sort(null);
            boolean lit=com.wormzjl.icheme.client.fx.FxPath.current()==com.wormzjl.icheme.client.fx.FxPath.SHADER&&ClientConfig.pbrTextures()&&com.wormzjl.icheme.client.fx.FxShaders.wiring()!=null&&!LIT.disabled();
            boolean drawn=false;
            if(lit){litEvent=event;litCamera=camera;try{drawn=LIT.run(DRAW_LIT);}finally{litEvent=null;litCamera=null;}}
            if(!drawn)drawFlat(pose,buffers,camera);
        }finally{SHOWN.clear();NAMES.clear();}
    }
    /** Sets {@code mine}'s matrix to {@link #BASE} translated to {@code origin} from the camera: as {@code PoseStack.translate} (to floats, then translate). */
    private static void at(PoseStack.Pose mine,BlockPos origin,Vec3 camera){
        mine.pose().set(BASE).translate((float)(origin.getX()-camera.x),(float)(origin.getY()-camera.y),(float)(origin.getZ()-camera.z));
    }
    /** COMPATIBLE, OFF or pbr_textures off: the vanilla entity cutout shader on the tiled textures; a shader pack loads _n and _s beside them. */
    private static void drawFlat(PoseStack pose,MultiBufferSource.BufferSource buffers,Vec3 camera){
        pose.pushPose();
        try{
            var mine=pose.last();BASE.set(mine.pose());
            for(int n=0;n<NAMES.size();n++){
                var name=NAMES.get(n);var type=texture(name).flat();var consumer=buffers.getBuffer(type);
                for(int i=0;i<SHOWN.size();i++){
                    var s=SHOWN.get(i);int at=s.wiring.index(name);if(at<0)continue;
                    var f=s.wiring.finish()[at];int light=s.light;
                    at(mine,s.origin,camera);
                    for(int k=0;k<f.length;k+=8)normal(vertex(consumer,mine,f[k],f[k+1],f[k+2]).setColor(255,255,255,255).setUv(f[k+3],f[k+4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light),mine,f[k+5],f[k+6],f[k+7]);
                }
                buffers.endBatch(type);
            }
            mine.pose().set(BASE);
        }finally{pose.popPose();}
    }
    /** SHADER path: our wiring shader with the texture's _n and _s, one cached buffer per structure and texture, its light as a uniform. */
    private static void drawLit(RenderLevelStageEvent event,Vec3 camera){
        var mc=Minecraft.getInstance();var textures=mc.getTextureManager();
        int shown=SHOWN.size(),cells=NAMES.size()*shown;
        if(litBuffers.length<cells)litBuffers=new VertexBuffer[Math.max(cells,2*litBuffers.length)];
        try{
            for(int n=0;n<NAMES.size();n++)for(int i=0;i<shown;i++){
                var s=SHOWN.get(i);int at=s.wiring.index(NAMES.get(n));
                litBuffers[n*shown+i]=at<0?null:uploaded(s.wiring.keys()[at],s.wiring.finish()[at]);
            }
            for(int n=0;n<NAMES.size();n++){
                var texture=texture(NAMES.get(n));
                var type=com.wormzjl.icheme.client.fx.FxRenderTypes.wiring(texture.base);
                type.setupRenderState();var shader=com.mojang.blaze3d.systems.RenderSystem.getShader();
                try{
                    if(shader==null)throw new IllegalStateException("The wiring shader is not bound");
                    var origin=shader.getUniform("WiringOrigin");var light=shader.getUniform("WiringLight");
                    if(origin==null||light==null)throw new IllegalStateException("The wiring shader lacks WiringOrigin or WiringLight");
                    com.wormzjl.icheme.client.fx.FxShaders.defaultUniforms(shader,event.getModelViewMatrix(),event.getProjectionMatrix(),mc.getWindow());
                    shader.setSampler("NormalMap",textures.getTexture(texture.normal));shader.setSampler("SpecularMap",textures.getTexture(texture.specular));
                    shader.apply();
                    for(int i=0;i<shown;i++){
                        var buffer=litBuffers[n*shown+i];if(buffer==null)continue;var s=SHOWN.get(i);
                        origin.set((float)(s.origin.getX()-camera.x),(float)(s.origin.getY()-camera.y),(float)(s.origin.getZ()-camera.z));origin.upload();
                        light.set(s.light&0xFFFF,s.light>>>16&0xFFFF);light.upload();
                        buffer.bind();buffer.draw();
                    }
                }finally{if(shader!=null)shader.clear();VertexBuffer.unbind();type.clearRenderState();}
            }
        }finally{java.util.Arrays.fill(litBuffers,0,Math.min(cells,litBuffers.length),null);}
    }
    /** The finish mesh of {@code key}, uploaded on first use from its vertices; light comes from the shader's uniform. */
    private static VertexBuffer uploaded(Uploaded key,float[] finish){
        var buffer=UPLOADED.get(key);if(buffer!=null)return buffer;
        int vertices=finish.length/8;
        try(var bytes=new ByteBufferBuilder(Math.max(256,vertices*DefaultVertexFormat.NEW_ENTITY.getVertexSize()))){
            var builder=new BufferBuilder(bytes,VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
            for(int k=0;k<finish.length;k+=8)builder.addVertex(finish[k],finish[k+1],finish[k+2]).setColor(-1).setUv(finish[k+3],finish[k+4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0).setNormal(finish[k+5],finish[k+6],finish[k+7]);
            var mesh=builder.build();if(mesh==null)return null;
            com.wormzjl.icheme.client.fx.FxStats.add(com.wormzjl.icheme.client.fx.FxStats.Counter.WIRING_BYTES,mesh.vertexBuffer().remaining());
            buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();buffer.upload(mesh);VertexBuffer.unbind();
        }
        UPLOADED.put(key,buffer);return buffer;
    }
    private interface Corner{void at(float x,float y,float z,float u,float v,float nx,float ny,float nz);}
    /** A convex finish polygon as quads (fan pairs, the last corner repeated when odd), v from metres to texture periods. */
    private static void corners(Mesh.Polygon polygon,Corner out){
        var p=polygon.positions();var uv=polygon.uvs();var n=polygon.normals();
        for(int first=1;first+1<polygon.size();first+=2)for(int c:new int[]{0,first,first+1,Math.min(first+2,polygon.size()-1)})
            out.at((float)p[3*c],(float)p[3*c+1],(float)p[3*c+2],(float)uv[2*c],(float)(uv[2*c+1]/InstrumentWiring.PERIOD),(float)n[3*c],(float)n[3*c+1],(float)n[3*c+2]);
    }
    /** {@code polygons} as finish vertices of 8 floats each ({@link #corners}). */
    private static float[] vertices(List<Mesh.Polygon> polygons){
        int count=0;for(var p:polygons)count+=4*((p.size()-1)/2);
        var out=new float[8*count];int[] at={0};
        for(var p:polygons)corners(p,(x,y,z,u,v,nx,ny,nz)->{int k=at[0];out[k]=x;out[k+1]=y;out[k+2]=z;out[k+3]=u;out[k+4]=v;out[k+5]=nx;out[k+6]=ny;out[k+7]=nz;at[0]=k+8;});
        return out;
    }
    /**
     * The plan's wiring: the one kept while the atlas, detail, installed lists (by identity) and an exchanger's nozzle codes
     * hold, read without allocating; otherwise {@link #wiring(com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity)}.
     */
    private static Wiring wiring(Plan plan,com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell){
        var detail=com.wormzjl.icheme.client.model.EquipmentShellModel.detail();var atlas=com.wormzjl.icheme.client.model.EquipmentShellModel.sprite(MATERIALS[0]);if(atlas==null)return null;
        var kept=plan.wiring;
        if(kept!=null&&kept.atlas()==atlas&&kept.detail()==detail&&codesHold(plan,shell,kept))return kept;
        return plan.wiring=wiring(shell);
    }
    /** Whether an exchanger's nozzle codes, read from its loaded pieces, are still {@code kept}'s; true for other kinds. */
    private static boolean codesHold(Plan plan,com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell,Wiring kept){
        if(plan.portAt==null)return true;
        var level=shell.getLevel();var codes=plan.codes;java.util.Arrays.fill(codes,0);
        for(int i=0;i<plan.portAt.length;i++){var at=plan.portAt[i];if(level.hasChunkAt(at)&&level.getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity piece)codes[plan.portNozzle[i]]=ShapeRasterizer.nozzleCode(plan.shape,piece.cell(),plan.portNozzle[i],piece.nozzles());}
        var held=kept.codes();if(held.size()!=codes.length)return false;
        for(int i=0;i<codes.length;i++)if(held.get(i)!=codes[i])return false;
        return true;
    }
    /** The wiring, built again only when what it is drawn from changed; null while a sprite is missing. */
    private static Wiring wiring(com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell){
        var shape=shell.shape();var host=shell.instruments();
        var installed=new HashSet<String>();host.installed().forEach(i->installed.add(i.key().mount()));host.sensors().forEach(i->installed.add(i.key().mount()));
        var codes=new ArrayList<Integer>(java.util.Collections.nCopies(shape.nozzles().size(),0));
        if(shape.kind()==EquipmentShape.Kind.EXCHANGER)for(var port:ShapeRasterizer.ports(shape)){var c=port.cell();var at=shell.origin().offset(c.x(),c.y(),c.z());if(shell.getLevel().hasChunkAt(at)&&shell.getLevel().getBlockEntity(at) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity piece)codes.set(port.nozzle(),ShapeRasterizer.nozzleCode(shape,piece.cell(),port.nozzle(),piece.nozzles()));}
        var detail=com.wormzjl.icheme.client.model.EquipmentShellModel.detail();var atlas=com.wormzjl.icheme.client.model.EquipmentShellModel.sprite(MATERIALS[0]);if(atlas==null)return null;
        var kept=WIRING.get(shell.getBlockPos());
        if(kept!=null&&kept.atlas()==atlas&&kept.detail()==detail&&kept.shape().equals(shape)&&kept.installed().equals(installed)&&kept.codes().equals(codes))return kept;
        var drawing=ShapeRasterizer.instrumentWiring(shape,installed,detail,codes);List<net.minecraft.client.renderer.block.model.BakedQuad> quads=List.of();
        var steel=new ArrayList<Mesh.Polygon>();var finish=new java.util.TreeMap<String,List<Mesh.Polygon>>();
        for(int i=0;i<drawing.polygons().size();i++){var name=drawing.finishes().get(i).texture();if(name==null)steel.add(drawing.polygons().get(i));else finish.computeIfAbsent(name,k->new ArrayList<>()).add(drawing.polygons().get(i));}
        if(!steel.isEmpty()){
            var sprites=new net.minecraft.client.renderer.texture.TextureAtlasSprite[MATERIALS.length];for(var m:MATERIALS){sprites[m.ordinal()]=com.wormzjl.icheme.client.model.EquipmentShellModel.sprite(m);if(sprites[m.ordinal()]==null)return null;}
            quads=com.wormzjl.icheme.client.model.EquipmentShellModel.bake(steel,sprites,true);
        }
        if(WIRING.size()>=256)WIRING.clear();
        long version=++wiringBuilds;var at=shell.getBlockPos().immutable();
        var names=finish.keySet().toArray(String[]::new);var vertices=new float[names.length][];var keys=new Uploaded[names.length];
        for(int i=0;i<names.length;i++){vertices[i]=vertices(finish.get(names[i]));keys[i]=new Uploaded(at,version,names[i]);}
        var wiring=new Wiring(shape,Set.copyOf(installed),List.copyOf(codes),detail,atlas,quads,names,vertices,keys,version);
        com.wormzjl.icheme.client.fx.FxStats.add(com.wormzjl.icheme.client.fx.FxStats.Counter.WIRING_BUILDS,1);
        WIRING.put(shell.getBlockPos(),wiring);
        return wiring;
    }
    /** The frustum box of a mount's dial or head, with its tap. */
    private static net.minecraft.world.phys.AABB box(Instrument.Mount mount){
        var c=mount.centre();var t=mount.tap();
        return new net.minecraft.world.phys.AABB(Math.min(c.x,t.x)-.2,Math.min(c.y,t.y)-.2,Math.min(c.z,t.z)-.2,Math.max(c.x,t.x)+.2,Math.max(c.y,t.y)+.2,Math.max(c.z,t.z)+.2);
    }
    /** Hosts with installed instruments near the player, gathered again every {@link #HOST_TICKS} ticks or on a new level; prunes the plans of removed hosts. */
    private static List<InstrumentHosts.Host> shownHosts(Minecraft mc){
        long now=mc.level.getGameTime();
        if(shownIn!=mc.level||now<shownAt||now-shownAt>=HOST_TICKS){
            shown=InstrumentHosts.nearby(mc.level,mc.player.position(),120,true);shownAt=now;shownIn=mc.level;
            PLANS.values().removeIf(p->p.entity.isRemoved());
        }
        return shown;
    }
    private static void outline(PoseStack pose,MultiBufferSource buffers,Instrument.Selectable selection,Vec3 camera,int colour) {
        var lines=buffers.getBuffer(RenderType.lines());
        if(selection instanceof Instrument.Mount mount) {
            for(int i=0;i<32;i++) {
                double a=2*Math.PI*i/32,b=2*Math.PI*(i+1)/32;
                var first=mount.point(.177*Math.cos(a),.177*Math.sin(a),.073).subtract(camera);
                var second=mount.point(.177*Math.cos(b),.177*Math.sin(b),.073).subtract(camera);
                var direction=second.subtract(first).normalize();
                for(var point:List.of(first,second))normal(vertex(lines,pose.last(),(float)point.x,(float)point.y,(float)point.z).setColor(colour>>16&255,colour>>8&255,colour&255,135),pose.last(),(float)direction.x,(float)direction.y,(float)direction.z);
            }
        } else LevelRenderer.renderLineBox(pose,lines,selection.bounds().move(camera.scale(-1)),(colour>>16&255)/255f,(colour>>8&255)/255f,(colour&255)/255f,.53f);
    }
    /** An installed transmitter head at {@code tier} of {@link #HEADS}: text by tier, the connector while {@code stems}. */
    private static void drawHead(PoseStack pose,MultiBufferSource buffers,Head head,Vec3 camera,int tier,boolean stems){
        var consumer=buffers.getBuffer(TYPE);int light=lightAt(head.light);
        submit(pose,consumer,head.body,camera,0,255,light);
        if(!head.equipment&&stems)submit(pose,consumer,head.connector(),camera,0,255,light);
        // Font rendering may flush the shared geometry buffer. Finish every connector before requesting font buffers.
        if(tier<=PLAIN)text(pose,buffers,head.mount,camera,head.kind.symbol(),0,.038,.011f,.074,0xffa6d4d9);
        if(tier==FULL&&!head.id.isEmpty())text(pose,buffers,head.mount,camera,head.id,0,-.072,(float)Math.min(.0013,.15/Math.max(1,Minecraft.getInstance().font.width(head.id))),.074,0xff152126);
    }
    /** A placing ghost of a transmitter head: the full head, with its connector on a pipe; no text. */
    private static void drawSensor(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,Sensor.Kind kind,int alpha,boolean equipment){
        var consumer=buffers.getBuffer(alpha==255?TYPE:GHOST);var frame=frame(mount);int light=light(mount,0);
        submit(pose,consumer,SCRATCH.set(InstrumentGeometry.transmitter(kind==Sensor.Kind.LEVEL),frame.centre(),frame.normal()),camera,0,alpha,light);
        if(!equipment)submit(pose,consumer,SCRATCH.set(connection(mount,kind==Sensor.Kind.TEMPERATURE),frame.centre(),frame.normal()),camera,0,alpha,light);
    }
    /** An installed dial at {@code tier} of {@link #DIALS}: its housing, connector and text by tier, its needle always. */
    private static void drawDial(PoseStack pose,MultiBufferSource buffers,Dial dial,Vec3 camera,double ratio,int tier){
        var consumer=buffers.getBuffer(TYPE);int light=lightAt(dial.light);
        submit(pose,consumer,dial.housing(tier),camera,0,255,light);
        if(!dial.equipment&&tier<=PLAIN)submit(pose,consumer,dial.connector(),camera,0,255,light);
        submit(pose,consumer,dial.needle(ratio),camera,0,255,light);
        if(tier==FULL){
            text(pose,buffers,dial.mount,camera,dial.settings.label(),0,-.098,.0025f);
            var labels=dial.labels();
            for(int i=0;i<=4;i++){
                double fraction=i/4.0,angle=Math.PI*(1.25-1.5*fraction);
                String label=labels[i];
                float scale=Math.min(.0025f,.061f/Math.max(1,Minecraft.getInstance().font.width(label)));
                text(pose,buffers,dial.mount,camera,label,.081*Math.cos(angle),.081*Math.sin(angle)+.010,scale);
            }
        }
    }
    /** A placing ghost of a dial on equipment: the full housing and a needle at mid-scale; no text. */
    private static void drawGhostDial(PoseStack pose,MultiBufferSource buffers,Instrument.Mount mount,Vec3 camera,int alpha){
        var consumer=buffers.getBuffer(alpha==255?TYPE:GHOST);var frame=frame(mount);int light=light(mount,0);
        submit(pose,consumer,SCRATCH.set(InstrumentGeometry.housing(),frame.centre(),frame.normal()),camera,0,alpha,light);
        submit(pose,consumer,SCRATCH.one(InstrumentGeometry.needle(.5),frame.centre(),frame.normal()),camera,0,alpha,light);
    }
    /** A connector's local quads for {@code mount}, from {@link #CONNECTORS}. */
    private static List<InstrumentGeometry.Quad> connection(Instrument.Mount mount,boolean temperature){
        var tap=mount.localTap();var key=new Connector(List.of(tap[0],tap[1],tap[2]),mount.bottomEntry(),temperature,mount.probeDepth());
        if(CONNECTORS.size()>2048)CONNECTORS.clear();
        return CONNECTORS.computeIfAbsent(key,k->InstrumentGeometry.connection(new double[]{tap[0],tap[1],tap[2]},k.bottom(),k.temperature(),k.probeDepth()));
    }
    private static String compact(double value){return String.format(Locale.ROOT,Math.abs(value)>=10000?"%.1e":"%.3g",value);}
    /** A mount's centre and outward normal, the pose of every quad drawn on it. */
    private record Frame(double[] centre,double[] normal){}
    private static Frame frame(Instrument.Mount mount){var c=mount.centre();var n=mount.normal();return new Frame(new double[]{c.x,c.y,c.z},new double[]{n.x,n.y,n.z});}
    /** One light per instrument, at its centre (each vertex used to read the same point); ghosts full bright. */
    private static int light(Instrument.Mount mount,int tint){return tint==0?LevelRenderer.getLightColor(Minecraft.getInstance().level,net.minecraft.core.BlockPos.containing(mount.centre())):LightTexture.FULL_BRIGHT;}
    /** {@link #light} at an instrument's kept light position. */
    private static int lightAt(BlockPos at){return LevelRenderer.getLightColor(Minecraft.getInstance().level,at);}
    /**
     * Writes {@code posed}'s quads relative to the camera, {@code tint} (0: each quad's colour) at {@code alpha}. The pose's
     * transforms as {@code addVertex(pose, …)} and {@code setNormal(pose, …)} made them, into {@link #POSITION} and
     * {@link #NORMAL} (those defaults allocate a vector a vertex); the normal once a quad, as each of its vertices took it.
     */
    private static void submit(PoseStack pose,VertexConsumer consumer,Posed posed,Vec3 camera,int tint,int alpha,int light){
        var last=pose.last();var matrix=last.pose();var v=posed.xyz;var normals=posed.normals;double cx=camera.x,cy=camera.y,cz=camera.z;var p=POSITION;
        for(int q=0;q<posed.quads;q++){
            int c=tint==0?posed.colours[q]:tint,o=12*q,r=c>>16&255,g=c>>8&255,b=c&255;
            var n=last.transformNormal(normals[3*q],normals[3*q+1],normals[3*q+2],NORMAL);float nx=n.x,ny=n.y,nz=n.z;
            for(int j=0;j<4;j++){
                matrix.transformPosition((float)(v[o+j*3]-cx),(float)(v[o+j*3+1]-cy),(float)(v[o+j*3+2]-cz),p);
                consumer.addVertex(p.x,p.y,p.z).setColor(r,g,b,alpha).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(nx,ny,nz);
            }
        }
    }
    /** The render thread's transform targets for {@link #vertex}, {@link #normal} and {@link #submit}. */
    private static final org.joml.Vector3f POSITION=new org.joml.Vector3f(),NORMAL=new org.joml.Vector3f();
    /** As {@code consumer.addVertex(pose, x, y, z)}: the same transform, into {@link #POSITION}. */
    private static VertexConsumer vertex(VertexConsumer consumer,PoseStack.Pose pose,float x,float y,float z){
        var p=pose.pose().transformPosition(x,y,z,POSITION);return consumer.addVertex(p.x,p.y,p.z);
    }
    /** As {@code consumer.setNormal(pose, x, y, z)}: the same transform, into {@link #NORMAL}. */
    private static VertexConsumer normal(VertexConsumer consumer,PoseStack.Pose pose,float x,float y,float z){
        var n=pose.transformNormal(x,y,z,NORMAL);return consumer.setNormal(n.x,n.y,n.z);
    }
    private static void text(PoseStack pose,MultiBufferSource buffers,Instrument.Mount m,Vec3 camera,String text,double x,double y,float scale){text(pose,buffers,m,camera,text,x,y,scale,InstrumentGeometry.LABEL,0xff252b2e);}
    private static void text(PoseStack pose,MultiBufferSource buffers,Instrument.Mount m,Vec3 camera,String text,double x,double y,float scale,double depth,int colour){var mc=Minecraft.getInstance();var p=m.point(x,y,depth).subtract(camera);pose.pushPose();pose.translate(p.x,p.y,p.z);float angle=(float)Math.toDegrees(Math.atan2(m.normal().x,m.normal().z));pose.mulPose(Axis.YP.rotationDegrees(angle));pose.scale(scale,-scale,scale);mc.font.drawInBatch(text,-mc.font.width(text)/2f,0,colour,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);pose.popPose();}
    private static void drawLevelGhost(PoseStack pose,MultiBufferSource buffers,InstrumentHosts.Host host,Vec3 camera,int tint,int alpha){
        if(!(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell))return;
        var origin=shell.origin();var consumer=buffers.getBuffer(GHOST);
        pose.pushPose();pose.translate(origin.getX()-camera.x,origin.getY()-camera.y,origin.getZ()-camera.z);
        for(var face:com.wormzjl.icheme.science.equipment.ShapeRasterizer.tankGauge(shell.shape())){
            if(face.material()==com.wormzjl.icheme.science.equipment.Mesh.Material.GLASS)continue;
            var p=face.positions();var n=face.normals();
            for(int first=1;first+1<face.size();first+=2)for(int c:new int[]{0,first,first+1,Math.min(first+2,face.size()-1)})
                normal(vertex(consumer,pose.last(),(float)p[3*c],(float)p[3*c+1],(float)p[3*c+2]).setColor(tint>>16&255,tint>>8&255,tint&255,alpha).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT),pose.last(),(float)n[3*c],(float)n[3*c+1],(float)n[3*c+2]);
        }
        pose.popPose();
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen!=null)return;
        var hosts=hudHosts(mc);
        var selected=InstrumentHosts.select(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),null,true);
        var fitting=InstrumentHosts.selectFitting(hosts,mc.player.getEyePosition(),mc.player.getLookAngle(),mc.player.blockInteractionRange(),true);
        Component line;
        String sensorId=sensorIdAt(hosts,mc.player);
        if(sensorId!=null)line=Component.literal(sensorId);
        else if(fitting!=null&&fitting.mount() instanceof InstrumentHosts.LevelGauge&&(selected==null||fitting.distance()<selected.distance())&&InstrumentHosts.reachable(mc.player,fitting.host(),fitting.mount())){
            var display=TankRenderer.get(fitting.host().entity().getBlockPos(),mc.level.getGameTime());
            line=display==null?Component.translatable("gui.icheme.gauge.unavailable"):Component.literal(String.format(Locale.ROOT,"%.3f m",display.level()));
        }else{
            if(selected==null||!InstrumentHosts.reachable(mc.player,selected.host(),selected.mount()))return;
            var h=selected.host();var installed=h.metadata().installed().stream().filter(v->v.key().mount().equals(selected.mount().id())).findFirst().orElse(null);if(installed==null)return;
            var settings=installed.settings();var display=get(new FluidNetwork.InstrumentTarget(h.entity().getBlockPos(),installed.key(),installed.instance()));var sample=display==null?null:display.sample();
            line=sample==null||sample.availability()==Instrument.Availability.UNAVAILABLE?Component.translatable("gui.icheme.gauge.unavailable"):Component.literal(settings.number(sample.value())+" "+settings.label());
        }
        var g=event.getGuiGraphics();int x=g.guiWidth()/2+14,y=g.guiHeight()/2+12;
        g.fill(x-3,y-2,x+mc.font.width(line)+3,y+10,0xe0202a31);g.drawString(mc.font,line,x,y,0xffeaf1f5,false);
    }

    /** Hosts with installed instruments within 8 m of the eye, gathered again every {@link #HOST_TICKS} ticks, as {@link #shownHosts}. */
    private static List<InstrumentHosts.Host> hudHosts(Minecraft mc){
        long now=mc.level.getGameTime();
        if(hudIn!=mc.level||now<hudAt||now-hudAt>=HOST_TICKS){hudShown=InstrumentHosts.nearby(mc.level,mc.player.getEyePosition(),8,true);hudAt=now;hudIn=mc.level;}
        return hudShown;
    }
    private static List<InstrumentHosts.Host> hudShown=List.of();
    private static long hudAt=Long.MIN_VALUE;
    private static net.minecraft.world.level.Level hudIn;

    private static String sensorIdAt(List<InstrumentHosts.Host> hosts,net.minecraft.client.player.LocalPlayer player){
        var eye=player.getEyePosition();var end=eye.add(player.getLookAngle().scale(player.blockInteractionRange()));double nearest=Double.POSITIVE_INFINITY;String id=null;
        for(var host:hosts)for(var sensor:host.metadata().sensors())for(var mount:Sensor.mounts(host,sensor.kind())){
            if(!mount.id().equals(sensor.key().mount()))continue;var hit=mount.bounds().clip(eye,end).orElse(null);
            if(hit==null||hit.distanceTo(eye)>=nearest||!InstrumentHosts.reachable(player,host,mount))continue;nearest=hit.distanceTo(eye);
            id=host.metadata().sensorIds().getOrDefault(sensor.instance(),sensor.kind().name())+" / "+sensor.kind().name();
        }return id;
    }

    // ---- the client's live readings: ephemeral, identity/instance-bound, the nearest 64 within 120 m, requested explicitly ----
    private static final Map<FluidNetwork.InstrumentTarget,FluidNetwork.InstrumentDisplay> CACHE=new HashMap<>();
    private static final Map<FluidNetwork.InstrumentTarget,Long> RECEIVED=new HashMap<>();
    public static double age(FluidNetwork.InstrumentDisplay d){var mc=Minecraft.getInstance();long elapsed=mc.level==null?0:Math.max(0,mc.level.getGameTime()-RECEIVED.getOrDefault(d.target(),mc.level.getGameTime()));return (d.publishedTick()-d.sample().tick()+elapsed)/20d;}
    private static final Set<FluidNetwork.InstrumentTarget> WATCHED=new HashSet<>();
    private static net.minecraft.world.level.Level level;
    private static int ticks;
    public static FluidNetwork.InstrumentDisplay get(FluidNetwork.InstrumentTarget target){return CACHE.get(target);}
    public static void accept(FluidNetwork.InstrumentDisplay display){if(WATCHED.contains(display.target())){CACHE.put(display.target(),display);var mc=Minecraft.getInstance();if(mc.level!=null)RECEIVED.put(display.target(),mc.level.getGameTime());}}
    public static void forget(BlockPos pos){var removed=WATCHED.stream().filter(t->t.position().equals(pos)).toList();WATCHED.removeAll(removed);removed.forEach(CACHE::remove);removed.forEach(RECEIVED::remove);if(Minecraft.getInstance().getConnection()!=null)FluidNetwork.requestInstruments(List.of(),removed);}
    public static void clear(){WIRING.clear();UPLOADED.clear();com.wormzjl.icheme.client.fx.FxStats.set(com.wormzjl.icheme.client.fx.FxStats.Counter.WIRING_BYTES,0);SHOWN.clear();NAMES.clear();PLANS.clear();DIALS.clear();HEADS.clear();WIRES.clear();shown=List.of();shownIn=null;shownAt=Long.MIN_VALUE;hudShown=List.of();hudIn=null;hudAt=Long.MIN_VALUE;CACHE.clear();RECEIVED.clear();WATCHED.clear();level=null;ticks=0;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if(mc.level!=level){clear();level=mc.level;}if(mc.level==null||mc.player==null)return;if(++ticks%20!=0)return;var nearby=new ArrayList<FluidNetwork.InstrumentTarget>();var distances=new HashMap<FluidNetwork.InstrumentTarget,Double>();for(var h:InstrumentHosts.nearby(mc.level,mc.player.position(),120,true))for(var i:h.metadata().installed()){var mount=h.mount(i.key().mount());if(mount!=null&&mount.centre().distanceToSqr(mc.player.position())<=120*120){var target=new FluidNetwork.InstrumentTarget(h.entity().getBlockPos(),i.key(),i.instance());nearby.add(target);distances.put(target,mount.centre().distanceToSqr(mc.player.position()));}}nearby.sort(Comparator.comparingDouble(distances::get));var wanted=new HashSet<>(nearby.subList(0,Math.min(64,nearby.size())));var add=wanted.stream().filter(t->!WATCHED.contains(t)).toList();var remove=WATCHED.stream().filter(t->!wanted.contains(t)).toList();WATCHED.removeAll(remove);remove.forEach(CACHE::remove);remove.forEach(RECEIVED::remove);WATCHED.addAll(add);FluidNetwork.requestInstruments(add,remove);}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
}
