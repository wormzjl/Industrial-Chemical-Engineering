package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.*;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.*;
import org.jetbrains.annotations.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class ConduitModel implements IDynamicBakedModel {
    public static final ResourceLocation LOADER_ID=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"data_cable");
    public static final IGeometryLoader<Geometry> LOADER=(json,context)->new Geometry(json.has("medium")?CableMedium.valueOf(json.get("medium").getAsString()):CableMedium.DATA);
    private static final ChunkRenderTypeSet SOLID=ChunkRenderTypeSet.of(RenderType.solid());
    private final CableMedium medium;
    private final TextureAtlasSprite sprite;
    private final TextureAtlasSprite fitting,opening,marker;
    private final ItemTransforms transforms;
    private record Look(ConduitGeometry.Key geometry,boolean marked){}
    private final Map<Look,List<BakedQuad>> cache=new ConcurrentHashMap<>();
    private ConduitModel(CableMedium medium,TextureAtlasSprite sprite,TextureAtlasSprite fitting,TextureAtlasSprite opening,TextureAtlasSprite marker,ItemTransforms transforms){this.medium=medium;this.sprite=sprite;this.fitting=fitting;this.opening=opening;this.marker=marker;this.transforms=transforms;}
    @Override public List<BakedQuad> getQuads(@Nullable BlockState state,@Nullable Direction side,RandomSource random,ModelData data,@Nullable RenderType type){
        if(side!=null)return List.of();
        var key=data.get(ConduitBlockEntity.GEOMETRY);if(key==null)key=new ConduitGeometry.Key(0,0,medium);
        if(cache.size()>2048)cache.clear();
        var look=new Look(key,!Boolean.FALSE.equals(data.get(ConduitBlockEntity.MARKED)));
        return cache.computeIfAbsent(look,l->{var k=l.geometry();var sprites=new TextureAtlasSprite[Mesh.Material.values().length];Arrays.fill(sprites,sprite);
            sprites[Mesh.Material.FLANGE.ordinal()]=fitting;sprites[Mesh.Material.BOLT.ordinal()]=fitting;sprites[Mesh.Material.OPENING.ordinal()]=opening;
            sprites[Mesh.Material.GUARD.ordinal()]=marker;
            return EquipmentShellModel.bake(ConduitGeometry.polygons(k,l.marked()),sprites,material->-1,material->material==Mesh.Material.GUARD?k.medium().colour():0xffffff);});
    }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state,RandomSource random,ModelData data){return SOLID;}
    @Override public boolean useAmbientOcclusion(){return false;}
    @Override public boolean isGui3d(){return true;}
    @Override public boolean usesBlockLight(){return true;}
    @Override public boolean isCustomRenderer(){return false;}
    @Override public TextureAtlasSprite getParticleIcon(){return sprite;}
    @Override public ItemTransforms getTransforms(){return transforms;}
    @Override public ItemOverrides getOverrides(){return ItemOverrides.EMPTY;}
    public record Geometry(CableMedium medium) implements IUnbakedGeometry<Geometry> {
        @Override public BakedModel bake(IGeometryBakingContext context,ModelBaker baker,Function<Material,TextureAtlasSprite> sprites,ModelState state,ItemOverrides overrides){
            return new ConduitModel(medium,sprites.apply(context.getMaterial("cable")),sprites.apply(context.getMaterial("fitting")),sprites.apply(context.getMaterial("opening")),sprites.apply(context.getMaterial("marker")),context.getTransforms());
        }
    }
}
