package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.SightGlassGeometry;
import com.wormzjl.icheme.science.equipment.ValveGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
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
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Chunk-meshed model of a pipe or pipeline device (batch 2026-10-15-custom-equipment-rendering, owner request
 * 2026-10-16). Its key is the block state (kind, facing, connections) and the drawn geometry its block entity hands over
 * as model data (bore, the line met at each face, flanged joints, spool end); {@link LineGeometry} builds the polygons
 * and each key is baked once. Without model data the block draws at the default bore with nothing met; as an item, at
 * {@link LineGeometry.Key#item}.
 */
public final class FluidDeviceModel implements IDynamicBakedModel {
    public static final ResourceLocation LOADER_ID = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "fluid_device");
    /** {@code "loader": "icheme:fluid_device", "kind": "pipe"} (a {@link TopologyCompiler.Kind}), textures per material. */
    public static final IGeometryLoader<Geometry> LOADER = (json, context) ->
            new Geometry(TopologyCompiler.Kind.valueOf(GsonHelper.getAsString(json, "kind").toUpperCase(Locale.ROOT)),GsonHelper.getAsBoolean(json,"sight_glass_accessory",false),GsonHelper.getAsBoolean(json,"shutoff_valve_accessory",false),GsonHelper.getAsBoolean(json,"check_valve_accessory",false),GsonHelper.getAsBoolean(json,"pressure_valve_accessory",false),GsonHelper.getAsBoolean(json,"inline_mover_accessory",false));
    private static final int CACHE = 4096;
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    private static final ChunkRenderTypeSet GLASS = ChunkRenderTypeSet.of(RenderType.solid(),RenderType.translucent());
    private final com.wormzjl.icheme.science.equipment.RenderDetail detail = com.wormzjl.icheme.client.ClientConfig.renderDetail();
    private final TopologyCompiler.Kind kind;
    private final boolean accessory;
    private final boolean shutoffAccessory;
    private final boolean checkAccessory;
    private final boolean pressureAccessory;
    private final boolean moverAccessory;
    private final TextureAtlasSprite[] sprites;
    /** {@link #sprites} with the neutral pipe and flange sprites a painted line's tint colours, per painted mask. */
    private final TextureAtlasSprite[][] paintedSprites;
    private final TextureAtlasSprite particle;
    private final ItemTransforms transforms;
    /**
     * The cache key; {@code painted}: a bit per paint a laid line has ({@link Paint#BODY}, its pipe; {@link Paint#STRUCTURE},
     * its flanges), whose colours the block tint gives, so one bake serves every colour.
     */
    private record Look(SightGlassGeometry.Key geometry,boolean sightGlass,FluidDeviceBlock.Shutoff shutoff,boolean pressureValve,boolean filter,TopologyCompiler.Kind mover,int painted){}
    private record Baked(List<BakedQuad> solid,List<BakedQuad> glass){}
    private final Map<Look,Baked> quads = new ConcurrentHashMap<>();

    private FluidDeviceModel(TopologyCompiler.Kind kind,boolean accessory,boolean shutoffAccessory,boolean checkAccessory,boolean pressureAccessory,boolean moverAccessory, TextureAtlasSprite[] sprites, TextureAtlasSprite[][] paintedSprites, TextureAtlasSprite particle, ItemTransforms transforms) {
        this.paintedSprites = paintedSprites;
        this.kind = kind;
        this.accessory=accessory;
        this.shutoffAccessory=shutoffAccessory;
        this.checkAccessory=checkAccessory;
        this.pressureAccessory=pressureAccessory;
        this.moverAccessory=moverAccessory;
        this.sprites = sprites;
        this.particle = particle;
        this.transforms = transforms;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random, ModelData data, @Nullable RenderType renderType) {
        if (side != null) return List.of();
        var geometry=state!=null&&state.getBlock() instanceof FluidDeviceBlock?sightGlassKey(state,data):new SightGlassGeometry.Key(LineGeometry.Key.item(kind));
        var shutoff=checkAccessory?FluidDeviceBlock.Shutoff.CHECK:shutoffAccessory?FluidDeviceBlock.Shutoff.OPEN:state!=null&&FluidDeviceBlock.hasPipeValve(state)?state.getValue(FluidDeviceBlock.SHUTOFF):FluidDeviceBlock.Shutoff.NONE;
        var key = new Look(geometry,accessory||state!=null&&FluidDeviceBlock.hasSightGlass(state),shutoff,pressureAccessory||state!=null&&FluidDeviceBlock.hasPressureValve(state),state!=null&&FluidDeviceBlock.hasFilter(state),state!=null&&FluidDeviceBlock.hasMover(state)?state.getValue(FluidDeviceBlock.INLINE_MOVER).kind():moverAccessory?kind:TopologyCompiler.Kind.PIPE,
                state!=null&&data.get(FluidDeviceBlockEntity.DRAWN) instanceof FluidDeviceBlockEntity.Drawn drawn?EquipmentShellModel.painted(drawn.paint(),drawn.secondary()):0);
        var cached = quads.get(key);
        if(cached==null){if(quads.size()>=CACHE)quads.clear();cached=quads.computeIfAbsent(key,this::bake);}
        if(renderType==RenderType.translucent())return cached.glass;
        if(renderType!=null)return cached.solid;
        return java.util.stream.Stream.concat(cached.solid.stream(),cached.glass.stream()).toList();
    }

    private Baked bake(Look look) {
        if(look.mover!=TopologyCompiler.Kind.PIPE)return new Baked(quads(look,com.wormzjl.icheme.science.equipment.InlineMoverGeometry.polygons(look.geometry.pipe(),look.mover==TopologyCompiler.Kind.COMPRESSOR,moverAccessory,detail)),List.of());
        if(look.filter)return new Baked(quads(look,LineGeometry.filterPolygons(look.geometry.pipe(),detail)),List.of());
        if(look.pressureValve)return new Baked(quads(look,ValveGeometry.pressure(look.geometry.pipe(),pressureAccessory,detail)),List.of());
        if(look.shutoff==FluidDeviceBlock.Shutoff.CHECK)return new Baked(quads(look,ValveGeometry.check(look.geometry.pipe(),checkAccessory,detail)),List.of());
        if(look.shutoff!=FluidDeviceBlock.Shutoff.NONE)return new Baked(quads(look,ValveGeometry.shutoff(look.geometry.pipe(),look.shutoff==FluidDeviceBlock.Shutoff.OPEN,shutoffAccessory,detail)),List.of());
        if(!look.sightGlass)return new Baked(quads(look,LineGeometry.polygons(look.geometry.pipe(),detail)),List.of());
        var geometry=SightGlassGeometry.build(look.geometry,accessory,detail);
        return new Baked(quads(look,geometry.metal()),EquipmentShellModel.bake(geometry.glass(),sprites,false));
    }

    /** A pipeline device is never painted, but a painted line's pipe and flanges take its paints through the block tint (tint index: the paint's channel). */
    private List<BakedQuad> quads(Look look,List<com.wormzjl.icheme.science.equipment.Mesh.Polygon> polygons) {
        return EquipmentShellModel.bake(polygons,paintedSprites[look.painted],EquipmentShellModel.lineTints(look.painted,0));
    }

    /** Installation orientation is part of both the chunk-model and liquid-surface cache key. */
    public static SightGlassGeometry.Key sightGlassKey(BlockState state,ModelData data) {
        return new SightGlassGeometry.Key(blockKey(state,data),SightGlassGeometry.Axis.valueOf(state.getValue(FluidDeviceBlock.SIGHT_GLASS_AXIS).name()));
    }

    /** The same diameter/connection key for chunk geometry and the transient liquid overlay. */
    public static LineGeometry.Key blockKey(BlockState state,ModelData data) {
        var block=(FluidDeviceBlock)state.getBlock();
        int connections = FluidDeviceBlock.connectionMask(state);
        int facing = state.getValue(FluidDeviceBlock.FACING).ordinal();
        var drawn = data.get(FluidDeviceBlockEntity.DRAWN);
        if (drawn == null) return new LineGeometry.Key(block.kind(), facing, connections, PipeSizes.code(PipeSizes.outside(.05)), 0, 0, false);
        return new LineGeometry.Key(block.kind(), facing, connections, PipeSizes.code(PipeSizes.outside(drawn.bore())), drawn.faces(), drawn.flanged(), drawn.spool(), drawn.offsets());
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        return FluidDeviceBlock.hasSightGlass(state)?GLASS:SOLID;
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

    public record Geometry(TopologyCompiler.Kind kind,boolean accessory,boolean shutoffAccessory,boolean checkAccessory,boolean pressureAccessory,boolean moverAccessory) implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state, ItemOverrides overrides) {
            var bare=EquipmentShellModel.sprites(context, sprites);
            return new FluidDeviceModel(kind,accessory,shutoffAccessory,checkAccessory,pressureAccessory,moverAccessory, bare, EquipmentShellModel.paintedLines(context, sprites, bare), sprites.apply(context.getMaterial("particle")), context.getTransforms());
        }
    }
}
