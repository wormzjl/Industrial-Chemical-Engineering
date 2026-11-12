package com.wormzjl.icheme.runtime.fluid;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Server-thread-owned simulation clock, reached only through its {@link IslandCoordinator}, which checks the thread.
 * Persisted ticks accrue only while the server is running.
 *
 * <p>Online time is not counted per clock. It is derived from a shared epoch supplied at construction, the
 * world's online tick, as {@code onlineTick = epoch - base}; advancing the epoch advances every clock on it
 * at once, so no island has to be visited on a tick where nothing is due. Restoring a snapshot fixes the base
 * so the restored online tick, and with it the saved debt (online minus committed), is exactly the saved one;
 * the epoch itself only advances while the server runs, so there is no offline progress.
 */
public final class IslandClock {
    private final LongSupplier epoch;
    private final long base;
    private long committedTick,retryAtTick;
    private int cadenceTicks;
    private Slice outstanding;
    private int highLoadSamples,lowLoadSamples;
    public record Snapshot(long onlineTick,long committedTick,long retryAtTick,int cadenceTicks) {
        public Snapshot {
            if(committedTick<0||onlineTick<committedTick||retryAtTick<0||cadenceTicks<20||cadenceTicks>400)throw new IllegalArgumentException("Invalid island clock snapshot");
        }
    }
    public record Slice(long startTick,long endTick,long requestId) {
        public Slice {if(startTick<0||endTick<=startTick||requestId<=0)throw new IllegalArgumentException("Invalid simulation slice");}
        /** The process seconds the slice advances under {@code time}. */
        public double seconds(com.wormzjl.icheme.science.fluid.SimulationTime time){return time.seconds(endTick-startTick);}
    }
    /** Restores the saved clock on a shared epoch: its online tick reads exactly the saved one now. */
    public IslandClock(Snapshot saved,LongSupplier epoch) {
        this.epoch=Objects.requireNonNull(epoch);base=Math.subtractExact(epoch.getAsLong(),saved.onlineTick);
        committedTick=saved.committedTick;retryAtTick=saved.retryAtTick;cadenceTicks=saved.cadenceTicks;
    }
    private long online(){return Math.subtractExact(epoch.getAsLong(),base);}
    public long onlineTick(){return online();}
    public long committedTick(){return committedTick;}
    public long retryAtTick(){return retryAtTick;}
    public boolean busy(){return outstanding!=null;}
    public Snapshot snapshot(){return new Snapshot(online(),committedTick,retryAtTick,cadenceTicks);}
    /** The epoch tick at which this clock's online time reads {@code onlineTick}. */
    public long epochTickAt(long onlineTick){return Math.addExact(onlineTick,base);}

    /** A known event/fence can split a cadence; a fence at the current time blocks future reads. */
    public Optional<Slice> nextSlice(long requestId,long causalFenceTick) {
        return nextSlice(requestId,causalFenceTick,Integer.MAX_VALUE);
    }
    /** A failed wall-budget attempt may be retried as a shorter whole interval. This is
     * separate from configured cadence and never commits a partial result from that attempt. */
    public Optional<Slice> nextSlice(long requestId,long causalFenceTick,int maximumSliceTicks) {
        if(maximumSliceTicks<1)throw new IllegalArgumentException("Positive retry span required");
        if(causalFenceTick<committedTick)throw new IllegalArgumentException("Causal fence precedes committed state");
        long onlineTick=online();
        if(outstanding!=null||onlineTick<retryAtTick||onlineTick==committedTick||causalFenceTick==committedTick)return Optional.empty();
        long due=Math.min(Math.addExact(committedTick,Math.min(cadenceTicks,maximumSliceTicks)),causalFenceTick);
        if(onlineTick<due)return Optional.empty();
        return Optional.of(new Slice(committedTick,due,requestId));
    }
    /**
     * The online tick from which {@link #nextSlice} is present through the passage of online time alone, with
     * this fence and retry span; {@link Long#MAX_VALUE} while only a completion or an input change can make it
     * present (an outstanding slice, or a fence at the committed tick). The slice is present at a tick if and
     * only if that tick is at least this value, so a scheduler can wake the clock exactly then.
     */
    public long readyAtTick(long causalFenceTick,int maximumSliceTicks) {
        if(maximumSliceTicks<1)throw new IllegalArgumentException("Positive retry span required");
        if(causalFenceTick<committedTick)throw new IllegalArgumentException("Causal fence precedes committed state");
        if(outstanding!=null||causalFenceTick==committedTick)return Long.MAX_VALUE;
        long due=Math.min(Math.addExact(committedTick,Math.min(cadenceTicks,maximumSliceTicks)),causalFenceTick);
        return Math.max(due,retryAtTick);
    }
    /** Call only after the shared executor accepts this exact slice. A queue rejection leaves it ready. */
    public void admitted(Slice slice){if(outstanding!=null||slice.startTick!=committedTick||slice.endTick>online())throw new IllegalStateException("Invalid clock admission");outstanding=slice;}
    /** An accepted result advances once; a held result retains all of its debt. */
    public void completed(Slice slice,boolean accepted) {
        if(!slice.equals(outstanding))throw new IllegalStateException("Stale/duplicate clock completion");outstanding=null;
        if(accepted){committedTick=slice.endTick;retryAtTick=0;}
        else retryAtTick=Math.addExact(online(),cadenceTicks);
    }
    /**
     * Commits an identity or replay advance to {@code toTick} without a solve (a certified island's
     * materialisation). Refuses while a slice is outstanding, and a target below the committed tick, above
     * the online tick or past the causal fence; clears any retry. The same guards as a solved commit: no
     * commit past a fence, with an outstanding job, or past the online clock.
     */
    public void rest(long toTick,long causalFenceTick) {
        if(outstanding!=null)throw new IllegalStateException("A slice is outstanding; nothing may commit around it");
        if(toTick<committedTick||toTick>online()||toTick>causalFenceTick)throw new IllegalArgumentException("Rest target "+toTick+" outside committed "+committedTick+", online "+online()+", fence "+causalFenceTick);
        committedTick=toTick;retryAtTick=0;
    }
    /** Moves a held owner's retry to {@code delayTicks} online ticks from now, never earlier than it already is. */
    public void deferRetry(long delayTicks) {
        if(delayTicks<0||outstanding!=null)throw new IllegalStateException("Only a held owner with no outstanding slice defers its retry");
        retryAtTick=Math.max(retryAtTick,Math.addExact(online(),delayTicks));
    }
    /** A held owner whose failure is deterministic waits for an input change, not for time: no retry deadline at all
     * ({@link #readyAtTick} reads {@link Long#MAX_VALUE}) until {@link #inputsChanged()}. */
    public void holdUntilInputsChange() {
        if(outstanding!=null)throw new IllegalStateException("Only a held owner with no outstanding slice waits for its inputs");
        retryAtTick=Long.MAX_VALUE;
    }
    /** A configuration/event change may justify retrying a held owner before its ordinary backoff expires. */
    public void inputsChanged(){retryAtTick=0;}
    /** CPU gating is supplied by measurements; elapsed wall time alone is not evidence of CPU saturation. */
    public void performanceSample(boolean cpuGated,boolean loadLow) {
        if(cpuGated){highLoadSamples++;lowLoadSamples=0;if(highLoadSamples>=3){cadenceTicks=Math.min(400,(int)Math.ceil(cadenceTicks*1.25));highLoadSamples=0;}}
        else if(loadLow){lowLoadSamples++;highLoadSamples=0;if(lowLoadSamples>=10){cadenceTicks=Math.max(20,(int)Math.floor(cadenceTicks*.9));lowLoadSamples=0;}}
        else{highLoadSamples=0;lowLoadSamples=0;}
    }
}
