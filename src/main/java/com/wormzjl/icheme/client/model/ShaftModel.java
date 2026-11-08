package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.RotationAnimation;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ShaftGeometry;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Immutable hexagonal shaft model. Items use its quads; world shafts are drawn by the animation
 * batch.
 */
public final class ShaftModel implements IDynamicBakedModel {
    public static final ResourceLocation LOADER_ID =
            ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "shaft");
    public static final IGeometryLoader<Geometry> LOADER =
            (json, context) -> new Geometry(json.get("size").getAsInt());
    private static final ModelProperty<Boolean> ANIMATED = new ModelProperty<>();
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    private final List<List<BakedQuad>> quads;
    private final TextureAtlasSprite sprite;
    private final ItemTransforms transforms;

    private ShaftModel(int pixels, TextureAtlasSprite sprite, ItemTransforms transforms) {
        var sprites = new TextureAtlasSprite[Mesh.Material.values().length];
        Arrays.fill(sprites, sprite);
        this.quads =
                java.util.stream.IntStream.range(0, 3)
                        .mapToObj(
                                axis ->
                                        EquipmentShellModel.bake(
                                                ShaftGeometry.polygons(axis, pixels),
                                                sprites,
                                                false))
                        .toList();
        this.sprite = sprite;
        this.transforms = transforms;
    }

    @Override
    public ModelData getModelData(
            BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        // Chunk compilation may run on a worker. Only immutable positions enter the client-thread
        // handoff.
        RotationAnimation.discover(pos);
        return data.derive().with(ANIMATED, true).build();
    }

    @Override
    public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource random,
            ModelData data,
            @Nullable RenderType type) {
        if (side != null || Boolean.TRUE.equals(data.get(ANIMATED))) return List.of();
        int axis = state == null ? 1 : state.getValue(RotatedPillarBlock.AXIS).ordinal();
        return quads.get(axis);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(
            BlockState state, RandomSource random, ModelData data) {
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
        return sprite;
    }

    @Override
    public ItemTransforms getTransforms() {
        return transforms;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    public record Geometry(int pixels) implements IUnbakedGeometry<Geometry> {
        public Geometry {
            if (pixels < 2 || pixels > 8) throw new IllegalArgumentException("Invalid shaft size");
        }

        @Override
        public BakedModel bake(
                IGeometryBakingContext context,
                ModelBaker baker,
                Function<Material, TextureAtlasSprite> sprites,
                ModelState state,
                ItemOverrides overrides) {
            return new ShaftModel(
                    pixels, sprites.apply(context.getMaterial("shaft")), context.getTransforms());
        }
    }
}
