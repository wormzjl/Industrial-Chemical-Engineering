package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.*;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.client.model.data.*;
import java.util.*;

/** Focused fresh-world checks for the real placement adapter, rather than preconstructed connectivity facts. */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ConduitLayingGameTests {
    private ConduitLayingGameTests(){}
    @GameTest(template="empty",timeoutTicks=80,batch="conduit-collision")
    public static void cableBlocksUseTheSharedPipeShapeForPhysicalCollisionAndPicking(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(1,2,1));
        for(var medium:CableMedium.values())for(int joints:new int[]{48,18,52})for(int offset:new int[]{0,8}){
            level.setBlock(at,(medium==CableMedium.POWER?ModBlocks.POWER_CABLE:ModBlocks.DATA_CABLE).get().defaultBlockState(),3);
            var wire=(ConduitBlockEntity)level.getBlockEntity(at);var key=new ConduitGeometry.Key(joints,0,medium);
            if((joints&16)!=0)key=key.connect(Direction.WEST.ordinal(),offset,0);
            wire.configure(new DataBus.Cable(new DataBus.Site(level.dimension().location().toString(),at,0),key,0,Map.of()));
            var state=level.getBlockState(at);var selection=state.getShape(level,at);var collision=state.getCollisionShape(level,at);
            var expected=LineShapes.shape(key.joints(),5,PipeSizes.code(medium.outside()),key.offsets());
            helper.assertTrue(!collision.isEmpty(),"Conduit physical collision is disabled: "+medium);
            helper.assertTrue(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(collision,selection,net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME),"Selection and physical collision disagree: "+medium);
            helper.assertTrue(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(collision,expected,net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME),"Conduit bypassed shared pipe shape: "+medium);
            level.removeBlock(at,false);
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100,batch="conduit-hand-placement")
    public static void blockItemsRegisterExplicitConnectionsAndKeepParallelLinesSeparate(GameTestHelper helper){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"ConduitHand"));player.setGameMode(GameType.CREATIVE);player.setYRot(-90);
        for(var medium:CableMedium.values()){
            var at=helper.absolutePos(new BlockPos(1,2,1+medium.ordinal()*4));var item=(medium==CableMedium.POWER?ModItems.POWER_CABLE:ModItems.DATA_CABLE).get();
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,8));
            for(var cell:List.of(at,at.east(),at.north())){
                // The empty GameTest template can contain boundary barriers; this fixture requires free target cells.
                level.setBlock(cell,Blocks.AIR.defaultBlockState(),3);
                level.setBlock(cell.below(),Blocks.STONE.defaultBlockState(),3);player.setPos(cell.getX()+.5,cell.getY(),cell.getZ()+2);
                var hit=new BlockHitResult(cell.below().getCenter().add(0,.5,0),Direction.UP,cell.below(),false);
                var action=item.useOn(new net.minecraft.world.item.context.UseOnContext(player,InteractionHand.MAIN_HAND,hit));
                helper.assertTrue(action.consumesAction(),"BlockItem refused: "+medium+" / "+cell+" / "+item.getClass().getName()+" / "+action+" / "+level.getBlockState(cell));
            }
            var first=(ConduitBlockEntity)level.getBlockEntity(at);var second=(ConduitBlockEntity)level.getBlockEntity(at.east());var parallel=(ConduitBlockEntity)level.getBlockEntity(at.north());
            helper.assertTrue(CableConnections.meets(first.cable(),Direction.EAST,second.cable()),"Hand-placed blocks do not join: "+medium);
            helper.assertTrue(!CableConnections.meets(first.cable(),Direction.NORTH,parallel.cable()),"A missing laid branch was silently added: "+medium);
            helper.assertTrue(first.cable().equals(world.controls().facts().cable(first.cable().site())),"Hand-placed cable is not registered");
            for(var cell:List.of(at,at.east(),at.north())){level.removeBlock(cell,false);level.removeBlock(cell.below(),false);}
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80,batch="conduit-rack-anchor")
    public static void aStaleNamedRackLaneCannotTurnIntoANewSlot(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(1,1,1));level.setBlock(at,ModBlocks.PIPE_RACK.get().defaultBlockState(),3);
        var stale=new LineTarget(null,at,Direction.NORTH,new Vec3(.5,.5,.5),RackGeometry.OPTIONS);
        for(var medium:CableMedium.values())helper.assertTrue(LayingTestCommands.resolve(level,stale,Map.of(),medium)==null,"Stale lane became a new slot: "+medium);
        level.removeBlock(at,false);helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=500,batch="power-direct")
    public static void powerLayingSelectsAndSuppliesALargeMotor(GameTestHelper helper){exercise(helper,CableMedium.POWER,false);}
    @GameTest(template="empty",timeoutTicks=500,batch="power-stub")
    public static void anExistingPowerStubCanBeAttachedWithoutReplacingIt(GameTestHelper helper){exercise(helper,CableMedium.POWER,true);}
    @GameTest(template="empty",timeoutTicks=500,batch="data-direct")
    public static void dataLayingBindsItsControllerAndMotor(GameTestHelper helper){exercise(helper,CableMedium.DATA,false);}

    @GameTest(template="empty",timeoutTicks=80,batch="line-clearance")
    public static void socketsNeverSkipUnrelatedWalls(GameTestHelper helper){
        var level=helper.getLevel();var owner=helper.absolutePos(new BlockPos(1,2,1));
        level.setBlock(owner,ModBlocks.LOCAL_CONTROLLER.get().defaultBlockState().setValue(ControlPanelBlock.FACING,Direction.WEST),3);
        var panel=(ControlPanelBlockEntity)level.getBlockEntity(owner);var clear=DataPorts.panel(panel);
        level.setBlock(clear.cell(),Blocks.STONE.defaultBlockState(),3);
        helper.assertTrue(DataPorts.panel(panel).cell().equals(clear.cell()),"Data socket jumped to the far side of a wall");
        var power=com.wormzjl.icheme.runtime.control.DataPorts.power(UUID.randomUUID(),DataPort.Kind.POWER_INPUT,level,owner,
                owner.getCenter().add(.52,0,0),Direction.EAST);
        helper.assertTrue(power.cell().equals(clear.cell()),"Power socket jumped to the far side of a wall");
        helper.assertTrue(PipeAnchor.socket(level,clear.cell(),clear.face(),owner,DataPorts.packed(clear)).anchor()==null,"Blocked socket was accepted");
        level.removeBlock(clear.cell(),false);level.removeBlock(owner,false);helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100,batch="line-clearance")
    public static void everyCableAdapterRechecksLeadsBeforeLayingOrAttaching(GameTestHelper helper){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var owner=helper.absolutePos(new BlockPos(1,2,1));var entry=owner.east(3);var last=owner.east(6);var wall=owner.east();
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"LeadClearance"));player.setGameMode(GameType.SURVIVAL);
        player.setPos(last.getX()-.5,last.getY(),last.getZ()-1);
        for(var medium:CableMedium.values()){
            for(int x=0;x<=6;x++)level.setBlock(owner.east(x),Blocks.AIR.defaultBlockState(),3);
            var tool=new ItemStack(ModItems.PIPE_LAYING_TOOL.get());tool.set(medium==CableMedium.POWER?ModItems.PIPE_LAYING_POWER.get():ModItems.PIPE_LAYING_DATA.get(),true);
            player.setItemInHand(InteractionHand.MAIN_HAND,tool);
            var item=(medium==CableMedium.POWER?ModItems.POWER_CABLE:ModItems.DATA_CABLE).get();player.getInventory().add(new ItemStack(item,16));
            level.setBlock(owner,(medium==CableMedium.POWER?ModBlocks.CREATIVE_POWER.get():ModBlocks.LOCAL_CONTROLLER.get()).defaultBlockState(),3);
            // A retained, previously built entry beyond the socket: new work must check the connecting space too.
            var port=new DataPort(UUID.randomUUID(),medium==CableMedium.POWER?DataPort.Kind.POWER_OUTPUT:DataPort.Kind.CONTROLLER,0,
                    level.dimension().location().toString(),owner,owner.getCenter().add(.52,0,0),entry,Direction.WEST,0,0);
            if(level.getBlockEntity(owner) instanceof ControlPanelBlockEntity panel)panel.bind(port.id(),port);
            else ((CreativePowerBlockEntity)level.getBlockEntity(owner)).instruments().powerPort(port);
            world.controls().facts().port(port);
            var start=end(port);var finish=new LineTarget(null,last,Direction.EAST,new Vec3(.5,.5,.5),0);
            var a=LayingTestCommands.resolve(level,start,world.controls().facts().ports(),medium);var b=LayingTestCommands.resolve(level,finish,world.controls().facts().ports(),medium);
            helper.assertTrue(a!=null&&b!=null,"Clear socket anchors failed: "+medium);
            var preview=LinePlacement.search(level,a.placement(),b.placement(),medium.outside(),LayingTestCommands.leads(a,b));
            helper.assertTrue(preview.found(),"Clear lead and route refused: "+medium);
            // Change the world after preview, outside every new route cell.
            level.setBlock(wall,Blocks.STONE.defaultBlockState(),3);
            helper.assertTrue(!LinePlacement.search(level,a.placement(),b.placement(),medium.outside(),LayingTestCommands.leads(a,b)).found(),"Preview ignored a blocked lead: "+medium);
            int inventory=player.getInventory().countItem(item),cables=world.controls().facts().cables().size();
            var refusal=LayingTestCommands.lay(player,InteractionHand.MAIN_HAND,start,finish,preview.cells().stream().map(LayingTestCommands::block).toList(),preview.options(),medium);
            helper.assertTrue(refusal!=null&&player.getInventory().countItem(item)==inventory&&world.controls().facts().cables().size()==cables,
                    "A refused lead consumed items or changed wiring: "+medium);
            helper.assertTrue(preview.cells().stream().allMatch(c->level.getBlockState(LayingTestCommands.block(c)).isAir()),"A refused lead placed part of its route");
            level.setBlock(wall,ModBlocks.EQUIPMENT_INTERIOR.get().defaultBlockState().setValue(EquipmentInteriorBlock.OPEN,true),3);
            helper.assertTrue(LinePlacement.checkLeads(level,LayingTestCommands.leads(a,b))!=null,"A lead crossed a reserved open equipment interior: "+medium);
            EquipmentTestCleanup.remove(level,wall);level.setBlock(wall,Blocks.STONE.defaultBlockState(),3);
            // The zero-new-block attachment adapter must pass the same check and preserve the existing stub.
            level.setBlock(entry,(medium==CableMedium.POWER?ModBlocks.POWER_CABLE:ModBlocks.DATA_CABLE).get().defaultBlockState(),3);
            var stub=(ConduitBlockEntity)level.getBlockEntity(entry);var old=new DataBus.Cable(new DataBus.Site(port.dimension(),entry,0),new ConduitGeometry.Key(48,0,medium),0,Map.of());stub.configure(old);
            helper.assertTrue(LayingTestCommands.attach(player,InteractionHand.MAIN_HAND,start,medium)!=null&&old.equals(stub.cable()),"Attachment crossed a wall: "+medium);
            level.removeBlock(wall,false);
            helper.assertTrue(LayingTestCommands.attach(player,InteractionHand.MAIN_HAND,start,medium)==null&&port.id().equals(stub.cable().endpoints().get(port.face())),"Clear existing end could not attach: "+medium);
            helper.assertTrue(level.getBlockEntity(entry)==stub&&player.getInventory().countItem(item)==inventory,"Attachment replaced a stub or consumed a new cable");
            level.setBlock(owner,Blocks.STONE.defaultBlockState(),3);var bound=stub.cable();
            helper.assertTrue(LayingTestCommands.attachment(level,start,world.controls().facts().ports(),medium)==null
                    &&LayingTestCommands.attach(player,InteractionHand.MAIN_HAND,start,medium)!=null&&bound.equals(stub.cable()),"A stale registered socket remained connectable after its host was replaced: "+medium);
            level.removeBlock(entry,false);level.removeBlock(owner,false);world.controls().facts().removePort(port.id());
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80,batch="line-clearance")
    public static void cableRackLanesParticipateInCollisionAndUnloadedLeadsAreRefused(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(1,2,1));
        level.setBlock(at,ModBlocks.PIPE_RACK.get().defaultBlockState(),3);var rack=(PipeRackBlockEntity)level.getBlockEntity(at);
        for(var medium:CableMedium.values()){
            int arms=RackGeometry.runs(rack.rack());var lane=new RackGeometry.Lane(0,RackGeometry.width(medium.outside()),arms,arms,PipeSizes.code(medium.outside()));
            var cable=new DataBus.Cable(new DataBus.Site(level.dimension().location().toString(),at,1),new ConduitGeometry.Key(arms,RackGeometry.points(rack.rack(),lane),medium),arms,Map.of());
            rack.cables(List.of(cable));
            for(var box:RackGeometry.boxes(rack.rack(),rack.cableLane(cable)))helper.assertTrue(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                    net.minecraft.world.phys.shapes.Shapes.box(box[0],box[1],box[2],box[3],box[4],box[5]),rack.shape(),net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST),"Cable lane omitted from rack collision: "+medium);
        }
        var unloaded=at.offset(4096,0,0);helper.assertTrue(!level.hasChunkAt(unloaded),"Unloaded fixture unexpectedly loaded");
        var lead=new LinePlacement.Lead(at,unloaded,at.getCenter(),.125,List.of(unloaded.getCenter(),unloaded.getCenter().add(.25,0,0)));
        helper.assertTrue(LinePlacement.checkLeads(level,List.of(lead))!=null&&!level.hasChunkAt(unloaded),"A lead loaded a chunk or accepted unavailable space");
        level.removeBlock(at,false);helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80,batch="line-clearance")
    public static void pipeDataAndPowerRefuseChangedRouteCellsWithoutPartialWrites(GameTestHelper helper){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var first=helper.absolutePos(new BlockPos(1,2,1));var last=first.east(4);var wall=first.east(2);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"LineRecheck"));player.setGameMode(GameType.SURVIVAL);player.setPos(last.getX(),last.getY(),last.getZ()-1);
        for(int kind=0;kind<3;kind++){
            for(int i=0;i<=4;i++)level.setBlock(first.east(i),Blocks.AIR.defaultBlockState(),3);
            var tool=new ItemStack(ModItems.PIPE_LAYING_TOOL.get());
            if(kind==0)tool.set(ModItems.PIPE_LAYING_SETTINGS.get(),new com.wormzjl.icheme.world.item.PipelineToolItem.Settings(.1));
            else tool.set(kind==1?ModItems.PIPE_LAYING_DATA.get():ModItems.PIPE_LAYING_POWER.get(),true);
            var item=(kind==0?ModItems.FLUID_PIPE:kind==1?ModItems.DATA_CABLE:ModItems.POWER_CABLE).get();
            player.setItemInHand(InteractionHand.MAIN_HAND,tool);player.getInventory().add(new ItemStack(item,16));
            double outside=kind==0?PipeSizes.outside(.1):.125;
            var start=new PipeAnchor.Click(first,Direction.EAST);var finish=new PipeAnchor.Click(last,Direction.EAST);
            var a=PipeAnchor.open(level,start,outside).anchor();var b=PipeAnchor.open(level,finish,outside).anchor();
            var preview=LinePlacement.search(level,a,b,outside,List.of());helper.assertTrue(preview.found(),"Clear route did not plan: "+kind);
            var path=preview.cells().stream().map(PipeAnchor::block).toList();
            level.setBlock(wall,Blocks.STONE.defaultBlockState(),3);
            int held=player.getInventory().countItem(item),fluids=world.registrations().size(),cables=world.controls().facts().cables().size();
            var refusal=kind==0?LayingTestCommands.lay(player,InteractionHand.MAIN_HAND,start,finish,path,preview.options()):
                    LayingTestCommands.lay(player,InteractionHand.MAIN_HAND,new LineTarget(null,first,Direction.EAST,start.local(),0),new LineTarget(null,last,Direction.EAST,finish.local(),0),path,preview.options(),kind==1?CableMedium.DATA:CableMedium.POWER);
            helper.assertTrue(refusal!=null&&held==player.getInventory().countItem(item)&&fluids==world.registrations().size()&&cables==world.controls().facts().cables().size(),"Changed obstacle caused partial inventory/topology writes: "+kind);
            helper.assertTrue(path.stream().filter(p->!p.equals(wall)).allMatch(p->level.getBlockState(p).isAir()),"Changed obstacle caused partial placement: "+kind);
        }
        level.removeBlock(wall,false);helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80,batch="service-port-families")
    public static void controllersAndInlineMoversUseTheirAvailableConnectionSpace(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(1,2,1));
        for(var block:List.of(ModBlocks.LOCAL_CONTROLLER,ModBlocks.DCS))for(var face:Direction.Plane.HORIZONTAL){
            level.setBlock(at.relative(face.getOpposite()),Blocks.AIR.defaultBlockState(),3);
            level.setBlock(at,block.get().defaultBlockState().setValue(ControlPanelBlock.FACING,face),3);
            var port=DataPorts.panel((ControlPanelBlockEntity)level.getBlockEntity(at));
            helper.assertTrue(port.u()==0&&port.v()==0,"A controller's unobstructed connector must use the block-face centre");
            var panel=(ControlPanelBlockEntity)level.getBlockEntity(at);FluidWorldAuthority.find(level.getServer()).orElseThrow().controls().loaded(panel);
            var anchor=LayingTestCommands.resolve(level,end(panel.dataPort()),Map.of(panel.dataPort().id(),panel.dataPort()),CableMedium.DATA);
            var leadRefusal=anchor==null?null:LinePlacement.checkLeads(level,LineLaying.leads(anchor,anchor));
            helper.assertTrue(anchor!=null&&leadRefusal==null,"The authored cabinet collision blocks its own data entry: "+block.get()+" / "+face+" / "+panel.dataPort()+" / "+leadRefusal+" / "+level.getBlockState(at).getCollisionShape(level,at));
            if(block==ModBlocks.LOCAL_CONTROLLER){
                var bounds=level.getBlockState(at).getCollisionShape(level,at).bounds();
                helper.assertTrue(bounds.getXsize()<1&&bounds.getZsize()<1,"The local cabinet still collides as a full block");
            }
            level.removeBlock(at,false);
        }
        for(var mover:List.of(FluidDeviceBlock.InlineMover.PUMP,FluidDeviceBlock.InlineMover.COMPRESSOR))for(var face:Direction.Plane.HORIZONTAL){
            level.setBlock(at,ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.INLINE_MOVER,mover).setValue(FluidDeviceBlock.FACING,face),3);
            var pipe=(FluidDeviceBlockEntity)level.getBlockEntity(at);
            var power=com.wormzjl.icheme.runtime.power.PowerPorts.create(pipe);
            var data=DataPorts.equipment(new com.wormzjl.icheme.runtime.instrument.InstrumentHosts.Host(1,pipe,List.of(),true),true);
            helper.assertTrue(power!=null&&data!=null&&power.kind().power()&&!data.kind().power(),"Inline mover is missing a distinct typed service connection: "+mover+" / "+face);
            helper.assertTrue(power.centre().distanceToSqr(data.centre())>.01,"Inline mover power and data glands overlap: "+mover+" / "+face);
            for(var port:List.of(power,data)){
                var normal=Vec3.atLowerCornerOf(port.face().getOpposite().getNormal());
                helper.assertTrue(port.cablePoint().subtract(port.centre()).dot(normal)>=-.075-1e-9,"An overhanging device points its cable entry backwards through the casing");
            }
            level.removeBlock(at,false);
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40,batch="line-model-packets")
    public static void linePacketsRefreshCachedGeometryBeforeTheNextChunkMesh(GameTestHelper helper){
        var level=helper.getLevel();var registries=level.registryAccess();var at=helper.absolutePos(new BlockPos(1,2,1));
        var probe=new CachedModel(at);probe.setLevel(level);
        probe.requestModelDataUpdate();
        var initial=new CompoundTag();initial.putInt("Revision",1);probe.handleUpdateTag(initial,registries);
        helper.assertTrue(probe.cached.get(CachedModel.REVISION)==1&&probe.hookRevision==1,"Chunk loading did not invalidate the old model after reading its geometry");
        int previous=probe.refreshes;
        var changed=new CompoundTag();changed.putInt("Revision",2);
        probe.onDataPacket(null,ClientboundBlockEntityDataPacket.create(probe,(entity,lookup)->changed),registries);
        helper.assertTrue(probe.refreshes==previous+1&&probe.cached.get(CachedModel.REVISION)==2&&probe.hookRevision==2,"A live packet left the chunk mesher using stale model data");
        for(var type:List.of(ConduitBlockEntity.class,FluidDeviceBlockEntity.class,PipeRackBlockEntity.class,EquipmentShellBlockEntity.class)){
            try{
                helper.assertTrue(type.getMethod("onDataPacket",net.minecraft.network.Connection.class,ClientboundBlockEntityDataPacket.class,HolderLookup.Provider.class).getDeclaringClass()==ModelledBlockEntity.class,"A line bypasses the tested shared packet lifecycle: "+type.getSimpleName());
            }catch(NoSuchMethodException missing){throw new AssertionError(missing);}
        }
        for(var medium:CableMedium.values()){
            var state=(medium==CableMedium.POWER?ModBlocks.POWER_CABLE:ModBlocks.DATA_CABLE).get().defaultBlockState();level.setBlock(at,state,3);
            var server=(ConduitBlockEntity)level.getBlockEntity(at);var client=new ConduitBlockEntity(at,state);client.setLevel(level);
            var site=new DataBus.Site(level.dimension().location().toString(),at,0);
            for(int mask:new int[]{48,17,6}){
                int face=Integer.numberOfTrailingZeros(mask);var id=UUID.randomUUID();var key=new ConduitGeometry.Key(mask,0,medium).connect(face,-9,12);
                server.configure(new DataBus.Cable(site,key,0,Map.of(Direction.values()[face],id)));
                client.onDataPacket(null,server.getUpdatePacket(),registries);
                helper.assertTrue(key.equals(client.getModelData().get(ConduitBlockEntity.GEOMETRY)),"The live cable model lost its elbows or off-centre endpoint");
                helper.assertTrue(client.cable().endpoints().containsValue(id),"The live cable model lost its socket binding");
            }
            level.removeBlock(at,false);
        }
        helper.succeed();
    }

    /** A chunk mesher's cached snapshot: reading a packet alone must not leave it at the previous revision. */
    private static final class CachedModel extends ModelledBlockEntity {
        static final ModelProperty<Integer> REVISION=new ModelProperty<>();
        int revision,hookRevision,refreshes;ModelData cached=ModelData.EMPTY;
        CachedModel(BlockPos at){super(ModBlockEntities.DATA_CABLE.get(),at,ModBlocks.DATA_CABLE.get().defaultBlockState());}
        @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){super.loadAdditional(tag,registries);revision=tag.getInt("Revision");}
        @Override public ModelData getModelData(){return ModelData.of(REVISION,revision);}
        @Override public void requestModelDataUpdate(){cached=getModelData();refreshes++;}
        @Override protected void clientGeometryChanged(){hookRevision=revision;}
    }

    private static LineTarget end(DataPort p){return new LineTarget(p.id(),p.owner(),p.face().getOpposite(),new Vec3(.5,.5,.5),0);}
    private static void exercise(GameTestHelper helper,CableMedium medium,boolean stub){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(new BlockPos(8,2,8));var shape=EquipmentShape.unit(EquipmentShape.Kind.MOTOR,1000,1,EquipmentShape.Heading.NORTH,0);
        for(var c:ShapeRasterizer.layout(shape).shell())EquipmentTestCleanup.remove(level,origin.offset(c.x(),c.y(),c.z()));
        for(var c:ShapeRasterizer.layout(shape).interior())EquipmentTestCleanup.remove(level,origin.offset(c.x(),c.y(),c.z()));
        EquipmentStructure.place(level,origin,shape,Paint.DEFAULT,shape.sizeY());
        var socket=medium==CableMedium.POWER?DataSocketGeometry.power(shape):DataSocketGeometry.equipment(shape);
        var normal=Direction.values()[socket.face()];var centre=new Vec3(origin.getX()+socket.x(),origin.getY()+socket.y(),origin.getZ()+socket.z());
        var station=BlockPos.containing(centre.add(Vec3.atLowerCornerOf(normal.getNormal()).scale(6)));
        if(medium==CableMedium.POWER)level.setBlock(station,ModBlocks.CREATIVE_POWER.get().defaultBlockState(),3);
        else level.setBlock(station,ModBlocks.LOCAL_CONTROLLER.get().defaultBlockState().setValue(ControlPanelBlock.FACING,normal),3);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"ConduitPlacement"));player.setGameMode(GameType.CREATIVE);
        var tool=new ItemStack(ModItems.PIPE_LAYING_TOOL.get());tool.set(medium==CableMedium.POWER?ModItems.PIPE_LAYING_POWER.get():ModItems.PIPE_LAYING_DATA.get(),true);player.setItemInHand(InteractionHand.MAIN_HAND,tool);
        var placed=new ArrayList<BlockPos>();boolean[] laid={false},broken={false};long[] breakTick={0};int[] retained={0};DataPort[] selected=new DataPort[2];
        helper.succeedWhen(()->{
            if(!laid[0]){
                var ports=world.controls().facts().ports();
                var motor=ports.values().stream().filter(p->p.kind().medium()==medium&&level.getBlockEntity(p.owner()) instanceof EquipmentShellBlockEntity shell&&origin.equals(shell.origin())).findFirst().orElse(null);
                var source=ports.values().stream().filter(p->p.kind().medium()==medium&&p.owner().equals(station)).findFirst().orElse(null);
                helper.assertTrue(motor!=null&&source!=null,"Waiting for structural socket registration");
                var eye=motor.centre().add(Vec3.atLowerCornerOf(normal.getNormal()).scale(3.5)).add(0,.25,.15);var look=motor.centre().subtract(eye).normalize();
                player.setPos(eye.x,eye.y-player.getEyeHeight(),eye.z);
                var hit=level.clip(new ClipContext(eye,eye.add(look.scale(5)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
                helper.assertTrue(Objects.equals(motor,LinePortTarget.aim(level,List.of(motor),eye,look,5,hit)),"The visible motor socket was not selected");
                helper.assertTrue(LayingTestCommands.resolve(level,new LineTarget(null,motor.owner(),normal,new Vec3(.5,.5,.5),0),ports,medium)==null,"A casing click became a loose cable end");
                var a=LayingTestCommands.resolve(level,end(source),ports,medium);var b=LayingTestCommands.resolve(level,end(motor),ports,medium);
                helper.assertTrue(a!=null&&b!=null,"Socket anchors must resolve");
                var route=LinePlacement.search(level,a.placement(),b.placement(),medium.outside(),LineLaying.leads(a,b));
                helper.assertTrue(route.found(),"No route between sockets");
                var refusal=LayingTestCommands.lay(player,InteractionHand.MAIN_HAND,end(source),end(motor),route.cells().stream().map(LayingTestCommands::block).toList(),route.options(),medium);
                helper.assertTrue(refusal==null,"Cable lay refused: "+refusal);placed.addAll(route.cells().stream().map(LayingTestCommands::block).toList());
                if(stub){
                    var wire=(ConduitBlockEntity)level.getBlockEntity(motor.cell());var built=wire.cable();var ends=new EnumMap<Direction,UUID>(Direction.class);ends.putAll(built.endpoints());ends.remove(motor.face());wire.configure(new DataBus.Cable(built.site(),built.geometry(),built.arms(),ends));
                    helper.assertTrue(!new DataBus(world.controls().facts().cables(),ports,medium).connected(source.id(),motor.id()),"The loose end was already bound");
                    helper.assertTrue(LayingTestCommands.lay(player,InteractionHand.MAIN_HAND,end(motor),end(motor),List.of(),List.of(),medium)==null,"Attaching the existing stub failed");
                    helper.assertTrue(level.getBlockEntity(motor.cell())==wire,"Attachment replaced the existing block entity");
                }
                for(var p:List.of(source,motor)){
                    var wire=(ConduitBlockEntity)level.getBlockEntity(p.cell());
                    helper.assertTrue(Objects.equals(wire.cable().endpoints().get(p.face()),p.id()),"The placed endpoint lost its socket ID");
                    helper.assertTrue(DataBus.loadEndpoints(wire.getUpdateTag(level.registryAccess()).getList("Endpoints",10)).containsValue(p.id()),"The client snapshot lost its socket ID");
                }
                helper.assertTrue(new DataBus(world.controls().facts().cables(),ports,medium).connected(source.id(),motor.id()),"The actual placed line is disconnected");
                selected[0]=source;selected[1]=motor;laid[0]=true;
            }
            if(medium==CableMedium.POWER){
                var motor=selected[1];var terminal=(EquipmentShellBlockEntity)level.getBlockEntity(motor.owner());var energy=terminal.energy();
                helper.assertTrue(energy!=null&&energy.getEnergyStored()>0,"Waiting for FE through the actual placed cable");
                var unit=world.shafts().unitAt(new PhysicalFluidTopology.Position(level.dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()));
                helper.assertTrue(unit!=null,"Waiting for the motor shaft unit");
                if(broken[0]){
                    helper.assertTrue(level.getGameTime()-breakTick[0]>=120,"Waiting past one power/presentation cadence");
                    helper.assertTrue(world.shafts().view(unit.id()).speed()==0,"The charged unloaded motor still turns after its FE cable was broken");
                    helper.assertTrue(world.shafts().view(unit.id()).electricPower()==0,"A disconnected drive still reports electric draw");
                    helper.assertTrue(!com.wormzjl.icheme.runtime.power.PowerNetwork.of(level.getServer()).supplied(unit),"The broken circuit still reports a live source");
                    helper.assertTrue(energy.getEnergyStored()==retained[0],"Unplugging the unloaded motor discarded buffered energy");
                    for(var p:placed)level.removeBlock(p,false);level.removeBlock(station,false);EquipmentTestCleanup.remove(level,new EquipmentStructure.Vessel(origin,shape).controller());
                    return;
                }
                helper.assertTrue(energy.getEnergyStored()==energy.getMaxEnergyStored()&&world.shafts().view(unit.id()).speed()>0,"Waiting for the connected unloaded motor to run with a full buffer");
                helper.assertTrue(com.wormzjl.icheme.runtime.power.PowerNetwork.of(level.getServer()).supplied(unit),"The full motor cannot see its connected source without receiving FE");
                retained[0]=energy.getEnergyStored();
            }
            var removed=placed.get(placed.size()/2);level.destroyBlock(removed,false);
            helper.assertTrue(!new DataBus(world.controls().facts().cables(),world.controls().facts().ports(),medium).connected(selected[0].id(),selected[1].id()),"Breaking the line did not disconnect it");
            if(medium==CableMedium.POWER){broken[0]=true;breakTick[0]=level.getGameTime();helper.assertTrue(false,"Waiting for power loss to stop the motor");}
            for(var p:placed)level.removeBlock(p,false);level.removeBlock(station,false);EquipmentTestCleanup.remove(level,new EquipmentStructure.Vessel(origin,shape).controller());
        });
    }
}
