package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class EquipmentProtectionGameTests {
    private EquipmentProtectionGameTests() {}

    private record Fixture(
            ServerLevel level,
            EquipmentConstructorBlockEntity constructor,
            EquipmentStructure.Vessel vessel) {
        BlockPos cell(ShapeRasterizer.Cell cell) {
            return vessel.origin().offset(cell.x(), cell.y(), cell.z());
        }

        BlockPos shell() {
            return cell(ShapeRasterizer.layout(vessel.shape()).controller());
        }

        BlockPos interior() {
            return cell(ShapeRasterizer.layout(vessel.shape()).interior().iterator().next());
        }

        void assertPresent(GameTestHelper helper) {
            for (var cells :
                    List.of(
                            ShapeRasterizer.layout(vessel.shape()).shell(),
                            ShapeRasterizer.layout(vessel.shape()).interior()))
                for (var cell : cells)
                    helper.assertTrue(
                            EquipmentProtection.cell(level.getBlockState(cell(cell))),
                            "Equipment cell changed at " + cell);
        }

        void assertAbsent(GameTestHelper helper) {
            for (var cells :
                    List.of(
                            ShapeRasterizer.layout(vessel.shape()).shell(),
                            ShapeRasterizer.layout(vessel.shape()).interior()))
                for (var cell : cells)
                    helper.assertTrue(
                            level.getBlockState(cell(cell)).isAir(),
                            "Equipment cell remains at " + cell);
        }

        void clear() {
            EquipmentTestCleanup.remove(level, shell());
            level.removeBlock(constructor.getBlockPos(), false);
        }
    }

    private static Fixture place(GameTestHelper helper, Direction facing) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(8, 1, 8));
        level.setBlock(
                at,
                ModBlocks.EQUIPMENT_CONSTRUCTOR
                        .get()
                        .defaultBlockState()
                        .setValue(EquipmentConstructorBlock.FACING, facing),
                3);
        var constructor = (EquipmentConstructorBlockEntity) level.getBlockEntity(at);
        var vessel = constructor.vessel(constructor.design());
        for (int x = vessel.origin().getX() >> 4;
                x <= (vessel.origin().getX() + vessel.shape().sizeX()) >> 4;
                x++)
            for (int z = vessel.origin().getZ() >> 4;
                    z <= (vessel.origin().getZ() + vessel.shape().sizeZ()) >> 4;
                    z++) level.getChunk(x, z);
        EquipmentStructure.place(
                level, vessel.origin(), vessel.shape(), Paint.DEFAULT, Integer.MAX_VALUE);
        return new Fixture(level, constructor, vessel);
    }

    private static ServerPlayer player(Fixture fixture, GameType mode) {
        var player =
                FakePlayerFactory.get(
                        fixture.level(),
                        new GameProfile(new UUID(0, 7802 + mode.getId()), "EquipmentOperator"));
        player.setGameMode(mode);
        player.setPos(fixture.constructor().getBlockPos().getCenter());
        return player;
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-protection-world")
    public static void bothCellKindsRejectUnapprovedWorldAndChunkEdits(GameTestHelper helper) {
        var fixture = place(helper, Direction.SOUTH);
        try {
            for (var at : List.of(fixture.shell(), fixture.interior())) {
                var before = fixture.level().getBlockState(at);
                var entity = fixture.level().getBlockEntity(at);
                helper.assertTrue(
                        before.getDestroySpeed(fixture.level(), at) < 0,
                        "Survival mining is possible");
                helper.assertTrue(
                        !fixture.level().destroyBlock(at, false), "Direct destruction succeeded");
                helper.assertTrue(
                        !fixture.level().removeBlock(at, false), "Direct removal succeeded");
                helper.assertTrue(
                        !fixture.level().setBlock(at, Blocks.STONE.defaultBlockState(), 3),
                        "Replacement succeeded");
                helper.assertTrue(
                        fixture.level()
                                        .getChunkAt(at)
                                        .setBlockState(at, Blocks.AIR.defaultBlockState(), false)
                                == null,
                        "Direct chunk write succeeded");
                helper.assertTrue(
                        fixture.level().getBlockState(at) == before
                                && fixture.level().getBlockEntity(at) == entity,
                        "A rejected write changed the binding");
            }
            fixture.assertPresent(helper);
            helper.succeed();
        } finally {
            fixture.clear();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-protection-player")
    public static void survivalCannotBreakButCreativeRemovesTheWholeEquipment(
            GameTestHelper helper) {
        var fixture = place(helper, Direction.EAST);
        try {
            var at = fixture.interior();
            var state = fixture.level().getBlockState(at);
            helper.assertTrue(
                    !state.onDestroyedByPlayer(
                            fixture.level(),
                            at,
                            player(fixture, GameType.SURVIVAL),
                            true,
                            fixture.level().getFluidState(at)),
                    "Survival removed a cell");
            fixture.assertPresent(helper);
            helper.assertTrue(
                    player(fixture, GameType.CREATIVE).gameMode.destroyBlock(at),
                    "Creative breaking was refused");
            fixture.assertAbsent(helper);
            helper.succeed();
        } finally {
            fixture.clear();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-protection-blast")
    public static void blastsMobsAndPistonsCannotDismantleEitherCellKind(GameTestHelper helper) {
        var fixture = place(helper, Direction.SOUTH);
        try {
            for (var at : List.of(fixture.shell(), fixture.interior())) {
                var state = fixture.level().getBlockState(at);
                helper.assertTrue(
                        state.getPistonPushReaction()
                                == net.minecraft.world.level.material.PushReaction.BLOCK,
                        "Piston can move the equipment");
                helper.assertTrue(
                        !state.canEntityDestroy(
                                fixture.level(), at, player(fixture, GameType.SURVIVAL)),
                        "Entity can destroy equipment");
                state.onBlockExploded(fixture.level(), at, null);
            }
            var blast = fixture.shell().getCenter();
            fixture.level()
                    .explode(null, blast.x, blast.y, blast.z, 8, Level.ExplosionInteraction.TNT);
            fixture.assertPresent(helper);
            helper.succeed();
        } finally {
            fixture.clear();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-deconstruction")
    public static void aSurvivalConstructorRemovesItsPhysicalTargetAndKeepsConnectedPipes(
            GameTestHelper helper) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            var fixture = place(helper, facing);
            BlockPos pipe = null;
            try {
                // Changing the draft's kind/size must not change which existing physical structure
                // is removed.
                fixture.constructor()
                        .apply(
                                fixture.constructor()
                                        .design()
                                        .landed(
                                                new SentDesign(
                                                        "P-101",
                                                        "",
                                                        1,
                                                        EquipmentShape.unit(
                                                                EquipmentShape.Kind.PUMP,
                                                                .1,
                                                                1,
                                                                EquipmentShape.Heading.NORTH,
                                                                0),
                                                        null)));
                var selected =
                        EquipmentDeconstruction.target(
                                fixture.level(),
                                fixture.constructor().getBlockPos(),
                                facing,
                                fixture.constructor().design().placement());
                helper.assertTrue(
                        fixture.vessel().equals(selected), "Wrong physical target for " + facing);
                var port = ShapeRasterizer.ports(fixture.vessel().shape()).getFirst();
                pipe =
                        fixture.vessel()
                                .origin()
                                .offset(
                                        port.cell().x() + port.dx(),
                                        port.cell().y() + port.dy(),
                                        port.cell().z() + port.dz());
                fixture.level().setBlock(pipe, ModBlocks.FLUID_PIPE.get().defaultBlockState(), 3);
                int count =
                        EquipmentDeconstruction.remove(
                                player(fixture, GameType.SURVIVAL),
                                fixture.constructor(),
                                selected.origin(),
                                EquipmentDeconstruction.stamp(selected));
                helper.assertTrue(count > 0, "No cells deconstructed");
                fixture.assertAbsent(helper);
                helper.assertTrue(
                        fixture.level().getBlockState(pipe).is(ModBlocks.FLUID_PIPE.get()),
                        "Deconstruction removed the pipe");
            } finally {
                fixture.clear();
                if (pipe != null) fixture.level().removeBlock(pipe, false);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-deconstruction-checks")
    public static void staleTargetsAndDeniedCellsLeaveTheEntireEquipmentIntact(
            GameTestHelper helper) {
        var fixture = place(helper, Direction.SOUTH);
        try {
            var player = player(fixture, GameType.SURVIVAL);
            refused(
                    () ->
                            EquipmentDeconstruction.remove(
                                    player,
                                    fixture.constructor(),
                                    fixture.vessel().origin().east(),
                                    EquipmentDeconstruction.stamp(fixture.vessel())),
                    "changed");
            refused(
                    () ->
                            EquipmentDeconstruction.remove(
                                    player,
                                    fixture.constructor(),
                                    fixture.vessel().origin(),
                                    EquipmentDeconstruction.stamp(fixture.vessel()) + 1),
                    "changed");
            fixture.assertPresent(helper);
            Consumer<BlockEvent.BreakEvent> deny =
                    event -> {
                        if (event.getPos().equals(fixture.interior())) event.setCanceled(true);
                    };
            NeoForge.EVENT_BUS.addListener(deny);
            try {
                refused(
                        () ->
                                EquipmentDeconstruction.remove(
                                        player,
                                        fixture.constructor(),
                                        fixture.vessel().origin(),
                                        EquipmentDeconstruction.stamp(fixture.vessel())),
                        "protected");
            } finally {
                NeoForge.EVENT_BUS.unregister(deny);
            }
            var far =
                    new EquipmentStructure.Vessel(
                            fixture.vessel().origin().south(fixture.vessel().shape().sizeZ() + 3),
                            fixture.vessel().shape());
            var high = far.origin().offset(far.shape().sizeX(), 0, far.shape().sizeZ());
            for (int x = far.origin().getX() >> 4; x <= high.getX() >> 4; x++)
                for (int z = far.origin().getZ() >> 4; z <= high.getZ() >> 4; z++)
                    fixture.level().getChunk(x, z);
            EquipmentStructure.place(
                    fixture.level(), far.origin(), far.shape(), Paint.DEFAULT, Integer.MAX_VALUE);
            try {
                refused(
                        () ->
                                EquipmentDeconstruction.remove(
                                        player,
                                        fixture.constructor(),
                                        far.origin(),
                                        EquipmentDeconstruction.stamp(far)),
                        "changed");
                EquipmentDeconstruction.remove(
                        player,
                        fixture.constructor(),
                        fixture.vessel().origin(),
                        EquipmentDeconstruction.stamp(fixture.vessel()));
                refused(
                        () ->
                                EquipmentDeconstruction.remove(
                                        player,
                                        fixture.constructor(),
                                        fixture.vessel().origin(),
                                        EquipmentDeconstruction.stamp(fixture.vessel())),
                        "changed");
                helper.assertTrue(
                        EquipmentProtection.cell(fixture.level().getBlockState(far.controller())),
                        "A repeated request removed the next unit");
            } finally {
                EquipmentTestCleanup.remove(fixture.level(), far.controller());
            }
            fixture.assertAbsent(helper);
            helper.succeed();
        } finally {
            fixture.clear();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-protection-scope")
    public static void internalEditAuthorityIsCellScopedThreadConfinedAndExceptionSafe(
            GameTestHelper helper) {
        var fixture = place(helper, Direction.SOUTH);
        try {
            EquipmentPermissionChecks.verify(fixture.level(), fixture.shell(), fixture.interior());
            helper.assertTrue(
                    !fixture.level().removeBlock(fixture.shell(), false),
                    "A scoped edit leaked authority");
            fixture.assertPresent(helper);
            helper.succeed();
        } finally {
            fixture.clear();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "equipment-deconstruction-wire")
    public static void repliesKeepTheirRequestIdentityOutcomeAndTranslatedMessage(
            GameTestHelper helper) {
        for (boolean done : List.of(false, true)) {
            var sent =
                    new com.wormzjl.icheme.network.EquipmentNetwork.DeconstructAnswer(
                            helper.absolutePos(BlockPos.ZERO),
                            71,
                            done,
                            net.minecraft.network.chat.Component.translatable(
                                    "message.icheme.equipment.deconstruct.done",
                                    "12, 64, -8",
                                    382));
            var buffer =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                com.wormzjl.icheme.network.EquipmentNetwork.DeconstructAnswer.STREAM_CODEC.encode(
                        buffer, sent);
                var read =
                        com.wormzjl.icheme.network.EquipmentNetwork.DeconstructAnswer.STREAM_CODEC
                                .decode(buffer);
                helper.assertTrue(
                        sent.equals(read) && buffer.readableBytes() == 0,
                        "Deconstruction reply changed on the wire");
            } finally {
                buffer.release();
            }
        }
        helper.succeed();
    }

    private static void refused(Runnable action, String reason) {
        try {
            action.run();
            throw new AssertionError("Removal was not refused: " + reason);
        } catch (IllegalStateException refused) {
            if (!reason.equals(refused.getMessage())) throw refused;
        }
    }
}
