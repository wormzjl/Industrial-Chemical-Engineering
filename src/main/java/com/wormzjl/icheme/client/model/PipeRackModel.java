package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.world.level.block.PipeRackBlock;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Chunk-meshed model of a pipe rack cell (batch 2026-10-26-pipe-laying-system, RACK_LAYERS_PLAN option A): the frame and
 * the lanes its block entity hands over as model data; {@link RackGeometry} builds the polygons and each key is baked
 * once. Without model data, the bare frame its block state gives, with a bent at each end; as an item, one facing north.
 */
public final class PipeRackModel implements IDynamicBakedModel {
    public static final ResourceLocation LOADER_ID = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "pipe_rack");
    /** {@code "loader": "icheme:pipe_rack"}, textures per material (the frame takes "steelwork" and its pedestals "concrete", the lanes "pipe"). */
    public static final IGeometryLoader<Geometry> LOADER = (json, context) -> new Geometry();
    private static final int CACHE = 4096;
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());

    /**
     * The cache key; {@code painted}: two bits per lane, its painted channels ({@link Paint#BODY} its pipe,
     * {@link Paint#STRUCTURE} its flanges), whose colours the block tint gives (tint index: twice the lane's option, plus
     * the channel); {@code spools}: a bit per lane with a flange pair between spools.
     */
    private record Look(RackGeometry.Rack rack, List<RackGeometry.Lane> lanes,List<RackGeometry.Lane> occupied, long painted, int spools) {
    }

    private final TextureAtlasSprite[][] paintedSprites;
    private final TextureAtlasSprite particle;
    private final ItemTransforms transforms;
    private final Map<Look, List<BakedQuad>> quads = new ConcurrentHashMap<>();

    private PipeRackModel(TextureAtlasSprite[][] paintedSprites, TextureAtlasSprite particle, ItemTransforms transforms) {
        this.paintedSprites = paintedSprites;
        this.particle = particle;
        this.transforms = transforms;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random, ModelData data, @Nullable RenderType renderType) {
        if (side != null) return List.of();
        var bare = state != null && state.getBlock() instanceof PipeRackBlock ? PipeRackBlock.rack(state) : new RackGeometry.Rack(2, RackGeometry.STRAIGHT);
        var drawn = data.get(PipeRackBlockEntity.DRAWN);
        long painted = 0;
        if (drawn != null) for (int i = 0; i < drawn.paints().size(); i++)
            painted |= (long) EquipmentShellModel.painted(drawn.paints().get(i), drawn.secondaries().get(i)) << 2 * i;
        var key = drawn == null ? new Look(RackGeometry.framed(bare, PipeRackBlockEntity.BARE, RackGeometry.BEAM_TOP), List.of(),List.of(), 0, 0)
                : new Look(drawn.rack(), drawn.lanes(),drawn.occupied(), painted, drawn.spools());
        var cached = quads.get(key);
        if (cached == null) {
            if (quads.size() >= CACHE) quads.clear();
            cached = quads.computeIfAbsent(key, this::bake);
        }
        return cached;
    }

    /**
     * The frame (its braces left out where a lane passes), then each lane with its flanges, as a laid pipe draws them:
     * its painted channels with the neutral sprites, tinted by twice its option plus the channel.
     */
    private List<BakedQuad> bake(Look look) {
        var out = new ArrayList<>(EquipmentShellModel.bake(RackGeometry.polygons(look.rack, look.occupied), paintedSprites[0], false));
        for (int i = 0; i < look.lanes.size(); i++) {
            var lane = look.lanes.get(i);
            var polygons = RackGeometry.lane(look.rack, lane, (look.spools >> i & 1) != 0);
            int painted = (int) (look.painted >> 2 * i & 3);
            out.addAll(EquipmentShellModel.bake(polygons, paintedSprites[painted], EquipmentShellModel.lineTints(painted, 2 * lane.option())));
        }
        return List.copyOf(out);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return SOLID;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particle;
    }

    @Override
    public ItemTransforms getTransforms() {
        return transforms;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    public record Geometry() implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state, ItemOverrides overrides) {
            var bare = EquipmentShellModel.sprites(context, sprites);
            return new PipeRackModel(EquipmentShellModel.paintedLines(context, sprites, bare), sprites.apply(context.getMaterial("particle")), context.getTransforms());
        }
    }
}
