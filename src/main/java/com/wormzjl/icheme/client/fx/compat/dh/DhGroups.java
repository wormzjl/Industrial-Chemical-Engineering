package com.wormzjl.icheme.client.fx.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.wormzjl.icheme.client.fx.FxGuard;
import com.wormzjl.icheme.client.fx.silhouette.SilhouetteBox;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The calls into Distant Horizons' API 7 (batch 2026-11-10-custom-shader-system, W10, plan sections 4.7 and 4.8). Only
 * {@link DhBridge} calls this class, always through its guard, so a missing class or method is caught there; this class is
 * not even loaded before the first such call. The level load and unload events, which DH fires inside its own threads, run
 * through the same guard.
 * <ul>
 * <li>The client level's render register comes from DH's level load event (a dedicated server's client), or from the
 * client's current level as DH wraps it ({@link #poll}). In singleplayer DH fires the load event, and lists its levels,
 * only for the integrated server's level wrapper (a {@code SERVER_LEVEL}), never a {@code CLIENT_LEVEL}; the client's own
 * wrapper ({@code IDhApiWorldProxy.getSinglePlayerLevel}, on a client the wrapped client level) carries the render register
 * there (bench 2026-11-10, FX_BENCH_REVIEW.md note 4: no register, so no group was ever registered).</li>
 * <li>A group is positioned at its structure's origin ({@code createRelativePositionedGroup}), resource location
 * {@link #RESOURCE}, registered inactive.</li>
 * </ul>
 */
final class DhGroups {
    static final String RESOURCE="icheme:equipment";

    /** A registered group and the register that holds it. */
    record Handle(IDhApiRenderableBoxGroup group,IDhApiCustomRenderRegister register) {}

    private static volatile @Nullable IDhApiLevelWrapper level;
    private static volatile @Nullable FxGuard guard;
    private static boolean bound;

    private DhGroups(){}

    static int major(){return DhApi.getApiMajorVersion();}
    static int minor(){return DhApi.getApiMinorVersion();}
    static String version(){return DhApi.getModVersion()+", API "+DhApi.getApiMajorVersion()+"."+DhApi.getApiMinorVersion()+"."+DhApi.getApiPatchVersion();}

    /** Listens for DH's level load and unload, once per session. */
    static synchronized void bind(FxGuard calls) {
        guard=calls;
        if(bound)return;
        bound=true;
        DhApiEventRegister.on(DhApiLevelLoadEvent.class,new Load());
        DhApiEventRegister.on(DhApiLevelUnloadEvent.class,new Unload());
    }

    static final class Load extends DhApiLevelLoadEvent {
        @Override public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> input) {
            var g=guard;if(g!=null)g.run(()->{var w=input.value.levelWrapper;if(w!=null&&w.getLevelType()==EDhApiLevelType.CLIENT_LEVEL)level=w;});
        }
    }
    static final class Unload extends DhApiLevelUnloadEvent {
        @Override public void onLevelUnload(DhApiEventParam<DhApiLevelUnloadEvent.EventParam> input) {
            var g=guard;if(g!=null)g.run(()->{if(input.value.levelWrapper==level)level=null;});
        }
    }

    /** DH's chunk render distance in blocks; 0 before its config is ready. */
    static int radius() {
        var configs=DhApi.Delayed.configs;if(configs==null)return 0;
        Integer chunks=configs.graphics().chunkRenderDistance().getValue();
        return chunks==null?0:Math.max(0,chunks)*16;
    }

    /**
     * The client level's render register; null before DH's renderer is ready. A poll also follows the client to its current
     * level (a dimension change in singleplayer fires no client-level unload).
     */
    static @Nullable IDhApiCustomRenderRegister register(boolean poll) {
        var w=level;
        if(poll){var current=poll();if(current!=null)level=w=current;}
        // A wrapper whose DH level is gone (unloaded, a level change) has no register until the next poll finds the current one.
        return w==null?null:w.getRenderRegister();
    }
    /**
     * The client's current level as DH wraps it, if it has a render register: the client wrapper itself, or else a loaded
     * client level DH lists. Null when DH has no world loaded.
     */
    private static @Nullable IDhApiLevelWrapper poll() {
        var world=DhApi.Delayed.worldProxy;
        if(world==null)return null;
        try{
            if(!world.worldLoaded())return null;
            var current=world.getSinglePlayerLevel();
            if(current!=null&&current.getRenderRegister()!=null)return current;
            for(var w:world.getAllLoadedLevelWrappers())if(w!=null&&w.getLevelType()==EDhApiLevelType.CLIENT_LEVEL&&w.getRenderRegister()!=null)return w;
        }catch(IllegalStateException notYet){/* DH has no world loaded: try again later */}
        return null;
    }

    static boolean ready(){return DhApi.Delayed.customRenderObjectFactory!=null&&register(false)!=null;}

    /**
     * Registers {@code boxes} at {@code origin} as one inactive group: its {@link Handle}, typed {@code Object} so that
     * {@link DhBridge}'s descriptors name no class that holds a DH type; null when DH is not ready.
     */
    static @Nullable Object add(BlockPos origin,List<SilhouetteBox> boxes) {
        var factory=DhApi.Delayed.customRenderObjectFactory;var register=register(false);
        if(factory==null||register==null||boxes.isEmpty())return null;
        var list=new ArrayList<DhApiRenderableBox>(boxes.size());
        for(var b:boxes)list.add(new DhApiRenderableBox(new DhApiVec3d(b.x0(),b.y0(),b.z0()),new DhApiVec3d(b.x1(),b.y1(),b.z1()),new Color(b.rgb()),b.stone()?EDhApiBlockMaterial.STONE:EDhApiBlockMaterial.METAL));
        var group=factory.createRelativePositionedGroup(RESOURCE,new DhApiVec3d(origin.getX(),origin.getY(),origin.getZ()),list);
        group.setActive(false);
        register.add(group);
        return new Handle(group,register);
    }

    static void active(Object handle,boolean on){((Handle)handle).group().setActive(on);}
    static void remove(Object handle){var h=(Handle)handle;h.register().remove(h.group().getId());}
}
