package com.wormzjl.icheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.network.ProcessSimulatorNetwork;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ProcessSimulatorMenu;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.ProcessSimulatorBlockEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The process simulator's block on a world (W8 of batch 2026-11-02-process-simulator, plan sections 3.4 and 3.6): what only a real block entity, menu and player can show. The rules behind them
 * have their unit gates in {@code SheetStoreTest} and {@code SimulatorBlockTest}. Registers nothing: a simulator is no fluid node, and the structure's clearing removes the blocks.
 */
@GameTestHolder("icheme_column_test")
@PrefixGameTestTemplate(false)
public final class ProcessSimulatorGameTests {
    private static final BlockPos AT = new BlockPos(1, 1, 1);

    private ProcessSimulatorGameTests() {
    }

    private static ProcessSimulatorBlockEntity place(GameTestHelper helper, BlockPos at) {
        helper.setBlock(at, ModBlocks.PROCESS_SIMULATOR.get());
        return (ProcessSimulatorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(at));
    }

    private static ServerPlayer player(GameTestHelper helper, String name, BlockPos at) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        var near = helper.absolutePos(at);
        player.moveTo(near.getX() + .5, near.getY() + 1, near.getZ() + .5);
        return player;
    }

    /** What the real open does to a player the fake cannot show a screen to: the same claim and the same server menu. */
    private static boolean open(ServerPlayer player, ProcessSimulatorBlockEntity block, int container) {
        var menu = new ProcessSimulatorMenu(container, ContainerLevelAccess.create(block.getLevel(), block.getBlockPos()), block.getBlockPos());
        player.containerMenu = menu;
        return block.opens(player);
    }

    /** As {@link #open}, with both players taken to stand at the block: a fake player is on no player list, so the block's own liveness check sees it as gone. */
    private static boolean openBeside(ServerPlayer player, ProcessSimulatorBlockEntity block, int container) {
        player.containerMenu = new ProcessSimulatorMenu(container, ContainerLevelAccess.create(block.getLevel(), block.getBlockPos()), block.getBlockPos());
        return block.store().open(player.getUUID(), id -> true);
    }

    private static void close(ServerPlayer player) {
        var menu = player.containerMenu;
        player.containerMenu = player.inventoryMenu;
        menu.removed(player);
    }

    private static byte[] body() {
        var catalog = MaterialRuntime.active();
        var network = catalog.presets().network().packageId();
        var sheet = new Flowsheet(new Flowsheet.Basis(network, catalog.requirePackage(network).revision(), List.of("Water")), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT);
        return SheetBody.pack(FlowsheetFile.write(sheet), new byte[]{7});
    }

    private static void send(ServerPlayer player, byte[] body) {
        var parts = SheetBody.parts(body);
        for (int i = 0; i < parts.size(); i++) ProcessSimulatorNetwork.receive(player, new ProcessSimulatorNetwork.SheetPart(player.containerMenu.containerId, i, parts.size(), parts.get(i)));
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void theOldIdReachesTheSimulator(GameTestHelper helper) {
        var old = ResourceLocation.fromNamespaceAndPath("icheme", "column_calculator_v3");
        helper.assertTrue(BuiltInRegistries.BLOCK.get(old) == ModBlocks.PROCESS_SIMULATOR.get(), "The old block id reaches nothing");
        helper.assertTrue(BuiltInRegistries.ITEM.get(old) == ModItems.PROCESS_SIMULATOR.get(), "The old item id reaches nothing");
        helper.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.get(old) == ModBlockEntities.PROCESS_SIMULATOR.get(), "The old block entity id reaches nothing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aSheetSurvivesAReloadAndRidesInNoUpdateTag(GameTestHelper helper) {
        var block = place(helper, AT);
        var editor = player(helper, "editor", AT);
        open(editor, block, 1);
        var body = body();
        send(editor, body);
        helper.assertTrue(java.util.Arrays.equals(body, block.store().body()), "The upload was not stored");
        var registries = helper.getLevel().registryAccess();
        block.saveDesign(design());
        var id = block.sheetId();
        var saved = block.saveWithFullMetadata(registries);
        helper.assertTrue(block.getUpdateTag(registries).isEmpty() && block.getUpdatePacket() == null, "The sheet rides in an update");
        close(editor);
        var fresh = place(helper, AT.above());
        fresh.loadWithComponents(saved, registries);
        helper.assertTrue(java.util.Arrays.equals(body, fresh.store().replay()), "The sheet did not survive");
        helper.assertTrue(fresh.savedDesign().isPresent(), "The design did not survive");
        helper.assertTrue(id.equals(fresh.sheetId()), "The block's own name did not survive: its autosave would start again");
        helper.assertTrue(fresh.store().holder() == null, "The holder is state and is not saved");
        helper.succeed();
    }

    /** A tag the calculator left loads too. A sheet that cannot be read opens empty, and its bytes ride through every save after. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aDamagedSheetOpensEmptyAndItsBytesSurviveASave(GameTestHelper helper) {
        var block = place(helper, AT);
        var registries = helper.getLevel().registryAccess();
        var tag = block.saveWithFullMetadata(registries);
        tag.putByteArray("Sheet", new byte[]{1, 2, 3});
        tag.putInt("V3DataVersion", 12);
        block.loadWithComponents(tag, registries);
        helper.assertTrue(block.store().replay().length == 0 && block.store().damaged(), "A damaged sheet did not open empty");
        var again = block.saveWithFullMetadata(registries);
        var fresh = place(helper, AT.above());
        fresh.loadWithComponents(again, registries);
        helper.assertTrue(fresh.store().damaged() && java.util.Arrays.equals(new byte[]{1, 2, 3}, fresh.store().kept().getFirst()), "The damaged bytes were not kept");
        helper.succeed();
    }

    private static ColumnDesign design() {
        var catalog = MaterialRuntime.active();
        return ColumnDesign.of(catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog), ColumnComponents.Choices.DEFAULT);
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aConstructorImportsTheNearestSimulatorsDesign(GameTestHelper helper) {
        helper.setBlock(AT, ModBlocks.EQUIPMENT_CONSTRUCTOR.get());
        var constructor = (EquipmentConstructorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(AT));
        helper.assertTrue(constructor.importColumn(4).isEmpty(), "A simulator found where none stands");
        var block = place(helper, AT.east(3));
        helper.assertTrue(constructor.importColumn(4).orElseThrow().isEmpty(), "A design found where none is saved");
        var near = player(helper, "editor", AT);
        open(near, block, 1);
        ProcessSimulatorNetwork.receive(near, new ProcessSimulatorNetwork.SaveDesign(1, MaterialRuntime.active().presets().column(MaterialRuntime.active().presets().network().defaultColumnPreset()).input(MaterialRuntime.active()), ColumnComponents.Choices.DEFAULT));
        close(near);
        helper.assertTrue(block.savedDesign().isPresent(), "Save design was not stored");
        var imported = constructor.importColumn(4).orElseThrow().orElseThrow();
        helper.assertTrue(imported.column() != null, "The import took no column");
        helper.assertTrue(constructor.importColumn(2).isEmpty(), "A simulator out of reach answered");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aSecondPlayerReadsAndCannotUpload(GameTestHelper helper) {
        var block = place(helper, AT);
        var editor = player(helper, "editor", AT);
        var reader = player(helper, "reader", AT);
        helper.assertTrue(openBeside(editor, block, 1), "The first player does not edit");
        helper.assertFalse(openBeside(reader, block, 2), "The second player edits");
        var body = body();
        send(reader, body);
        helper.assertTrue(block.store().body().length == 0, "A reader's upload was stored");
        ProcessSimulatorNetwork.receive(reader, new ProcessSimulatorNetwork.SaveDesign(2, MaterialRuntime.active().presets().column(MaterialRuntime.active().presets().network().defaultColumnPreset()).input(MaterialRuntime.active()), ColumnComponents.Choices.DEFAULT));
        helper.assertTrue(block.savedDesign().isEmpty(), "A reader's design was stored");
        close(reader);
        helper.assertTrue(block.store().holder().equals(editor.getUUID()), "A reader's close freed the sheet");
        close(editor);
        helper.assertTrue(block.store().holder() == null, "The editor's close did not free the sheet");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void anUploadCountsOnlyFromTheScreenNearTheBlock(GameTestHelper helper) {
        var block = place(helper, AT);
        var editor = player(helper, "editor", AT);
        open(editor, block, 5);
        var part = new ProcessSimulatorNetwork.SheetPart(6, 0, 1, body());
        ProcessSimulatorNetwork.receive(editor, part);
        helper.assertTrue(block.store().body().length == 0, "An upload for another screen was stored");
        var far = helper.absolutePos(AT).above(200);
        editor.moveTo(far.getX(), far.getY(), far.getZ());
        send(editor, body());
        helper.assertTrue(block.store().body().length == 0, "An upload from out of reach was stored");
        var near = helper.absolutePos(AT);
        editor.moveTo(near.getX() + .5, near.getY() + 1, near.getZ() + .5);
        close(editor);
        send(editor, body());
        helper.assertTrue(block.store().body().length == 0, "An upload with the screen closed was stored");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aBadUploadIsRefusedAndTheStoredSheetStands(GameTestHelper helper) {
        var block = place(helper, AT);
        var editor = player(helper, "editor", AT);
        open(editor, block, 1);
        var body = body();
        send(editor, body);
        send(editor, new byte[100]);
        send(editor, SheetBody.pack("not json".getBytes(), new byte[0]));
        ProcessSimulatorNetwork.receive(editor, new ProcessSimulatorNetwork.SheetPart(1, 0, SheetBody.MAX_PARTS + 1, new byte[10]));
        helper.assertTrue(java.util.Arrays.equals(body, block.store().body()), "A bad upload moved the stored sheet");
        close(editor);
        helper.succeed();
    }

    /**
     * {@code PlayerList.remove} leaves through {@code removePlayerImmediately} and {@code Entity.remove} with this reason, which closes the menu. A mock player cannot log in here (KubeJS refuses
     * its channel), so the fake takes the last step of that path. The fake's name is this test's alone: the factory keeps it, removed.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void theHolderFreesOnDisconnect(GameTestHelper helper) {
        var block = place(helper, AT);
        var player = player(helper, "leaver", AT);
        helper.assertTrue(open(player, block, 1), "The player does not edit");
        player.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        helper.assertTrue(block.store().holder() == null, "A disconnect did not free the sheet");
        helper.succeed();
    }

    /** A holder who is gone, whatever word went missing, holds nothing: the next to open edits. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aHolderNoLongerAtTheBlockHoldsNothing(GameTestHelper helper) {
        var block = place(helper, AT);
        var gone = player(helper, "editor", AT);
        var next = player(helper, "reader", AT);
        helper.assertTrue(open(gone, block, 1), "The first player does not edit");
        helper.assertTrue(open(next, block, 2), "A stale holder kept the sheet");
        helper.assertTrue(block.store().holder().equals(next.getUUID()), "The sheet did not pass on");
        close(gone);
        helper.assertTrue(block.store().holder().equals(next.getUUID()), "An old close freed the new holder");
        close(next);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void theHolderFreesWhenTheBlockGoes(GameTestHelper helper) {
        var block = place(helper, AT);
        var editor = player(helper, "editor", AT);
        open(editor, block, 1);
        helper.getLevel().removeBlock(helper.absolutePos(AT), false);
        helper.assertTrue(block.store().holder() == null, "A block gone holds its sheet");
        helper.assertFalse(editor.containerMenu.stillValid(editor), "The screen outlives its block");
        close(editor);
        helper.succeed();
    }

    /** Opening claims and reads, and starts nothing: the service stands as it stood. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "simulator")
    public static void aReplayRunsNoSolverAndCreatesNoProcessState(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var block = place(helper, AT);
        var editor = player(helper, "editor", AT);
        open(editor, block, 1);
        send(editor, body());
        var before = ProcessSolveServices.diagnostics(server);
        var reader = player(helper, "reader", AT);
        ProcessSimulatorNetwork.open(reader, block);
        block.store().replay();
        var after = ProcessSolveServices.diagnostics(server);
        helper.assertTrue(before.outstandingJobs() == after.outstandingJobs() && before.readyJobs() == after.readyJobs() && before.activeWorkers() == after.activeWorkers(), "A replay reached the solve service");
        close(editor);
        helper.succeed();
    }
}
