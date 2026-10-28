package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * A world to review by hand (batch 2026-10-26-multi-machine-framework): one column plant with every part a column design
 * can ask for, built on the ground as its design asks and left standing. A 2 m column of 20 trays, fed on tray 12, with a
 * side draw (tray 9), a pumparound (drawn from tray 7, returned to tray 5), a kettle reboiler and sump steam; overhead a
 * steam ejector, an air cooler, a lying receiver with a boot on 5 m saddles and a standard reflux pump; the pumparound's
 * standard pump and exchanger. Every boundary line has its control valve and a tank. The build validates the plant
 * complete, then puts its report back to "not validated" for the reviewer's own Validate.
 *
 * <p>Not a gate: its namespace runs only in {@code runReviewWorldServer}, which leaves the world in
 * {@code run/review-world/world} to be copied into a client's {@code saves}.
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ColumnReviewWorld {
    private ColumnReviewWorld() {}

    /** The column's design: every component kind a design can ask for. */
    static ColumnTrays trays() {
        var components = new ColumnComponents(ColumnComponents.Condenser.AIR_COOLER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD),
                true, true);
        return new ColumnTrays(20, 12, List.of(9), List.of(21), List.of(new ColumnTrays.Pumparound(7, 5)), components);
    }

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "review")
    public static void aFullColumnPlantStandsComplete(GameTestHelper helper) {
        var level = helper.getLevel();
        // Clear of the test's own structure block, on the ground.
        var corner = helper.absolutePos(new BlockPos(48, 0, 48));
        level.getChunk(corner.getX() >> 4, corner.getZ() >> 4);
        var base = new BlockPos(corner.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE, corner.getX(), corner.getZ()), corner.getZ());
        var plant = new AssemblyGameTests.Plant(helper, base, new BlockPos(-26, 0, -26), new BlockPos(22, 30, 26));
        String[] failure = {null};
        try {
            build(plant);
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            IChemE.LOGGER.error("review_world build failed", e);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(failure[0] != null || plant.valvesInstalled(), "Waiting for the valves"))
                .thenExecute(() -> {
                    if (failure[0] == null) finish(plant, failure);
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }

    /**
     * The plant, west to east: the pumparound's pump and exchanger (south) and the overhead train (north: ejector, air
     * cooler, receiver, reflux pump) on the column's right, where its reflux and pumparound nozzles face; the column; the
     * kettle on its left, beside the vapour return, the side draw and the steam. Boundary lines first, straight out of
     * their nozzles; then the lines between units, each the shortest that touches no other line.
     */
    private static void build(AssemblyGameTests.Plant plant) {
        plant.hold();
        var shape = EquipmentShape.column(2, trays(), EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(2));
        ColumnDesign.misfit(shape).ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        var column = plant.column = plant.place(shape, 0, 0, 0);
        var ejector = plant.place(AssemblyGameTests.Plant.unit(Kind.EJECTOR), -6, 0, -10);
        var cooler = plant.place(AssemblyGameTests.Plant.unit(Kind.AIR_COOLER), -15, 0, -13);
        // The receiver stands high, as a reflux drum does over its pump: its boot drain's line has room under it.
        var drum = plant.drum = plant.place(EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 5,
                EquipmentShape.defaultConnections(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, 5, true), true), -16, 0, -1);
        var refluxPump = plant.place(AssemblyGameTests.Plant.unit(Kind.PUMP), -8, 0, -3);
        var paPump = plant.place(AssemblyGameTests.Plant.unit(Kind.PUMP), -8, 0, 12);
        var paCooler = plant.place(AssemblyGameTests.Plant.unit(Kind.EXCHANGER), -15, 0, 12);
        var kettle = plant.place(AssemblyGameTests.Plant.unit(Kind.KETTLE), 12, 0, 0);

        // Boundary lines: the feed and steam valves point at the column, the products' away from it.
        plant.outside(column, Role.FEED, false, true);
        plant.outside(column, Role.STEAM, false, true);
        plant.outside(column, Role.DRAW, true, true);
        plant.outside(kettle, Role.LIQUID_OUTLET, true, true);
        plant.offgasEnd = plant.outside(drum, Role.VAPOUR_OUTLET, true, true);
        plant.outside(drum, Role.BOOT_DRAIN, true, true);

        // The overhead: column, ejector, air cooler, receiver, reflux pump, the tee, the reflux back to the column.
        plant.route(AssemblyGameTests.Plant.front(column, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(ejector, Role.SUCTION));
        plant.route(AssemblyGameTests.Plant.front(ejector, Role.DISCHARGE), AssemblyGameTests.Plant.front(cooler, Role.PROCESS_INLET));
        plant.route(AssemblyGameTests.Plant.front(cooler, Role.PROCESS_OUTLET), AssemblyGameTests.Plant.front(drum, Role.SIDE));
        plant.route(AssemblyGameTests.Plant.front(drum, Role.LIQUID_OUTLET), AssemblyGameTests.Plant.front(refluxPump, Role.SUCTION));
        plant.tee(plant.route(AssemblyGameTests.Plant.front(refluxPump, Role.DISCHARGE), AssemblyGameTests.Plant.front(column, Role.REFLUX)));
        // The pumparound: drawn through its pump and its exchanger's shell, back two trays up.
        plant.route(AssemblyGameTests.Plant.front(column, Role.PUMPAROUND_DRAW), AssemblyGameTests.Plant.front(paPump, Role.SUCTION));
        plant.route(AssemblyGameTests.Plant.front(paPump, Role.DISCHARGE), AssemblyGameTests.Plant.front(paCooler, Role.SHELL_INLET));
        plant.route(AssemblyGameTests.Plant.front(paCooler, Role.SHELL_OUTLET), AssemblyGameTests.Plant.front(column, Role.RETURN));
        // The reboiler: the bottoms into the kettle, its vapour back under the bottom tray.
        plant.route(AssemblyGameTests.Plant.front(column, Role.BOTTOMS), AssemblyGameTests.Plant.front(kettle, Role.FEED));
        plant.route(AssemblyGameTests.Plant.front(kettle, Role.VAPOUR_OUTLET), AssemblyGameTests.Plant.front(column, Role.VAPOUR_RETURN));
        for (var valve : plant.valves.entrySet()) plant.install(valve.getKey(), valve.getValue());
    }

    /** Validates the plant complete, then leaves it for the reviewer: report back to not validated, spawn in front, noon. */
    private static void finish(AssemblyGameTests.Plant plant, String[] failure) {
        var entry = AssemblyService.validate(plant.level, plant.controller());
        if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) {
            failure[0] = "not complete: " + plant.faults();
            return;
        }
        AssemblyStore.of(plant.level).put(plant.column.origin().asLong(), AssemblyStore.Entry.NOT_VALIDATED);
        // North of the column, facing it (yaw 0: south), with the overhead train on the right.
        var spawn = plant.base.offset(3, 0, -22);
        plant.level.setDefaultSpawnPos(spawn, 0);
        plant.level.setDayTime(6000);
        IChemE.LOGGER.info("review_world status=COMPLETE slots={} column={} spawn={} devices={}", entry.report().slots().size(),
                plant.column.origin().toShortString(), spawn.toShortString(), plant.devices.size());
    }
}
