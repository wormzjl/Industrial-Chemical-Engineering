from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/client/DataCableLayingClient.java');s=p.read_text(encoding='utf-8')
s=s.replace('private static DataCableLaying.End start,end;','private static DataCableLaying.End start,end,hovered;')
s=s.replace('public static boolean holding(LocalPlayer p){return tool(p.getMainHandItem())||tool(p.getOffhandItem());}','public static boolean holding(LocalPlayer p){return LayingToolAim.holding(p)&&PipeLayingToolItem.data(LayingToolAim.held(p));}')
a=s.index('        if(!(mc.hitResult instanceof BlockHitResult hit)');b=s.index('\n    }',a)
s=s[:a]+'''        var click=LayingToolAim.aimed(mc,(rack,hit)->false);
        return click==null?null:new DataCableLaying.End(null,click.clicked(),click.face(),click.local(),0);'''+s[b:]
s=s.replace('mc.level==null||!tool(mc.player.getItemInHand(event.getHand()))','mc.level==null||!holding(mc.player)||!tool(mc.player.getItemInHand(event.getHand()))')
a=s.index('    @SubscribeEvent public static void tick(');b=s.index('    private static void plan(',a)
s=s[:a]+'''    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(!mc.options.keyUse.isDown())armed=true;
        if(mc.player==null||mc.level==null||!holding(mc.player)||dimension!=null&&!dimension.equals(mc.level.dimension())){clear();return;}
        age++;if(start!=null&&start.clicked().getCenter().distanceToSqr(mc.player.position())>96*96){clear();say("gui.icheme.data.cancelled");}
        hovered=mc.screen==null?aimed():null;if(start!=null&&!sent&&age%5==0)plan(hovered);
    }
'''+s[b:]
s=s.replace('private static void clear(){request=null;','private static void clear(){hovered=null;request=null;')
mark='        if(start!=null){var anchor=DataCableLaying.resolve'
a=s.index(mark)
s=s[:a]+'''        if(hovered!=null){
            if(mc.player.isShiftKeyDown()&&start==null){
                if(mc.level.getBlockEntity(hovered.clicked()) instanceof DataCableBlockEntity wire)for(var segment:DataCableGeometry.segments(wire.geometry()))box(pose,buffers,camera,new AABB(Math.min(segment.ax(),segment.bx())-.04,Math.min(segment.ay(),segment.by())-.04,Math.min(segment.az(),segment.bz())-.04,Math.max(segment.ax(),segment.bx())+.04,Math.max(segment.ay(),segment.by())+.04,Math.max(segment.az(),segment.bz())+.04).move(hovered.clicked()),true);
                else if(mc.level.getBlockEntity(hovered.clicked()) instanceof PipeRackBlockEntity rack)for(var cable:rack.cables())if(cable.site().lane()==hovered.lane())for(var b:RackGeometry.boxes(rack.rack(),rack.cableLane(cable)))box(pose,buffers,camera,new AABB(b[0],b[1],b[2],b[3],b[4],b[5]).move(rack.getBlockPos()),true);
            }else if(hovered.port()==null&&hovered.lane()==0&&mc.level.getBlockEntity(hovered.clicked()) instanceof PipeRackBlockEntity rack){
                for(var free:LayingToolAim.freeSlots(rack))box(pose,buffers,camera,free,false);
                if(start==null||route==null||!route.found()){
                    var slot=LayingToolAim.slot(mc.level,new PipeAnchor.Click(hovered.clicked(),hovered.face(),hovered.local()),DataCableGeometry.OUTSIDE);
                    if(slot!=null)DataControlRenderer.draw(pose,buffers,camera,Vec3.atLowerCornerOf(rack.getBlockPos()),DataCableGeometry.path(RackGeometry.path(rack.rack(),slot.lane())),slot.fits()?0x70c7e8:0xff5555,135);
                }
            }
        }
'''+s[a:]
a=s.rfind('\n}')
s=s[:a]+'''    private static void box(PoseStack pose,MultiBufferSource buffers,Vec3 camera,AABB bounds,boolean refused){net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines()),bounds.move(camera.scale(-1)),refused?1:.3f,refused?.3f:1,refused?.3f:.35f,1);}
'''+s[a:]
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/PipeLayingClient.java');s=p.read_text(encoding='utf-8').replace('mc.level == null || !tool(player.getItemInHand(event.getHand()))','mc.level == null || !holding(player) || !tool(player.getItemInHand(event.getHand()))');p.write_text(s,encoding='utf-8')
