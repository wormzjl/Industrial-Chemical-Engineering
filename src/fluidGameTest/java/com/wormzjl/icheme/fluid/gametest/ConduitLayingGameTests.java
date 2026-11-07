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
@GameTestHolder("icheme_cable_test")
@PrefixGameTestTemplate(false)
public final class ConduitLayingGameTests {
    private ConduitLayingGameTests(){}
    @GameTest(template="empty",timeoutTicks=500,batch="power-direct")
    public static void powerLayingSelectsAndSuppliesALargeMotor(GameTestHelper helper){exercise(helper,CableMedium.POWER,false);}
    @GameTest(template="empty",timeoutTicks=500,batch="power-stub")
    public static void anExistingPowerStubCanBeAttachedWithoutReplacingIt(GameTestHelper helper){exercise(helper,CableMedium.POWER,true);}
    @GameTest(template="empty",timeoutTicks=500,batch="data-direct")
    public static void dataLayingBindsItsControllerAndMotor(GameTestHelper helper){exercise(helper,CableMedium.DATA,false);}
    @GameTest(template="empty",timeoutTicks=200,batch="pipe-regression-straight")
    public static void sharedAnchorsStillLayAndDismantleFluidPipe(GameTestHelper helper){PipeLayingGameTests.aLaidLineJoinsItsTanksOnlyAndComesOutOneBlockAtATime(helper);}
    @GameTest(template="empty",timeoutTicks=200,batch="pipe-regression-rack")
    public static void sharedAnchorsStillUseFluidRackCorners(GameTestHelper helper){PipeLayingGameTests.aLineThroughARackCornerJoinsItsTanksAndALaneComesOutAlone(helper);}

    @GameTest(template="empty",timeoutTicks=80,batch="service-port-families")
    public static void controllersAndInlineMoversUseTheirAvailableConnectionSpace(GameTestHelper helper){
        var level=helper.getLevel();var at=helper.absolutePos(new BlockPos(1,2,1));
        for(var block:List.of(ModBlocks.LOCAL_CONTROLLER,ModBlocks.DCS))for(var face:Direction.Plane.HORIZONTAL){
            level.setBlock(at,block.get().defaultBlockState().setValue(ControlPanelBlock.FACING,face),3);
            var port=DataPorts.panel((ControlPanelBlockEntity)level.getBlockEntity(at));
            helper.assertTrue(port.u()==0&&port.v()==0,"A controller's unobstructed connector must use the block-face centre");
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

    private static CableLaying.End end(DataPort p){return new CableLaying.End(p.id(),p.owner(),p.face().getOpposite(),new Vec3(.5,.5,.5),0);}
    private static void exercise(GameTestHelper helper,CableMedium medium,boolean stub){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(new BlockPos(8,2,8));var shape=EquipmentShape.unit(EquipmentShape.Kind.MOTOR,1000,1,EquipmentShape.Heading.NORTH,0);
        for(var c:ShapeRasterizer.layout(shape).shell())level.setBlock(origin.offset(c.x(),c.y(),c.z()),Blocks.AIR.defaultBlockState(),3);
        for(var c:ShapeRasterizer.layout(shape).interior())level.setBlock(origin.offset(c.x(),c.y(),c.z()),Blocks.AIR.defaultBlockState(),3);
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
                helper.assertTrue(CableLaying.resolve(level,new CableLaying.End(null,motor.owner(),normal,new Vec3(.5,.5,.5),0),ports,medium)==null,"A casing click became a loose cable end");
                var a=CableLaying.resolve(level,end(source),ports,medium);var b=CableLaying.resolve(level,end(motor),ports,medium);
                helper.assertTrue(a!=null&&b!=null,"Socket anchors must resolve");
                var route=PipeRoute.search(a.end(),a.heading(),b.end(),medium.outside(),PipeAnchor.space(level),PipeRoute.Limits.DEFAULT);
                helper.assertTrue(route.found(),"No route between sockets");
                var refusal=CableLaying.lay(player,InteractionHand.MAIN_HAND,end(source),end(motor),route.cells().stream().map(CableLaying::block).toList(),route.options(),medium);
                helper.assertTrue(refusal==null,"Cable lay refused: "+refusal);placed.addAll(route.cells().stream().map(CableLaying::block).toList());
                if(stub){
                    var wire=(ConduitBlockEntity)level.getBlockEntity(motor.cell());var built=wire.cable();var ends=new EnumMap<Direction,UUID>(Direction.class);ends.putAll(built.endpoints());ends.remove(motor.face());wire.configure(new DataBus.Cable(built.site(),built.geometry(),built.arms(),ends));
                    helper.assertTrue(!new DataBus(world.controls().facts().cables(),ports,medium).connected(source.id(),motor.id()),"The loose end was already bound");
                    helper.assertTrue(CableLaying.lay(player,InteractionHand.MAIN_HAND,end(motor),end(motor),List.of(),List.of(),medium)==null,"Attaching the existing stub failed");
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
                    for(var p:placed)level.removeBlock(p,false);level.removeBlock(station,false);level.removeBlock(new EquipmentStructure.Vessel(origin,shape).controller(),false);
                    return;
                }
                helper.assertTrue(energy.getEnergyStored()==energy.getMaxEnergyStored()&&world.shafts().view(unit.id()).speed()>0,"Waiting for the connected unloaded motor to run with a full buffer");
                helper.assertTrue(com.wormzjl.icheme.runtime.power.PowerNetwork.of(level.getServer()).supplied(unit),"The full motor cannot see its connected source without receiving FE");
                retained[0]=energy.getEnergyStored();
            }
            var removed=placed.get(placed.size()/2);level.destroyBlock(removed,false);
            helper.assertTrue(!new DataBus(world.controls().facts().cables(),world.controls().facts().ports(),medium).connected(selected[0].id(),selected[1].id()),"Breaking the line did not disconnect it");
            if(medium==CableMedium.POWER){broken[0]=true;breakTick[0]=level.getGameTime();helper.assertTrue(false,"Waiting for power loss to stop the motor");}
            for(var p:placed)level.removeBlock(p,false);level.removeBlock(station,false);level.removeBlock(new EquipmentStructure.Vessel(origin,shape).controller(),false);
        });
    }
}
