package com.wormzjl.icheme.client.fx.state;

import java.util.*;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * The client's watch of one kind of published state, without Minecraft classes (batch 2026-11-13-codebase-review, C11;
 * taken out of {@link CellWatch}): the keys watched, each one's last published state, and the request of the nearest at
 * most {@code maximum} keys. A periodic {@link #request} watches the first {@code maximum} of the caller's in-range keys
 * in its order of nearest, asks again for a watched key whose first state has not arrived (registration on the server is
 * queued), and releases the rest; {@link #forget} drops keys that left; {@link #reset} ends every watch at a level change.
 * The caller keeps what needs the game: which keys are in range, their order, the period, and when a release may be sent.
 * Client thread only. Nothing here infers process state.
 *
 * @param <K> a watched key (a target, a position)
 * @param <S> the published state
 */
public final class ClientWatch<K,S> {
    /** Where the watch's requests go: the keys to watch, then the keys to release, each in order. */
    @FunctionalInterface public interface Requests<K>{void send(List<K> watch,List<K> unwatch);}

    private final int maximum;
    private final Requests<K> requests;
    private final Set<K> watched=new HashSet<>();
    private final Map<K,S> states=new HashMap<>();

    /** A watch of at most {@code maximum} keys at a time, asking through {@code requests}. */
    public ClientWatch(int maximum,Requests<K> requests){this.maximum=maximum;this.requests=Objects.requireNonNull(requests);}

    /** Whether {@code key} is watched now. */
    public boolean watches(K key){return watched.contains(key);}
    /** The server's state for {@code key}, kept while it is watched; the state it replaces, or null (also when not watched). */
    public @Nullable S accept(K key,S state){return watched.contains(key)?states.put(key,state):null;}
    /** The last state published for {@code key}, or null. */
    public @Nullable S state(K key){return states.get(key);}
    /** The states held now, by key; read only (the live map, so that a frame's walk allocates no view). */
    public Map<K,S> states(){return states;}
    /** The number of states held now ({@code FxStats}). */
    public int stored(){return states.size();}

    /**
     * The periodic request: {@code near} (the keys in range, a list this sorts) in the order {@code nearest}; the first
     * {@code maximum} are wanted. Each wanted key not watched, or watched with no state yet, is asked for; each watched
     * key not wanted is released with its state. One request carries both lists.
     */
    public void request(List<K> near,Comparator<? super K> nearest) {
        near.sort(nearest);
        var wanted=new LinkedHashSet<K>();for(var t:near){if(wanted.size()>=maximum)break;wanted.add(t);}
        var unwatch=watched.stream().filter(t->!wanted.contains(t)).toList();var watch=wanted.stream().filter(t->!watched.contains(t)||!states.containsKey(t)).toList();
        watched.removeAll(unwatch);unwatch.forEach(states::remove);watched.addAll(watch);
        requests.send(watch,unwatch);
    }

    /** Drops the watched keys {@code gone} accepts, with their states, and returns them for the caller to release. */
    public List<K> forget(Predicate<? super K> gone) {
        var out=watched.stream().filter(gone).toList();
        watched.removeAll(out);out.forEach(states::remove);
        return out;
    }

    /** The level changed: every watch ends; with {@code release} (the client is connected) a held watch is released first. */
    public void reset(boolean release) {
        if(release&&!watched.isEmpty())requests.send(List.of(),List.copyOf(watched));
        states.clear();watched.clear();
    }
    /** The client left: every watch and state goes, with no request. */
    public void clear(){states.clear();watched.clear();}
}
