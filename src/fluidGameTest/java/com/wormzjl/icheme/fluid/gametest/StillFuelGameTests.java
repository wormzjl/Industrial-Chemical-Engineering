package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.fluid.StillUnit;
import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.testworld.PlantBuilder;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.Positions;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class StillFuelGameTests {
    private StillFuelGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "still-unloaded-fuel")
    public static void aSolidStillBurnsOneFuelItemWhileItsPotChunkIsUnloaded(GameTestHelper helper) {
        var plant = remotePlant(helper, 96);
        var level = plant.level;
        var world = plant.world;
        plant.hold();
        var vessel = placeSolidStill(plant);
        var shape = vessel.shape();
        var pot = pot(vessel);
        var potPosition = Positions.of(level, pot);

        var charge = PlantBuilder.front(vessel, EquipmentShape.Role.CHARGE);
        var outward = PlantBuilder.out(vessel, EquipmentShape.Role.CHARGE);
        var source = charge.relative(outward);
        level.setBlock(source, ModBlocks.FLUID_GENERATOR.get().defaultBlockState()
                .setValue(FluidDeviceBlock.FACING, outward.getOpposite()), Block.UPDATE_ALL);
        level.setBlock(charge, ModBlocks.FLUID_PIPE.get().defaultBlockState()
                .setValue(FluidDeviceBlock.FACING, outward), Block.UPDATE_ALL);
        plant.devices.add(source);
        plant.devices.add(charge);
        var generator = (FluidDeviceBlockEntity) level.getBlockEntity(source);
        TankCharge.run(world, generator.fluidIdentity(), new FluidDeviceSpec(1, 298.15, 300_000, LevelLoopGameTests.water()));

        var coal = new ItemStack(Items.COAL, 2);
        double oneItem = EquipmentShellBlockEntity.fuelEnergy(coal);
        long[] device = {0};
        IItemHandler[] capability = {null};
        StillUnit[] before = {null};
        helper.startSequence().thenWaitUntil(() -> {
            var record = world.at(potPosition).orElse(null);
            helper.assertTrue(record != null, "Waiting for the still's pot registration");
            device[0] = record.device().id();
            var view = world.view(device[0]);
            helper.assertTrue(view != null && view.state() != null && view.state().phaseVolumes()[1] > .05 * shape.volume(),
                    "Waiting for the still to fill through its charge nozzle");
            capability[0] = level.getCapability(Capabilities.ItemHandler.BLOCK, pot, Direction.UP);
            helper.assertTrue(capability[0] != null, "Waiting for the still's hopper capability");
        }).thenExecute(() -> {
            level.removeBlock(source, false);
            level.removeBlock(charge, false);
            var slot = capability[0];
            helper.assertTrue(slot.insertItem(0, coal, true).isEmpty() && slot.getStackInSlot(0).isEmpty(),
                    "A simulated hopper insert changed the fuel slot");
            helper.assertTrue(slot.insertItem(0, coal, false).isEmpty() && slot.getStackInSlot(0).getCount() == 2,
                    "The still did not accept both pieces of coal");
            plant.release();
        }).thenWaitUntil(() -> helper.assertTrue(!level.hasChunkAt(pot), "Waiting for the pot chunk to unload"))
                .thenExecute(() -> {
                    before[0] = world.powerTopology().still(device[0]);
                    helper.assertTrue(before[0].burning() == 0 && capability[0].getStackInSlot(0).getCount() == 2,
                            "The still burned fuel before firing");
                    helper.assertTrue(world.stillSettings(device[0], .1, before[0].reflux()) == null,
                            "The unloaded still refused a firing change");
                }).thenWaitUntil(() -> {
                    var after = world.powerTopology().still(device[0]);
                    helper.assertTrue(!level.hasChunkAt(pot), "Firing the still loaded its pot chunk");
                    helper.assertTrue(after.heated() > before[0].heated(), "Waiting for heat into the unloaded pot");
                    helper.assertTrue(capability[0].getStackInSlot(0).getCount() == 1, "The still must burn one piece of coal");
                    double accounted = after.burning() + after.heater().drawn(after.heated() - before[0].heated());
                    helper.assertTrue(Math.abs(accounted - oneItem) <= oneItem * 1e-12,
                            "The pot's heat and fire reserve did not account for one fuel item");
                }).thenExecute(() -> {
                    helper.assertTrue(world.stillSettings(device[0], 0, before[0].reflux()) == null,
                            "The unloaded still refused to stop firing");
                    plant.hold();
                }).thenWaitUntil(() -> {
                    var slot = level.getCapability(Capabilities.ItemHandler.BLOCK, pot, Direction.DOWN);
                    helper.assertTrue(slot != null, "Waiting for the returning hopper capability");
                    var remaining = slot.getStackInSlot(0);
                    helper.assertTrue(remaining.is(Items.COAL) && remaining.getCount() == 1,
                            "The reloaded pot did not expose the remaining coal");
                    slot.extractItem(0, 1, false);
                }).thenExecute(plant::remove)
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the test still to be removed"))
                .thenExecute(plant::release)
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "still-legacy-fuel")
    public static void aLegacyStillFuelSlotMigratesFromDiskOnceWhileThePotIsUnloaded(GameTestHelper helper) {
        var plant = remotePlant(helper, 104);
        var level = plant.level;
        var world = plant.world;
        plant.hold();
        var pot = pot(placeSolidStill(plant));
        var potPosition = Positions.of(level, pot);
        var registries = level.registryAccess();

        var legacyCoal = new ItemStack(Items.COAL, 3);
        legacyCoal.set(DataComponents.CUSTOM_NAME, Component.literal("Legacy still coal"));
        var component = new CompoundTag();
        component.putInt("batch", 27);
        legacyCoal.set(DataComponents.CUSTOM_DATA, CustomData.of(component));
        var legacySlot = new ItemStackHandler(1);
        legacySlot.setStackInSlot(0, legacyCoal);
        var fuelTag = legacySlot.serializeNBT(registries);
        long[] device = {0};

        helper.startSequence().thenWaitUntil(() -> {
            var record = world.at(potPosition).orElse(null);
            helper.assertTrue(record != null && world.powerTopology().still(record.device().id()) != null,
                    "Waiting for the solid still's registry entry");
            device[0] = record.device().id();
        }).thenExecute(() -> {
            var shell = (EquipmentShellBlockEntity) level.getBlockEntity(pot);
            var old = shell.saveWithFullMetadata(registries);
            old.put("StillFuel", fuelTag);
            shell.loadWithComponents(old, registries);
            shell.setChanged();
            level.getChunkSource().save(true);
            plant.release();
        }).thenWaitUntil(() -> helper.assertTrue(!level.hasChunkAt(pot), "Waiting for the saved pot chunk to unload"))
                .thenExecute(() -> {
                    var topology = world.powerTopology();
                    long id = device[0];
                    // The old checkpoint has the pot and its state but no engine-owned fuel slot.
                    topology.still(topology.still(id).withFuelStack(null));
                    var slot = world.stillFuel(id);
                    helper.assertTrue(slot != null, "The legacy pot's fuel slot was not imported");
                    assertStack(helper, legacyCoal, slot.getStackInSlot(0));
                    assertStack(helper, legacyCoal, slot.extractItem(0, legacyCoal.getCount(), true));
                    assertStack(helper, legacyCoal, slot.getStackInSlot(0));
                    assertStack(helper, legacyCoal, slot.extractItem(0, legacyCoal.getCount(), false));
                    helper.assertTrue(slot.extractItem(0, legacyCoal.getCount(), false).isEmpty()
                                    && "".equals(topology.still(id).fuelStack()),
                            "The migrated engine slot did not stay empty after extraction");
                    helper.assertTrue(!level.hasChunkAt(pot), "Legacy fuel migration loaded the pot chunk");
                    plant.hold();
                }).thenWaitUntil(() -> {
                    var shell = level.getBlockEntity(pot);
                    helper.assertTrue(shell instanceof EquipmentShellBlockEntity
                                    && fuelTag.equals(shell.saveWithFullMetadata(registries).get("StillFuel")),
                            "The returning pot did not have its old slot tag");
                    var slot = level.getCapability(Capabilities.ItemHandler.BLOCK, pot, Direction.UP);
                    helper.assertTrue(slot != null && slot.getStackInSlot(0).isEmpty(),
                            "The pot imported the same legacy stack again on chunk load");
                }).thenExecute(plant::remove)
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the legacy test still to be removed"))
                .thenExecute(plant::release)
                .thenSucceed();
    }

    private static PlantBuilder remotePlant(GameTestHelper helper, int chunkOffset) {
        var test = helper.absolutePos(BlockPos.ZERO);
        var remote = new BlockPos(((test.getX() >> 4) + chunkOffset) * 16 + 8, 80, (test.getZ() >> 4) * 16 + 8);
        return AssemblyGameTests.plant(helper, remote, new BlockPos(-8, -2, -8), new BlockPos(10, 14, 10));
    }

    private static Vessel placeSolidStill(PlantBuilder plant) {
        var shape = PlantBuilder.unit(EquipmentShape.Kind.BATCH_STILL)
                .withStill(BatchStillOptions.DEFAULT.withCondenser(BatchStillOptions.Condenser.NONE));
        return plant.place(shape, 0, 0, 0);
    }

    private static BlockPos pot(Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());
        return vessel.origin().offset(cell.x(), cell.y(), cell.z());
    }

    private static void assertStack(GameTestHelper helper, ItemStack expected, ItemStack actual) {
        helper.assertTrue(ItemStack.isSameItemSameComponents(expected, actual) && expected.getCount() == actual.getCount(),
                "The imported legacy stack lost its item, count or components");
    }
}
