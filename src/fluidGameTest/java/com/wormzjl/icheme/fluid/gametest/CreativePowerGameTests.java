package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.registry.*;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.power.PowerNetwork;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.CreativePowerBlockEntity;

import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class CreativePowerGameTests {
    private CreativePowerGameTests() {}

    private static LineTarget end(DataPort port) {
        return new LineTarget(
                port.id(), port.owner(), port.face().getOpposite(), new Vec3(.5, .5, .5), 0);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void creativeSupplyConnectsAndPowersEveryFaceTogether(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(4, 4, 4));
        level.setBlock(at, ModBlocks.CREATIVE_POWER.get().defaultBlockState(), 3);
        var source = (CreativePowerBlockEntity) level.getBlockEntity(at);
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RfOmni"));
        player.setGameMode(GameType.CREATIVE);
        var tool = new ItemStack(ModItems.PIPE_LAYING_TOOL.get());
        tool.set(ModItems.PIPE_LAYING_POWER.get(), true);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        helper.startSequence()
                .thenWaitUntil(
                        () -> {
                            helper.assertTrue(
                                    source.powerPorts().size() == 6
                                            && source.powerPorts().stream()
                                                    .allMatch(
                                                            p ->
                                                                    world.controls()
                                                                                    .facts()
                                                                                    .port(p.id())
                                                                            != null),
                                    "Waiting for all six registered sockets");
                        })
                .thenExecute(
                        () -> {
                            var facts = world.controls().facts();
                            var primary = source.instruments().powerPort();
                            var paths = new EnumMap<Direction, List<BlockPos>>(Direction.class);
                            var visible =
                                    InstrumentHosts.powerPorts(level, at.getCenter(), 2).stream()
                                            .filter(p -> p.owner().equals(at))
                                            .toList();
                            helper.assertTrue(
                                    visible.size() == 6,
                                    "The laying tool does not see six power sockets");
                            for (var face : Direction.values()) {
                                var port =
                                        source.powerPorts().stream()
                                                .filter(p -> p.face() == face.getOpposite())
                                                .findFirst()
                                                .orElseThrow();
                                var normal = Vec3.atLowerCornerOf(face.getNormal());
                                var eye = port.centre().add(normal.scale(2.5));
                                var look = normal.scale(-1);
                                helper.assertTrue(
                                        port.equals(
                                                LinePortTarget.aim(
                                                        level, visible, eye, look, 5, null)),
                                        "Socket picking missed " + face);
                                player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
                                var tip = at.relative(face, 3);
                                var free = new LineTarget(null, tip, face, new Vec3(.5, .5, .5), 0);
                                var a =
                                        LayingTestCommands.resolve(
                                                level, end(port), facts.ports(), CableMedium.POWER);
                                var b =
                                        LayingTestCommands.resolve(
                                                level, free, facts.ports(), CableMedium.POWER);
                                helper.assertTrue(
                                        a != null && b != null, "Socket cannot resolve on " + face);
                                var route =
                                        LinePlacement.search(
                                                level,
                                                a.placement(),
                                                b.placement(),
                                                CableMedium.POWER.outside(),
                                                LineLaying.leads(a, b));
                                helper.assertTrue(route.found(), "No cable route from " + face);
                                var path =
                                        route.cells().stream()
                                                .map(LayingTestCommands::block)
                                                .toList();
                                helper.assertTrue(
                                        LayingTestCommands.lay(
                                                        player,
                                                        InteractionHand.MAIN_HAND,
                                                        end(port),
                                                        free,
                                                        path,
                                                        route.options(),
                                                        CableMedium.POWER)
                                                == null,
                                        "Placement refused on " + face);
                                paths.put(face, path);
                                var cap =
                                        level.getCapability(
                                                Capabilities.EnergyStorage.BLOCK, tip, face);
                                helper.assertTrue(
                                        cap != null && cap.extractEnergy(123, false) == 123,
                                        "Cable cannot extract FE on " + face);
                                var direct =
                                        level.getCapability(
                                                Capabilities.EnergyStorage.BLOCK, at, face);
                                helper.assertTrue(
                                        direct != null
                                                && !direct.canReceive()
                                                && direct.extractEnergy(321, false) == 321,
                                        "Standard FE capability is not output-only on " + face);
                            }
                            var read =
                                    ControlFacts.load(
                                            facts.save(
                                                    new net.minecraft.nbt.CompoundTag(),
                                                    level.registryAccess()));
                            for (var port : source.powerPorts())
                                helper.assertTrue(
                                        port.equals(read.port(port.id())),
                                        "Saved data lost a supply face");
                            var copy = new CreativePowerBlockEntity(at, source.getBlockState());
                            copy.setLevel(level);
                            copy.handleUpdateTag(
                                    source.getUpdateTag(level.registryAccess()),
                                    level.registryAccess());
                            helper.assertTrue(
                                    copy.powerPorts().equals(source.powerPorts()),
                                    "Client/chunk replay derived different socket identities");
                            PowerNetwork.loaded(source);
                            for (var face : Direction.values()) {
                                var tip = at.relative(face, 3);
                                var cap =
                                        level.getCapability(
                                                Capabilities.EnergyStorage.BLOCK, tip, face);
                                helper.assertTrue(
                                        cap != null && cap.extractEnergy(456, false) == 456,
                                        "A second connection displaced the " + face + " supply");
                            }
                            helper.assertTrue(
                                    primary.equals(source.instruments().powerPort()),
                                    "Multiple faces replaced the legacy primary socket");
                            var broken = paths.get(Direction.UP);
                            level.destroyBlock(broken.get(1), false);
                            for (var face : Direction.values()) {
                                var cap =
                                        level.getCapability(
                                                Capabilities.EnergyStorage.BLOCK,
                                                at.relative(face, 3),
                                                face);
                                helper.assertTrue(
                                        cap != null
                                                && cap.extractEnergy(100, false)
                                                        == (face == Direction.UP ? 0 : 100),
                                        "Breaking one face affected the wrong branch: " + face);
                            }
                            for (var path : paths.values())
                                for (var cell : path) level.removeBlock(cell, false);
                            level.removeBlock(at, false);
                        })
                .thenSucceed();
    }
}
