package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import com.wormzjl.icheme.runtime.fluid.SavedEquipment;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.world.level.block.Positions;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.CreativePowerBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-thread-owned FE wiring. Only immutable structural facts cross into the shared connectivity
 * builder.
 */
public final class PowerNetwork {
    private static final Map<MinecraftServer, PowerNetwork> SERVERS = new ConcurrentHashMap<>();

    public static PowerNetwork of(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, PowerNetwork::new);
    }

    public static void forget(MinecraftServer server) {
        SERVERS.remove(server);
    }

    private static final Comparator<DataBus.Site> SITE_ORDER = Comparator
            .comparing(DataBus.Site::dimension).thenComparing(DataBus.Site::cell);

    private final MinecraftServer server;
    private final Set<DataBus.Site> pending = new LinkedHashSet<>();
    private long revision = Long.MIN_VALUE;
    private FluidWorldAuthority world;
    private PowerTerminals terminals;
    private Map<DataBus.Site, List<DataPort>> physicalPorts = Map.of();
    private Set<DataBus.Site> creative = Set.of();
    private List<DataBus.Site> creativeOrder = List.of();
    private Set<DataBus.Site> cableCells = Set.of();
    private PowerGrid grid;
    private boolean transferring;

    private PowerNetwork(MinecraftServer server) {
        this.server = server;
        grid = new PowerGrid(new DataBus(List.of(), Map.of(), CableMedium.POWER), Map.of(), this::energy);
    }

    private void owned() {
        if (!server.isSameThread())
            throw new IllegalStateException("FE wiring belongs to the server thread");
    }

    public static void loaded(BlockEntity be) {
        if (be.getLevel() instanceof ServerLevel level
                && (PowerPorts.eligible(be)
                        || be instanceof Instrument.Holder h
                                && h.instruments().powerPort() != null)) {
            var network = of(level.getServer());
            network.owned();
            network.pending.add(site(level, be.getBlockPos()));
        }
    }

    /** The block replacement hook calls this. Chunk unloads leave the source and its wires in place. */
    public static void removed(ServerLevel level, BlockPos pos) {
        var network = of(level.getServer());
        network.owned();
        var at = site(level, pos);
        network.pending.remove(at);
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var facts = world.controls().facts();
            facts.creativePower(at, false);
            for (var port : facts.ports().values())
                if (port.kind() == DataPort.Kind.POWER_OUTPUT && at.equals(site(port)))
                    facts.removePort(port.id());
        });
    }

    private void refresh() {
        owned();
        world = FluidWorldAuthority.find(server).orElse(null);
        if (world == null) return;
        var facts = world.controls().facts();
        if (terminals == null) {
            terminals = new PowerTerminals(world.powerTopology());
            terminals.refresh();
            restoreLegacyShells();
        }
        terminals.refresh();
        if (!facts.powerReady()) migratePower(facts);
        var physical = pending.isEmpty() ? Map.<DataBus.Site, List<DataPort>>of() : physical(facts.ports());
        for (var host : List.copyOf(pending)) {
            pending.remove(host);
            var level = level(host.dimension());
            var chunk = loadedChunk(level, host.cell());
            if (chunk == null) continue;
            var be = chunk.getBlockEntity(host.cell());
            if (!(be instanceof Instrument.Holder holder)) continue;
            if (!PowerPorts.eligible(be)) {
                if (holder.instruments().powerPort() != null) {
                    holder.instruments().powerPort(null);
                    publish(be);
                }
                continue;
            }
            var registered = physical.getOrDefault(host, List.of());
            var cached = holder.instruments().powerPort();
            var mount = PowerPorts.create(be);
            if (mount == null) continue;
            long unit = terminals.unit(host);
            if (be instanceof EquipmentShellBlockEntity shell && unit == 0 && shell.origin() != null)
                unit = PowerTerminals.unit(world.powerTopology(), Positions.of(level, shell.origin()), shell.shape());
            if (be instanceof CreativePowerBlockEntity) {
                facts.creativePower(host, true);
                unit = 0;
            }
            var port = remount(facts, registered, cached, mount).withPowerHost(unit);
            facts.port(port);
            if (!port.equals(holder.instruments().powerPort())) {
                holder.instruments().powerPort(port);
                publish(be);
            }
            for (var socket : holder.powerPorts()) facts.port(socket);
        }
        if (revision != facts.topologyRevision()) {
            var ports = facts.ports();
            var standing = facts.standing();
            grid = new PowerGrid(new DataBus(standing, ports, CableMedium.POWER), ports, this::energy);
            physicalPorts = physical(ports);
            creative = facts.creativePower();
            creativeOrder = creative.stream().sorted(SITE_ORDER).toList();
            var cells = new HashSet<DataBus.Site>();
            for (var cable : standing)
                if (cable.site().lane() == 0 && cable.geometry().medium() == CableMedium.POWER)
                    cells.add(cable.site());
            cableCells = Set.copyOf(cells);
            revision = facts.topologyRevision();
        }
    }

    private static Map<DataBus.Site, List<DataPort>> physical(Map<UUID, DataPort> ports) {
        var byOwner = new HashMap<DataBus.Site, List<DataPort>>();
        for (var p : ports.values())
            if (p.kind() == DataPort.Kind.POWER_INPUT || p.kind() == DataPort.Kind.POWER_OUTPUT)
                byOwner.computeIfAbsent(site(p), ignored -> new ArrayList<>()).add(p);
        byOwner.values().forEach(list -> list.sort(Comparator.comparing(DataPort::id)));
        return byOwner;
    }

    private static DataPort remount(ControlFacts facts, List<DataPort> registered, DataPort cached, DataPort mount) {
        var port = registered.stream().filter(p -> cached != null && p.id().equals(cached.id()))
                .findFirst().orElseGet(() -> registered.stream().filter(p -> p.face() == mount.face())
                        .findFirst().orElse(registered.isEmpty() && cached != null ? cached
                                : registered.isEmpty() ? mount : registered.getFirst()));
        return DataPorts.remount(port, mount, facts.wired(port.id()));
    }

    private void restoreLegacyShells() {
        var anchors = terminals.legacyAnchors();
        if (anchors.isEmpty()) return;
        // Old readers still have process/shaft registrations; only their shell geometry is missing.
        for (var at : anchors.stream().sorted(SITE_ORDER).toList()) {
            try {
                var vessel = SavedEquipment.ownedBy(world, at.dimension(), at.cell());
                if (vessel == null) continue;
                var origin = vessel.origin();
                terminals.legacyShell(new Position(at.dimension(), origin.getX(), origin.getY(), origin.getZ()), vessel.shape());
            } catch (IllegalArgumentException invalid) {
                com.wormzjl.icheme.IChemE.LOGGER.warn("Cannot decode legacy power terminal at {}", at, invalid);
            }
        }
    }

    private void migratePower(ControlFacts facts) {
        var physical = physical(facts.ports());
        var candidates = new HashSet<DataBus.Site>();
        for (var entry : physical.entrySet()) {
            long unit = terminals.unit(entry.getKey());
            if (unit > 0) {
                for (var port : entry.getValue())
                    if (port.host() == 0) facts.port(port.withPowerHost(unit));
            } else if (entry.getValue().stream().anyMatch(p -> p.kind() == DataPort.Kind.POWER_OUTPUT)) {
                candidates.add(entry.getKey());
            }
        }
        // A pre-registry creative source could power a terminal before either end had a cable.
        for (var terminal : terminals.sites())
            for (var face : Direction.values())
                candidates.add(neighbour(terminal, face));
        // The old capability push also fed a cable beside a creative block, without a bound source socket.
        var ports = facts.ports();
        var standing = facts.standing();
        var bus = new DataBus(standing, ports, CableMedium.POWER);
        var receiving = new IdentityHashMap<Set<UUID>, Boolean>();
        for (var cable : standing) {
            if (cable.site().lane() != 0 || cable.geometry().medium() != CableMedium.POWER) continue;
            boolean hasReceiver = receiving.computeIfAbsent(bus.connected(cable.site()),
                    circuit -> circuit.stream().map(ports::get)
                            .anyMatch(port -> port.kind() == DataPort.Kind.POWER_INPUT
                                    || port.kind() == DataPort.Kind.POWER_BOTH));
            if (hasReceiver)
                for (var face : Direction.values())
                    candidates.add(neighbour(cable.site(), face));
        }
        for (var source : candidates.stream().sorted(SITE_ORDER).toList()) {
            var tag = world.savedBlockEntity(source.dimension(), source.cell());
            if (tag == null || !"icheme:creative_power".equals(tag.getString("id"))) continue;
            var cached = savedPrimary(tag, source);
            var registered = physical.getOrDefault(source, List.of()).stream()
                    .filter(p -> p.kind() == DataPort.Kind.POWER_OUTPUT).toList();
            var mount = PowerPorts.creative(source.dimension(), source.cell());
            var primary = remount(facts, registered, cached, mount).withPowerHost(0);
            for (var socket : PowerPorts.omnidirectional(primary)) facts.port(socket);
            facts.creativePower(source, true);
        }
        facts.finishPowerMigration();
    }

    private static DataPort savedPrimary(CompoundTag tag, DataBus.Site owner) {
        if (!tag.contains("PowerPort", 10)) return null;
        try {
            var port = DataPort.load(tag.getCompound("PowerPort"));
            return port.kind() == DataPort.Kind.POWER_OUTPUT && owner.equals(site(port)) ? port : null;
        } catch (IllegalArgumentException invalid) {
            // Mirror the block's metadata reader. A registered port still retains its identity.
            return null;
        }
    }

    private static DataBus.Site site(DataPort port) {
        return new DataBus.Site(port.dimension(), port.owner(), 0);
    }

    private static DataBus.Site site(ServerLevel level, BlockPos pos) {
        return new DataBus.Site(level.dimension().location().toString(), pos, 0);
    }

    private static DataBus.Site neighbour(DataBus.Site site, Direction face) {
        return new DataBus.Site(site.dimension(), site.cell().relative(face), 0);
    }

    private static void publish(BlockEntity be) {
        be.setChanged();
        InstrumentHosts.loaded(be);
        be.getLevel()
                .sendBlockUpdated(
                        be.getBlockPos(),
                        be.getBlockState(),
                        be.getBlockState(),
                        Block.UPDATE_CLIENTS);
    }

    private IEnergyStorage energy(PowerGrid.Address address) {
        var site = new DataBus.Site(address.dimension(), address.owner(), 0);
        if (creative.contains(site)) return address.host() == 0 ? PowerPorts.CREATIVE : null;
        long unit = terminals == null ? 0 : terminals.unit(site);
        if (unit != 0 && (address.host() == 0 || address.host() == unit))
            return world.electric().port(unit);
        return address.host() == 0 ? foreignEnergy(address) : null;
    }

    private ServerLevel level(String dimension) {
        var key =
                ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.parse(dimension));
        return server.getLevel(key);
    }

    private IEnergyStorage foreignEnergy(PowerGrid.Address address) {
        var level = level(address.dimension());
        var pos = address.owner();
        var chunk = loadedChunk(level, pos);
        if (chunk == null) return null;
        var state = chunk.getBlockState(pos);
        var be = state.hasBlockEntity() ? chunk.getBlockEntity(pos) : null;
        if (be instanceof ConduitBlockEntity || state.hasBlockEntity() && be == null) return null;
        // The position-only overload reads through Level.getChunkAt and renews a ticket on every probe.
        return Capabilities.EnergyStorage.BLOCK.getCapability(level, pos, state, be, address.face());
    }

    private static LevelChunk loadedChunk(ServerLevel level, BlockPos pos) {
        return level == null || level.isOutsideBuildHeight(pos) ? null
                : level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void tick() {
        owned();
        if (transferring) return;
        refresh();
        transferring = true;
        try {
            if (world != null) {
                for (var source : creativeOrder) push(source, PowerPorts.CREATIVE);
                for (long id : world.electric().charged()) {
                    var shaft = world.powerTopology().shaft(id);
                    var at = shaft == null ? null : terminals.terminal(shaft);
                    if (at != null) push(at, world.electric().port(id));
                }
            }
            grid.tick();
        } finally {
            transferring = false;
        }
    }

    private void push(DataBus.Site site, IEnergyStorage source) {
        if (source == null) return;
        for (var face : Direction.values()) {
            var sink = neighbour(site, face);
            // A cable beside a powered block exposes the same bus receive capability as before.
            if (cableCells.contains(sink)) {
                int offer = source.extractEnergy(Integer.MAX_VALUE, true);
                int taken = grid.transfer(sink, offer, false, true);
                if (taken > 0) source.extractEnergy(taken, false);
            } else {
                var storage = energy(new PowerGrid.Address(sink.dimension(), sink.cell(), face.getOpposite()));
                if (storage != null) EnergyTransfer.move(source, storage, Integer.MAX_VALUE);
            }
        }
    }

    /**
     * Scheduled motor supply check, including an ordinary FE source placed directly beside its
     * terminal.
     */
    public boolean supplied(ShaftUnit unit) {
        owned();
        if (transferring || unit == null) return false;
        refresh();
        var at = terminals == null ? null : terminals.terminal(unit);
        if (at == null) return false;
        transferring = true;
        try {
            for (var port : physicalPorts.getOrDefault(at, List.of()))
                if (port.kind() == DataPort.Kind.POWER_INPUT && grid.supplied(port.id())) return true;
            for (var face : Direction.values()) {
                var source = energy(new PowerGrid.Address(at.dimension(), at.cell().relative(face), face.getOpposite()));
                if (source != null && source.canExtract() && source.extractEnergy(1, true) > 0) return true;
            }
            return false;
        } finally {
            transferring = false;
        }
    }

    /**
     * No local buffer or alternate energy unit: neighbouring mods see the connected FE stores
     * directly.
     */
    public IEnergyStorage capability(DataBus.Site site) {
        return new IEnergyStorage() {
            private int transfer(int amount, boolean simulate, boolean input) {
                owned();
                if (transferring) return 0;
                refresh();
                transferring = true;
                try {
                    return grid.transfer(site, amount, simulate, input);
                } finally {
                    transferring = false;
                }
            }

            @Override
            public int receiveEnergy(int amount, boolean simulate) {
                return transfer(amount, simulate, true);
            }

            @Override
            public int extractEnergy(int amount, boolean simulate) {
                return transfer(amount, simulate, false);
            }

            private int total(boolean capacity) {
                owned();
                if (transferring) return 0;
                refresh();
                transferring = true;
                try {
                    return grid.total(site, capacity);
                } finally {
                    transferring = false;
                }
            }

            private boolean available(boolean input) {
                owned();
                if (transferring) return false;
                refresh();
                transferring = true;
                try {
                    return grid.available(site, input);
                } finally {
                    transferring = false;
                }
            }

            @Override
            public int getEnergyStored() {
                return total(false);
            }

            @Override
            public int getMaxEnergyStored() {
                return total(true);
            }

            @Override
            public boolean canExtract() {
                return available(false);
            }

            @Override
            public boolean canReceive() {
                return available(true);
            }
        };
    }
}
