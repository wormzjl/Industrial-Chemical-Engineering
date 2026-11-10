package com.wormzjl.icheme.client.fx.silhouette;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.fx.FxCompat;
import com.wormzjl.icheme.client.fx.FxGuard;
import com.wormzjl.icheme.client.fx.state.SilhouetteStore;
import com.wormzjl.icheme.runtime.fluid.Silhouettes;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Painted equipment silhouettes in Distant Horizons' far terrain (batch 2026-11-10-custom-shader-system, W10, plan section
 * 4.7). Names no DH type: it draws through a {@link FarRenderer}, loaded by name only when DH is present and its API passes
 * the gate (section 4.8).
 * <ul>
 * <li><b>Subscription.</b> Only while DH is present, its integration works and {@code dh_silhouettes} is on, the client
 * subscribes ({@link SilhouetteStore#subscribe}) with DH's render radius, capped at {@link Silhouettes#MAXIMUM_RADIUS}, and
 * again when that radius changes or the store dropped the subscription (level change). It unsubscribes when the option
 * turns off or the integration fails; a logout ends it in the store.</li>
 * <li><b>Shapes.</b> Each placement is built ({@link SilhouetteShapes}) on one low-priority worker thread, a placement of
 * the same shape and paint from a small cache; the client tick only drains the results.</li>
 * <li><b>Groups.</b> Hulls and near sets are registered within {@link SilhouetteBudget#BOXES}, planned again when a result
 * arrives, a structure leaves, or the player moved {@link #REPLAN} blocks. Within the vanilla render distance both groups
 * are inactive, where the real model draws; out to {@link #FAR_TIER} render distances the near set draws, past it the
 * hull. The states are set again only after the player moved {@link #STEP} blocks or the render distance changed, so no
 * frame does any work here: DH draws the groups.</li>
 * </ul>
 * Client thread only, apart from the worker's builds. Every step runs through one {@link FxGuard}: a defect turns the
 * silhouettes off for the session, and DH's grey voxels stay.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class FarSilhouettes implements SilhouetteStore.Listener {
    static final String DH="distanthorizons";
    static final String BRIDGE="com.wormzjl.icheme.client.fx.compat.dh.DhBridge";
    /** The hull replaces the near set past this many vanilla render distances. */
    static final double FAR_TIER=4;
    /** The player moves this far, blocks, before the groups' states are set again. */
    static final double STEP=8;
    /** The player moves this far, blocks, before the budget is planned again. */
    static final double REPLAN=256;
    private static final int CACHE=64;

    private static final FarSilhouettes INSTANCE=new FarSilhouettes();
    private static final FxGuard GUARD=FxGuard.effect("far_silhouettes");

    private static final class Entry {
        final Silhouettes.Placement placement;
        final int generation;
        final double x0,z0,x1,z1;
        SilhouetteShapes.@Nullable Built built;
        FarRenderer.@Nullable Group near,hull;
        Entry(Silhouettes.Placement placement,int generation) {
            this.placement=placement;this.generation=generation;
            var o=placement.origin();var s=placement.shape();
            x0=o.getX();z0=o.getZ();x1=x0+s.sizeX();z1=z0+s.sizeZ();
        }
        double distanceSquared(double x,double z){double dx=Math.max(0,Math.max(x0-x,x-x1)),dz=Math.max(0,Math.max(z0-z,z-z1));return dx*dx+dz*dz;}
        void drop(){if(near!=null)near.remove();if(hull!=null)hull.remove();near=hull=null;}
    }
    private record Result(BlockPos origin,int generation,SilhouetteShapes.Built built) {}
    private record Key(EquipmentShape shape,Paint paint) {}

    private final Map<BlockPos,Entry> entries=new HashMap<>();
    private final Queue<Result> results=new ConcurrentLinkedQueue<>();
    /** The worker's cache; touched on the worker thread only. */
    private final Map<Key,SilhouetteShapes.Built> cache=new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Key,SilhouetteShapes.Built> eldest){return size()>CACHE;}
    };
    private @Nullable ExecutorService worker;
    private @Nullable FarRenderer far;
    private @Nullable Boolean present;
    private int generation;
    private boolean replan,restate,shut;
    private double planX=Double.NaN,planZ,stateX=Double.NaN,stateZ;
    private int view=-1;

    private FarSilhouettes(){}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!GUARD.run(INSTANCE::update))INSTANCE.shutdown();}

    /** Once the guard opened: ends the subscription and removes the groups, best effort, a single time. */
    private void shutdown() {
        if(shut)return;
        shut=true;
        try{SilhouetteStore.listen(null);if(SilhouetteStore.subscribed())SilhouetteStore.unsubscribe();}
        catch(RuntimeException|LinkageError ignored){/* the guard has logged the defect */}
        for(var e:entries.values())try{e.drop();}catch(RuntimeException|LinkageError ignored){/* DH keeps its grey voxels */}
        entries.clear();results.clear();
        if(worker!=null)worker.shutdownNow();
    }

    /** The far renderer: loaded once per session, {@link FarRenderer#NONE} without DH or when its gate refused it. */
    FarRenderer far() {
        if(far==null)far=FxCompat.load(DH,BRIDGE,FarRenderer.class,FarRenderer.NONE);
        return far;
    }

    private void update() {
        var mc=Minecraft.getInstance();
        if(present==null)present=FxCompat.loaded(DH);
        boolean want=present&&mc.level!=null&&mc.player!=null&&mc.getConnection()!=null&&ClientConfig.dhSilhouettes();
        var far=want?far():null;
        if(far==null||!far.on()){stop();return;}
        far.tick();
        int radius=Silhouettes.radius(far.radius());
        if(radius<=0)return;
        if(!SilhouetteStore.subscribed()||SilhouetteStore.radius()!=radius){SilhouetteStore.listen(this);SilhouetteStore.subscribe(radius);}
        for(Result r;(r=results.poll())!=null;){
            var e=entries.get(r.origin());
            if(e!=null&&e.generation==r.generation()&&e.built==null){e.built=r.built();replan=true;}
        }
        if(!far.ready())return;
        double px=mc.player.getX(),pz=mc.player.getZ();
        if(replan||Double.isNaN(planX)||moved(px,pz,planX,planZ,REPLAN)){plan(far,px,pz);planX=px;planZ=pz;replan=false;restate=true;}
        int blocks=mc.options.getEffectiveRenderDistance()*16;
        if(restate||blocks!=view||Double.isNaN(stateX)||moved(px,pz,stateX,stateZ,STEP)){state(px,pz,blocks);stateX=px;stateZ=pz;view=blocks;restate=false;}
    }

    private static boolean moved(double x,double z,double fromX,double fromZ,double blocks){double dx=x-fromX,dz=z-fromZ;return dx*dx+dz*dz>=blocks*blocks;}

    /** Ends the subscription and removes every group. */
    private void stop() {
        if(SilhouetteStore.subscribed())SilhouetteStore.unsubscribe();
        else if(!entries.isEmpty())cleared();
    }

    /** Registers each built structure's groups within the budget, the nearest first; removes those it no longer gets. */
    private void plan(FarRenderer far,double px,double pz) {
        var built=new ArrayList<Entry>();
        for(var e:entries.values())if(e.built!=null)built.add(e);
        var candidates=new ArrayList<SilhouetteBudget.Candidate>(built.size());
        for(var e:built)candidates.add(new SilhouetteBudget.Candidate(e.distanceSquared(px,pz),e.built.near().size(),e.built.far().size()));
        var tiers=SilhouetteBudget.plan(candidates,SilhouetteBudget.BOXES);
        for(int k=0;k<built.size();k++){
            var e=built.get(k);var tier=tiers[k];var origin=e.placement.origin();
            if(tier==SilhouetteBudget.Tier.NONE){e.drop();continue;}
            if(e.hull==null)e.hull=far.add(origin,e.built.far());
            if(tier==SilhouetteBudget.Tier.NEAR){if(e.near==null)e.near=far.add(origin,e.built.near());}
            else if(e.near!=null){e.near.remove();e.near=null;}
        }
    }

    /** Sets each group active or not for the player at (px, pz) with a vanilla render distance of {@code blocks}. */
    private void state(double px,double pz,int blocks) {
        double inner=(double)blocks*blocks,outer=FAR_TIER*FAR_TIER*inner;
        for(var e:entries.values()){
            double d=e.distanceSquared(px,pz);boolean beyond=d>=inner,nearTier=beyond&&d<outer&&e.near!=null;
            if(e.near!=null)e.near.active(nearTier);
            if(e.hull!=null)e.hull.active(beyond&&!nearTier);
        }
    }

    @Override public void placed(Silhouettes.Placement placement) {
        GUARD.run(()->{
            var old=entries.remove(placement.origin());if(old!=null){old.drop();replan=true;}
            var e=new Entry(placement,++generation);entries.put(placement.origin(),e);
            var key=new Key(placement.shape(),placement.paint());var origin=placement.origin();int g=e.generation;
            worker().execute(()->GUARD.run(()->{
                var b=cache.get(key);
                if(b==null){b=SilhouetteShapes.build(key.shape(),key.paint());cache.put(key,b);}
                results.add(new Result(origin,g,b));
            }));
        });
    }
    @Override public void removed(BlockPos origin) {
        GUARD.run(()->{var e=entries.remove(origin);if(e!=null){e.drop();replan=true;}});
    }
    @Override public void cleared() {
        GUARD.run(()->{
            for(var e:entries.values())e.drop();
            entries.clear();results.clear();
            planX=stateX=Double.NaN;view=-1;replan=restate=false;
        });
    }

    private ExecutorService worker() {
        if(worker==null)worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"IChemE far silhouettes");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
        return worker;
    }
}
