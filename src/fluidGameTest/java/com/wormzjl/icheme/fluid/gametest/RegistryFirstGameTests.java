package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registry first (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md section 7): the registry decides which IChemE
 * devices exist, and the world is re-aligned to it. Each test edits the world or the registry directly and removes what it
 * placed, its result held until then.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class RegistryFirstGameTests {
    private RegistryFirstGameTests() {}

    /**
     * Step 3 (decision S11): a held tank keeps what it holds and joins no flow, apart from the line it stood on; the
     * operators' listing names it with what its cell holds; a drop removes it as a broken tank, what it held booked as
     * removed, and refuses a device that is not held.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "registry-first-held")
    public static void aHeldTankJoinsNoFlowIsListedAndDropped(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west = helper.absolutePos(new BlockPos(1, 1, 1));
        var pipe = west.east();
        var east = west.east(2);
        level.setBlock(west, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        level.setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(east, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        long westId = world.at(position(level, west)).orElseThrow().device().id();
        long eastId = world.at(position(level, east)).orElseThrow().device().id();
        String[] failure = {null};
        double[] before = {0};
        var said = new ArrayList<String>();
        var operator = operator(level, west, said);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(joined(world, westId, eastId), "Waiting for the line to join its tanks"))
                .thenExecute(() -> {
                    before[0] = moles(world, eastId);
                    if (!world.hold(eastId, true) || world.hold(eastId, true)) failure[0] = "the hold was not queued once";
                })
                .thenWaitUntil(() -> helper.assertTrue(alone(world, eastId), "Waiting for the held tank to stand apart"))
                .thenExecute(() -> {
                    if (failure[0] == null && joined(world, westId, eastId)) failure[0] = "the held tank still joins the line";
                    if (failure[0] == null && Math.abs(moles(world, eastId) - before[0]) > 1e-9 * Math.max(1, before[0]))
                        failure[0] = "the held tank lost what it held: " + before[0] + " -> " + moles(world, eastId);
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices held");
                    if (failure[0] == null && said.stream().noneMatch(line -> line.startsWith("#" + eastId + " reservoir at ") && line.contains("its cell holds icheme:")))
                        failure[0] = "the listing does not name the held tank: " + said;
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices drop " + westId);
                    if (failure[0] == null && (world.at(position(level, west)).isEmpty() || said.stream().noneMatch(line -> line.contains("is not held"))))
                        failure[0] = "a device that is not held was dropped: " + said;
                    said.clear();
                    level.getServer().getCommands().performPrefixedCommand(operator, "icheme devices drop " + eastId);
                    if (failure[0] == null && said.stream().noneMatch(line -> line.startsWith("Dropped #" + eastId)))
                        failure[0] = "the drop was refused: " + said;
                })
                .thenWaitUntil(() -> helper.assertTrue(!world.registrations().containsKey(eastId) && world.held().stream().noneMatch(r -> r.device().id() == eastId),
                        "Waiting for the dropped tank to leave"))
                .thenExecute(() -> {
                    if (failure[0] == null && !level.getBlockState(east).is(ModBlocks.FLUID_RESERVOIR.get())) failure[0] = "the drop touched the block in its cell";
                    for (var at : List.of(west, pipe, east)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> helper.assertTrue(world.at(position(level, west)).isEmpty() && world.at(position(level, pipe)).isEmpty(), "Waiting for the line's registrations to leave"))
                .thenExecute(() -> {
                    if (failure[0] != null) helper.fail(failure[0]);
                })
                .thenSucceed();
    }

    private static PhysicalFluidTopology.Position position(ServerLevel level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    private static boolean joined(FluidWorldAuthority world, long a, long b) {
        return world.diagnosticSnapshots().stream().anyMatch(s -> s.graph().reservoirs().stream().anyMatch(n -> n.id() == a) && s.graph().reservoirs().stream().anyMatch(n -> n.id() == b));
    }

    /** Whether {@code tank} is an island of its own: no pipe, no other vessel. */
    private static boolean alone(FluidWorldAuthority world, long tank) {
        return world.diagnosticSnapshots().stream().anyMatch(s -> s.graph().pipes().isEmpty() && !s.graph().reservoirs().isEmpty() && s.graph().reservoirs().stream().allMatch(n -> n.id() == tank));
    }

    private static double moles(FluidWorldAuthority world, long tank) {
        for (var s : world.diagnosticSnapshots())
            for (var n : s.graph().reservoirs())
                if (n.id() == tank && n.kind() == PassiveNetwork.NodeKind.RESERVOIR) return Arrays.stream(n.inventory().moles()).sum();
        return Double.NaN;
    }

    /** An operator at {@code at} whose command replies land in {@code said}. */
    private static CommandSourceStack operator(ServerLevel level, BlockPos at, List<String> said) {
        var sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) {said.add(message.getString());}
            @Override public boolean acceptsSuccess() {return true;}
            @Override public boolean acceptsFailure() {return true;}
            @Override public boolean shouldInformAdmins() {return false;}
        };
        return new CommandSourceStack(sink, Vec3.atCenterOf(at), Vec2.ZERO, level, 4, "registry-first", Component.literal("registry-first"), level.getServer(), null);
    }
}
