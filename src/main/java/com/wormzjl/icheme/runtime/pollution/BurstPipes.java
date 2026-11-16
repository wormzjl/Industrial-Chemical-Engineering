package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.Releases;
import com.wormzjl.icheme.science.math.DeterministicMath;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-block fluid devices a blast destroys (batch 2026-11-15-pollution-system, WP6a, S5; owner 2026-11-15: a pipe releases
 * its contents only when an explosion destroys it, and has no damage meter). {@code FluidDeviceBlock.onBlockExploded} marks
 * the position for the length of its removal ({@link #exploded}); its {@code onRemove} then lets out what the device held
 * ({@link #burst}, {@link FluidWorldAuthority#releaseContents}) before the registration leaves. A player's break is never
 * marked and releases nothing.
 *
 * <p>The burst sound ({@code icheme:pipe_burst_gas}, {@code _liquid}, {@code _empty}): at most one per cube and tick, so a
 * chain of blasts does not stack hundreds; played after the level ticks ({@link #flush}) at the cube's largest burst, by the
 * phase that released the most mass, its volume by the cube's released mass. Server thread only; all of it is state, none
 * saved. Never throws.</p>
 */
public final class BurstPipes {
    /** A mark lives at most this many ticks (it is cleared when the removal returns). */
    public static final int MARK_TICKS = 2;
    /** Below this mass, kg, a burst is empty: the rupture alone. */
    public static final double EMPTY_KILOGRAMS = 1e-3;
    /** The released mass at volume 1 and at volume 3, kg (a gas line block, a one-block tank of liquid). */
    public static final double QUIET_KILOGRAMS = 0.01, LOUD_KILOGRAMS = 100;

    /** What escaped: the sound's tail. */
    public enum Phase { GAS, LIQUID, EMPTY }

    private record Mark(ServerLevel level, long position) {
    }

    private record Key(ResourceKey<Level> dimension, long cube) {
    }

    /** One cube's bursts of this tick. */
    private static final class Heard {
        final ServerLevel level;
        BlockPos at;
        double largest = -1, gas, liquid;

        Heard(ServerLevel level) {
            this.level = level;
        }
    }

    private static final Map<Mark, Integer> MARKS = new HashMap<>();
    private static final Map<MinecraftServer, LinkedHashMap<Key, Heard>> HEARD = new IdentityHashMap<>();
    private static long failures;

    private BurstPipes() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The rules (pure).

    /** The sound's tail: empty below {@link #EMPTY_KILOGRAMS}, else the phase that released more mass (gas on a tie). */
    public static Phase phase(double gasKilograms, double liquidKilograms) {
        double gas = Math.max(0, gasKilograms), liquid = Math.max(0, liquidKilograms);
        if (!(gas + liquid >= EMPTY_KILOGRAMS)) {
            return Phase.EMPTY;
        }
        return liquid > gas ? Phase.LIQUID : Phase.GAS;
    }

    /** The volume, 1 to 3, on the decades of released mass from {@link #QUIET_KILOGRAMS} to {@link #LOUD_KILOGRAMS}. */
    public static float volume(double kilograms) {
        if (!(kilograms > QUIET_KILOGRAMS)) {
            return 1f;
        }
        double decades = DeterministicMath.log10(kilograms / QUIET_KILOGRAMS) / DeterministicMath.log10(LOUD_KILOGRAMS / QUIET_KILOGRAMS);
        return (float) (1 + 2 * Math.clamp(decades, 0, 1));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The hooks.

    /** {@code FluidDeviceBlock.onBlockExploded}: runs {@code removal} with {@code position} marked as a blast's. */
    public static void exploded(ServerLevel level, BlockPos position, Runnable removal) {
        var mark = new Mark(level, position.asLong());
        boolean marked = false;
        try {
            MARKS.put(mark, level.getServer().getTickCount());
            marked = true;
        } catch (RuntimeException failure) {
            failed("mark", failure);
        }
        try {
            removal.run();
        } finally {
            if (marked) {
                MARKS.remove(mark);
            }
        }
    }

    /** Whether a blast is removing the block at {@code position} now. */
    public static boolean marked(ServerLevel level, BlockPos position) {
        try {
            Integer at = MARKS.get(new Mark(level, position.asLong()));
            return at != null && level.getServer().getTickCount() - at <= MARK_TICKS;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** {@code FluidDeviceBlock.onRemove} at a marked position, before device {@code id} leaves: its contents to the air, and its sound. */
    public static void burst(ServerLevel level, BlockPos position, FluidWorldAuthority world, long id) {
        try {
            var spilled = world.releaseContents(id);
            heard(level, position, spilled == null ? Releases.Spilled.NONE : spilled);
        } catch (RuntimeException failure) {
            failed("burst", failure);
        }
    }

    private static void heard(ServerLevel level, BlockPos position, Releases.Spilled spilled) {
        var cubes = HEARD.computeIfAbsent(level.getServer(), s -> new LinkedHashMap<>());
        var heard = cubes.computeIfAbsent(new Key(level.dimension(), PollutionService.cube(position)), k -> new Heard(level));
        double gas = Math.max(0, spilled.gasKilograms()), liquid = Math.max(0, spilled.liquidKilograms());
        heard.gas += gas;
        heard.liquid += liquid;
        if (gas + liquid > heard.largest) {
            heard.largest = gas + liquid;
            heard.at = position.immutable();
        }
    }

    /** The sounds of this tick's bursts, one per cube; from the pollution tick, after the levels ticked. Never throws. */
    public static void flush(MinecraftServer server) {
        try {
            var cubes = HEARD.remove(server);
            if (cubes == null) {
                return;
            }
            for (var heard : cubes.values()) {
                var event = switch (phase(heard.gas, heard.liquid)) {
                    case GAS -> ModBlocks.PIPE_BURST_GAS.get();
                    case LIQUID -> ModBlocks.PIPE_BURST_LIQUID.get();
                    case EMPTY -> ModBlocks.PIPE_BURST_EMPTY.get();
                };
                heard.level.playSound(null, heard.at, event, SoundSource.BLOCKS, volume(heard.gas + heard.liquid), 1f);
            }
        } catch (RuntimeException failure) {
            failed("sound", failure);
        }
    }

    /** Forgets a stopped server's bursts. Never throws. */
    public static void forget(MinecraftServer server) {
        try {
            HEARD.remove(server);
            MARKS.keySet().removeIf(mark -> mark.level().getServer() == server);
        } catch (RuntimeException failure) {
            failed("forget", failure);
        }
    }

    private static void failed(String stage, RuntimeException failure) {
        if (failures++ % 1000 == 0) {
            IChemE.LOGGER.warn("pollution status=BURST_FAILED stage={} failures={} detail={}", stage, failures, String.valueOf(failure));
        }
    }
}
