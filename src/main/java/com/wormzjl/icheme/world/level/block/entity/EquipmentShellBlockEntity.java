package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.FloatingRoofs;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import com.wormzjl.icheme.science.equipment.ShapeRepair;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One shell cell of a parametric vessel: the vessel's shape, this cell's coordinates inside it, the drawn sizes of the
 * nozzles that reach it, and the vessel's {@link Paint}. Never ticks. The client model turns the cell's
 * {@link ShapeRasterizer.CellKey} into the quads of this cell and tints them with the paint, and the block turns the key
 * into its collision shape, so the cell needs nothing from any other cell or chunk. The nozzle sizes follow the pipes
 * connected to them; the server sets them ({@code EquipmentStructure}).
 *
 * <p>A reservoir or mover's node cell ({@link ShapeRasterizer#fluidCell}) carries the equipment's
 * fluid identity and is its presentation binding, as a device block entity is for a one-block tank. Nothing of the
 * drum's contents is kept here.
 */
public final class EquipmentShellBlockEntity extends BlockEntity implements com.wormzjl.icheme.runtime.instrument.Instrument.Holder, FluidView.Receiver {
    /** Model data read by the shell model while the chunk is meshed. */
    public static final ModelProperty<ShapeRasterizer.CellKey> CELL = new ModelProperty<>();
    /** A saved cell or box beyond this many cells along any side is corrupt (the largest unit is under 100). */
    private static final int MAX_CELL = 512;
    private @Nullable EquipmentShape shape;
    private int cellX, cellY, cellZ;
    private long nozzles;
    /** The vessel's paint, the same on every cell; the client tints the cell's painted parts with it. */
    private Paint paint = Paint.DEFAULT;
    private @Nullable ShapeRasterizer.CellKey key;
    /** The equipment's fluid identity, on its node cell only (0 elsewhere and while unregistered). */
    private long identity;
    private final com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments=new com.wormzjl.icheme.runtime.instrument.Instrument.Metadata();
    @Override public com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments(){return instruments;}
    /**
     * The layout this cell was placed by: its {@link ShapeRasterizer#stamp} and its box (0 for a cell saved before the
     * stamp existed). Another stamp on load sends the vessel to {@link EquipmentStructure#refreshLater}.
     */
    private long stamp;
    private int[] box = new int[3];
    /**
     * The saved kind of a cell whose kind this build no longer has (owner decision D6, batch 2026-10-26-backward-compat):
     * the cell stays unbound, and its load removes its whole structure ({@link EquipmentStructure#removeRetiredLater}).
     */
    private @Nullable String retired;
    /** A retired cell's data as it was saved, written back unchanged until the cell is removed. */
    private @Nullable CompoundTag retiredData;
    private @Nullable FluidView lastView;
    /**
     * A floating-roof tank's node cell: the liquid's level its last view gave, m, saved with the cell and sent with its
     * data, so the roof stands there ({@link FloatingRoofs}) until the tank's first display after a load.
     */
    private float roofLevel;
    /**
     * Client: the cells holding a moving part's pivot, whose parts the client moves (owner requests 2026-10-16 and
     * 2026-10-18: air cooler fans, pump shafts and motor fans, compressor piston rods). Render thread only; weak, so an
     * unloaded level frees them.
     */
    private static final Set<EquipmentShellBlockEntity> CLIENT_MOVING = Collections.newSetFromMap(new WeakHashMap<>());
    /** Client: the moving parts whose pivots this cell holds, by index ({@link ShapeRasterizer#moving}). */
    private int[] moving = new int[0];
    /**
     * Client: the node cells of floating-roof tanks (batch 2026-10-21-floating-roof-tanks), whose roof, ladder and sight
     * glass the client draws from the tank's display ({@code TankRenderer}). Render thread only; weak, as above.
     */
    private static final Set<EquipmentShellBlockEntity> CLIENT_TANKS = Collections.newSetFromMap(new WeakHashMap<>());
    /**
     * Client: the cells holding a nozzle's flange face, which the pipe laying tool marks while it is held (batch
     * 2026-10-26-pipe-laying-system). Render thread only; weak, as above.
     */
    private static final Set<EquipmentShellBlockEntity> CLIENT_PORTS = Collections.newSetFromMap(new WeakHashMap<>());

    public EquipmentShellBlockEntity(BlockPos position, BlockState state) {
        super(ModBlockEntities.EQUIPMENT_SHELL.get(), position, state);
    }

    @Override
    public long fluidIdentity() {
        return identity;
    }

    /** The engine's bucket hands over the drum's view (the authority checked the identity). */
    @Override
    public void acceptFluidView(FluidView view) {
        lastView = view;
        var tank = view.tank();
        if (tank == null || view.state() == null) return;
        float now = (float) tank.level(view.state());
        if (Float.isFinite(now) && Math.abs(now - roofLevel) > 1e-3f) {
            roofLevel = Math.max(0, now);
            setChanged();
        }
    }

    public @Nullable FluidView lastView() {
        return lastView;
    }

    /** Server: the drum registered with this cell as its node takes its identity; its presentation follows the engine's buckets. */
    public void bindIdentity(long id) {
        if (id <= 0) throw new IllegalStateException("Invalid fluid identity");
        identity = id;com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world -> world.deviceLoaded(id));
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
        }
    }

    /** This cell's position in the fluid topology. */
    public PhysicalFluidTopology.Position fluidPosition() {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }

    /** Client: the cells holding a moving part's pivot in the client's level. */
    public static List<EquipmentShellBlockEntity> clientMovingCells() {
        return List.copyOf(CLIENT_MOVING);
    }

    public int[] moving() {
        return moving;
    }

    /** Client: the node cells of the floating-roof tanks in the client's level. */
    public static List<EquipmentShellBlockEntity> clientTankCells() {
        return List.copyOf(CLIENT_TANKS);
    }

    /** Client: the cells holding a nozzle's flange face in the client's level. */
    public static List<EquipmentShellBlockEntity> clientPortCells() {
        return List.copyOf(CLIENT_PORTS);
    }

    /**
     * Client: lists the moving parts whose pivots lie in this cell ({@link ShapeRasterizer#moving}) for the client that moves
     * them, a floating-roof tank's node cell for the client that draws its roof, and a nozzle's cell for the laying tool.
     */
    private void trackMoving() {
        if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.MoverSounds.track(this);
        moving = new int[0];
        CLIENT_MOVING.remove(this);
        CLIENT_TANKS.remove(this);
        CLIENT_PORTS.remove(this);
        if (shape == null || level == null || !level.isClientSide) return;
        if (shape.kind() == EquipmentShape.Kind.TANK && cell().equals(ShapeRasterizer.fluidCell(shape))) CLIENT_TANKS.add(this);
        for (var port : ShapeRasterizer.ports(shape))
            if (port.cell().equals(cell())) {
                CLIENT_PORTS.add(this);
                break;
            }
        if (!shape.unit() || !ProcessUnit.moves(shape.kind())) return;
        var parts = ShapeRasterizer.moving(shape);
        var here = new ArrayList<Integer>();
        for (int k = 0; k < parts.size(); k++) {
            var p = parts.get(k).motion().pivot();
            if (Math.floor(p[0]) == cellX && Math.floor(p[1]) == cellY && Math.floor(p[2]) == cellZ) here.add(k);
        }
        if (here.isEmpty()) return;
        moving = here.stream().mapToInt(Integer::intValue).toArray();
        CLIENT_MOVING.add(this);
    }

    /**
     * A floating-roof tank's node cell enters its level's roof index (both sides), which its world collides with and
     * rides; any other cell leaves it.
     */
    private void trackRoof() {
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);
        if (level == null) return;
        var roofs = FloatingRoofs.of(level);
        if (shape != null && shape.kind() == EquipmentShape.Kind.TANK && cell().equals(ShapeRasterizer.fluidCell(shape)))
            roofs.add(worldPosition, origin(), shape, () -> roofLevel);
        else roofs.remove(worldPosition);
    }

    /** Server: a node binds its saved registration without creating process state; the client lists moving parts. */
    @Override
    public void onLoad() {
        super.onLoad();com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);
        trackRoof();
        if (level != null && level.isClientSide) trackMoving();
        if (level instanceof ServerLevel serverLevel && retired != null) {
            EquipmentStructure.removeRetiredLater(serverLevel, worldPosition.offset(-cellX, -cellY, -cellZ), retired, box, worldPosition);
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || shape == null) return;
        if (stale()) EquipmentStructure.refreshLater(serverLevel, origin(), shape, box, worldPosition);
        else if (cell().equals(ShapeRasterizer.layout(shape).controller())) {
            com.wormzjl.icheme.runtime.assembly.AssemblyService.controllerLoaded(serverLevel, worldPosition, shape);
            EquipmentStructure.restateLater(serverLevel, origin(), shape);
        }
        var here = fluidPosition();
        FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world -> world.at(here)
                .filter(record -> record.device().position().equals(here) && !record.device().ports().isEmpty())
                .ifPresent(record -> {
                    if (identity != record.device().id()) bindIdentity(record.device().id());
                    else world.deviceLoaded(identity);
                }));
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        released();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.InstrumentRenderer.forget(worldPosition);
        released();
    }

    /** Unloaded or removed: publications stop marking the drum; the client stops moving its parts. */
    private void released() {
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);
        if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.InstrumentRenderer.forget(worldPosition);
        if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.MoverSounds.forget(this);
        CLIENT_MOVING.remove(this);
        CLIENT_TANKS.remove(this);
        CLIENT_PORTS.remove(this);
        if (level != null) FloatingRoofs.of(level).remove(worldPosition);
        if (identity != 0 && level instanceof ServerLevel serverLevel && serverLevel.getServer().isSameThread())
            FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world -> world.deviceUnloaded(identity));
    }

    /** Opens equipment controls; the player may stand within reach of the whole structure, not only this cell. */
    public void open(ServerPlayer player, boolean debug) {
        if (identity == 0 || shape == null) {
            player.displayClientMessage(Component.translatable("gui.icheme.fluid.waiting_identity"), true);
            return;
        }
        double reach = FluidDeviceMenu.REACH + Math.max(shape.sizeX(), Math.max(shape.sizeY(), shape.sizeZ()));
        Component unit = Component.translatable("gui.icheme.vessel.title." + shape.kind().name().toLowerCase(Locale.ROOT));
        // A unit's heat side names its side (batch 2026-10-27-heat-exchanger-design).
        var heat = FluidWorldAuthority.find(player.server).map(world -> world.registrations().get(identity)).map(record -> record.device().heat()).orElse(null);
        Component title = heat == null ? unit : Component.translatable("gui.icheme.heat_side.title", unit, Component.translatable("gui.icheme.heat_side." + heat.side().name().toLowerCase(Locale.ROOT)));
        player.openMenu(new MenuProvider() {
            public Component getDisplayName() {
                return title;
            }

            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                return new FluidDeviceMenu(containerId, inventory, worldPosition, identity, debug, reach);
            }
        }, buffer -> {
            buffer.writeBlockPos(worldPosition);
            buffer.writeLong(identity);
            buffer.writeBoolean(debug);
            buffer.writeDouble(reach);
        });
        // Replay only after the opening packet and server menu exist, preserving packet order.
        if (player.containerMenu instanceof FluidDeviceMenu menu && menu.identity() == identity)
            FluidWorldAuthority.find(player.server).ifPresent(world -> world.replayOnOpen(menu));
    }

    public void bind(EquipmentShape shape, ShapeRasterizer.Cell cell, Paint paint) {
        assign(shape, cell, 0);
        this.paint = paint;
        stamp = ShapeRasterizer.stamp(shape);
        box = new int[]{shape.sizeX(), shape.sizeY(), shape.sizeZ()};
        setChanged();
        trackRoof();
    }

    /**
     * The vessel's layout stands incomplete: a refresh left cells of it out where other blocks stand. The cell keeps no
     * stamp, so the next load refreshes the vessel again and places what the player has cleared since (batch
     * 2026-10-26-backward-compat).
     */
    public void layoutIncomplete() {
        stamp = 0;
        setChanged();
    }

    /** A cell inside the vessel's box takes its key; one a smaller layout left outside draws nothing until its vessel is refreshed. */
    private void assign(EquipmentShape shape, ShapeRasterizer.Cell cell, long nozzles) {
        this.shape = shape;
        cellX = cell.x();
        cellY = cell.y();
        cellZ = cell.z();
        this.nozzles = nozzles;
        boolean inside = cellX < shape.sizeX() && cellY < shape.sizeY() && cellZ < shape.sizeZ();
        key = inside ? ShapeRasterizer.key(shape, cellX, cellY, cellZ, nozzles) : null;
    }

    /** Whether this cell was placed by another layout than the one the code draws for its shape (server, after load). */
    public boolean stale() {
        return shape != null && (key == null || stamp != ShapeRasterizer.stamp(shape));
    }

    /** The box of the layout this cell was placed by (zeros when it was saved before the stamp existed). */
    public int[] placedBox() {
        return box.clone();
    }

    /** The saved kind name of a cell whose kind this build no longer has, or null. */
    public @Nullable String retired() {
        return retired;
    }

    /** The origin a retired cell was placed from (its saved cell coordinates), or null for any other cell. */
    public @Nullable BlockPos retiredOrigin() {
        return retired == null ? null : worldPosition.offset(-cellX, -cellY, -cellZ);
    }

    /** Server: a refreshed drum's node moved elsewhere; this cell no longer stands for its fluid identity. */
    public void releaseIdentity() {
        if (identity == 0) return;
        released();
        identity = 0;
        setChanged();
        if(level instanceof ServerLevel)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
    }

    public @Nullable EquipmentShape shape() {
        return shape;
    }

    public Paint paint() {
        return paint;
    }

    public @Nullable ShapeRasterizer.CellKey key() {
        return key;
    }

    public ShapeRasterizer.Cell cell() {
        return new ShapeRasterizer.Cell(cellX, cellY, cellZ);
    }

    public long nozzles() {
        return nozzles;
    }

    /** Server: draw this cell's nozzles at new sizes; syncs when its key changes. */
    public void nozzles(long nozzles) {
        if (shape == null || nozzles == this.nozzles) return;
        var before = key;
        assign(shape, cell(), nozzles);
        setChanged();
        if (!key.equals(before) && level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    /** The vessel's origin cell (its lower north-west corner), or null while unbound. */
    public @Nullable BlockPos origin() {
        return shape == null ? null : worldPosition.offset(-cellX, -cellY, -cellZ);
    }

    /** The nozzle whose flange face this cell holds, facing {@code outward}, or null. */
    public EquipmentShape.@Nullable Nozzle nozzleFacing(Direction outward) {
        var port = port(outward);
        return port == null ? null : shape.nozzles().get(port.nozzle());
    }

    /** The largest bore of a pipe on the nozzle facing {@code outward} ({@link NozzleBore}); a pipe's largest where there is none. */
    public double largestBore(Direction outward) {
        var port = port(outward);
        return port == null ? PipeSizes.MAX_BORE : NozzleBore.largest(shape, port.nozzle());
    }

    /**
     * The largest bore a pipe at {@code pipe} may take: the least {@link #largestBore} of the nozzles beside it that it
     * joins (a bit per {@link Direction} in {@code joints}); a pipe's largest beside none.
     */
    public static double largestBoreBeside(Level level, BlockPos pipe, int joints) {
        double largest = PipeSizes.MAX_BORE;
        for (var direction : Direction.values()) {
            var next = pipe.relative(direction);
            if ((joints >> direction.ordinal() & 1) != 0 && level.isLoaded(next) && level.getBlockEntity(next) instanceof EquipmentShellBlockEntity shell)
                largest = Math.min(largest, shell.largestBore(direction.getOpposite()));
        }
        return largest;
    }

    /** The port this cell offers towards {@code outward}, or null. */
    public ShapeRasterizer.@Nullable Port port(Direction outward) {
        if (shape == null) return null;
        for (var port : ShapeRasterizer.ports(shape))
            if (port.cell().x() == cellX && port.cell().y() == cellY && port.cell().z() == cellZ
                    && port.dx() == outward.getStepX() && port.dy() == outward.getStepY() && port.dz() == outward.getStepZ()) return port;
        return null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);instruments.write(tag);
        write(tag);
        if (identity != 0) tag.putLong("FluidIdentity", identity);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);instruments.read(tag);
        shape = null;
        key = null;
        paint = Paint.DEFAULT;
        stamp = 0;
        box = new int[3];
        identity = tag.getLong("FluidIdentity");
        lastView = null;
        roofLevel = tag.getFloat("RoofLevel");
        if (!Float.isFinite(roofLevel) || roofLevel < 0) {
            IChemE.LOGGER.warn("Equipment shell at {} has an invalid roof level {}; the roof lands", worldPosition, roofLevel);
            roofLevel = 0;
        }
        retired = null;
        retiredData = null;
        if (!tag.contains("Diameter")) return;
        try {
            // A cell outside the box of the current layout stays bound until its vessel is refreshed (it was placed by
            // a larger one); the box it was placed by, when saved, bounds it.
            int[] cell = tag.getIntArray("Cell"), placed = tag.contains("Box") ? tag.getIntArray("Box") : new int[3];
            if (cell.length != 3 || cell[0] < 0 || cell[1] < 0 || cell[2] < 0 || cell[0] >= MAX_CELL || cell[1] >= MAX_CELL || cell[2] >= MAX_CELL)
                throw new IllegalArgumentException("Cell outside any vessel");
            if (placed.length != 3 || placed[0] < 0 || placed[1] < 0 || placed[2] < 0 || placed[0] > MAX_CELL || placed[1] > MAX_CELL || placed[2] > MAX_CELL)
                throw new IllegalArgumentException("Invalid placed box");
            cellX = cell[0];
            cellY = cell[1];
            cellZ = cell[2];
            box = placed;
            var read = readShape(tag);
            if (read.retired() != null) {
                // Kept as saved until its structure is removed, so a save before then loses nothing of it.
                retired = read.retired();
                retiredData = tag.copy();
                return;
            }
            for (var repair : read.repairs())
                IChemE.LOGGER.warn("equipment_compat kind={} at={} repair={}", read.shape().kind().name().toLowerCase(Locale.ROOT), worldPosition.toShortString(), repair);
            Paint painted;
            try {
                painted = Paint.decode(tag.getIntArray("Paint"));
            } catch (IllegalArgumentException invalid) {
                painted = Paint.DEFAULT;
                IChemE.LOGGER.warn("equipment_compat at={} repair=paint ({}) -> default", worldPosition.toShortString(), invalid.getMessage());
            }
            assign(read.shape(), new ShapeRasterizer.Cell(cell[0], cell[1], cell[2]), tag.getLong("Nozzles"));
            paint = painted;
            stamp = tag.getLong("Layout");
        } catch (IllegalArgumentException invalid) {
            IChemE.LOGGER.warn("Equipment shell at {} has invalid data and stays unbound: {}", worldPosition, invalid.getMessage());
        }
    }

    private void write(CompoundTag tag) {
        if (retiredData != null) {
            for (var name : retiredData.getAllKeys()) if (!tag.contains(name)) tag.put(name, retiredData.get(name).copy());
            return;
        }
        if (shape == null) return;
        writeShape(tag, shape);
        tag.putIntArray("Cell", new int[]{cellX, cellY, cellZ});
        tag.putLong("Nozzles", nozzles);
        tag.putIntArray("Paint", paint.encode());
        tag.putLong("Layout", stamp);
        tag.putIntArray("Box", box);
        if (roofLevel > 0) tag.putFloat("RoofLevel", roofLevel);
    }

    /**
     * A shape's NBT: kind, diameter, tangent length, orientation, front, support, a column's trays and its component
     * design (shape fields 2, batch 2026-10-26-multi-machine-framework) and its turned nozzles (shape fields 3, finding
     * F3), a drum's side connections and whether it has a
     * boot, an air cooler's bank; a drum's and a designed shape's bottom outlet turns (a column's leaves to the front).
     */
    public static void writeShape(CompoundTag tag, EquipmentShape shape) {
        tag.putString("Kind", shape.kind().name().toLowerCase(Locale.ROOT));
        tag.putDouble("Diameter", shape.diameter());
        tag.putDouble("TangentLength", shape.tangentLength());
        tag.putString("Orientation", shape.orientation().name().toLowerCase(Locale.ROOT));
        tag.putString("Front", shape.front().name().toLowerCase(Locale.ROOT));
        tag.putInt("Support", shape.support());
        if (shape.trays() != null) {
            tag.putIntArray("Trays", shape.trays().encode());
            if (shape.trays().components() != null) tag.put("Components", ColumnDesignTags.writeComponents(shape.trays().components()));
            if (!shape.trays().turns().isEmpty()) tag.put("Turns", ColumnDesignTags.writeTurns(shape.trays().turns()));
        } else {
            if (shape.drum()) {
                tag.putIntArray("Connections", EquipmentShape.Connection.encode(shape.connections()));
                tag.putBoolean("Boot", shape.boot());
            }
            tag.putInt("Outlet", shape.outlet());
        }
        if (shape.kind() == EquipmentShape.Kind.AIR_COOLER) tag.putIntArray("Bank", shape.bank().encode());
    }

    /**
     * A saved shape read onto the current rules ({@link ShapeRepair}): every field by name, a missing one at its
     * default, a refused one repaired, a kind this build no longer has named as retired. Never throws.
     */
    public static ShapeRepair.Read readShape(CompoundTag tag) {
        return ShapeRepair.read(saved(tag));
    }

    /** The shape fields of {@code tag} as written, before any rule reads them. */
    public static ShapeRepair.Saved saved(CompoundTag tag) {
        return new ShapeRepair.Saved(tag.getString("Kind"), tag.getString("Front"), tag.contains("Orientation") ? tag.getString("Orientation") : null,
                tag.getDouble("Diameter"), tag.getDouble("TangentLength"), tag.getInt("Support"), tag.contains("Trays") ? tag.getIntArray("Trays") : null,
                tag.contains("Connections") ? tag.getIntArray("Connections") : null, tag.getBoolean("Boot"), tag.getInt("Outlet"),
                tag.contains("Bank") ? tag.getIntArray("Bank") : null, ColumnDesignTags.saved(tag, "Components"), ColumnDesignTags.savedTurns(tag, "Turns"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = new CompoundTag();instruments.write(tag);
        write(tag);
        if(identity!=0)tag.putLong("FluidIdentity",identity);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Data that arrives after the block was meshed: refresh the model data and rebuild the section. */
    @Override
    public void onDataPacket(net.minecraft.network.Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        requestModelDataUpdate();
        trackMoving();
        trackRoof();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 0);
    }

    @Override
    public ModelData getModelData() {
        return key == null ? ModelData.EMPTY : ModelData.of(CELL, key);
    }
}
