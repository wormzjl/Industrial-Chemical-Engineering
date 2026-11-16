package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.pollution.PollutionEffects;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Exposure;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The world pass of pollution (batch 2026-11-15-pollution-system, WP5): a toxic cube kills a cow and spares a zombie
 * (the immune tag), through the field's pass and job (WP3) and the effects pass; creative players are spared.
 *
 * <p>Pollution fills a whole 16 m cube and the dimension's field is shared, so these tests run in a batch of their own,
 * clear the dimension's field at their start and at their end, and remove what they spawn.</p>
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PollutionEffectsGameTests {
    private PollutionEffectsGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "pollution")
    public static void toxicCubeKillsACowAndSparesAZombie(GameTestHelper helper) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        helper.assertTrue(service.enabled(), "Pollution runs in the GameTest server");
        service.clear(dimension);
        // Free cells start at x and z 2 (vanilla rings the one-cell structure with barriers).
        var cowAt = helper.absolutePos(new BlockPos(2, 2, 2));
        var zombieAt = helper.absolutePos(new BlockPos(4, 2, 2));
        long cube = PollutionService.cube(cowAt);
        var cubes = new LinkedHashSet<Long>();
        cubes.add(cube);
        cubes.add(PollutionService.cube(zombieAt));
        for (long c : cubes) {
            service.add(dimension, c, Channel.TOXIC, 3.0);
        }
        Cow[] cow = new Cow[1];
        Zombie[] zombie = new Zombie[1];
        var zombieHurt = new AtomicBoolean();
        helper.startSequence()
                // One field pass (100 ticks) and its job: the installed index proves the pass wiring.
                .thenWaitUntil(() -> helper.assertTrue(service.index(dimension, cube, Channel.TOXIC) > Exposure.HIGH,
                        "Waiting for the pass to install the toxic cube; index " + service.index(dimension, cube, Channel.TOXIC)))
                .thenExecute(() -> {
                    // The seed alone holds HIGH for one field pass (100 ticks): exactly the five hits the cow needs, so a
                    // pass job that installs a few ticks late left four (run `union`, batch 2026-11-16-fouling-corrosion).
                    // The same release again, for the next pass, keeps the cube at HIGH or above through a second window.
                    for (long c : cubes) {
                        service.add(dimension, c, Channel.TOXIC, 3.0);
                    }
                    cow[0] = helper.spawn(EntityType.COW, new BlockPos(2, 2, 2));
                    cow[0].setNoAi(true);
                    zombie[0] = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 2));
                    zombie[0].setNoAi(true);
                    zombie[0].setPersistenceRequired();
                })
                .thenExecuteFor(200, () -> {
                    var source = zombie[0].getLastDamageSource();
                    if (source != null && (source.is(PollutionEffects.TOXIC_GAS) || source.is(PollutionEffects.CORROSIVE_GAS)
                            || source.is(PollutionEffects.PARTICULATE))) {
                        zombieHurt.set(true);
                    }
                })
                .thenExecute(() -> {
                    // Read first, then remove everything this test added, then judge: a failed assertion leaves nothing behind.
                    boolean cowDied = cow[0].isDeadOrDying();
                    float cowHealth = cow[0].getHealth();
                    boolean zombieWeak = zombie[0].hasEffect(MobEffects.WEAKNESS);
                    service.clear(dimension);
                    zombie[0].discard();
                    if (!cow[0].isRemoved()) {
                        cow[0].discard();
                    }
                    for (var drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(cowAt).inflate(4))) {
                        drop.discard();
                    }
                    helper.assertTrue(cowDied, "A cow (10 HP) dies within 200 ticks at toxic HIGH; health " + cowHealth);
                    helper.assertFalse(zombieHurt.get(), "A zombie (pollution_immune) takes no pollution damage");
                    helper.assertFalse(zombieWeak, "A zombie gets no toxic weakness");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "pollution")
    public static void creativePlayersAreSparedSurvivalPlayersArePoisoned(GameTestHelper helper) {
        var levels = new Exposure.Level[Channel.values().length];
        java.util.Arrays.fill(levels, Exposure.Level.NONE);
        levels[Channel.TOXIC.ordinal()] = Exposure.Level.LOW;
        var creative = helper.makeMockPlayer(GameType.CREATIVE);
        var survival = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertFalse(PollutionEffects.affected(creative), "A creative player is not affected");
        helper.assertTrue(PollutionEffects.affected(survival), "A survival player is affected");
        PollutionEffects.treat(creative, levels, null, 1);
        PollutionEffects.treat(survival, levels, null, 1);
        helper.assertFalse(creative.hasEffect(MobEffects.POISON), "A creative player gets no poison");
        helper.assertTrue(survival.hasEffect(MobEffects.POISON), "A survival player gets poison at toxic LOW");
        helper.assertTrue(survival.hasEffect(MobEffects.WEAKNESS), "A survival player gets weakness at toxic LOW");
        // The mock players were never added to the level: nothing to remove.
        helper.succeed();
    }
}
