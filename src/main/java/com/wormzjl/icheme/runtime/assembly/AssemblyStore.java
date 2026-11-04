package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A level's assembly reports (batch 2026-10-26-multi-machine-framework, plan sections 7 and 11): each master's status,
 * its last report and the structural codes of what the report relied on, with an index from each position to the masters
 * whose reports rely on it. State, not facts: a store of another format, or one that does not read, is dropped whole and
 * marked for rebuilding; a column with a component design and no entry is then validated once when its controller loads.
 */
public final class AssemblyStore extends SavedData {
    public static final String DATA_NAME = "icheme_assemblies";
    /** The state format (2: a slot's units in parallel, S3b). Another one is dropped (AGENTS.md: state formats change freely). */
    public static final int FORMAT = 2;
    /** Bounds a read checks: masters per level, slots, ports, footprint positions per report. */
    static final int MAX_MASTERS = 4096, MAX_SLOTS = 256, MAX_PORTS = 1024, MAX_FOOTPRINT = 2 * AssemblyWalker.MAX_WALK;

    /** A master's status (plan section 8); NO_DESIGN is the column's own and never stored. */
    public enum Status {
        NOT_VALIDATED, CHECKED, COMPLETE
    }

    /**
     * A master's entry: its status, its last report (none while not validated), the structural code of each block and
     * nozzle the report relied on, by packed position, and where the change that reverted a report happened
     * ({@link AssemblyReport#NONE} for a column never validated).
     */
    public record Entry(Status status, @Nullable AssemblyReport report, Map<Long, Integer> codes, long changedAt) {
        public static final Entry NOT_VALIDATED = reverted(AssemblyReport.NONE);

        public Entry {
            Objects.requireNonNull(status, "status");
            codes = Map.copyOf(codes);
            if ((status == Status.NOT_VALIDATED) != (report == null)) throw new IllegalArgumentException("A report goes with a checked status only");
            if (report != null && changedAt != AssemblyReport.NONE) throw new IllegalArgumentException("A change goes with a reverted report only");
        }

        /** The entry of a fresh report: complete or checked. */
        public static Entry of(AssemblyReport report, Map<Long, Integer> codes) {
            return new Entry(report.complete() ? Status.COMPLETE : Status.CHECKED, report, codes, AssemblyReport.NONE);
        }

        /** The entry of a report reverted by a change at {@code at}. */
        public static Entry reverted(long at) {
            return new Entry(Status.NOT_VALIDATED, null, Map.of(), at);
        }

        /** The positions the entry relies on: the coded blocks and nozzles, and its members' origins. */
        Set<Long> positions() {
            var out = new HashSet<Long>(codes.keySet());
            if (report != null) out.addAll(report.members());
            return out;
        }

        /** Whether the report holds the unit at {@code origin}. */
        public boolean holds(long origin) {
            return report != null && report.members().contains(origin);
        }
    }

    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> index = new HashMap<>(), chunks = new HashMap<>();
    private boolean rebuild;

    public AssemblyStore() {
    }

    /** The store of {@code level}: read once, then kept by the level's data storage. */
    public static AssemblyStore of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(AssemblyStore::new, AssemblyStore::load, null), DATA_NAME);
    }

    public @Nullable Entry entry(long master) {
        return entries.get(master);
    }

    public Map<Long, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /** Whether the store was dropped: columns with a design and no entry are validated when their controllers load. */
    public boolean rebuilding() {
        return rebuild;
    }

    /** Stores {@code entry} for {@code master}, its index replaced. */
    public void put(long master, Entry entry) {
        unindex(master);
        if (entries.size() >= MAX_MASTERS && !entries.containsKey(master)) throw new IllegalStateException("Too many assemblies in one level");
        entries.put(master, entry);
        for (var position : entry.positions()) index.computeIfAbsent(position, p -> new HashSet<>()).add(master);
        for (var position : entry.codes().keySet()) chunks.computeIfAbsent(chunk(position), c -> new HashSet<>()).add(master);
        setDirty();
    }

    /** The packed chunk of a packed block position. */
    static long chunk(long position) {
        var at = net.minecraft.core.BlockPos.of(position);
        return net.minecraft.world.level.ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4);
    }

    /** The master's report dropped by a change at {@code at}: back to not validated. */
    public void revert(long master, long at) {
        if (entries.containsKey(master) && entries.get(master).status() != Status.NOT_VALIDATED) put(master, Entry.reverted(at));
    }

    /** The master's entry removed: its column is gone. */
    public void forget(long master) {
        unindex(master);
        if (entries.remove(master) != null) setDirty();
    }

    /** The masters whose reports rely on {@code position}. */
    public Set<Long> mastersAt(long position) {
        var masters = index.get(position);
        return masters == null ? Set.of() : Set.copyOf(masters);
    }

    /** The masters whose reports coded a block in the chunk {@code chunk}. */
    public Set<Long> mastersInChunk(long chunk) {
        var masters = chunks.get(chunk);
        return masters == null ? Set.of() : Set.copyOf(masters);
    }

    /** The units other masters' reports hold: a unit another assembly holds is shared, not bound again. */
    public Set<Long> taken(long except) {
        var out = new HashSet<Long>();
        entries.forEach((master, entry) -> {
            if (master != except && entry.report() != null) for (long member : entry.report().members()) if (member != master) out.add(member);
        });
        return out;
    }

    private void unindex(long master) {
        var old = entries.get(master);
        if (old == null) return;
        for (var position : old.positions()) {
            var masters = index.get(position);
            if (masters != null && masters.remove(master) && masters.isEmpty()) index.remove(position);
        }
        for (var position : old.codes().keySet()) {
            long c = chunk(position);
            var masters = chunks.get(c);
            if (masters != null && masters.remove(master) && masters.isEmpty()) chunks.remove(c);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return save(tag);
    }

    CompoundTag save(CompoundTag tag) {
        tag.putInt("format", FORMAT);
        tag.putBoolean("rebuild", rebuild);
        var list = new ListTag();
        entries.forEach((master, entry) -> list.add(write(master, entry)));
        tag.put("entries", list);
        return tag;
    }

    /** A saved store; one of another format, or one that does not read, is dropped and marked for rebuilding. */
    public static AssemblyStore load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag);
    }

    static AssemblyStore load(CompoundTag tag) {
        var store = new AssemblyStore();
        try {
            int format = tag.getInt("format");
            if (format != FORMAT) throw new IllegalArgumentException("state format " + format + ": this build reads " + FORMAT);
            var list = tag.getList("entries", Tag.TAG_COMPOUND);
            if (list.size() > MAX_MASTERS) throw new IllegalArgumentException("too many assemblies: " + list.size());
            for (int i = 0; i < list.size(); i++) {
                var e = list.getCompound(i);
                long master = e.getLong("master");
                if (store.entries.containsKey(master)) throw new IllegalArgumentException("two entries of master " + master);
                store.put(master, read(e));
            }
            store.rebuild = tag.getBoolean("rebuild");
        } catch (RuntimeException dropped) {
            IChemE.LOGGER.warn("assembly_store status=STATE_DROPPED detail={}", dropped.getMessage());
            store = new AssemblyStore();
            store.rebuild = true;
        }
        store.setDirty(false);
        return store;
    }

    private static CompoundTag write(long master, Entry entry) {
        var t = new CompoundTag();
        t.putLong("master", master);
        t.putString("status", entry.status().name());
        var positions = new long[entry.codes().size()];
        var codes = new int[positions.length];
        int i = 0;
        for (var c : entry.codes().entrySet()) {
            positions[i] = c.getKey();
            codes[i++] = c.getValue();
        }
        t.putLongArray("coded", positions);
        t.putIntArray("codes", codes);
        var report = entry.report();
        if (report == null) {
            t.putLong("changed", entry.changedAt());
            return t;
        }
        var slots = new ListTag();
        for (var s : report.slots()) {
            var st = new CompoundTag();
            st.putString("part", s.slot().part());
            st.putInt("index", s.slot().index());
            st.putString("state", s.state().name());
            st.putLong("member", s.member());
            st.putLong("at", s.at());
            st.putString("detail", s.detail());
            st.putLongArray("parallel", s.parallel().stream().mapToLong(Long::longValue).toArray());
            slots.add(st);
        }
        t.put("slots", slots);
        var ports = new ListTag();
        for (var p : report.ports()) {
            var pt = new CompoundTag();
            pt.putLong("unit", p.unit());
            pt.putString("role", p.role().name());
            pt.putInt("ordinal", p.ordinal());
            pt.putString("state", p.state().name());
            ports.add(pt);
        }
        t.put("ports", ports);
        t.putLongArray("footprint", report.footprint().stream().mapToLong(Long::longValue).sorted().toArray());
        t.putLongArray("members", report.members().stream().mapToLong(Long::longValue).toArray());
        return t;
    }

    private static Entry read(CompoundTag t) {
        var status = Status.valueOf(t.getString("status"));
        var positions = t.getLongArray("coded");
        var codeValues = t.getIntArray("codes");
        if (positions.length != codeValues.length || positions.length > MAX_FOOTPRINT) throw new IllegalArgumentException("codes do not match their positions");
        var codes = new HashMap<Long, Integer>();
        for (int i = 0; i < positions.length; i++) if (codes.put(positions[i], codeValues[i]) != null) throw new IllegalArgumentException("a position coded twice");
        if (status == Status.NOT_VALIDATED) {
            if (t.contains("slots") || !codes.isEmpty()) throw new IllegalArgumentException("a report without a checked status");
            return Entry.reverted(t.contains("changed") ? t.getLong("changed") : AssemblyReport.NONE);
        }
        var slotList = t.getList("slots", Tag.TAG_COMPOUND);
        var portList = t.getList("ports", Tag.TAG_COMPOUND);
        if (slotList.isEmpty() || slotList.size() > MAX_SLOTS || portList.size() > MAX_PORTS) throw new IllegalArgumentException("slot or port count out of range");
        var slots = new ArrayList<SlotReport>();
        for (int i = 0; i < slotList.size(); i++) {
            var s = slotList.getCompound(i);
            var parallel = s.getLongArray("parallel");
            if (parallel.length >= com.wormzjl.icheme.science.equipment.ColumnComponents.MAX_UNITS) throw new IllegalArgumentException("too many units in parallel");
            slots.add(new SlotReport(new Slot(s.getString("part"), s.getInt("index")), State.valueOf(s.getString("state")), s.getLong("member"), s.getLong("at"),
                    s.getString("detail"), Arrays.stream(parallel).boxed().toList()));
        }
        var ports = new ArrayList<PortReport>();
        for (int i = 0; i < portList.size(); i++) {
            var p = portList.getCompound(i);
            int ordinal = p.getInt("ordinal");
            if (ordinal < 0) throw new IllegalArgumentException("negative port ordinal");
            ports.add(new PortReport(p.getLong("unit"), Role.valueOf(p.getString("role")), ordinal, PortState.valueOf(p.getString("state"))));
        }
        var footprint = t.getLongArray("footprint");
        if (footprint.length > MAX_FOOTPRINT || !(t.get("footprint") instanceof LongArrayTag)) throw new IllegalArgumentException("footprint out of range");
        var members = new ArrayList<Long>();
        for (long m : t.getLongArray("members")) members.add(m);
        if (members.isEmpty()) throw new IllegalArgumentException("a report without its master");
        var report = new AssemblyReport(slots, ports, new HashSet<>(Arrays.stream(footprint).boxed().toList()), members);
        var entry = Entry.of(report, codes);
        if (entry.status() != status) throw new IllegalArgumentException("status " + status + " does not match the report");
        return entry;
    }
}
