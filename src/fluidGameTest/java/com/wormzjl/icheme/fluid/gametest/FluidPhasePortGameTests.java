package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * The three devices of the phase-ports batch placed as blocks (plan section 6, phase-ports WP5): a tank's top, side and
 * bottom outlets each carrying their phase to a void; a liquid-only pump on a gas tank refused with its reason on its
 * status line (the compressor's target, ratio limit and shutoff are CompressorTest's). Every observation is the device's
 * view as the engine publishes it ({@link FluidWorldAuthority#view}).
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPhasePortGameTests {
    private FluidPhasePortGameTests() {}
    private static long identity(GameTestHelper helper,BlockPos pos){return ((FluidDeviceBlockEntity)helper.getLevel().getBlockEntity(pos)).fluidIdentity();}
    private static void place(GameTestHelper helper,BlockPos pos,BlockState state){helper.getLevel().setBlock(pos,state,3);}
    private static void clear(GameTestHelper helper,Collection<BlockPos> positions){for(var pos:positions)helper.getLevel().removeBlock(pos,false);}

    /**
     * Plan section 6, scenario 1. A tank charged with nitrogen at 102 kPa ({@link TankCharge}; a placed tank holds nothing,
     * decision D54) is filled to about a fifth with water from a 130 kPa generator on its west side; then the generator goes and a pipe and a void at 1 atm go on its top, its east side and its bottom. On the first
     * interval the new outlets carry: the top outlet (UP face, VAPOR port) draws gas and carries no liquid, the bottom
     * outlet (DOWN face, LIQUID port) draws water and carries no gas, the side outlet (BULK) draws all phases together,
     * and the tank page's lines (its view's outlets, as the menu snapshot carries them) say so, the bottom one with the
     * level head. The vent pressure stays below the humid-vent floor of decision D14.
     *
     * <p>Every reading is taken at a stated point of the engine's own clock, so that two runs read the same state
     * (PHASE_PORTS_REVIEW.md "GameTest reads at a fixed online tick (2026-10-13)"). The engine publishes the tank's state
     * at the end of each interval it commits, and this test sees it one or two server ticks later, as the worker's
     * completion is drained at the end of one tick or the next (wall time). So the generator goes at a stated online tick,
     * {@link #EDIT_AFTER_TICKS} after the end of the published interval that first shows the tank at 150 kg, not on the
     * tick that first sees it; and the outlets are read on the new island's first interval, which ends one tick after that
     * edit.
     */
    @GameTest(template="empty",timeoutTicks=30000,batch="fluid-phase-port-outlets")
    public static void aTanksTopSideAndBottomOutletsEachCarryTheirPhase(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var tank=helper.absolutePos(new BlockPos(2,3,0));var feedPipe=tank.west();var generator=feedPipe.west();
        var top=tank.above();var topVoid=top.above();var side=tank.east();var sideVoid=side.east();var bottom=tank.below();var bottomVoid=bottom.below();
        var all=List.of(tank,feedPipe,generator,top,topVoid,side,sideVoid,bottom,bottomVoid);
        place(helper,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());long tankId=identity(helper,tank);
        // A placed tank holds nothing (decision D54): its gas cushion is charged first, at 102 kPa, the nearest a bag can be
        // charged to the 1 atm nitrogen this test was written on.
        var charge=new TankCharge(helper,world,tank,Direction.SOUTH,102000);
        int[] stage={-1};long[] deadline={System.nanoTime()+180_000_000_000L};long[] ids=new long[3];long[] edit={-1};
        helper.onEachTick(()->{
            if(stage[0]==2)return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var view=world.view(tankId);
            helper.assertTrue(System.nanoTime()<deadline[0],"Stage "+stage[0]+" did not complete: "+view.status()+" "+view.outlets());
            if(stage[0]==-1) {
                if(!charge.charged())return;
                place(helper,generator,ModBlocks.FLUID_GENERATOR.get().defaultBlockState());long generatorId=identity(helper,generator);
                TankCharge.run(world,generatorId,new FluidDeviceSpec(1,298.15,130000,world.registrations().get(generatorId).spec().composition()));
                place(helper,feedPipe,ModBlocks.FLUID_PIPE.get().defaultBlockState());
                stage[0]=0;deadline[0]=System.nanoTime()+90_000_000_000L;return;
            }
            helper.assertTrue(!view.status().startsWith("HELD"),"Held: "+view.status());
            if(stage[0]==0) {
                if(view.state()==null||view.state().mass()<150)return;
                if(edit[0]<0) {
                    edit[0]=view.committedTick()+EDIT_AFTER_TICKS;
                    helper.assertTrue(view.onlineTick()<edit[0],"The interval ending at "+view.committedTick()+" was published at "+view.onlineTick()+", after the stated edit tick");
                }
                if(view.onlineTick()<edit[0])return;
                clear(helper,List.of(generator,feedPipe));
                for(var pos:List.of(top,side,bottom))place(helper,pos,ModBlocks.FLUID_PIPE.get().defaultBlockState());
                for(var pos:List.of(topVoid,sideVoid,bottomVoid))place(helper,pos,ModBlocks.FLUID_VOID.get().defaultBlockState());
                ids[0]=identity(helper,top);ids[1]=identity(helper,side);ids[2]=identity(helper,bottom);stage[0]=1;return;
            }
            // The new island starts cold (a first interval of one tick) at the edit's fence: its first published interval
            // ends at edit + 1.
            if(view.committedTick()<edit[0]+1)return;
            helper.assertTrue(view.committedTick()==edit[0]+1,"The new island's first interval was not read: committed "+view.committedTick()+", edit "+edit[0]);
            var outlets=view.outlets();
            helper.assertTrue(outlets.size()==3&&outlets.stream().allMatch(o->o.massFlow()>0),"The first interval carries three outlets: "+outlets);
            var byPort=new EnumMap<PassiveNetwork.PhasePort,FluidView.Outlet>(PassiveNetwork.PhasePort.class);for(var o:outlets)byPort.put(o.port(),o);
            helper.assertTrue(byPort.size()==3,"One top, one side and one bottom outlet: "+outlets);
            helper.assertTrue(byPort.get(PassiveNetwork.PhasePort.VAPOR).drawn()==2,"The top outlet draws gas: "+outlets);
            helper.assertTrue(byPort.get(PassiveNetwork.PhasePort.LIQUID).drawn()==1,"The bottom outlet draws water: "+outlets);
            helper.assertTrue(byPort.get(PassiveNetwork.PhasePort.LIQUID).head()>0,"The bottom outlet shows its level head: "+outlets);
            // What each pipe carried over the interval, as its own inspection page shows it.
            var topPhases=world.view(ids[0]).pipeInfo().contents().phaseMoles();var bottomPhases=world.view(ids[2]).pipeInfo().contents().phaseMoles();var sidePhases=world.view(ids[1]).pipeInfo().contents().phaseMoles();
            helper.assertTrue(sum(topPhases[0])+sum(topPhases[1])==0&&sum(topPhases[2])>0,"The top pipe carries gas only");
            helper.assertTrue(sum(bottomPhases[2])==0&&sum(bottomPhases[1])>0,"The bottom pipe carries water only");
            helper.assertTrue(sum(sidePhases[1])>0&&sum(sidePhases[2])>0,"The side pipe carries both phases together");
            // The tank page is built from the same view: the menu snapshot validates and carries the three lines.
            var page=FluidNetwork.snapshot(world,world.registrations().get(tankId),view);
            helper.assertTrue(page.view().outlets().equals(outlets),"The tank page carries its outlet lines");
            System.out.println("PHASE_PORT_GAMETEST outlets tick="+view.onlineTick()+" committed="+view.committedTick()+" edit="+edit[0]+" tankP="+view.state().pressure()+" tankMass="+view.state().mass()+" "+outlets);
            stage[0]=2;clear(helper,all);helper.succeed();
        });
    }
    private static double sum(double[] values){double s=0;for(double v:values)s+=v;return s;}
    /** Online ticks from the end of the published interval that first shows the tank at 150 kg to the edit: far more than
     * the one or two ticks the publication takes to reach the test, and less than the shortest cadence (20 ticks is the
     * floor; at the edit tick a slice may end there, never beyond it). */
    private static final long EDIT_AFTER_TICKS=10;

    /**
     * Plan section 6, scenario 2 (its first half). A liquid-only pump (decision D1) on the east side of a nitrogen tank,
     * facing east into a pipe and a void: its supply is 100 % vapour, so it is refused (INLET_WRONG_PHASE, decision D6),
     * carries nothing, and its status line gives the reason with the tank named as a device, in the words the menu shows.
     */
    @GameTest(template="empty",timeoutTicks=30000,batch="fluid-pump-refused")
    public static void aPumpOnAGasTankIsRefusedWithItsReasonOnItsStatusLine(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        // A pipe block between the tank and the in-line pump, as the line stood with the one-block pump (decision D20).
        var tank=helper.absolutePos(new BlockPos(0,1,0));var suction=tank.east();var pump=suction.east();var pipe=pump.east();var sink=pipe.east();var all=List.of(tank,suction,pump,pipe,sink);
        place(helper,tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState());
        // A placed tank holds nothing (decision D54): the gas tank is charged first, at 102 kPa (TankCharge).
        var charge=new TankCharge(helper,world,tank,Direction.SOUTH,102000);
        var p=world.registrations().get(identity(helper,tank)).device().position();
        String expected="ERROR: pump inlet not liquid (vapour 100.0 % by volume, from reservoir at "+p.x()+", "+p.y()+", "+p.z()+")";
        boolean[] done={false};long[] deadline={System.nanoTime()+180_000_000_000L};long[] pumpIds={-1};
        helper.onEachTick(()->{
            if(done[0])return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            if(pumpIds[0]<0) {
                helper.assertTrue(System.nanoTime()<deadline[0],"The tank was not charged");
                if(!charge.charged())return;
                place(helper,suction,ModBlocks.FLUID_PIPE.get().defaultBlockState());
                place(helper,pump,FluidPumpStartupGameTests.inlinePump(Direction.EAST));
                place(helper,pipe,ModBlocks.FLUID_PIPE.get().defaultBlockState());place(helper,sink,ModBlocks.FLUID_VOID.get().defaultBlockState());
                pumpIds[0]=identity(helper,pump);deadline[0]=System.nanoTime()+60_000_000_000L;return;
            }
            long pumpId=pumpIds[0];var view=world.view(pumpId);
            helper.assertTrue(System.nanoTime()<deadline[0],"The pump was not refused: "+view.status());
            if(!view.status().startsWith("ERROR: pump inlet"))return;
            helper.assertTrue(view.status().contains(expected),"The reason names the tank: "+view.status());
            helper.assertTrue(view.massFlow()==0,"A refused pump carries nothing: "+view.massFlow());
            helper.assertTrue(!view.status().contains("limit"),"No limit is shown for a refused pump: "+view.status());
            var page=FluidNetwork.snapshot(world,world.registrations().get(pumpId),view);
            helper.assertTrue(page.view().status().contains(expected),"The pump's page shows the reason");
            System.out.println("PHASE_PORT_GAMETEST pump tick="+view.onlineTick()+" committed="+view.committedTick()+" status="+view.status());
            done[0]=true;clear(helper,all);helper.succeed();
        });
    }
}
