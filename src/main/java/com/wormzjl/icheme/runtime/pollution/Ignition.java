package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.ExplosionLaw;
import com.wormzjl.icheme.science.pollution.PollutionField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ignition of flammable cubes (batch 2026-11-15-pollution-system, WP6a; plan section 7): every {@link #IGNITION_TICKS}
 * ticks, each cube whose combustible index is at least 1 (the LFL; rare) is searched for a flame, and one found ignites it
 * there ({@link Blasts}). At most {@link #CUBE_BUDGET} flammable cubes are searched per pass and dimension, from a rotating
 * start. Server thread only; reads the installed field, the cached cells and loaded chunks ({@code getChunkNow}), steps
 * nothing and loads no chunk.
 *
 * <p>The sources, in the order searched: a block of the tag {@code icheme:ignition_sources} (a state with a {@code lit}
 * property only while lit; Create's blaze burner, by its {@code blaze} property's name, only while fading, kindled or
 * seething), found by the section's palette first ({@code maybeHas}) and only then by a scan of that one section; an entity
 * in the cube that burns or is of the entity tag {@code icheme:ignition_sources}; the plant's own flames
 * ({@link FluidWorldAuthority#flames}). And any explosion inside a flammable cube ({@link #detonated}): its position is
 * queued and ignites the cube at the next pass. A failed pass counts toward the blasts' switch-off ({@link Blasts#failed}).</p>
 */
public final class Ignition {
    /** Server ticks between two ignition passes: half a second of play. */
    public static final int IGNITION_TICKS = 10;
    /** Flammable cubes searched per pass and dimension; more are searched over later passes from a rotating start. */
    public static final int CUBE_BUDGET = 64;
    /** Explosions waiting for the next pass; more are dropped (each then ignites nothing). */
    public static final int EVENT_CAP = 256;
    /** Create's blaze burner's heat property ({@code BlazeBurnerBlock.HEAT_LEVEL}), read by name: no compile-time dependency on Create. */
    static final String BLAZE_PROPERTY = "blaze";

    public static final TagKey<Block> SOURCE_BLOCKS = TagKey.create(Registries.BLOCK, id("ignition_sources"));
    public static final TagKey<EntityType<?>> SOURCE_ENTITIES = TagKey.create(Registries.ENTITY_TYPE, id("ignition_sources"));

    private static boolean detonationFailureLogged;

    private record Detonation(ResourceKey<Level> dimension, BlockPos at) {
    }

    private final Blasts blasts;
    private final Map<ResourceKey<Level>, Integer> starts = new HashMap<>();
    private final ArrayDeque<Detonation> detonations = new ArrayDeque<>();

    Ignition(Blasts blasts) {
        this.blasts = blasts;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, path);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The rules (pure).

    /** A cube ignites at a flame from its LFL up: {@link ExplosionLaw#flammable}. */
    public static boolean ignites(double combustibleIndex) {
        return ExplosionLaw.flammable(combustibleIndex);
    }

    /** A source block counts while lit: a state with no {@code lit} property always, one with it only when it is true. */
    static boolean lit(boolean hasLit, boolean isLit) {
        return !hasLit || isLit;
    }

    /** Create's blaze burner is a flame while it gives heat: fading, kindled or seething (not none, nor smouldering). */
    static boolean blazeLit(String heat) {
        return "fading".equals(heat) || "kindled".equals(heat) || "seething".equals(heat);
    }

    /** Where the next pass starts, of {@code size} cubes: {@code start} when the budget ran out there, else the first. */
    static int rotate(int start, int size) {
        return size <= 0 ? 0 : Math.floorMod(start, size);
    }

    /** Whether {@code state} is a flame: of the source tag, lit where it can be unlit, and a blaze burner only while it heats. */
    static boolean source(BlockState state) {
        if (!state.is(SOURCE_BLOCKS)) {
            return false;
        }
        boolean hasLit = state.hasProperty(BlockStateProperties.LIT);
        if (!lit(hasLit, hasLit && state.getValue(BlockStateProperties.LIT))) {
            return false;
        }
        for (Property<?> property : state.getProperties()) {
            if (BLAZE_PROPERTY.equals(property.getName())) {
                return blazeLit(valueName(state, property));
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Whether {@code entity} lights a cloud: it burns, or it is of the entity source tag (blazes, fireballs, lightning, primed TNT, rockets). */
    static boolean igniter(Entity entity) {
        return entity != null && entity.isAlive() && (entity.isOnFire() || entity.getType().is(SOURCE_ENTITIES));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The explosion hook.

    /**
     * NeoForge's {@link ExplosionEvent.Detonate} (game bus): an explosion's centre is queued for the next ignition pass, which
     * ignites the cube there if it is flammable. Nothing else happens in the handler. Never throws.
     */
    public static void detonated(ExplosionEvent.Detonate event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel level)) {
                return;
            }
            var service = PollutionService.find(level.getServer()).orElse(null);
            if (service == null || !service.enabled()) {
                return;
            }
            service.ignition().queue(level.dimension(), BlockPos.containing(event.getExplosion().center()));
        } catch (RuntimeException failure) {
            if (!detonationFailureLogged) {
                detonationFailureLogged = true;
                IChemE.LOGGER.warn("pollution status=DETONATION_FAILED detail={}", String.valueOf(failure));
            }
        }
    }

    /** Queues an explosion at {@code at} for the next pass; dropped when the explosions are off or {@link #EVENT_CAP} wait. */
    void queue(ResourceKey<Level> dimension, BlockPos at) {
        if (blasts.off() || detonations.size() >= EVENT_CAP) {
            return;
        }
        detonations.add(new Detonation(dimension, at.immutable()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pass.

    /** One ignition pass over every loaded dimension with a field; never throws. */
    void run(MinecraftServer server, PollutionService service, int now) {
        if (blasts.off()) {
            detonations.clear();
            return;
        }
        try {
            while (!detonations.isEmpty()) {
                var d = detonations.poll();
                long cube = PollutionService.cube(d.at());
                if (ignites(service.index(d.dimension(), cube, Channel.COMBUSTIBLE))) {
                    blasts.ignite(d.dimension(), cube, d.at(), now);
                }
            }
            FluidWorldAuthority world = null;
            try {
                world = FluidWorldAuthority.find(server).orElse(null);
            } catch (RuntimeException unread) {
                // No fluid world (or off its thread): the plant's flames are not read this pass.
            }
            for (ServerLevel level : server.getAllLevels()) {
                dimension(level, service, now, world);
            }
            blasts.succeeded();
        } catch (RuntimeException failure) {
            blasts.failed("ignition", failure);
        }
    }

    private void dimension(ServerLevel level, PollutionService service, int now, FluidWorldAuthority world) {
        var key = level.dimension();
        PollutionField field = service.field(key);
        int n = field.size();
        if (n == 0) {
            starts.remove(key);
            return;
        }
        var settings = service.settings();
        int start = rotate(starts.getOrDefault(key, 0), n);
        int searched = 0;
        int next = 0;
        Map<Long, List<BlockPos>> flames = null;
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            long cube = field.key(i);
            var cell = service.cell(key, cube);
            double air = cell.loaded() ? cell.airShare() : 1.0;
            if (!ignites(PollutionField.index(field.amount(i, Channel.COMBUSTIBLE), air, settings)) || blasts.waits(key, cube)) {
                continue;
            }
            int x = CubeKey.x(cube);
            int y = CubeKey.y(cube);
            int z = CubeKey.z(cube);
            LevelChunk chunk = loaded(level, x, z);
            if (chunk == null) {
                continue;
            }
            if (searched >= CUBE_BUDGET) {
                next = i;
                break;
            }
            searched++;
            BlockPos at = blockSource(chunk, x, y, z);
            if (at == null) {
                at = entitySource(level, x, y, z);
            }
            if (at == null && world != null) {
                if (flames == null) {
                    flames = new HashMap<>();
                }
                at = plantSource(flames, world, key, x, y, z);
            }
            if (at != null) {
                blasts.ignite(key, cube, at, now);
            }
        }
        starts.put(key, next);
    }

    private static LevelChunk loaded(ServerLevel level, int x, int z) {
        try {
            return level.getChunkSource().getChunkNow(x, z);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** The first source block of the cube's section, or null: the palette first, a scan of the section only when it may hold one. */
    static BlockPos blockSource(LevelChunk chunk, int x, int y, int z) {
        try {
            int index = chunk.getSectionIndexFromSectionY(y);
            if (index < 0 || index >= chunk.getSectionsCount()) {
                return null;
            }
            var section = chunk.getSection(index);
            if (section.hasOnlyAir() || !section.maybeHas(Ignition::source)) {
                return null;
            }
            for (int dy = 0; dy < 16; dy++) {
                for (int dz = 0; dz < 16; dz++) {
                    for (int dx = 0; dx < 16; dx++) {
                        if (source(section.getBlockState(dx, dy, dz))) {
                            return new BlockPos(x * 16 + dx, y * 16 + dy, z * 16 + dz);
                        }
                    }
                }
            }
            return null;
        } catch (RuntimeException failure) {
            // A damaged section reads as no source.
            return null;
        }
    }

    /** The block of the first igniting entity in the cube, or null. */
    private static BlockPos entitySource(ServerLevel level, int x, int y, int z) {
        try {
            var box = new AABB(x * 16.0, y * 16.0, z * 16.0, x * 16.0 + 16, y * 16.0 + 16, z * 16.0 + 16);
            var found = level.getEntities((Entity) null, box, Ignition::igniter);
            return found.isEmpty() ? null : found.get(0).blockPosition();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** The first of the plant's flames in the cube, from the fluid world's cached views, one read per chunk column and pass; or null. */
    private static BlockPos plantSource(Map<Long, List<BlockPos>> flames, FluidWorldAuthority world, ResourceKey<Level> key, int x, int y, int z) {
        try {
            long column = (long) x << 32 | (z & 0xFFFFFFFFL);
            var found = flames.get(column);
            if (found == null) {
                found = world.flames(key.location().toString(), x, z);
                flames.put(column, found);
            }
            for (var at : found) {
                if (at.getY() >> 4 == y) {
                    return at;
                }
            }
            return null;
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
