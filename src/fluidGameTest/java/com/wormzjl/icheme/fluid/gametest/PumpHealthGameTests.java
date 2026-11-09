package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Pump health in the world (batch 2026-11-03-rotating-machinery W10b, build plan revision 2, section 3.4): a creative motor
 * drives a constructed pump from a water supply held at about twice its NPSH3 into a tank. It cavitates and loses health
 * at its commits (its status line names its health); a repair kit used on its casing is accepted on the engine's tick
 * drain, taken from the player, and makes the pump new. The test removes everything it placed,
 * before it passes or fails.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PumpHealthGameTests {
    private PumpHealthGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "pump-health")
    public static void aCavitatingPumpLosesHealthAndAKitRestoresIt(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 2, 8)), new BlockPos(-8, -2, -8), new BlockPos(12, 10, 12));
        var level = plant.level;
        var world = plant.world;
        String[] failure = {null};
        long[] ids = new long[3];
        BlockPos[] casing = {null};
        ServerPlayer[] player = {null};
        var sequence = helper.startSequence();
        try {
            plant.hold();
            var pump = plant.place(AssemblyGameTests.Plant.unit(EquipmentShape.Kind.PUMP), 0, 0, 0);
            var suction = AssemblyGameTests.Plant.front(pump, Role.SUCTION);
            var generator = suction.relative(AssemblyGameTests.Plant.out(pump, Role.SUCTION));
            var discharge = AssemblyGameTests.Plant.front(pump, Role.DISCHARGE);
            var tank = discharge.relative(AssemblyGameTests.Plant.out(pump, Role.DISCHARGE));
            plant.pipe(suction);
            plant.pipe(discharge);
            level.setBlock(generator, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(generator.immutable());
            level.setBlock(tank, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(tank.immutable());
            ids[0] = ((FluidDeviceBlockEntity) level.getBlockEntity(generator)).fluidIdentity();
            ids[1] = ((FluidDeviceBlockEntity) level.getBlockEntity(tank)).fluidIdentity();
            var cell = ShapeRasterizer.fluidCell(pump.shape());
            casing[0] = pump.origin().offset(cell.x(), cell.y(), cell.z());
            ids[2] = ((EquipmentShellBlockEntity) level.getBlockEntity(casing[0])).fluidIdentity();
            // The supply at about twice the pump's rated NPSH3 above the water's vapour pressure: it cavitates near the
            // erosion peak at its rated flow, harder past it.
            var law = (CentrifugalPump) ((FlowControl.Machine) world.registrations().get(ids[2]).device().control()).law();
            var water = FluidDeviceSpec.water(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
            double supply = 3169 + 2 * 997 * 9.81 * law.npsh(1).required(law.ratedFlow());
            TankCharge.run(world, ids[0], new FluidDeviceSpec(1, 298.15, supply, water));
            plant.drive(pump);
        } catch (RuntimeException e) {
            failure[0] = "build: " + e;
        }
        if (failure[0] == null) {
            // It delivers and loses health at its commits.
            sequence = sequence.thenWaitUntil(() -> {
                var view = world.view(ids[1]);
                Double health = world.health().health(ids[2]);
                helper.assertTrue(view.state() != null && view.state().mass() > .01 && health != null && health < 1,
                        "Waiting for the pump to deliver and wear: " + world.view(ids[2]).status() + ", health " + health);
            }).thenExecute(() -> {
                // W10d: the page's readings carry its health and suction; its status line says CAVITATION once.
                var view = world.view(ids[2]);
                var status = view.status();
                if (view.pump() == null || !(view.pump().health() < 1) || view.pump().npshAvailable() == null || view.pump().npshRequired() == null || view.pump().curve() == null)
                    failure[0] = "no pump readings on the casing's view: " + view.pump();
                else if (!status.contains("CAVITATION") || status.contains("HEALTH ")) failure[0] = "the pump's status line does not say CAVITATION once: " + status;
                // A survival player beside the casing uses one kit on it. Not joined to the player list (a join syncs every
                // mod's data to a mock connection): the kit reads the player it was used by.
                var p = player[0] = new ServerPlayer(level.getServer(), level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "repair-kit-test"),
                        net.minecraft.server.level.ClientInformation.createDefault());
                p.setPos(casing[0].getX() + .5, casing[0].getY() + 2, casing[0].getZ() + 2.5);
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.REPAIR_KIT.get(), 2));
                var stack = p.getItemInHand(InteractionHand.MAIN_HAND);
                var hit = new BlockHitResult(Vec3.atCenterOf(casing[0]), Direction.UP, casing[0], false);
                var result = stack.getItem().onItemUseFirst(stack, new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
                if (!result.consumesAction()) failure[0] = "the kit was not used on the casing: " + result;
            });
            // Accepted on the tick drain: one kit taken, the pump new again.
            sequence = sequence.thenWaitUntil(() -> {
                if (failure[0] != null) return;
                helper.assertTrue(player[0].getItemInHand(InteractionHand.MAIN_HAND).getCount() == 1, "Waiting for the kit to be taken: " + world.health().health(ids[2]));
            }).thenExecute(() -> {
                if (failure[0] != null) return;
                double health = world.health().health(ids[2]);
                if (health < .999) failure[0] = "the repair did not restore the pump: " + health;
            });
        }
        sequence.thenExecute(() -> {
                    plant.remove();
                }).thenWaitUntil(() -> helper.assertTrue(plant.removed() && world.shafts().units().stream()
                                .noneMatch(u -> plant.inside(new BlockPos(u.position().x(), u.position().y(), u.position().z()))), "Waiting for the plant and its shaft parts to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }

    /**
     * W10c (owner 2026-11-04, "Yes, add them"): an in-line pump fed at about twice its NPSH3 publishes its mover state, as a
     * casing does, running with its crackle byte above 0, for the watchers that ask for it.
     */
    @GameTest(template = "empty", timeoutTicks = 2400, batch = "inline-pump-sound")
    public static void anInlinePumpPublishesItsCrackle(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var source = helper.absolutePos(new BlockPos(0, 1, 0));
        var inlet = source.east();
        var pump = inlet.east();
        var outlet = pump.east();
        var sink = outlet.east();
        var placed = java.util.List.of(source, inlet, pump, outlet, sink);
        level.setBlock(source, ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), 3);
        level.setBlock(sink, ModBlocks.FLUID_VOID.get().defaultBlockState(), 3);
        level.setBlock(inlet, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(outlet, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
        level.setBlock(pump, FluidPumpStartupGameTests.inlinePump(Direction.EAST), 3);
        var water = FluidDeviceSpec.water(com.wormzjl.icheme.science.material.MaterialRuntime.active()).composition();
        long sourceId = ((FluidDeviceBlockEntity) level.getBlockEntity(source)).fluidIdentity();
        long sinkId = ((FluidDeviceBlockEntity) level.getBlockEntity(sink)).fluidIdentity();
        long pumpId = ((FluidDeviceBlockEntity) level.getBlockEntity(pump)).fluidIdentity();
        // This test owns a legacy set-flow fixture; supply its integral drive so sound tests a running pump.
        boolean[] powered={false};
        helper.onEachTick(()->{
            var energy=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,pump,Direction.UP);
            if(energy!=null)powered[0]|=energy.receiveEnergy(Integer.MAX_VALUE,false)>0;
        });
        // Its default set flow, 0.01 m3/s, needs 1.68 m of NPSH3: the supply stands at twice that over the water's vapour pressure.
        double npsh3 = com.wormzjl.icheme.science.machine.Npsh.forDuty(.01, 2950).required(.01);
        TankCharge.run(world, sourceId, new FluidDeviceSpec(1, 298.15, 3169 + 2 * 997 * 9.81 * npsh3, water));
        var r = world.registrations().get(sinkId);
        world.edit(sinkId, r.revision(), r.device(), new FluidDeviceSpec(1, 298.15, 101_325, water));
        String[] failure = {null};
        helper.startSequence().thenWaitUntil(() -> {
            var state = world.publishedMover(pumpId);
            helper.assertTrue(powered[0] && state != null && state.running() && !state.compressor() && state.crackle() > 0,
                    "Waiting for the in-line pump's crackle: " + state + " / " + world.view(pumpId).status());
        }).thenExecute(() -> {
            var state = world.publishedMover(pumpId);
            if (state.crackle() != world.health().crackle(pumpId)) failure[0] = "the published crackle is not the pump's: " + state;
            for (var at : placed) level.removeBlock(at, false);
        }).thenWaitUntil(() -> helper.assertTrue(java.util.stream.LongStream.of(sourceId, sinkId, pumpId).noneMatch(world.registrations()::containsKey),
                "Waiting for the line to leave")).thenExecute(() -> {
            if (failure[0] != null) helper.fail(failure[0]);
        }).thenSucceed();
    }
}
