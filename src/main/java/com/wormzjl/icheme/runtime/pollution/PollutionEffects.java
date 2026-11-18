package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Exposure;
import com.wormzjl.icheme.science.pollution.GroundEffects;
import com.wormzjl.icheme.science.pollution.MapColumns;
import com.wormzjl.icheme.science.pollution.PollutionField;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The world pass of pollution (batch 2026-11-15-pollution-system, WP5; plan section 6): every {@link #EFFECT_TICKS} ticks,
 * the living things in a polluted cube get the effects and damage of {@link Exposure}, and players nearby see the cloud.
 * Server thread only; reads the installed field and the cached cells, steps nothing and loads no chunk.
 *
 * <p>Crash-proof (owner, 2026-11-15): the pass catches at its edge and each entity in its own guard; after
 * {@link #FAILURE_LIMIT} failed passes in a row the effects turn off for the session ({@code pollution status=EFFECTS_OFF});
 * the field keeps running.</p>
 */
public final class PollutionEffects {
    /** Server ticks between two world passes: one second of play. */
    public static final int EFFECT_TICKS = 20;
    /** Affected cubes served per pass and dimension; more are served over later passes from a rotating start. */
    public static final int CUBE_BUDGET = 256;
    /** Failed passes in a row that turn the effects off for the session. */
    public static final int FAILURE_LIMIT = 3;
    /** Effect length in ticks, renewed each pass: 3 s, so it lasts one missed pass. */
    public static final int EFFECT_DURATION = 60;
    /** Players within this distance of a cube's centre see its particles. */
    public static final double PARTICLE_RANGE = 48.0;

    public static final TagKey<EntityType<?>> IMMUNE = TagKey.create(Registries.ENTITY_TYPE, id("pollution_immune"));
    public static final ResourceKey<DamageType> TOXIC_GAS = ResourceKey.create(Registries.DAMAGE_TYPE, id("toxic_gas"));
    public static final ResourceKey<DamageType> CORROSIVE_GAS = ResourceKey.create(Registries.DAMAGE_TYPE, id("corrosive_gas"));
    public static final ResourceKey<DamageType> PARTICULATE = ResourceKey.create(Registries.DAMAGE_TYPE, id("particulate"));

    private static final Channel[] CHANNELS = Channel.values();
    private static final Predicate<LivingEntity> AFFECTED = PollutionEffects::affected;

    private final boolean effects;
    private final boolean particles;
    private final Map<ResourceKey<Level>, Integer> starts = new HashMap<>();
    private final Exposure.Level[] levels = new Exposure.Level[CHANNELS.length];
    /** The cube's real indices beside {@link #levels}: a worn respirator's filter load (batch 2026-11-18-respirator). */
    private final double[] indices = new double[CHANNELS.length];
    private int pass;
    private int failures;
    private boolean off;
    private boolean missingTypeLogged;
    private boolean entityFailureLogged;

    PollutionEffects(boolean effects, boolean particles) {
        this.effects = effects;
        this.particles = particles;
        this.off = !effects && !particles;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, path);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pass.

    /** One world pass over every loaded dimension with a field; never throws. */
    void run(MinecraftServer server, PollutionService service) {
        if (off) {
            return;
        }
        try {
            pass++;
            entityFailureLogged = false;
            for (ServerLevel level : server.getAllLevels()) {
                dimension(level, service);
                water(level, service);
                GroundSoil.spoil(level, service, CUBE_BUDGET);
            }
            failures = 0;
        } catch (RuntimeException failure) {
            failures++;
            IChemE.LOGGER.warn("pollution status=EFFECTS_FAILED failures={} detail={}", failures, String.valueOf(failure));
            if (failures >= FAILURE_LIMIT) {
                off = true;
                IChemE.LOGGER.error("pollution status=EFFECTS_OFF detail={} failed world passes in a row, the last: {}", FAILURE_LIMIT, String.valueOf(failure));
            }
        }
    }

    private void dimension(ServerLevel level, PollutionService service) {
        var key = level.dimension();
        PollutionField field = service.field(key);
        int n = field.size();
        if (n == 0) {
            starts.remove(key);
            return;
        }
        var settings = service.settings();
        boolean show = particles && !level.players().isEmpty();
        DamageSource[] sources = effects ? sources(level) : null;
        int start = Math.floorMod(starts.getOrDefault(key, 0), n);
        int served = 0;
        int next = 0;
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            long cube = field.key(i);
            var cell = service.cell(key, cube);
            if (!cell.loaded()) {
                continue;
            }
            boolean any = false;
            for (var channel : CHANNELS) {
                double index = PollutionField.index(field.amount(i, channel), cell.airShare(), settings);
                var l = Exposure.level(index);
                indices[channel.ordinal()] = index;
                levels[channel.ordinal()] = l;
                any |= l != Exposure.Level.NONE;
            }
            if (!any) {
                continue;
            }
            int x = CubeKey.x(cube);
            int y = CubeKey.y(cube);
            int z = CubeKey.z(cube);
            if (level.getChunkSource().getChunkNow(x, z) == null) {
                continue;
            }
            if (served >= CUBE_BUDGET) {
                // Over the budget: the next pass starts at this cube.
                next = i;
                break;
            }
            served++;
            if (show) {
                particles(level, x, y, z);
            }
            if (effects) {
                var box = new AABB(x * 16.0, y * 16.0, z * 16.0, x * 16.0 + 16, y * 16.0 + 16, z * 16.0 + 16);
                for (var entity : level.getEntitiesOfClass(LivingEntity.class, box, AFFECTED)) {
                    try {
                        // A worn respirator lowers what is breathed; the armour still sees the cube's air.
                        boolean here = PollutionService.cube(entity.blockPosition()) == cube;
                        treat(entity, levels, RespiratorGear.protect(entity, here, indices, levels), sources, pass);
                    } catch (RuntimeException failure) {
                        if (!entityFailureLogged) {
                            entityFailureLogged = true;
                            IChemE.LOGGER.warn("pollution status=EFFECT_FAILED dimension={} entity={} detail={}", key.location(), entity.getType(), String.valueOf(failure));
                        }
                    }
                }
            }
        }
        starts.put(key, next);
    }

    /**
     * Water over polluted ground (batch 2026-11-18-ground-pollution, G4; config {@code pollution.groundWater}): every living
     * thing in water in a chunk column whose ground index reaches {@link Exposure#LOW} is treated as the air treats a toxic
     * cube at that index ({@link GroundEffects#water}). Reads the published ground snapshot; at most
     * {@link #CUBE_BUDGET} columns a pass, loaded chunks only.
     */
    private void water(ServerLevel level, PollutionService service) {
        if (!effects || !IChemE.pollutionGroundWater()) {
            return;
        }
        var snapshot = service.groundSnapshot(level.dimension());
        if (snapshot.size() == 0) {
            return;
        }
        DamageSource[] sources = sources(level);
        int served = 0;
        for (int i = 0; i < snapshot.size() && served < CUBE_BUDGET; i++) {
            var exposure = GroundEffects.water(snapshot.value(i));
            if (exposure == null) {
                continue;
            }
            long column = snapshot.column(i);
            int x = MapColumns.x(column), z = MapColumns.z(column);
            if (level.getChunkSource().getChunkNow(x, z) == null) {
                continue;
            }
            served++;
            var box = new AABB(x * 16.0, level.getMinBuildHeight(), z * 16.0, x * 16.0 + 16, level.getMaxBuildHeight(), z * 16.0 + 16);
            for (var entity : level.getEntitiesOfClass(LivingEntity.class, box, e -> AFFECTED.test(e) && e.isInWater())) {
                try {
                    treat(entity, exposure, sources, pass);
                } catch (RuntimeException failure) {
                    if (!entityFailureLogged) {
                        entityFailureLogged = true;
                        IChemE.LOGGER.warn("pollution status=EFFECT_FAILED dimension={} entity={} detail={}", level.dimension().location(), entity.getType(), String.valueOf(failure));
                    }
                }
            }
        }
    }

    /** The damage sources by channel ordinal (null for a channel with no damage, or a type the data does not hold). */
    private DamageSource[] sources(ServerLevel level) {
        var out = new DamageSource[CHANNELS.length];
        var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        out[Channel.TOXIC.ordinal()] = source(registry.getHolder(TOXIC_GAS).orElse(null));
        out[Channel.CORROSIVE.ordinal()] = source(registry.getHolder(CORROSIVE_GAS).orElse(null));
        out[Channel.PARTICULATE.ordinal()] = source(registry.getHolder(PARTICULATE).orElse(null));
        if (!missingTypeLogged && (out[Channel.TOXIC.ordinal()] == null || out[Channel.CORROSIVE.ordinal()] == null
                || out[Channel.PARTICULATE.ordinal()] == null)) {
            missingTypeLogged = true;
            IChemE.LOGGER.warn("pollution status=DAMAGE_TYPE_MISSING detail=a damage type of icheme is not in the registry; that channel gives no damage");
        }
        return out;
    }

    private static DamageSource source(Holder<DamageType> type) {
        return type == null ? null : new DamageSource(type);
    }

    /** The damage sources of a level, for a test that treats one entity by hand. */
    public static DamageSource[] damageSources(ServerLevel level) {
        return new PollutionEffects(true, false).sources(level);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // One entity.

    /** Whether pollution acts on the entity: alive, not a creative or spectator player, not of an immune type. */
    public static boolean affected(LivingEntity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        return !entity.getType().is(IMMUNE);
    }

    /**
     * One pass's treatment of an entity in a cube at {@code levels} (by channel ordinal): the effects for
     * {@link #EFFECT_DURATION} ticks, the damage of each channel, and corroded armour. A damage below 1 HP per second is
     * dealt as 1 HP every few passes, by {@code pass}; {@code sources} may be null (no damage) or hold nulls.
     */
    public static void treat(LivingEntity entity, Exposure.Level[] levels, DamageSource[] sources, int pass) {
        treat(entity, levels, levels, sources, pass);
    }

    /**
     * As {@link #treat(LivingEntity, Exposure.Level[], DamageSource[], int)}, with the effects and damage at {@code breathed}
     * (a worn respirator's, {@link RespiratorGear#protect}) and the armour corroded by the cube's own {@code levels}.
     */
    public static void treat(LivingEntity entity, Exposure.Level[] levels, Exposure.Level[] breathed, DamageSource[] sources, int pass) {
        if (!affected(entity)) {
            return;
        }
        boolean player = entity instanceof Player;
        for (var channel : CHANNELS) {
            var level = breathed[channel.ordinal()];
            if (level == null || level == Exposure.Level.NONE) {
                continue;
            }
            for (var dose : Exposure.effects(channel, level)) {
                if (dose.effect() == Exposure.Effect.NAUSEA && !player) {
                    continue;
                }
                entity.addEffect(new MobEffectInstance(effect(dose.effect()), EFFECT_DURATION, dose.amplifier(), true, false, true));
            }
        }
        if (sources != null) {
            for (var channel : CHANNELS) {
                var level = breathed[channel.ordinal()];
                double perSecond = Exposure.damagePerSecond(channel, level);
                var source = sources[channel.ordinal()];
                if (!(perSecond > 0) || source == null || !entity.isAlive()) {
                    continue;
                }
                double amount = perSecond * com.wormzjl.icheme.science.fluid.SimulationTime.playSeconds(EFFECT_TICKS);
                if (amount < 1) {
                    // 1 HP every few passes: 0.5 HP/s is 1 HP every second pass. No per-entity state.
                    int every = (int) Math.round(1.0 / amount);
                    if (Math.floorMod(pass, every) != 0) {
                        continue;
                    }
                    amount = 1;
                }
                // Each channel's damage counts in full: the hurt cooldown of the hit before (another channel, or poison)
                // would otherwise swallow it.
                entity.invulnerableTime = 0;
                entity.hurt(source, (float) amount);
            }
        }
        var corrosive = levels[Channel.CORROSIVE.ordinal()];
        if (Exposure.corrodesArmour(corrosive) && entity.isAlive()) {
            for (var slot : EquipmentSlot.values()) {
                if (!slot.isArmor()) {
                    continue;
                }
                var stack = entity.getItemBySlot(slot);
                if (!stack.isEmpty() && stack.isDamageableItem()) {
                    stack.hurtAndBreak(1, entity, slot);
                }
            }
        }
    }

    static Holder<MobEffect> effect(Exposure.Effect effect) {
        return switch (effect) {
            case POISON -> MobEffects.POISON;
            case WEAKNESS -> MobEffects.WEAKNESS;
            case SLOWNESS -> MobEffects.MOVEMENT_SLOWDOWN;
            case BLINDNESS -> MobEffects.BLINDNESS;
            case MINING_FATIGUE -> MobEffects.DIG_SLOWDOWN;
            case NAUSEA -> MobEffects.CONFUSION;
            case DARKNESS -> MobEffects.DARKNESS;
            case HUNGER -> MobEffects.HUNGER;
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Particles: vanilla one-shot packets, to the players within PARTICLE_RANGE of the cube's centre.

    private void particles(ServerLevel level, int x, int y, int z) {
        double cx = x * 16.0 + 8;
        double cy = y * 16.0 + 8;
        double cz = z * 16.0 + 8;
        List<ServerPlayer> players = level.players();
        double range = PARTICLE_RANGE * PARTICLE_RANGE;
        for (int p = 0; p < players.size(); p++) {
            var player = players.get(p);
            if (player.distanceToSqr(cx, cy, cz) > range) {
                continue;
            }
            for (var channel : CHANNELS) {
                var l = levels[channel.ordinal()];
                int count = count(l);
                if (count == 0) {
                    continue;
                }
                DustParticleOptions dust = switch (channel) {
                    case TOXIC -> Dust.TOXIC;
                    case COMBUSTIBLE -> Dust.COMBUSTIBLE;
                    case CORROSIVE -> Dust.CORROSIVE;
                    case PARTICULATE -> Dust.PARTICULATE;
                };
                if (channel == Channel.COMBUSTIBLE) {
                    count /= 2;
                }
                // Long distance: the vanilla 32-block limit of a plain packet is below PARTICLE_RANGE.
                level.sendParticles(player, dust, true, cx, cy, cz, count, 6.0, 6.0, 6.0, 0.0);
            }
        }
    }

    /** The colours, made on first use (a data test that reads this class's keys loads no particle type). */
    private static final class Dust {
        static final DustParticleOptions TOXIC = new DustParticleOptions(new Vector3f(0.30f, 0.80f, 0.25f), 1.5f);
        static final DustParticleOptions COMBUSTIBLE = new DustParticleOptions(new Vector3f(0.95f, 0.65f, 0.35f), 1.5f);
        static final DustParticleOptions CORROSIVE = new DustParticleOptions(new Vector3f(0.90f, 0.80f, 0.20f), 1.5f);
        static final DustParticleOptions PARTICULATE = new DustParticleOptions(new Vector3f(0.35f, 0.35f, 0.35f), 1.5f);
    }

    /** Particles per channel and pass: 4 at LOW, 8 at MEDIUM, 16 at HIGH and above. */
    static int count(Exposure.Level level) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case NONE -> 0;
            case LOW -> 4;
            case MEDIUM -> 8;
            case HIGH, EXTREME -> 16;
        };
    }
}
