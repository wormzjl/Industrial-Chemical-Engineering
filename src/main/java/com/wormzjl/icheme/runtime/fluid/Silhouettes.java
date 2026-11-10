package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/**
 * The far silhouettes' subscriptions (batch 2026-11-10-custom-shader-system, W10, plan section 4.7): a client that draws
 * Distant Horizons' far terrain asks for the placed structures within its far radius ({@link #MAXIMUM_RADIUS} at most), and
 * receives each one's origin, shape and paint as the structure registry records them ({@code EquipmentStructure.record}),
 * the {@link #MAXIMUM_STRUCTURES} nearest first; then the placements, repaints and removals while it stays subscribed.
 * Registry facts only, read as stored: nothing solves, nothing is created, nothing is saved. A subscription is presentation
 * state on a request-driven wire: it ends with the player's logout, its dimension change or its own request.
 * <ul>
 * <li>{@link #watch}: each subscription request is answered at once with a full set ({@link Batch#reset}), which replaces
 * the one before; at most one full set per {@link #REQUEST_TICKS} per player (phase 2, fx-noise): a request inside that
 * window waits for the cadence after it.</li>
 * <li>{@link #publish}, on the presentation cadence: a subscriber whose registry changed ({@link #changed}) or who moved
 * {@link #MOVE} blocks since its last set is ranked again, and receives only the differences.</li>
 * </ul>
 */
public final class Silhouettes {
    /** The farthest radius a client may ask for, blocks. */
    public static final int MAXIMUM_RADIUS=2048;
    /** The most structures one client holds. */
    public static final int MAXIMUM_STRUCTURES=256;
    /** A subscriber is ranked again after it moved this far, blocks. */
    static final double MOVE=64;
    /**
     * The fewest online ticks between two full sets to one player (5 s): a request inside that window waits, and the first
     * cadence after it ends answers the latest one. A full set is up to 256 shapes, so a client that asks in a loop costs
     * the server one registry rank per 5 s at most.
     */
    public static final long REQUEST_TICKS=100;

    /** A structure as the registry records it: its origin, its shape, its paint. */
    public record Placement(BlockPos origin,EquipmentShape shape,Paint paint) {
        public Placement{origin=origin.immutable();Objects.requireNonNull(shape);Objects.requireNonNull(paint);}
    }
    /** What one client receives: a full set ({@code reset}, which replaces what it held) or the differences, placements then removals. */
    public record Batch(boolean reset,List<Placement> placed,List<BlockPos> removed) {
        public Batch{placed=List.copyOf(placed);removed=removed.stream().map(BlockPos::immutable).toList();}
        public boolean empty(){return !reset&&placed.isEmpty()&&removed.isEmpty();}
    }
    /** The registry's structures of a dimension, as stored. */
    public interface Source {
        void structures(String dimension,Consumer<Placement> into);
    }
    /** Where a subscriber stands now: its dimension and horizontal position; null for one that left. */
    public record Stand(String dimension,double x,double z) {}

    private static final class Subscription {
        final String dimension;
        final int radius;
        double x,z;
        final Map<BlockPos,Placement> sent=new HashMap<>();
        Subscription(String dimension,int radius,double x,double z){this.dimension=dimension;this.radius=radius;this.x=x;this.z=z;}
    }

    private final Source source;
    private final Map<UUID,Subscription> subscriptions=new HashMap<>();
    /** The dimensions whose registry records changed since the last publication. */
    private final Set<String> changed=new HashSet<>();
    /** Each player's last full set, online tick, and the radius of a request that waits for its window to end. */
    private final Map<UUID,Long> answered=new HashMap<>();
    private final Map<UUID,Integer> waiting=new HashMap<>();

    public Silhouettes(Source source){this.source=Objects.requireNonNull(source);}

    /** The radius a request for {@code radius} blocks gets: 0 to {@link #MAXIMUM_RADIUS}. */
    public static int radius(int radius){return Math.clamp(radius,0,MAXIMUM_RADIUS);}

    /**
     * Player {@code player}'s request at online tick {@code tick}: subscribe within {@code radius} blocks of where it stands
     * ({@code on}), or end its subscription. Returns the full set to send it now, or null for nothing to send now: an end, or
     * a request within {@link #REQUEST_TICKS} of its last full set, which {@link #publish} answers once that window ends.
     */
    public Batch watch(UUID player,Stand stand,int radius,boolean on,long tick) {
        Objects.requireNonNull(player);
        if(!on||stand==null){subscriptions.remove(player);waiting.remove(player);return null;}
        Long last=answered.get(player);
        if(last!=null&&tick>=last&&tick-last<REQUEST_TICKS){waiting.put(player,radius(radius));return null;}
        waiting.remove(player);
        return subscribe(player,stand,radius,tick);
    }
    /** A full set: a client that asks again has dropped what it held (a level change), so every answer is a reset. */
    private Batch subscribe(UUID player,Stand stand,int radius,long tick) {
        var s=new Subscription(stand.dimension(),radius(radius),stand.x(),stand.z());
        subscriptions.put(player,s);answered.put(player,tick);
        var wanted=rank(s);
        s.sent.putAll(wanted);
        return new Batch(true,List.copyOf(wanted.values()),List.of());
    }
    /** Ends {@code player}'s subscription (it left). */
    public void forget(UUID player){subscriptions.remove(player);waiting.remove(player);answered.remove(player);}
    /** Whether {@code player} is subscribed. */
    public boolean subscribed(UUID player){return subscriptions.containsKey(player);}
    /** The registry's record of a structure in {@code dimension} changed: placed, repainted, moved or removed. */
    public void changed(String dimension){if(!subscriptions.isEmpty())changed.add(dimension);}
    public boolean idle(){return subscriptions.isEmpty()&&waiting.isEmpty();}

    /**
     * The presentation cadence at online tick {@code tick}: a waiting request whose window ended gets its full set; each
     * other subscriber where it now stands ({@code stands}; null for one that left or changed dimension, whose subscription
     * ends), ranked again where its registry changed or it moved; the differences to send it.
     */
    public Map<UUID,Batch> publish(java.util.function.Function<UUID,Stand> stands,long tick) {
        var out=new LinkedHashMap<UUID,Batch>();
        for(var it=waiting.entrySet().iterator();it.hasNext();) {
            var e=it.next();Long last=answered.get(e.getKey());
            if(last!=null&&tick>=last&&tick-last<REQUEST_TICKS)continue;
            it.remove();var stand=stands.apply(e.getKey());
            if(stand==null){subscriptions.remove(e.getKey());continue;}
            out.put(e.getKey(),subscribe(e.getKey(),stand,e.getValue(),tick));
        }
        for(var it=subscriptions.entrySet().iterator();it.hasNext();) {
            var e=it.next();var s=e.getValue();if(out.containsKey(e.getKey()))continue;var stand=stands.apply(e.getKey());
            if(stand==null||!stand.dimension().equals(s.dimension)){it.remove();continue;}
            double dx=stand.x()-s.x,dz=stand.z()-s.z;
            if(!changed.contains(s.dimension)&&dx*dx+dz*dz<MOVE*MOVE)continue;
            s.x=stand.x();s.z=stand.z();
            var wanted=rank(s);
            var placed=new ArrayList<Placement>();var removed=new ArrayList<BlockPos>();
            for(var w:wanted.values()){var had=s.sent.get(w.origin());if(had==null||!had.shape().equals(w.shape())||!had.paint().equals(w.paint()))placed.add(w);}
            for(var origin:s.sent.keySet())if(!wanted.containsKey(origin))removed.add(origin);
            s.sent.clear();s.sent.putAll(wanted);
            var batch=new Batch(false,placed,removed);
            if(!batch.empty())out.put(e.getKey(),batch);
        }
        changed.clear();
        return out;
    }

    /** The structures within {@code s}'s radius of where it stands, the nearest {@link #MAXIMUM_STRUCTURES} by their boxes, by origin. */
    private Map<BlockPos,Placement> rank(Subscription s) {
        record Near(Placement placement,double distance){}
        var near=new ArrayList<Near>();double reach=(double)s.radius*s.radius;
        source.structures(s.dimension,p->{double d=distanceSquared(p,s.x,s.z);if(d<=reach)near.add(new Near(p,d));});
        near.sort(Comparator.comparingDouble(Near::distance).thenComparingLong(n->n.placement().origin().asLong()));
        var out=new LinkedHashMap<BlockPos,Placement>();
        for(var n:near){if(out.size()>=MAXIMUM_STRUCTURES)break;out.putIfAbsent(n.placement().origin(),n.placement());}
        return out;
    }
    /** The horizontal distance, squared, from (x, z) to the box structure {@code p} spans. */
    static double distanceSquared(Placement p,double x,double z) {
        var o=p.origin();var shape=p.shape();
        double dx=Math.max(0,Math.max(o.getX()-x,x-(o.getX()+shape.sizeX()))),dz=Math.max(0,Math.max(o.getZ()-z,z-(o.getZ()+shape.sizeZ())));
        return dx*dx+dz*dz;
    }
}
