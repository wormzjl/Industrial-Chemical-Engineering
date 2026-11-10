package com.wormzjl.icheme.client.fx.state;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.EquipmentNetwork;
import com.wormzjl.icheme.runtime.fluid.Silhouettes;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The client's far silhouette records (batch 2026-11-10-custom-shader-system, W10, plan section 4.7): the placed structures
 * the server's registry holds within the subscribed radius, by origin, as {@link EquipmentNetwork.SilhouetteBatch}es bring
 * them. Client thread only. The client asks for them only when its {@link Listener} (the Distant Horizons integration) says
 * so ({@link #subscribe}, {@link #unsubscribe}); a level change or a disconnect ends the subscription, and the listener hears
 * {@link Listener#cleared} and subscribes again for the new level when it wants it.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class SilhouetteStore {
    /** What the far renderer does with the records. Called on the client thread; an exception detaches the listener. */
    public interface Listener {
        /** A structure arrived, or its shape or paint changed: replace what is held at its origin. */
        void placed(Silhouettes.Placement placement);
        /** The structure at {@code origin} left the subscription (removed, or now beyond it). */
        void removed(BlockPos origin);
        /** Every record is dropped: a new full set follows, or the subscription ended (level change, disconnect, unsubscribe). */
        void cleared();
    }

    private static final Map<BlockPos,Silhouettes.Placement> KNOWN=new LinkedHashMap<>();
    private static @Nullable Listener listener;
    private static boolean subscribed;
    private static int radius;
    private static ClientLevel world;
    private SilhouetteStore(){}

    /** Sets the listener (null: none). It hears what arrives from now on; {@link #known} holds what came before. */
    public static void listen(@Nullable Listener value){listener=value;}

    /** Asks the server for the structures within {@code blocks} (at most {@link Silhouettes#MAXIMUM_RADIUS}); its full set replaces what is held. */
    public static void subscribe(int blocks) {
        var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.level==null)return;
        radius=Silhouettes.radius(blocks);subscribed=true;
        EquipmentNetwork.watchSilhouettes(radius,true);
    }
    /** Ends the subscription and drops every record. */
    public static void unsubscribe() {
        boolean was=subscribed;subscribed=false;
        if(was&&Minecraft.getInstance().getConnection()!=null)EquipmentNetwork.watchSilhouettes(0,false);
        drop(was);
    }
    public static boolean subscribed(){return subscribed;}
    /** The radius last subscribed, blocks. */
    public static int radius(){return radius;}
    /** The records held, by origin: a copy. */
    public static Map<BlockPos,Silhouettes.Placement> known(){return Map.copyOf(KNOWN);}

    /** A batch from the server: kept only while subscribed. */
    public static void accept(EquipmentNetwork.SilhouetteBatch batch) {
        if(!subscribed)return;
        if(batch.reset())drop(true);
        for(var p:batch.placed()){KNOWN.put(p.origin(),p);tell(l->l.placed(p));}
        for(var origin:batch.removed())if(KNOWN.remove(origin)!=null)tell(l->l.removed(origin));
    }

    private static void drop(boolean notify){KNOWN.clear();if(notify)tell(Listener::cleared);}
    private static void tell(java.util.function.Consumer<Listener> call) {
        var l=listener;if(l==null)return;
        try{call.accept(l);}
        catch(RuntimeException|LinkageError failure){
            listener=null;
            IChemE.LOGGER.warn("Far silhouette listener failed and is detached for this session",failure);
        }
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(world==mc.level)return;
        world=mc.level;
        // The server ends a subscription at a dimension change or a disconnect; the client drops its records with it.
        // A new level of the same dimension (a respawn) keeps the server's: end it, before the listener asks again.
        boolean was=subscribed;subscribed=false;
        if(was&&mc.getConnection()!=null)EquipmentNetwork.watchSilhouettes(0,false);
        drop(was||!KNOWN.isEmpty());
    }
}
