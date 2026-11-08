from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
# Register derived block/copy. No item, loot, network fact identity.
p=r/'registry/ModBlocks.java';s=p.read_text();i=s.index('    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ConduitBlock> DATA_CABLE');s=s[:i]+'''    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.LinePieceBlock> LINE_PIECE=BLOCKS.registerBlock("line_piece",
            com.wormzjl.icheme.world.level.block.LinePieceBlock::new,BlockBehaviour.Properties.of().strength(.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape().noLootTable());
'''+s[i:];p.write_text(s)
p=r/'registry/ModBlockEntities.java';s=p.read_text();i=s.index('    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity>>');s=s[:i]+'''    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.icheme.world.level.block.entity.LinePieceBlockEntity>> LINE_PIECE=BLOCK_ENTITY_TYPES.register("line_piece",
            ()->BlockEntityType.Builder.of(com.wormzjl.icheme.world.level.block.entity.LinePieceBlockEntity::new,ModBlocks.LINE_PIECE.get()).build(null));
'''+s[i:];p.write_text(s)
p=r/'runtime/control/ControlFacts.java';s=p.read_text();i=s.index('    public void cable(DataBus.Cable');j=s.index('    private void changed()',i);s=s[:i]+'''    private java.util.function.Consumer<DataBus.Site> lineChanges=site->{};
    public void lineChanges(java.util.function.Consumer<DataBus.Site> listener){lineChanges=Objects.requireNonNull(listener);}
    private void leadChanged(DataPort port){if(port!=null)lineChanges.accept(new DataBus.Site(port.dimension(),port.cell(),0));}
    public void cable(DataBus.Cable cable){if(cable.equals(cables.put(cable.site(),cable)))return;wiredPorts=null;changed();lineChanges.accept(cable.site());}
    public void removeCable(DataBus.Site site){if(cables.remove(site)!=null){wiredPorts=null;changed();lineChanges.accept(site);}}
    public void port(DataPort port){var old=ports.put(port.id(),port);if(port.equals(old))return;changed();leadChanged(old);leadChanged(port);}
    public void removePort(UUID id){var old=ports.remove(id);if(old!=null){changed();leadChanged(old);}}
    public void panel(Panel panel){var before=panels.put(panel.id(),panel);if(panel.equals(before))return;if(before==null)changed();else setDirty();}
    public void removePanel(UUID id){if(panels.remove(id)!=null){removePort(id);changed();}}
'''+s[j:];p.write_text(s)
p=r/'runtime/fluid/FluidWorldAuthority.java';s=p.read_text().replace('controlFacts=com.wormzjl.icheme.runtime.control.ControlFacts.open(server);','controlFacts=com.wormzjl.icheme.runtime.control.ControlFacts.open(server);\n        com.wormzjl.icheme.world.level.block.LinePieces.watch(server,controlFacts);');p.write_text(s)
p=r/'IChemE.java';s=p.read_text().replace('com.wormzjl.icheme.runtime.power.PowerNetwork.of(event.getServer()).tick();','com.wormzjl.icheme.world.level.block.LinePieces.flush(event.getServer());\n            com.wormzjl.icheme.runtime.power.PowerNetwork.of(event.getServer()).tick();');p.write_text(s)
p=r/'world/level/block/LinePlacement.java';s=p.read_text().replace('private static List<AABB> bounds','public static List<AABB> bounds').replace('double radius=lead.outside()/2;','double radius=Math.max(3.0/32,lead.outside()/2);');# Allow its existing derived copy during idempotent attachment/preflight
s=s.replace('            if(state.canBeReplaced())continue;','''            if(state.canBeReplaced())continue;
            if(state.getBlock() instanceof LinePieceBlock&&LinePieces.at(level,pos).stream().allMatch(p->p.lead().equals(lead)))continue;''');p.write_text(s)
p=r/'world/level/block/LineLaying.java';s=p.read_text();s=s.replace('var a=LineAnchors.resolve(level,first,settings);','first=LinePieces.target(level,first,settings.profile());last=LinePieces.target(level,last,settings.profile());\n        var a=LineAnchors.resolve(level,first,settings);',1);# server normalize after reach check and before action
s=s.replace('if(request.action()==Action.DISMANTLE)return result(request,dismantle(player,world,request.first(),settings),0);','if(request.action()==Action.DISMANTLE)return result(request,dismantle(player,world,LinePieces.target(level,request.first(),settings.profile()),settings),0);');s=s.replace('var a=LineAnchors.resolve(level,request.first(),settings);var b=LineAnchors.resolve(level,request.last(),settings);','var a=LineAnchors.resolve(level,LinePieces.target(level,request.first(),settings.profile()),settings);var b=LineAnchors.resolve(level,LinePieces.target(level,request.last(),settings.profile()),settings);');i=s.index('    private static Result result(');s=s[:i]+'''    /** Native destruction of a derived copy delegates to the same segment registration/removal owner. */
    public static void breakPiece(net.minecraft.server.level.ServerLevel level,LinePieces.Piece piece){
        var site=piece.parent();
        if(!level.hasChunkAt(site.cell())){LinePieces.queue(level.getServer(),site);return;}
        var profile=LineProfile.of(piece.port().kind().medium());var backend=LineBackend.of(profile);
        var host=backend.hosts(level,site.cell(),profile).stream().filter(h->h.lane()==site.lane()).findFirst().orElse(null);
        if(host==null){LinePieces.queue(level.getServer(),site);return;}
        if(host.lane()==0)level.destroyBlock(host.cell(),true);
        else FluidWorldAuthority.find(level.getServer()).ifPresent(world->{backend.forgetLane(level,world,host);backend.refresh(level,List.of(host.cell()));net.minecraft.world.level.block.Block.popResource(level,host.cell(),new ItemStack(profile.item()));});
    }
'''+s[i:];p.write_text(s)
# Entry and proxy copies persist rebuildable lead descriptors; clients index them without process data.
for file in ['ConduitBlockEntity.java','PipeRackBlockEntity.java']:
 p=r/'world/level/block/entity'/file;s=p.read_text();name=file[:-5];s=s.replace('extends ModelledBlockEntity {','extends ModelledBlockEntity implements com.wormzjl.icheme.world.level.block.LinePieces.Copy {') if name=='ConduitBlockEntity' else s.replace('extends BlockEntity {','extends BlockEntity implements com.wormzjl.icheme.world.level.block.LinePieces.Copy {')
 # Rack extends Modelled? inspect automated handle any
 if 'implements com.wormzjl.icheme.world.level.block.LinePieces.Copy' not in s:
  s=s.replace('public final class PipeRackBlockEntity extends ModelledBlockEntity', 'public final class PipeRackBlockEntity extends ModelledBlockEntity implements com.wormzjl.icheme.world.level.block.LinePieces.Copy')
 i=s.index('\n',s.index('public final class '))+1
 s=s[:i]+'''    private java.util.List<com.wormzjl.icheme.world.level.block.LinePieces.Piece> pieces=java.util.List.of();
    public java.util.List<com.wormzjl.icheme.world.level.block.LinePieces.Piece> linePieces(){return pieces;}
    public void linePieces(java.util.List<com.wormzjl.icheme.world.level.block.LinePieces.Piece> value){if(pieces.equals(value))return;pieces=java.util.List.copyOf(value);setChanged();com.wormzjl.icheme.world.level.block.LinePieces.clientCopy(this,pieces);if(level!=null&&!level.isClientSide())level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);}
'''+s[i:]
 s=s.replace('super.onLoad();','super.onLoad();com.wormzjl.icheme.world.level.block.LinePieces.clientCopy(this,pieces);')
 s=s.replace('super.onChunkUnloaded();','com.wormzjl.icheme.world.level.block.LinePieces.unloaded(this);super.onChunkUnloaded();').replace('super.setRemoved();','com.wormzjl.icheme.world.level.block.LinePieces.unloaded(this);super.setRemoved();')
 # compact Conduit vs multiline Rack
 if name=='ConduitBlockEntity':
  s=s.replace('super.saveAdditional(tag,provider);','super.saveAdditional(tag,provider);tag.put("LinePieces",com.wormzjl.icheme.world.level.block.LinePieces.save(pieces));').replace('super.loadAdditional(tag,provider);','super.loadAdditional(tag,provider);pieces=com.wormzjl.icheme.world.level.block.LinePieces.load(tag);com.wormzjl.icheme.world.level.block.LinePieces.clientCopy(this,pieces);')
 else:
  s=s.replace('writeCables(tag);','writeCables(tag);tag.put("LinePieces",com.wormzjl.icheme.world.level.block.LinePieces.save(pieces));').replace('super.loadAdditional(tag, registries);','super.loadAdditional(tag, registries);pieces=com.wormzjl.icheme.world.level.block.LinePieces.load(tag);com.wormzjl.icheme.world.level.block.LinePieces.clientCopy(this,pieces);')
 p.write_text(s)
p=r/'runtime/control/ControlService.java';s=p.read_text().replace('else facts.cable(cable.cable());}', 'else facts.cable(cable.cable());if(cable.getLevel() instanceof net.minecraft.server.level.ServerLevel server)com.wormzjl.icheme.world.level.block.LinePieces.queue(server.getServer(),cable.cable().site());}');s=s.replace('public void loaded(PipeRackBlockEntity rack){','public void loaded(PipeRackBlockEntity rack){if(rack.getLevel() instanceof net.minecraft.server.level.ServerLevel server)for(var c:facts.cables())if(c.site().cell().equals(rack.getBlockPos())&&c.site().dimension().equals(server.dimension().location().toString()))com.wormzjl.icheme.world.level.block.LinePieces.queue(server.getServer(),c.site());');p.write_text(s)
# Union derived lead envelope with owned native shapes, without replacing equipment or its block identity.
p=r/'world/level/block/ConduitBlock.java';s=p.read_text().replace('return LineShapes.shape(key.joints(),5,com.wormzjl.icheme.science.equipment.PipeSizes.code(key.medium().outside()),key.offsets());','return LinePieces.shape(level,pos,LineShapes.shape(key.joints(),5,com.wormzjl.icheme.science.equipment.PipeSizes.code(key.medium().outside()),key.offsets()));');p.write_text(s)
p=r/'world/level/block/EquipmentShellBlock.java';s=p.read_text().replace('return level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell && shell.key() != null\n                ? EquipmentStructure.collision(shell.key()) : Shapes.block();','return LinePieces.shape(level,position,level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell && shell.key() != null\n                ? EquipmentStructure.collision(shell.key()) : Shapes.block());');p.write_text(s)
p=r/'world/level/block/EquipmentInteriorBlock.java';s=p.read_text().replace('if (shape != null) return shape;','if (shape != null) return LinePieces.shape(level,position,shape);');p.write_text(s)
# Shared lead rendering from loaded derived copies; works with socket owner unloaded and deduplicates contributors.
p=r/'client/DataControlRenderer.java';s=p.read_text().replace('            if(connected(mc.level,port))drawLead(pose,buffers,camera,port,false);','');needle='        for(var rack:PipeRackBlockEntity.clientRacks())';i=s.index(needle,s.index('@SubscribeEvent public static void render'));s=s[:i]+'''        for(var piece:com.wormzjl.icheme.world.level.block.LinePieces.visible(mc.level))if(piece.port().centre().distanceToSqr(camera)<96*96)drawLead(pose,buffers,camera,piece.port(),false);
'''+s[i:];s=s.replace('var mesh=PORTS.get(port);var outward=mesh==null?DataPorts.outward(Minecraft.getInstance().level,port):mesh.outward();','if(port==null)return;var outward=port.face().getOpposite();');p.write_text(s)
# Proxy clicks are resolved by the same target normalizer for preview and server.
p=r/'client/CableLayingClient.java';s=p.read_text().replace('return click==null?null:new LineTarget(null,click.clicked(),click.face(),click.local(),0);','return click==null?null:LinePieces.target(mc.level,new LineTarget(null,click.clicked(),click.face(),click.local(),0),LineProfile.of(medium()));');p.write_text(s)
