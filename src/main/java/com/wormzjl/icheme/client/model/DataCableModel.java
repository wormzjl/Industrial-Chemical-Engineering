package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.entity.DataCableBlockEntity;
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

public final class DataCableModel implements IDynamicBakedModel {
    public static final ResourceLocation LOADER_ID=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,"data_cable");
    public static final IGeometryLoader<Geometry> LOADER=(json,context)->new Geometry();
    private static final ChunkRenderTypeSet SOLID=ChunkRenderTypeSet.of(RenderType.solid());
    private final TextureAtlasSprite sprite;
    private final ItemTransforms transforms;
    private final Map<DataCableGeometry.Key,List<BakedQuad>> cache=new ConcurrentHashMap<>();
    private DataCableModel(TextureAtlasSprite sprite,ItemTransforms transforms){this.sprite=sprite;this.transforms=transforms;}
    @Override public List<BakedQuad> getQuads(@Nullable BlockState state,@Nullable Direction side,RandomSource random,ModelData data,@Nullable RenderType type){
        if(side!=null)return List.of();
        var key=data.get(DataCableBlockEntity.GEOMETRY);if(key==null)key=new DataCableGeometry.Key(0,0);
        if(cache.size()>2048)cache.clear();
        return cache.computeIfAbsent(key,k->{var sprites=new TextureAtlasSprite[Mesh.Material.values().length];Arrays.fill(sprites,sprite);
            return EquipmentShellModel.bake(DataCableGeometry.polygons(k),sprites,false);});
    }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state,RandomSource random,ModelData data){return SOLID;}
    @Override public boolean useAmbientOcclusion(){return false;}
    @Override public boolean isGui3d(){return true;}
    @Override public boolean usesBlockLight(){return true;}
    @Override public boolean isCustomRenderer(){return false;}
    @Override public TextureAtlasSprite getParticleIcon(){return sprite;}
    @Override public ItemTransforms getTransforms(){return transforms;}
    @Override public ItemOverrides getOverrides(){return ItemOverrides.EMPTY;}
    public record Geometry() implements IUnbakedGeometry<Geometry> {
        @Override public BakedModel bake(IGeometryBakingContext context,ModelBaker baker,Function<Material,TextureAtlasSprite> sprites,ModelState state,ItemOverrides overrides){
            return new DataCableModel(sprites.apply(context.getMaterial("cable")),context.getTransforms());
        }
    }
}
