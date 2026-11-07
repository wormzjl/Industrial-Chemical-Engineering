package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.MachineAnimation;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PortMarkings;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
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
import net.neoforged.neoforge.client.model.lighting.QuadLighter;
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Chunk-meshed model of a vessel shell cell (batch 2026-10-15-custom-equipment-rendering, route A1). The cell's key
 * arrives as model data from its block entity; the quads of a key are built once from {@link ShapeRasterizer#surface}
 * and cached, and body cells share one key per column. Quads stay inside the cell, carry smooth vertex normals and have
 * no ambient occlusion; their directional shade is baked into the vertex colour from the smooth normal (owner report
 * 2026-10-16: the per-quad six-step shade banded a round shell), so vanilla and Sodium shade them alike.
 * Without model data (Distant Horizons asks that way) the model is empty and the particle sprite gives the colour.
 * Units' moving parts are left out ({@link ShapeRasterizer#fixedSurface}): {@link MachineAnimation} draws them, moving or
 * at rest, so its switch meshes no chunk again. A cell caches solid and glass layers separately; only cells containing
 * glass request the translucent chunk pass.
 */
public final class EquipmentShellModel implements IDynamicBakedModel {
    public static final ResourceLocation LOADER_ID = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "equipment_shell");
    public static final IGeometryLoader<Geometry> LOADER = (json, context) -> new Geometry();
    private static final int CACHE = 4096;
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    private static final ChunkRenderTypeSet GLASS = ChunkRenderTypeSet.of(RenderType.solid(), RenderType.translucent());
    private final TextureAtlasSprite[] sprites;
    private final TextureAtlasSprite particle;
    /** Rebuilt with the model on every resource reload, since quads embed atlas coordinates. Meshing threads share them. */
    private final Map<ShapeRasterizer.CellKey, Layers> quads = new ConcurrentHashMap<>();
    /** The sprites of the model last baked, for parts drawn outside the chunk mesh ({@link #sprite}). */
    private static volatile TextureAtlasSprite @Nullable [] current;

    private EquipmentShellModel(TextureAtlasSprite[] sprites, TextureAtlasSprite particle) {
        this.sprites = sprites;
        this.particle = particle;
        current = sprites;
    }

    /** The sprite of {@code material} in the model last baked, or null before the first bake. */
    public static @Nullable TextureAtlasSprite sprite(Mesh.Material material) {
        var sprites = current;
        return sprites == null ? null : sprites[material.ordinal()];
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random, ModelData data, @Nullable RenderType renderType) {
        var key = data.get(EquipmentShellBlockEntity.CELL);
        if (side != null || key == null) return List.of();
        var layers = baked(key);
        if (renderType == RenderType.translucent()) return layers.glass();
        return renderType == null ? layers.all() : layers.solid();
    }

    private Layers baked(ShapeRasterizer.CellKey key) {
        var baked = quads.get(key);
        if (baked == null) {
            if (quads.size() >= CACHE) quads.clear();
            baked = quads.computeIfAbsent(key, this::bake);
        }
        return baked;
    }

    /** A cell's quads without its moving parts. */
    private record Layers(List<BakedQuad> solid, List<BakedQuad> glass, List<BakedQuad> all) {
    }

    /**
     * Each convex polygon becomes a fan of quads; a last single triangle repeats its final vertex. A painted material's
     * quads carry its paint channel as their tint index, so the cell's paint colours them as the chunk is meshed.
     */
    private Layers bake(ShapeRasterizer.CellKey key) {
        return layers(ShapeRasterizer.chunkSurface(key), bake(PortMarkings.surface(key), sprites, true));
    }

    private Layers layers(List<Mesh.Polygon> polygons, List<BakedQuad> markings) {
        var solid = marked(bake(polygons.stream().filter(p -> p.material() != Mesh.Material.GLASS).toList(), sprites, true), markings);
        var glass = bake(polygons.stream().filter(p -> p.material() == Mesh.Material.GLASS).toList(), sprites, true);
        return new Layers(solid, glass, marked(solid, glass));
    }

    private static List<BakedQuad> marked(List<BakedQuad> surface,List<BakedQuad> markings) {
        if(markings.isEmpty())return surface;
        var out=new ArrayList<BakedQuad>(surface.size()+markings.size());out.addAll(surface);out.addAll(markings);
        return List.copyOf(out);
    }

    /**
     * Each convex polygon as a fan of quads, a last single triangle repeating its final vertex; sprites by material, and
     * {@code painted}: tinted by the material's paint channel (a vessel's), or never (a pipeline device's).
     */
    public static List<BakedQuad> bake(List<Mesh.Polygon> polygons, TextureAtlasSprite[] sprites, boolean painted) {
        java.util.function.ToIntFunction<Mesh.Material> tints = painted ? Mesh.Material::channel : material -> -1;
        return bake(polygons, sprites, tints);
    }

    /** As {@link #bake(List, TextureAtlasSprite[], boolean)}, each material's quads with the tint index {@code tint} gives it (-1: none). */
    public static List<BakedQuad> bake(List<Mesh.Polygon> polygons, TextureAtlasSprite[] sprites, java.util.function.ToIntFunction<Mesh.Material> tints) {
        return bake(polygons,sprites,tints,material->0xffffff);
    }

    /** Fixed material colours multiplied by the same lighting as uncoloured surfaces, without a block tint lookup. */
    public static List<BakedQuad> bake(List<Mesh.Polygon> polygons, TextureAtlasSprite[] sprites, java.util.function.ToIntFunction<Mesh.Material> tints, java.util.function.ToIntFunction<Mesh.Material> colours) {
        var out = new ArrayList<BakedQuad>();
        for (var polygon : polygons) {
            int tint = tints.applyAsInt(polygon.material());
            for (int first = 1; first + 1 < polygon.size(); first += 2)
                out.add(quad(polygon, sprites[polygon.material().ordinal()], tint, colours.applyAsInt(polygon.material()), 0, first, first + 1, Math.min(first + 2, polygon.size() - 1)));
        }
        return List.copyOf(out);
    }

    /**
     * A painted line's sprites for every {@link #painted} mask, indexed by it (index 0 is {@code bare}): {@code bare} with
     * its painted parts swapped for the neutral sprites the line's tint colours, {@code painted_pipe} for the primary
     * ({@link Paint#BODY}, the pipe) and {@code painted_flange} for the secondary ({@link Paint#STRUCTURE}, the flanges).
     */
    public static TextureAtlasSprite[][] paintedLines(IGeometryBakingContext context, Function<Material, TextureAtlasSprite> sprites, TextureAtlasSprite[] bare) {
        var out = new TextureAtlasSprite[4][];
        for (int painted = 0; painted < 4; painted++) {
            out[painted] = bare.clone();
            if ((painted >> Paint.BODY & 1) != 0 && context.hasMaterial("painted_pipe")) out[painted][Mesh.Material.PIPE.ordinal()] = sprites.apply(context.getMaterial("painted_pipe"));
            if ((painted >> Paint.STRUCTURE & 1) != 0 && context.hasMaterial("painted_flange")) out[painted][Mesh.Material.FLANGE.ordinal()] = sprites.apply(context.getMaterial("painted_flange"));
        }
        return out;
    }

    /** A line's painted mask: a bit per channel with a paint, {@link Paint#BODY} for {@code paint} and {@link Paint#STRUCTURE} for {@code secondary}. */
    public static int painted(int paint, int secondary) {
        return (paint != Paint.BARE ? 1 << Paint.BODY : 0) | (secondary != Paint.BARE ? 1 << Paint.STRUCTURE : 0);
    }

    /**
     * The tint indices a line's quads take: its pipe {@code tint} + {@link Paint#BODY} and its flanges {@code tint} +
     * {@link Paint#STRUCTURE}, each where {@code painted} has that channel; bolts, valves and the rest keep their colours.
     */
    public static java.util.function.ToIntFunction<Mesh.Material> lineTints(int painted, int tint) {
        return material -> material == Mesh.Material.PIPE && (painted >> Paint.BODY & 1) != 0 ? tint + Paint.BODY
                : material == Mesh.Material.FLANGE && (painted >> Paint.STRUCTURE & 1) != 0 ? tint + Paint.STRUCTURE : -1;
    }

    /** One sprite per material from the model's textures (named {@link Mesh.Material#texture()}), the particle where one is missing. */
    public static TextureAtlasSprite[] sprites(IGeometryBakingContext context, Function<Material, TextureAtlasSprite> sprites) {
        var particle = sprites.apply(context.getMaterial("particle"));
        var out = new TextureAtlasSprite[Mesh.Material.values().length];
        for (var material : Mesh.Material.values())
            out[material.ordinal()] = context.hasMaterial(material.texture()) ? sprites.apply(context.getMaterial(material.texture())) : particle;
        return out;
    }

    /**
     * The round bodies that take the metallic sheen ({@code IChemE.equipmentSheen}; owner request 2026-10-17,
     * {@code METALLIC_LOOK_RESEARCH.md}): shells, heads, cladding, skirts, heater casings, machine and motor bodies, a pump's castings, housing and joints, a motor's frame and cast parts. Pipes and
     * flanges have too few facets: their highlight would jump from facet to facet.
     */
    private static final Set<Mesh.Material> SHEEN = EnumSet.of(Mesh.Material.SHELL, Mesh.Material.HEAD, Mesh.Material.CLADDING, Mesh.Material.SUPPORT,
            Mesh.Material.HEATER, Mesh.Material.MACHINE, Mesh.Material.MOTOR, Mesh.Material.AC_HEADER, Mesh.Material.PUMP_CASTING, Mesh.Material.PUMP_VOLUTE,
            Mesh.Material.PUMP_FINS, Mesh.Material.PUMP_JOINT, Mesh.Material.MOTOR_FRAME, Mesh.Material.MOTOR_PLAIN);

    /**
     * A fixed highlight over the shade of a round body's vertex with normal (nx, nz): {@code k |nz|^8} and half as much
     * {@code |nx|^8}, so a round body seen from the north or south shows a bright band about 40° wide over its middle, from
     * the east or west a weaker one, and bright rims where its side turns away. It does not follow the view (no
     * reflection) and costs nothing per frame; the power is one for every material, so the pieces of one facet cut by cells
     * agree at their seams.
     */
    static float sheen(float k, float nx, float nz) {
        float x2 = nx * nx, z2 = nz * nz, x8 = x2 * x2 * x2 * x2, z8 = z2 * z2 * z2 * z2;
        return k * (z8 + .5F * x8);
    }

    /**
     * A vertex's shade, 0 to 255, as the chunk mesh bakes it into the vertex colour: the directional shade of its smooth
     * normal, by the formula NeoForge uses for its own normal-based lighting, and on a curved polygon (its vertex normals
     * differ) of a round body the sheen; flat faces keep their shade. Moving parts are baked the same way.
     */
    public static int shade(Mesh.Polygon polygon, int corner) {
        double[] n = polygon.normals();
        boolean curved = false;
        for (int i = 1; i < polygon.size() && !curved; i++)
            curved = Math.abs(n[3 * i] - n[0]) + Math.abs(n[3 * i + 1] - n[1]) + Math.abs(n[3 * i + 2] - n[2]) > 1e-4;
        float k = curved && SHEEN.contains(polygon.material()) ? IChemE.equipmentSheen() : 0;
        float nx = (float) n[3 * corner], ny = (float) n[3 * corner + 1], nz = (float) n[3 * corner + 2];
        return Math.round(255 * Math.min(1, QuadLighter.calculateShade(nx, ny, nz, false) + (k > 0 ? sheen(k, nx, nz) : 0)));
    }

    private static BakedQuad quad(Mesh.Polygon polygon, TextureAtlasSprite sprite, int tint, int colour, int... corners) {
        double[] p = polygon.positions(), uv = polygon.uvs(), n = polygon.normals();
        // Face direction from the corners' winding (Newell), for vanilla's directional shade.
        double fx = 0, fy = 0, fz = 0;
        for (int i = 0; i < 4; i++) {
            int a = 3 * corners[i], b = 3 * corners[(i + 1) % 4];
            fx += (p[a + 1] - p[b + 1]) * (p[a + 2] + p[b + 2]);
            fy += (p[a + 2] - p[b + 2]) * (p[a] + p[b]);
            fz += (p[a] - p[b]) * (p[a + 1] + p[b + 1]);
        }
        var baker = new QuadBakingVertexConsumer();
        baker.setSprite(sprite);
        baker.setTintIndex(tint);
        baker.setDirection(Direction.getNearest(fx, fy, fz));
        // The directional shade is baked into each vertex from its smooth normal, by the formula NeoForge uses for its own
        // normal-based lighting (an axis-facing face shades exactly as a block face does). Vanilla's per-quad shade would
        // band a round shell in six steps, so the quad asks for none; vanilla and Sodium both multiply the vertex colour.
        baker.setShade(false);
        baker.setHasAmbientOcclusion(false);
        for (int c : corners) {
            int shade = shade(polygon, c);
            baker.addVertex((float) p[3 * c], (float) p[3 * c + 1], (float) p[3 * c + 2])
                      .setColor(shade*(colour>>16&255)/255, shade*(colour>>8&255)/255, shade*(colour&255)/255, 255)
                    .setUv(sprite.getU((float) uv[2 * c]), sprite.getV((float) uv[2 * c + 1]))
                    .setNormal((float) n[3 * c], (float) n[3 * c + 1], (float) n[3 * c + 2]);
        }
        return baker.bakeQuad();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
        var key = data.get(EquipmentShellBlockEntity.CELL);
        return key != null && !baked(key).glass().isEmpty() ? GLASS : SOLID;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return false;
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
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    /** {@code "loader": "icheme:equipment_shell"} with a texture per material ({@link #sprites}) and {@code particle}. */
    public record Geometry() implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state, ItemOverrides overrides) {
            return new EquipmentShellModel(sprites(context, sprites), sprites.apply(context.getMaterial("particle")));
        }
    }
}
