package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.DataPorts;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.DcsGeometry;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.ControlPanelBlock;
import com.wormzjl.icheme.world.level.block.DcsBlock;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class DcsConsoleGameTests {
    private DcsConsoleGameTests() {}

    private static ServerPlayer player(GameTestHelper helper, BlockPos at) {
        var player =
                FakePlayerFactory.get(
                        helper.getLevel(), new GameProfile(UUID.randomUUID(), "DcsConsole"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(at.getX() + .5, at.getY(), at.getZ() - 2);
        return player;
    }

    private static BlockPlaceContext context(
            GameTestHelper helper, ServerPlayer player, BlockPos at) {
        helper.getLevel().setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
        return new BlockPlaceContext(
                player,
                InteractionHand.MAIN_HAND,
                player.getMainHandItem(),
                new BlockHitResult(Vec3.atBottomCenterOf(at), Direction.UP, at.below(), false));
    }

    @GameTest(template = "empty", timeoutTicks = 80, batch = "dcs-console")
    public static void twoHalvesShareOneIdentityAndOpenTheSameDcs(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(1, 2, 1));
        var player = player(helper, at);
        // NeoForge FakePlayer deliberately refuses every menu. Use a real server menu owner with
        // its inert connection.
        var menuPlayer =
                new ServerPlayer(
                        level.getServer(),
                        level,
                        new GameProfile(UUID.randomUUID(), "DcsMenu"),
                        net.minecraft.server.level.ClientInformation.createDefault());
        menuPlayer.connection = player.connection;
        menuPlayer.setGameMode(GameType.SURVIVAL);
        menuPlayer.setPos(player.position());
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
        var item = new ItemStack(ModItems.DCS.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        helper.assertTrue(
                ModItems.DCS.get().place(context(helper, player, at)).consumesAction(),
                "DCS placement failed");
        helper.assertTrue(item.getCount() == 1, "A console must consume one item");
        helper.assertTrue(
                level.getBlockState(at).getValue(DcsBlock.HALF) == DoubleBlockHalf.LOWER
                        && level.getBlockState(at.above()).getValue(DcsBlock.HALF)
                                == DoubleBlockHalf.UPPER,
                "DCS did not occupy two real cells");
        var box = (ControlPanelBlockEntity) level.getBlockEntity(at);
        var identity = box.identity();
        helper.assertTrue(
                level.getBlockEntity(at.above()) == null,
                "Upper half acquired a duplicate controller identity");
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        world.controls().loaded(box);
        var port = box.dataPort();
        helper.assertTrue(
                port != null && port.owner().equals(at) && port.centre().y == at.getY() + .5,
                "DCS moved the existing lower data connection");
        for (var cell : new BlockPos[] {at, at.above()}) {
            level.getBlockState(cell)
                    .useWithoutItem(
                            level,
                            menuPlayer,
                            new BlockHitResult(cell.getCenter(), Direction.NORTH, cell, false));
            helper.assertTrue(
                    menuPlayer.containerMenu instanceof ControlPanelMenu menu
                            && menu.dcs()
                            && menu.panel().equals(identity),
                    "Both console halves must open the same DCS");
            menuPlayer.closeContainer();
        }
        helper.assertTrue(
                world.controls().facts().panels().stream()
                                .filter(
                                        p ->
                                                p.position().equals(at)
                                                        || p.position().equals(at.above()))
                                .count()
                        == 1,
                "Console registered twice");
        level.removeBlock(at, false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80, batch = "dcs-console")
    public static void blockedPlacementAndBreakingEitherHalfRespectTheWholeConsole(
            GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(1, 2, 1));
        var player = player(helper, at);
        var item = new ItemStack(ModItems.DCS.get(), 8);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(at.above(), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(
                !ModItems.DCS.get().place(context(helper, player, at)).consumesAction()
                        && item.getCount() == 8
                        && level.getBlockState(at).isAir(),
                "Blocked top consumed an item or partially placed a DCS");
        for (boolean upper : new boolean[] {false, true}) {
            level.removeBlock(at.above(), false);
            helper.assertTrue(
                    ModItems.DCS.get().place(context(helper, player, at)).consumesAction(),
                    "Clear console placement failed");
            var box = (ControlPanelBlockEntity) level.getBlockEntity(at);
            var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
            world.controls().loaded(box);
            var id = box.identity();
            helper.assertTrue(
                    player.gameMode.destroyBlock(upper ? at.above() : at), "Survival break failed");
            helper.assertTrue(
                    level.getBlockState(at).isAir()
                            && level.getBlockState(at.above()).isAir()
                            && world.controls().facts().panel(id) == null,
                    "Breaking a console left a half or controller registration");
            var drops =
                    level.getEntitiesOfClass(
                            ItemEntity.class,
                            new AABB(at).expandTowards(0, 2, 0).inflate(2),
                            drop -> drop.getItem().is(ModItems.DCS.get()));
            helper.assertTrue(
                    drops.stream().mapToInt(drop -> drop.getItem().getCount()).sum() == 1,
                    "Breaking either half must drop exactly one DCS");
            drops.forEach(ItemEntity::discard);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80, batch = "dcs-console")
    public static void headingsAndBothCollisionShapesFollowTheDrawnConsole(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(1, 2, 1));
        int turn = 0;
        for (var facing :
                new Direction[] {
                    Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
                }) {
            level.setBlock(
                    at,
                    ModBlocks.DCS
                            .get()
                            .defaultBlockState()
                            .setValue(ControlPanelBlock.FACING, facing),
                    3);
            ModBlocks.DCS.get().complete(level, at);
            for (boolean upper : new boolean[] {false, true}) {
                var pos = upper ? at.above() : at;
                var state = level.getBlockState(pos);
                helper.assertTrue(
                        state.getValue(ControlPanelBlock.FACING) == facing,
                        "Console halves disagree on facing");
                helper.assertTrue(
                        state.getPistonPushReaction()
                                == net.minecraft.world.level.material.PushReaction.BLOCK,
                        "A piston could separate a console");
                for (var part : DcsGeometry.parts(upper)) {
                    var b = DcsGeometry.bounds(part, turn);
                    var centre =
                            new Vec3((b[0] + b[3]) / 32, (b[1] + b[4]) / 32, (b[2] + b[5]) / 32);
                    helper.assertTrue(
                            state.getShape(level, pos).toAabbs().stream()
                                            .anyMatch(box -> box.contains(centre))
                                    && state.getCollisionShape(level, pos).toAabbs().stream()
                                            .anyMatch(box -> box.contains(centre)),
                            "Rendered part is absent from picking/collision: "
                                    + part.name()
                                    + " / "
                                    + facing);
                }
            }
            level.removeBlock(at, false);
            turn++;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80, batch = "dcs-console")
    public static void legacyConsoleWaitsForRoomWithoutMovingItsIdentityOrSocket(
            GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(at.above(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(at, ModBlocks.DCS.get().defaultBlockState(), 3);
        var box = (ControlPanelBlockEntity) level.getBlockEntity(at);
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        world.controls().loaded(box);
        var id = box.identity();
        var port = box.dataPort();
        var facts = world.controls().facts().panel(id);
        ModBlocks.DCS.get().complete(level, at);
        helper.assertTrue(
                level.getBlockState(at.above()).is(Blocks.STONE) && level.getBlockEntity(at) == box,
                "Legacy rebuild overwrote an obstacle or replaced its controller");
        level.removeBlock(at.above(), false);
        helper.runAfterDelay(
                3,
                () -> {
                    helper.assertTrue(
                            level.getBlockState(at.above()).is(ModBlocks.DCS.get()),
                            "Console did not complete after its obstacle cleared");
                    helper.assertTrue(
                            id.equals(box.identity())
                                    && port.equals(box.dataPort())
                                    && facts.equals(world.controls().facts().panel(id))
                                    && DataPorts.panel(box).cell().equals(port.cell()),
                            "Console rebuild changed saved facts or the wired entry");
                    level.removeBlock(at, false);
                    helper.succeed();
                });
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = "dcs-console")
    public static void oldSingleBlockCorpusDefaultsToTheLowerHalfAndRetainsFacts(
            GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        try (var in =
                DcsConsoleGameTests.class.getResourceAsStream(
                        "/compat/control-blocks/dcs-single-block.snbt")) {
            if (in == null) throw new AssertionError("Missing old DCS corpus");
            var image = TagParser.parseTag(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var state =
                    NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                            image.getCompound("state"));
            helper.assertTrue(
                    state.is(ModBlocks.DCS.get())
                            && state.getValue(DcsBlock.HALF) == DoubleBlockHalf.LOWER,
                    "An old DCS did not default to its original lower block");
            var original = image.getCompound("entity");
            var panel = new ControlPanelBlockEntity(new BlockPos(3, 0, 0), state);
            panel.loadWithComponents(original, level.registryAccess());
            var saved = panel.saveWithFullMetadata(level.registryAccess());
            for (var key : original.getAllKeys())
                helper.assertTrue(
                        original.get(key).equals(saved.get(key)),
                        "Old console fact changed: " + key);
        }
        helper.succeed();
    }
}
