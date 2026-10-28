package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.icheme.world.level.block.entity.ColumnInputPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("icheme_column_test")
@PrefixGameTestTemplate(false)
public final class ColumnRegroupingGameTests {
    private ColumnRegroupingGameTests() {}
    @GameTest(template="empty",timeoutTicks=10000000,batch="column-native-service")
    public static void allCrudePresetsCalculateThroughTheMinecraftAdmissionAndCompletionPath(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var pos=helper.absolutePos(new BlockPos(0,1,0));
        level.setBlock(pos,ModBlocks.COLUMN_CALCULATOR_V3.get().defaultBlockState(),3);
        var block=(ColumnCalculatorV3BlockEntity)level.getBlockEntity(pos);
        var presets=java.util.Arrays.asList(ColumnInputPreset.values());
        int[] next={0};boolean[] waiting={false},done={false};long deadline=System.nanoTime()+180_000_000_000L;
        helper.onEachTick(()->{
            if(done[0])return;
            helper.assertTrue(System.nanoTime()<deadline,"Column service qualification exceeded its wall deadline");
            // The GameTest server ticks unpaced; yield only this isolated harness while real workers run.
            if(waiting[0])java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var state=block.state(0);
            if(waiting[0]) {
                if(state.status()==ColumnCalculatorV3BlockEntity.V3Status.CALCULATING)return;
                helper.assertTrue(state.status()==ColumnCalculatorV3BlockEntity.V3Status.SUCCESS,
                        "Native service failed "+presets.get(next[0]-1).id()+": "+state.diagnostics());
                helper.assertTrue(state.displayResult().isPresent(),"Accepted result was not published to the block");
                waiting[0]=false;
            }
            if(next[0]==presets.size()) {
                done[0]=true;level.removeBlock(pos,false);helper.succeed();return;
            }
            var input=presets.get(next[0]++).input(MaterialRuntime.current());
            long id=com.wormzjl.icheme.runtime.ProcessSolveServices.nextRequestId();
            var operation=block.tryBegin(state.inputRevision(),id,input).orElseThrow();
            var target=new com.wormzjl.icheme.runtime.ProcessSolveServices.ColumnTarget(level.dimension(),pos);
            var request=new com.wormzjl.icheme.runtime.ProcessSolveServices.V3ColumnRequest(id,target,operation,System.nanoTime());
            var admission=com.wormzjl.icheme.runtime.ProcessSolveServices.submitV3Column(server,request);
            helper.assertTrue(admission.accepted(),"Column admission failed: "+admission.admission());
            waiting[0]=true;
        });
    }
    /** No compatibility (AGENTS.md): saved column data this build cannot read is refused, never kept or interpreted,
     * and the block starts from a fresh input that its next save writes in the current format. */
    @GameTest(template="empty",timeoutTicks=100,batch="column-regrouping")
    public static void unreadableColumnDataIsRefusedAndReplacedByAFreshInput(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(0,1,0));
        level.setBlock(pos,ModBlocks.COLUMN_CALCULATOR_V3.get().defaultBlockState(),3);
        var block=(ColumnCalculatorV3BlockEntity)level.getBlockEntity(pos);
        var original=block.saveWithFullMetadata(level.registryAccess());
        var fresh=block.state(0).input();
        var old=new CompoundTag();old.putInt("V3DataVersion",8);
        var oldInput=new CompoundTag();oldInput.putDouble("retired_component_amount",42);old.put("Input",oldInput);
        for(String failure:java.util.List.of("version","axis","package","input","revision")) {
            var bad=failure.equals("version")?old.copy():original.copy();var input=bad.getCompound("Input");
            if(failure.equals("axis"))input.getList("Axis",net.minecraft.nbt.Tag.TAG_STRING).set(0,net.minecraft.nbt.StringTag.valueOf("Retired"));
            else if(failure.equals("package"))input.putString("Package","retired:package");
            else if(failure.equals("input"))bad.remove("Input");
            else if(failure.equals("revision"))bad.remove("ResultRevision");
            block.loadWithComponents(bad,level.registryAccess());
            var state=block.state(0);
            helper.assertTrue(state.status()==ColumnCalculatorV3BlockEntity.V3Status.DIRTY&&state.diagnostics().getFirst().startsWith("Saved column data refused"),"Unreadable "+failure+" was not refused: "+state.diagnostics());
            helper.assertTrue(state.input().equals(fresh)&&state.inputRevision()==0&&state.displayResult().isEmpty(),"Unreadable "+failure+" was interpreted");
            var saved=block.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(saved.getInt("V3DataVersion")==ColumnCalculatorV3BlockEntity.DATA_VERSION&&!saved.getCompound("Input").equals(oldInput),"Unreadable "+failure+" was saved back");
            helper.assertTrue(block.tryBegin(0,1,fresh).isPresent(),"The fresh input after refused "+failure+" cannot be calculated");
            block.loadWithComponents(original,level.registryAccess());
        }
        level.removeBlock(pos,false);helper.succeed();
    }

}
