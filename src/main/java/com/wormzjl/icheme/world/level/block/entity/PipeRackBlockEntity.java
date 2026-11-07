package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.PipeRackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pipe rack cell's lanes (batch 2026-10-26-pipe-laying-system, RACK_LAYERS_PLAN option A). Each lane is a laid pipe
 * registered in the fluid ledger at this block's position, lane number option + 1, with its face points (I1, I2). The
 * block entity keeps a copy of each lane's facts, its identity, option, width, arms, joints and bore: drawing, the shape
 * and picking read it, and it registers a lane again when the ledger has none (a facts reset), as a laid pipe's block
 * entity keeps its joints. Which arms meet a line next door ({@code joined}) and the drawn steel ({@code frame}, the
 * bits of {@link RackGeometry#steel}) are drawn state: computed on the server from loaded neighbours, sent in the update
 * tag, never saved.
 *
 * <p>Saved format {@value #FORMAT} (AGENTS.md, saves): a list {@code Lanes} of compounds, read by name and validated; a
 * damaged lane, or one this cell's shape does not take, is dropped from the copy, and the ledger's registration, the
 * fact, puts it back at the next load where the shape takes it. A lane's paints are facts only the copy holds: an absent
 * paint reads bare, an absent secondary paint reads the paint (as a laid pipe's does), and a damaged one is repaired to
 * that, the lane kept. Format rack-3 added the secondary paint, saved only when it differs from the paint; a rack-1
 * copy reads as it did.</p>
 */
public final class PipeRackBlockEntity extends ModelledBlockEntity {
    public static final String FORMAT = "rack-3";

    /**
     * A lane's saved copy: its identity in the ledger, its option and width in the cell ({@link RackGeometry}), its arms
     * (the two faces its pipe runs to) and the joints among them, its bore, and its paints, each 0xRRGGBB or
     * {@link Paint#BARE}: the primary (its pipe) and the secondary (its flanges).
     */
    public record Lane(long identity, int option, int width, int arms, int joints, double bore, int paint, int secondary) {
        public Lane {
            if (!RackGeometry.takes(option, width) || Integer.bitCount(arms) != 2 || (arms & ~PhysicalFluidTopology.ALL_FACES) != 0
                    || (joints & ~arms) != 0 || !(bore > 0 && bore <= PipeSizes.MAX_BORE) || !Paint.line(paint) || !Paint.line(secondary))
                throw new IllegalArgumentException("Invalid rack lane " + option + "+" + width);
        }

        /** A bare lane. */
        public Lane(long identity, int option, int width, int arms, int joints, double bore) {
            this(identity, option, width, arms, joints, bore, Paint.BARE, Paint.BARE);
        }

        public Lane painted(int rgb) {
            return painted(rgb, rgb);
        }

        public Lane painted(int primary, int secondary) {
            return new Lane(identity, option, width, arms, joints, bore, primary, secondary);
        }

        public double outside() {
            return PipeSizes.outsideOf(code());
        }

        int code() {
            return PipeSizes.code(PipeSizes.outside(bore));
        }

        /** As drawn, with the arms a line next door meets. */
        public RackGeometry.Lane drawn(int joined) {
            return new RackGeometry.Lane(option, width, arms, joined & arms, code());
        }

        /**
         * The facing its registration carries: an arm without a joint (where the lane ends open), else its lowest joint.
         * With its joints it gives back the arms ({@link #arms(int, int)}).
         */
        public int facing() {
            int open = arms & ~joints;
            return Integer.numberOfTrailingZeros(open != 0 ? open : joints);
        }

        /** A registration's arms from its joints and facing: its two joints; one and its open end; or straight through along its facing. */
        public static int arms(int joints, int facing) {
            return switch (Integer.bitCount(joints)) {
                case 2 -> joints;
                case 1 -> joints | 1 << facing;
                default -> 1 << facing | 1 << (facing ^ 1);
            };
        }
    }

    /**
     * What the model draws: the cell's frame and each lane with the arms it meets a line through, its paints (the pipe's
     * and the flanges'), and {@code spools}, a bit per lane that carries a flange pair between spools here
     * ({@link RackGeometry#spool}).
     */
    public record Drawn(RackGeometry.Rack rack, List<RackGeometry.Lane> lanes, List<Integer> paints, List<Integer> secondaries, int spools,List<RackGeometry.Lane> occupied) {
        public Drawn(RackGeometry.Rack rack,List<RackGeometry.Lane> lanes,List<Integer> paints,List<Integer> secondaries,int spools){this(rack,lanes,paints,secondaries,spools,lanes);}
        public Drawn{lanes=List.copyOf(lanes);paints=List.copyOf(paints);secondaries=List.copyOf(secondaries);occupied=List.copyOf(occupied);}
    }

    public static final ModelProperty<Drawn> DRAWN = new ModelProperty<>();
    private static final ConcurrentHashMap<RackGeometry.Rack, VoxelShape> FRAMES = new ConcurrentHashMap<>();
    /** Client thread only; weak, so a level dropped without unloading its racks frees them. */
    private static final Set<PipeRackBlockEntity> CLIENT_RACKS = Collections.newSetFromMap(new WeakHashMap<>());

    private List<Lane> lanes = List.of();
    private List<com.wormzjl.icheme.runtime.control.DataBus.Cable> cables=List.of();
    public List<com.wormzjl.icheme.runtime.control.DataBus.Cable> cables(){return cables;}
    public void cables(List<com.wormzjl.icheme.runtime.control.DataBus.Cable> value){
        cables=List.copyOf(value);shape=null;setChanged();
        if(level!=null&&!level.isClientSide())level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
    }
    private void writeCables(CompoundTag tag){var list=new ListTag();cables.forEach(c->list.add(c.save()));tag.put("DataCables",list);}
    public RackGeometry.Lane cableLane(com.wormzjl.icheme.runtime.control.DataBus.Cable cable){return new RackGeometry.Lane(cable.site().lane()-1,RackGeometry.width(cable.geometry().medium().outside()),cable.arms(),cable.geometry().joints(),PipeSizes.code(cable.geometry().medium().outside()));}
    private int[] joined = new int[0];
    /** The steel of a rack alone on the ground: a whole bent at each end, on pedestals. The item and a cell whose neighbours are not read yet draw it. */
    public static final int BARE = RackGeometry.BENT_BACK | RackGeometry.BENT_FRONT | RackGeometry.END_BACK | RackGeometry.END_FRONT | RackGeometry.GROUNDED;
    /** The drawn steel bits ({@link RackGeometry#STEEL}). */
    private int frame = BARE;
    private @Nullable VoxelShape shape;

    public PipeRackBlockEntity(BlockPos position, BlockState state) {
        super(ModBlockEntities.PIPE_RACK.get(), position, state);
    }

    public List<Lane> lanes() {
        return lanes;
    }

    /** The cell's lane geometry: its facing and corner. */
    public RackGeometry.Rack rack() {
        return PipeRackBlock.rack(getBlockState());
    }

    /** The cell's world coordinate along its facing: where its bents and spools fall on the run. */
    private int along() {
        return getBlockState().getValue(PipeRackBlock.FACING).getAxis().choose(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }

    /** The cell's frame as drawn: its lane geometry, the steel its neighbours give it, and its beams beneath its lanes. */
    public RackGeometry.Rack frame() {
        return RackGeometry.framed(rack(), frame, RackGeometry.beam(layingLanes()));
    }

    /** The faces the lanes have joints on, a bit per face. */
    private int faces() {
        int faces = 0;
        for (var lane : lanes) faces |= lane.joints;
        for(var cable:cables)faces|=cable.geometry().joints();
        return faces;
    }

    /** The lanes as drawn with no line met next door: what a new lane must fit beside, and what the route search reads. */
    private List<RackGeometry.Lane> unjoined() {
        var out = new ArrayList<RackGeometry.Lane>(lanes.size());
        for (var lane : lanes) out.add(lane.drawn(0));
        for(var cable:cables)out.add(cableLane(cable));
        return out;
    }

    /** All laid lines take the same rack slots, regardless of the medium carried. */
    public List<RackGeometry.Lane> layingLanes(){var out=new ArrayList<>(drawnLanes());for(var cable:cables)out.add(cableLane(cable));return List.copyOf(out);}

    /** The route search's view of this cell: its frame, its lanes, and the faces they have joints on. */
    public PipeRoute.Room room() {
        return PipeRoute.Room.rack(rack(), unjoined(), faces());
    }

    public List<RackGeometry.Lane> drawnLanes() {
        var out = new ArrayList<RackGeometry.Lane>();
        for (int i = 0; i < lanes.size(); i++) out.add(lanes.get(i).drawn(i < joined.length ? joined[i] : 0));
        return out;
    }

    /** The lane a click at cell-local point {@code (x, y, z)} hits, and its arm; {@link RackGeometry#NONE} for the frame. */
    public RackGeometry.Pick pick(double x, double y, double z) {
        return RackGeometry.pick(rack(), drawnLanes(), x, y, z);
    }

    /** Where {@code lane} meets face {@code face} ({@link RackGeometry#point}), or -1 where it has no arm there. */
    public int point(Lane lane, int face) {
        return RackGeometry.point(rack(), lane.option, lane.width, lane.outside(), face);
    }

    /** The frame and the lanes' legs: collision, outline and picking all use it. */
    public VoxelShape shape() {
        var cached = shape;
        if (cached != null) return cached;
        var rack = frame();
        var out = frame(rack);
        for (var lane : drawnLanes())
            for (var box : RackGeometry.boxes(rack, lane)) out = Shapes.or(out, Shapes.box(box[0], box[1], box[2], box[3], box[4], box[5]));
        return shape = out.optimize();
    }

    public static VoxelShape frame(RackGeometry.Rack rack) {
        return FRAMES.computeIfAbsent(rack, r -> {
            var out = Shapes.empty();
            for (var box : RackGeometry.members(r)) out = Shapes.or(out, Shapes.box(box[0], box[1], box[2], box[3], box[4], box[5]));
            return out.optimize();
        });
    }

    /** The ledger position of the lane of {@code option}. */
    private PhysicalFluidTopology.Position position(int option) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), option + 1);
    }

    /** A lane's face points in this cell, as its registration carries them. */
    public long points(Lane lane) {
        return RackGeometry.points(rack(), lane.drawn(0));
    }

    // ---- server ----

    /**
     * Server: a lane just laid here, bound to its registration; its joins are drawn by {@link #refresh} once its line is
     * placed. The clients get both in one update: a tick's block updates go out together at its end.
     */
    public void addLane(Lane lane) {
        var next = new ArrayList<>(lanes);
        if (!RackGeometry.fits(rack(), unjoined(), lane.drawn(0))) throw new IllegalStateException("The rack has no room for the lane");
        next.add(lane);
        setLanes(next, null);
    }

    /** Server: removes a lane from the copy (its registration is removed by the caller). */
    public void removeLane(long identity) {
        var next = new ArrayList<>(lanes);
        next.removeIf(lane -> lane.identity == identity);
        setLanes(next, null);
    }

    private void setLanes(List<Lane> next, int @Nullable [] nextJoined) {
        var previous = lanes;
        var previousJoined = joined;
        next.sort(Comparator.comparingInt(Lane::option));
        lanes = List.copyOf(next);
        joined = new int[lanes.size()];
        // A lane keeps its drawn joins while it stays; a new one starts open until refreshed.
        for (int i = 0; i < lanes.size(); i++)
            if (nextJoined != null) joined[i] = nextJoined[i];
            else for (int j = 0; j < previous.size(); j++) if (previous.get(j).identity == lanes.get(i).identity) joined[i] = previousJoined[j];
        shape = null;
        setChanged();
        // The server sends the copy whenever it changes, as a client draws only what it is sent: a lane with no joint (a
        // one-block line, open at both faces) changes nothing that refresh draws.
        if (level != null && !level.isClientSide() && !lanes.equals(previous)) sync();
    }

    /**
     * Server: matches the copy to the ledger, which holds the fluid facts. A lane with a registration takes its identity;
     * a lane without one registers again from the copy; a registration with no copy is adopted, bare. A lane this cell's
     * shape does not take, or with no room beside the lanes before it, is dropped from the copy. Each lane keeps the
     * copy's paint, which the ledger does not hold. Then every lane is drawn.
     */
    public void bind(FluidWorldAuthority world) {
        if (!(level instanceof ServerLevel)) return;
        var next = new ArrayList<Lane>();
        var taken = new ArrayList<RackGeometry.Lane>();
        for (var lane : lanes) {
            var record = world.at(position(lane.option)).orElse(null);
            if (record == null && RackGeometry.valid(rack(), lane.option, lane.width, lane.arms))
                record = world.placeLane(position(lane.option), PhysicalFluidTopology.Direction.values()[lane.facing()], lane.joints, lane.width, points(lane), lane.bore);
            var copied = record == null ? null : copy(record.device());
            if (copied != null && RackGeometry.fits(rack(), taken, copied.drawn(0))) {
                next.add(copied.painted(lane.paint, lane.secondary));
                taken.add(copied.drawn(0));
            }
        }
        for (int option = 0; option < RackGeometry.OPTIONS; option++) {
            int o = option;
            if (next.stream().anyMatch(lane -> lane.option == o)) continue;
            var record = world.at(position(option)).orElse(null);
            var copied = record == null ? null : copy(record.device());
            if (copied != null && RackGeometry.fits(rack(), taken, copied.drawn(0))) {
                next.add(copied);
                taken.add(copied.drawn(0));
            }
        }
        setLanes(next, null);
        refresh(true);
    }

    /** A registered lane as its copy, or null when the registration is no lane this cell can draw. */
    private @Nullable Lane copy(PhysicalFluidTopology.Device device) {
        if (device.kind() != TopologyCompiler.Kind.PIPE || device.slots() == 0) return null;
        try {
            var lane = new Lane(device.id(), device.position().lane() - 1, device.slots(), Lane.arms(device.joints(), device.facing().ordinal()), device.joints(), device.geometry().diameter());
            return RackGeometry.valid(rack(), lane.option, lane.width, lane.arms) ? lane : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Server: each lane takes its registered joints and bore (accepted input, as a laid pipe's joints do), and the arms it
     * meets a line through; with {@code neighbours}, the blocks next door redraw their side too.
     */
    public void refresh(boolean neighbours) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        var world = FluidWorldAuthority.find(serverLevel.getServer()).orElse(null);
        var next = new ArrayList<Lane>();
        for (var lane : lanes) {
            var record = world == null ? null : world.registrations().get(lane.identity);
            var copied = record == null ? null : copy(record.device());
            next.add(copied != null && copied.option == lane.option && copied.width == lane.width && copied.arms == lane.arms ? copied.painted(lane.paint, lane.secondary) : lane);
        }
        if (!next.equals(lanes)) setLanes(next, null);
        if (refreshDrawn()) sync();
        if (neighbours) for (var direction : Direction.values()) {
            var at = worldPosition.relative(direction);
            if (!level.isLoaded(at)) continue;
            var neighbour = level.getBlockEntity(at);
            if (neighbour instanceof FluidDeviceBlockEntity pipe) pipe.refreshGeometry(pipe.bore(), false);
            else if (neighbour instanceof PipeRackBlockEntity rack && rack.refreshDrawn()) rack.sync();
        }
    }

    /** Server: redraws the drawn state from the loaded neighbours ({@link #refreshDrawn}) and sends it to the clients when it changed. */
    public void redraw() {
        if (refreshDrawn()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    /**
     * Server: the drawn state from the loaded neighbours. Which joints of each lane meet a line next door: a lane of the
     * next rack with the opposite joint at the same point, or a pipe connected towards this rack; a face whose neighbour
     * is in an unloaded chunk keeps what it showed (reading it would load the chunk). And the steel ({@link
     * RackGeometry#steel}, along the run counted in world coordinates). True when anything changed.
     */
    private boolean refreshDrawn() {
        if (level == null || level.isClientSide()) return false;
        var next = new int[lanes.size()];
        for (int i = 0; i < lanes.size(); i++) {
            var lane = lanes.get(i);
            for (var direction : Direction.values()) {
                int face = direction.ordinal();
                if ((lane.joints >> face & 1) == 0) continue;
                var at = worldPosition.relative(direction);
                if (!level.isLoaded(at)) {
                    next[i] |= (i < joined.length ? joined[i] : 0) & 1 << face;
                    continue;
                }
                var neighbour = level.getBlockEntity(at);
                boolean meets = false;
                if (neighbour instanceof PipeRackBlockEntity rack) {
                    int point = point(lane, face);
                    for (var other : rack.lanes)
                        if ((other.joints >> (face ^ 1) & 1) != 0 && rack.point(other, face ^ 1) == point) meets = true;
                } else if (neighbour instanceof FluidDeviceBlockEntity pipe && pipe.kind() == TopologyCompiler.Kind.PIPE)
                    meets = pipe.getBlockState().getValue(FluidDeviceBlock.connection(direction.getOpposite()));
                if (meets) next[i] |= 1 << face;
            }
        }
        int drawn = steel();
        if (Arrays.equals(next, joined) && drawn == frame) return false;
        joined = next;
        frame = drawn;
        shape = null;
        return true;
    }

    /** The drawn steel bits from the loaded neighbours ({@link RackGeometry#steel}); a neighbour in an unloaded chunk keeps its bit. */
    private int steel() {
        var loaded = PipeRackBlock.neighbours(level, worldPosition);
        var around = new RackGeometry.Neighbours() {
            @Override
            public RackGeometry.Rack rack(int face) {
                return loaded.rack(face);
            }

            @Override
            public boolean known(int face) {
                return level.isLoaded(worldPosition.relative(Direction.values()[face]));
            }
        };
        return RackGeometry.steel(around, rack(), along(), frame);
    }

    /**
     * The faces where lane {@code index} ends open: an arm without a joint, or a joint nothing meets any more (the next
     * lane or pipe was dismantled). A line laid from there extends the lane (D11).
     */
    public int openFaces(int index) {
        var lane = lanes.get(index);
        int met = index < joined.length ? joined[index] : 0;
        return lane.arms & ~lane.joints | lane.joints & ~met;
    }

    /** Server: the lane meeting {@code direction}'s neighbour through that face, if one has a joint there. */
    public @Nullable Lane laneTowards(Direction direction) {
        for (var lane : lanes) if ((lane.joints >> direction.ordinal() & 1) != 0) return lane;
        return null;
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) CLIENT_RACKS.add(this);
        if (level instanceof ServerLevel serverLevel) FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(this::bind);
        if(level instanceof ServerLevel serverLevel)FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(w->w.controls().loaded(this));
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_RACKS.remove(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_RACKS.remove(this);
    }

    /** Client: the racks in the client's level, whose open lane ends the pipe laying tool marks. */
    public static List<PipeRackBlockEntity> clientRacks() {
        return List.copyOf(CLIENT_RACKS);
    }

    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        shape = null;
    }

    // ---- saved format ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(lanes, tag);
        writeCables(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        var wires=new ArrayList<com.wormzjl.icheme.runtime.control.DataBus.Cable>();
        try{
            var saved=tag.getList("DataCables",10);if(saved.size()>RackGeometry.OPTIONS)throw new IllegalArgumentException("Too many rack cables");var options=new java.util.HashSet<Integer>();
            for(var t:saved){var cable=com.wormzjl.icheme.runtime.control.DataBus.Cable.load((CompoundTag)t);if(cable.site().lane()==0||!cable.site().cell().equals(worldPosition)||!options.add(cable.site().lane()))throw new IllegalArgumentException("Invalid rack cable copy");wires.add(cable);}
        }catch(IllegalArgumentException invalid){wires.clear();}
        cables=List.copyOf(wires);
        var read = read(tag);
        int[] readJoined = null;
        if (tag.contains("Joined", Tag.TAG_INT_ARRAY) && tag.getIntArray("Joined").length == read.size()) readJoined = tag.getIntArray("Joined");
        if (tag.contains("Frame", Tag.TAG_INT)) frame = tag.getInt("Frame") & RackGeometry.STEEL;
        setLanes(new ArrayList<>(read), readJoined);
    }

    /** Writes {@code lanes} as format {@value #FORMAT}: the copy a rack saves. */
    public static CompoundTag write(List<Lane> lanes, CompoundTag tag) {
        var list = new ListTag();
        for (var lane : lanes) {
            var entry = new CompoundTag();
            entry.putLong("Identity", lane.identity);
            entry.putInt("Option", lane.option);
            entry.putInt("Width", lane.width);
            entry.putInt("Arms", lane.arms);
            entry.putInt("Joints", lane.joints);
            entry.putDouble("Bore", lane.bore);
            list.add(FluidDeviceBlockEntity.writePaints(lane.paint, lane.secondary, entry));
        }
        tag.put("Lanes", list);
        return tag;
    }

    /**
     * Reads format {@value #FORMAT}: every lane whose fields are present and valid, in option order; a lane on an option
     * read before it, or damaged, is dropped and logged. Whether the cell's shape takes it, and its room beside the
     * others, is {@link #bind}'s to check. Absent: no lanes.
     */
    public static List<Lane> read(CompoundTag tag) {
        var out = new ArrayList<Lane>();
        int used = 0;
        for (var element : tag.getList("Lanes", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) element;
            Lane lane = null;
            if (entry.contains("Identity", Tag.TAG_LONG) && entry.contains("Option", Tag.TAG_INT) && entry.contains("Width", Tag.TAG_INT) && entry.contains("Arms", Tag.TAG_INT)
                    && entry.contains("Joints", Tag.TAG_INT) && entry.contains("Bore", Tag.TAG_DOUBLE))
                try {
                    int paint = FluidDeviceBlockEntity.readPaint(entry, "pipe_rack");
                    lane = new Lane(entry.getLong("Identity"), entry.getInt("Option"), entry.getInt("Width"), entry.getInt("Arms"), entry.getInt("Joints"), entry.getDouble("Bore"),
                            paint, FluidDeviceBlockEntity.readSecondary(entry, paint, "pipe_rack"));
                } catch (IllegalArgumentException ignored) {
                }
            if (lane == null || (used >> lane.option & 1) != 0) {
                IChemE.LOGGER.warn("pipe_rack status=DROPPED detail=Damaged or repeated lane copy {}; the ledger's registration restores it", entry);
                continue;
            }
            used |= 1 << lane.option;
            out.add(lane);
        }
        out.sort(Comparator.comparingInt(Lane::option));
        return List.copyOf(out);
    }

    // ---- client ----

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = write(lanes, new CompoundTag());
        writeCables(tag);
        tag.putIntArray("Joined", joined.clone());
        tag.putInt("Frame", frame);
        return tag;
    }

    @Override
    public ModelData getModelData() {
        var rack = frame();
        var drawn = drawnLanes();
        int along = along(), spools = 0;
        for (int i = 0; i < drawn.size(); i++) if (RackGeometry.spool(rack, drawn.get(i), along)) spools |= 1 << i;
        return ModelData.of(DRAWN, new Drawn(rack, drawn, lanes.stream().map(Lane::paint).toList(), lanes.stream().map(Lane::secondary).toList(), spools,layingLanes()));
    }
}
