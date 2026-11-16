package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.pollution.MonitorSignals;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.world.level.block.PollutionMonitorBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A fixed pollution monitor (batch 2026-11-15-pollution-system, WP10): its data socket and its host number, which the
 * control facts hold (this entity keeps a copy for the client's gland and the chat line), and its lamp pass.
 *
 * <p>The pass runs once every {@link #PASS_TICKS} ticks on the server: a read of the installed pollution field (nothing
 * steps, nothing loads), the lamp set only when its colour changes, and the horn every {@link #HORN_TICKS} ticks while red.
 * It also binds the monitor to the data network when that was not yet possible at its load. A failing pass logs once and
 * leaves the lamp as it was.</p>
 */
public final class PollutionMonitorBlockEntity extends BlockEntity {
    /** Ticks between two lamp passes: one second of play. */
    public static final int PASS_TICKS = 20;
    /** Ticks between two horn blasts while the lamp is red. */
    public static final int HORN_TICKS = 40;

    private long host;
    private DataPort port;
    /** The control service this monitor is bound to; another one (a new server's) binds it again. Never saved. */
    private Object boundTo;
    private int passes;
    private boolean failureLogged;

    public PollutionMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POLLUTION_MONITOR.get(), pos, state);
    }

    /** The monitor's host on the data network (its {@code AT-} number), 0 before it is bound. */
    public long host() {
        return host;
    }

    public DataPort dataPort() {
        return port;
    }

    /** Set by the control service when it binds the monitor. */
    public void bind(long host, DataPort port) {
        this.host = host;
        this.port = port;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** The operator's tag of the monitor: {@code AT-0012}, or {@code AT-????} before it is bound. */
    public String tag() {
        return host > 0 ? String.format(java.util.Locale.ROOT, "AT-%04d", host) : "AT-????";
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            try {
                bind(server);
            } catch (RuntimeException failure) {
                // The lamp pass binds it again a second later.
                IChemE.LOGGER.error("pollution_monitor status=BIND_FAILED position={} detail={}", worldPosition.toShortString(), String.valueOf(failure));
            }
        } else if (level != null) com.wormzjl.icheme.client.DataControlRenderer.loaded(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) com.wormzjl.icheme.client.DataControlRenderer.unloaded(this);
    }

    private void bind(ServerLevel server) {
        var world = FluidWorldAuthority.find(server.getServer()).orElse(null);
        if (world == null) return;
        var controls = world.controls();
        if (controls == boundTo) return;
        controls.loaded(this);
        boundTo = controls;
    }

    /** The server ticker: one lamp pass every {@link #PASS_TICKS} ticks, the monitors spread over the second. */
    public static void tick(Level level, BlockPos pos, BlockState state, PollutionMonitorBlockEntity monitor) {
        if (!(level instanceof ServerLevel server) || Math.floorMod(level.getGameTime() + pos.hashCode(), PASS_TICKS) != 0) return;
        monitor.pass(server, state);
    }

    private void pass(ServerLevel server, BlockState state) {
        try {
            bind(server);
            var service = PollutionService.find(server.getServer()).orElse(null);
            var lamp = MonitorSignals.lamp(MonitorSignals.enabled(service), MonitorSignals.indices(service, server.dimension(), worldPosition));
            if (state.getValue(PollutionMonitorBlock.LAMP) != lamp)
                server.setBlock(worldPosition, state.setValue(PollutionMonitorBlock.LAMP, lamp), Block.UPDATE_CLIENTS);
            if (lamp == MonitorSignals.Lamp.RED) {
                if (passes % (HORN_TICKS / PASS_TICKS) == 0)
                    server.playSound(null, worldPosition, ModBlocks.GAS_ALARM.get(), SoundSource.BLOCKS, 1.5f, 1f);
                passes++;
            } else passes = 0;
        } catch (RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                IChemE.LOGGER.error("pollution_monitor status=PASS_FAILED position={} detail={}", worldPosition.toShortString(), String.valueOf(failure));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        // A copy for the client and the chat line; the control facts hold the socket and the host (registry first).
        if (host > 0) tag.putLong("Host", host);
        if (port != null) tag.put("DataPort", port.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        host = tag.contains("Host", 4) ? Math.max(0, tag.getLong("Host")) : 0;
        try {
            port = tag.contains("DataPort", 10) ? DataPort.load(tag.getCompound("DataPort")) : null;
        } catch (IllegalArgumentException invalid) {
            port = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider provider) {
        loadAdditional(tag, provider);
        if (level != null) com.wormzjl.icheme.client.DataControlRenderer.loaded(this);
    }
}
