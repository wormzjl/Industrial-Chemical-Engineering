package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.network.FluidNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One requested world-space display of {@link FluidWorldAuthority} (code base review R9): who watches which device
 * ({@link FluidWorldAuthority.Watchers}), the state last published for each device, and how a state reaches a watcher.
 * The engine-owned presentation rule (AGENTS.md, "Engine schedule and player-facing updates") holds for every one: a new
 * watch is answered at once from the cache (or a display's replay read, a read of what was last booked), the next states
 * go only to watchers, and a watcher who left, went out of range or out of the level is dropped where a state would go to
 * it. Nothing here solves or advances a process. Every method runs on the server thread, called by the authority, which
 * checks it ({@code owned()}).
 *
 * <p>Two kinds publish: a <b>bucket display</b> (open tanks, sight glasses, movers) when the presentation host presents its
 * device ({@link #publish}); a <b>cadence display</b> (flares, stills, towers, heaters, chimneys) at its device's slot of the
 * {@value FluidPresentation#BUCKET_TICKS}-tick cadence ({@link #publishDue}). The rotations and the drivers are not
 * displays of this kind: they are keyed by position, iterate by player and replace their whole cache each pass.
 */
final class Display<S> {
    /** Sends {@code state} to {@code player}: the display's own packet. {@code target} is null for an open tank, which is sent by its node cell {@code pos}. */
    interface Sink<S> {void send(ServerPlayer player,BlockPos pos,FluidNetwork.Target target,S state);}
    /** Whether {@code player} stands within the display's range of {@code pos}. */
    interface Range {boolean within(ServerPlayer player,BlockPos pos);}
    /** Whether {@code target} is a device of this display that {@code player}, in {@code level}, may watch now. */
    interface Check {boolean valid(ServerPlayer player,ServerLevel level,FluidNetwork.Target target);}

    final FluidWorldAuthority.Watchers watchers=new FluidWorldAuthority.Watchers();
    private final Map<Long,S> published=new HashMap<>();
    private final Function<UUID,ServerPlayer> players;
    private final Range range;
    private final Sink<S> sink;
    /** A target request's check; null for a display with a request of its own (an open tank's, by cell). */
    private final Check check;
    /** Runs after a new watch of a device, before its replay; null for none. */
    private final LongConsumer watched;
    /** Runs after each watcher leaves a device; null for none (then a player who left is forgotten at once). */
    private final LongConsumer unwatched;
    /** The replay read of a device with no cached state; null for none (the watch waits for the next publication). */
    private final Function<FluidNetwork.Target,S> replayRead;
    // A cadence display's parts; null for a bucket display.
    private final LongFunction<WorldTopologyLedger.Registration> records;
    private final Predicate<WorldTopologyLedger.Registration> kind;
    private final Supplier<Function<FluidNetwork.Target,S>> pass;

    private Display(Function<UUID,ServerPlayer> players,Range range,Sink<S> sink,Check check,LongConsumer watched,LongConsumer unwatched,
                    Function<FluidNetwork.Target,S> replay,LongFunction<WorldTopologyLedger.Registration> records,
                    Predicate<WorldTopologyLedger.Registration> kind,Supplier<Function<FluidNetwork.Target,S>> pass) {
        this.players=players;this.range=range;this.sink=sink;this.check=check;this.watched=watched;this.unwatched=unwatched;
        this.replayRead=replay;this.records=records;this.kind=kind;this.pass=pass;
    }
    /** A display the presentation host publishes at its device's bucket ({@link #publish}). */
    static <S> Display<S> bucket(Function<UUID,ServerPlayer> players,Range range,Check check,LongConsumer watched,LongConsumer unwatched,Sink<S> sink) {
        return new Display<>(players,range,sink,check,watched,unwatched,null,null,null,null);
    }
    /**
     * A display published at its device's slot of the cadence ({@link #publishDue}): a watched device whose record is gone or
     * no longer of {@code kind} loses its watchers; {@code pass} gives each pass's read (a pass may share one memo over its
     * devices), {@code replay} the read a new watch is answered with when nothing is cached.
     */
    static <S> Display<S> cadence(Function<UUID,ServerPlayer> players,Range range,Check check,LongFunction<WorldTopologyLedger.Registration> records,
                                  Predicate<WorldTopologyLedger.Registration> kind,Function<FluidNetwork.Target,S> replay,
                                  Supplier<Function<FluidNetwork.Target,S>> pass,Sink<S> sink) {
        return new Display<>(players,range,sink,check,null,null,replay,records,kind,pass);
    }
    /** A pass read that is the same function at every pass (no memo). */
    static <S> Supplier<Function<FluidNetwork.Target,S>> each(Function<FluidNetwork.Target,S> read){return ()->read;}

    /** The state last published for device {@code id} (a read), or null. */
    S published(long id){return published.get(id);}
    /** Forgets device {@code id}'s published state; its watchers stay. */
    void unpublish(long id){published.remove(id);}
    /** Forgets the published states of {@code devices}; their watchers stay. */
    void unpublish(Collection<Long> devices){published.keySet().removeAll(devices);}
    /** Forgets every published state; the watchers stay. */
    void clearPublished(){published.clear();}

    /**
     * A client's target request: each unwatched target ends, then each new watch of a valid target is answered with its
     * replay ({@link #replay(ServerPlayer,long,BlockPos,FluidNetwork.Target)}). Targets that fail the check are ignored.
     */
    void watch(ServerPlayer player,List<FluidNetwork.Target> watch,List<FluidNetwork.Target> unwatch) {
        var uuid=player.getUUID();var level=player.serverLevel();
        for(var target:unwatch)unwatch(uuid,target.identity());
        for(var target:watch) {
            if(!check.valid(player,level,target))continue;
            replay(player,target.identity(),target.position(),target);
        }
    }
    /**
     * Starts {@code player} watching device {@code id}; a new watch (not a repeat, not over the maximum) runs the on-watch
     * hook and is sent the state last published, or the replay read's, if there is one.
     */
    void replay(ServerPlayer player,long id,BlockPos pos,FluidNetwork.Target target) {
        if(!watchers.watch(player.getUUID(),id))return;
        if(watched!=null)watched.accept(id);
        var state=published.get(id);if(state==null&&replayRead!=null)state=replayRead.apply(target);
        if(state!=null)sink.send(player,pos,target,state);
    }
    /** Ends {@code player}'s watch of device {@code id}, then runs the unwatch hook. */
    void unwatch(UUID player,long id) {
        watchers.unwatch(player,id);
        if(unwatched!=null)unwatched.accept(id);
    }
    /** Ends every watch of {@code player} (it left): device by device where an unwatch hook runs, at once otherwise. */
    void forget(UUID player) {
        if(unwatched==null){watchers.forget(player);return;}
        for(long id:watchers.devices(player))unwatch(player,id);
    }
    /** Device {@code id} is gone or unloaded: its watchers and its published state end, then the unwatch hook runs. */
    void forget(long id) {
        watchers.remove(id);published.remove(id);
        if(unwatched!=null)unwatched.accept(id);
    }

    /**
     * A bucket display's publication: {@code state} becomes device {@code id}'s, and is sent to each of its watchers still
     * online, in {@code level} (the level its device was presented in) and within range; the others are dropped.
     */
    void publish(long id,BlockPos pos,FluidNetwork.Target target,S state,ServerLevel level) {
        published.put(id,state);
        send(id,pos,target,state,level,null);
    }
    /**
     * A cadence display's pass at online tick {@code now}: the states of the devices due now are forgotten, then each due
     * watched device's state is read and sent to its watchers still online, in its record's dimension and within range.
     * A device whose read gives nothing keeps its watchers and is sent nothing.
     */
    void publishDue(long now) {
        published.keySet().removeIf(id->FluidWorldAuthority.displayDue(id,now));if(watchers.empty())return;
        var read=pass.get();
        for(long id:watchers.devices()) {
            if(!FluidWorldAuthority.displayDue(id,now))continue;
            var record=records.apply(id);
            if(record==null||!kind.test(record)){watchers.remove(id);continue;}
            var pos=FluidWorldAuthority.cell(record);var target=new FluidNetwork.Target(pos,id);var state=read.apply(target);if(state==null)continue;
            published.put(id,state);
            send(id,pos,target,state,null,record.device().position().dimension());
        }
    }
    /**
     * Sends {@code state} to each watcher of device {@code id} still online and within range of {@code pos}, and in
     * {@code level} (a bucket display: the same level object) or, where {@code level} is null, in {@code dimension} (a
     * cadence display: its record's dimension by name); a watcher that is not is dropped.
     */
    private void send(long id,BlockPos pos,FluidNetwork.Target target,S state,ServerLevel level,String dimension) {
        for(var uuid:watchers.watchers(id)) {
            var player=players.apply(uuid);
            if(player==null||player.hasDisconnected()
                    ||(level!=null?player.serverLevel()!=level:!player.serverLevel().dimension().location().toString().equals(dimension))
                    ||!range.within(player,pos)){unwatch(uuid,id);continue;}
            sink.send(player,pos,target,state);
        }
    }
}
