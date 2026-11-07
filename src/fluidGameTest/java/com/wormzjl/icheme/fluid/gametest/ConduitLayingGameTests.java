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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
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
        var placed=new ArrayList<BlockPos>();boolean[] laid={false};DataPort[] selected=new DataPort[2];
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
            }
            var removed=placed.get(placed.size()/2);level.destroyBlock(removed,false);
            helper.assertTrue(!new DataBus(world.controls().facts().cables(),world.controls().facts().ports(),medium).connected(selected[0].id(),selected[1].id()),"Breaking the line did not disconnect it");
            for(var p:placed)level.removeBlock(p,false);level.removeBlock(station,false);level.removeBlock(new EquipmentStructure.Vessel(origin,shape).controller(),false);
        });
    }
}
