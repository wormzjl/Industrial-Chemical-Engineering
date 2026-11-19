package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runs the normal GameTest fixtures alone on a disposable review server. */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class UnloadedFixturesReview {
    private UnloadedFixturesReview() {}

    private static boolean skipped(GameTestHelper helper, String fixture) {
        String filter = System.getProperty(ReviewBatch.PROPERTY, "");
        if (filter.equals("unloaded-fixtures") || java.util.Arrays.asList(filter.split(",")).contains(fixture)) return false;
        helper.succeed();
        return true;
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-creative")
    public static void aCreativeSourceKeepsAnUnloadedMotorSuppliedUntilItIsBroken(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-creative")) ElectricGameTests.aCreativeSourceKeepsAnUnloadedMotorSuppliedUntilItIsBroken(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-generator")
    public static void anUnloadedGeneratorTransfersItsStoredEnergyToAnUnloadedNeighbour(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-generator")) ElectricGameTests.anUnloadedGeneratorTransfersItsStoredEnergyToAnUnloadedNeighbour(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-cable")
    public static void powerLayingSelectsAndSuppliesALargeMotor(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-cable")) ConduitLayingGameTests.powerLayingSelectsAndSuppliesALargeMotor(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-foreign-source")
    public static void anUnloadedForeignSourceStopsTheMeteredMotorAndLeavesItsCharge(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-foreign-source"))
            ElectricGameTests.anUnloadedForeignSourceStopsTheMeteredMotorAndLeavesItsCharge(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-foreign-receiver")
    public static void aForeignReceiverUnloadsWhileTheCreativeSourceRemainsRegistered(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-foreign-receiver"))
            ElectricGameTests.aForeignReceiverUnloadsWhileTheCreativeSourceRemainsRegistered(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-still")
    public static void aSolidStillBurnsOneFuelItemWhileItsPotChunkIsUnloaded(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-still")) StillFuelGameTests.aSolidStillBurnsOneFuelItemWhileItsPotChunkIsUnloaded(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-pollution")
    public static void savedTerrainAndTheRegisteredShellFeedAnUnloadedBlast(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-pollution")) UnloadedPollutionGameTests.savedTerrainAndTheRegisteredShellFeedAnUnloadedBlast(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 16000, batch = "unloaded-alarm")
    public static void aBeaconSoundsOnItsInputAndClearsWithItsBand(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-alarm")) AlarmBeaconGameTests.aBeaconSoundsOnItsInputAndClearsWithItsBand(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-legacy-still")
    public static void aLegacyStillFuelSlotMigratesFromDiskOnceWhileThePotIsUnloaded(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-legacy-still"))
            StillFuelGameTests.aLegacyStillFuelSlotMigratesFromDiskOnceWhileThePotIsUnloaded(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "unloaded-legacy-blast")
    public static void anUnloadedBlastFindsTheOldShellWhenTheBuildFactIsAbsent(GameTestHelper helper) {
        if (!skipped(helper, "unloaded-legacy-blast"))
            UnloadedPollutionGameTests.anUnloadedBlastFindsTheOldShellWhenTheBuildFactIsAbsent(helper);
    }
}
