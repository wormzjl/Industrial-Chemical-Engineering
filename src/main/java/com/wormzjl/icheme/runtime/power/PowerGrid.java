package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.*;
import java.util.function.Function;

/**
 * Thread-confined FE transport over compiled structural wiring. Capabilities are resolved on every
 * operation.
 */
public final class PowerGrid {
    public record Address(String dimension, BlockPos owner, Direction face) {
        public Address {
            owner = owner.immutable();
        }
    }

    private record Owner(String dimension, BlockPos position) {}

    private record Terminal(Owner owner, List<Address> inputs, List<Address> outputs) {}

    private record Circuit(
            List<Terminal> terminals, List<Terminal> inputs, List<Terminal> outputs) {}

    private record Endpoint(Owner owner, IEnergyStorage storage) {}

    private final DataBus bus;
    private final Map<UUID, Circuit> byPort;
    private final Map<UUID, Owner> owners;
    private final List<Circuit> active;
    private final Function<Address, IEnergyStorage> resolve;
    private boolean busy;
    private int rotation;

    public PowerGrid(
            DataBus bus, Map<UUID, DataPort> ports, Function<Address, IEnergyStorage> resolve) {
        this.bus = Objects.requireNonNull(bus);
        this.resolve = Objects.requireNonNull(resolve);
        var index = new HashMap<UUID, Circuit>();
        var operating = new ArrayList<Circuit>();
        var portOwners = new HashMap<UUID, Owner>();
        for (var group : bus.circuits()) {
            var inputs = new LinkedHashMap<Owner, List<Address>>();
            var outputs = new LinkedHashMap<Owner, List<Address>>();
            var owners = new LinkedHashSet<Owner>();
            for (var id : group) {
                var p = ports.get(id);
                if (p == null || !p.kind().power()) continue;
                var owner = new Owner(p.dimension(), p.owner());
                owners.add(owner);
                portOwners.put(id, owner);
                var address = new Address(p.dimension(), p.owner(), p.face().getOpposite());
                if (p.kind() != DataPort.Kind.POWER_OUTPUT)
                    inputs.computeIfAbsent(owner, k -> new ArrayList<>()).add(address);
                if (p.kind() != DataPort.Kind.POWER_INPUT)
                    outputs.computeIfAbsent(owner, k -> new ArrayList<>()).add(address);
            }
            if (owners.isEmpty()) continue;
            var terminals = new ArrayList<Terminal>();
            var receiving = new ArrayList<Terminal>();
            var producing = new ArrayList<Terminal>();
            for (var owner : owners) {
                var t =
                        new Terminal(
                                owner,
                                List.copyOf(inputs.getOrDefault(owner, List.of())),
                                List.copyOf(outputs.getOrDefault(owner, List.of())));
                terminals.add(t);
                if (!t.inputs().isEmpty()) receiving.add(t);
                if (!t.outputs().isEmpty()) producing.add(t);
            }
            var circuit =
                    new Circuit(
                            List.copyOf(terminals), List.copyOf(receiving), List.copyOf(producing));
            for (var id : group) index.put(id, circuit);
            if (terminals.size() > 1 && !receiving.isEmpty() && !producing.isEmpty())
                operating.add(circuit);
        }
        byPort = Map.copyOf(index);
        active = List.copyOf(operating);
        owners = Map.copyOf(portOwners);
    }

    private Circuit circuit(DataBus.Site site) {
        var group = bus.connected(site);
        return group.isEmpty() ? null : byPort.get(group.iterator().next());
    }

    private IEnergyStorage storage(Terminal terminal, boolean input) {
        for (var address : input ? terminal.inputs() : terminal.outputs()) {
            var store = resolve.apply(address);
            if (store != null && (input ? store.canReceive() : store.canExtract())) return store;
        }
        return null;
    }

    private List<Endpoint> endpoints(List<Terminal> terminals, boolean input) {
        var list = new ArrayList<Endpoint>(terminals.size());
        for (var t : terminals) {
            var s = storage(t, input);
            if (s != null && (input || s.extractEnergy(Integer.MAX_VALUE, true) > 0))
                list.add(new Endpoint(t.owner(), s));
        }
        return list;
    }

    /**
     * No scanning of unconnected sockets or rebuilding component/owner sets on the per-tick FE
     * path.
     */
    public void tick() {
        if (busy) return;
        busy = true;
        try {
            rotation++;
            for (var c : active) {
                var sources = endpoints(c.outputs(), false);
                if (sources.isEmpty()) continue;
                var sinks = endpoints(c.inputs(), true);
                for (var source : sources)
                    for (int i = 0; i < sinks.size(); i++) {
                        var sink = sinks.get(Math.floorMod(i + rotation, sinks.size()));
                        if (!source.owner().equals(sink.owner())
                                && EnergyTransfer.move(
                                                source.storage(), sink.storage(), Integer.MAX_VALUE)
                                        == 0
                                && source.storage().extractEnergy(Integer.MAX_VALUE, true) == 0)
                            break;
                    }
            }
        } finally {
            busy = false;
        }
    }

    public int transfer(DataBus.Site site, int amount, boolean simulate, boolean input) {
        if (busy || amount <= 0) return 0;
        busy = true;
        try {
            var c = circuit(site);
            if (c == null) return 0;
            int left = amount;
            for (var t : input ? c.inputs() : c.outputs()) {
                var s = storage(t, input);
                if (s == null) continue;
                left -=
                        Math.clamp(
                                input
                                        ? s.receiveEnergy(left, simulate)
                                        : s.extractEnergy(left, simulate),
                                0,
                                left);
                if (left == 0) break;
            }
            return amount - left;
        } finally {
            busy = false;
        }
    }

    public boolean available(DataBus.Site site, boolean input) {
        if (busy) return false;
        busy = true;
        try {
            var c = circuit(site);
            if (c == null) return false;
            for (var t : input ? c.inputs() : c.outputs())
                if (storage(t, input) != null) return true;
            return false;
        } finally {
            busy = false;
        }
    }

    /**
     * Read whether another terminal on this circuit can currently supply FE, even when this
     * consumer is full.
     */
    public boolean supplied(UUID port) {
        if (busy) return false;
        busy = true;
        try {
            var c = byPort.get(port);
            if (c == null) return false;
            for (var t : c.outputs()) {
                if (t.owner().equals(owners.get(port))) continue;
                var source = storage(t, false);
                if (source != null && source.extractEnergy(1, true) > 0) return true;
            }
            return false;
        } finally {
            busy = false;
        }
    }

    public int total(DataBus.Site site, boolean capacity) {
        if (busy) return 0;
        busy = true;
        try {
            var c = circuit(site);
            if (c == null) return 0;
            long sum = 0;
            for (var t : c.terminals()) {
                var s = storage(t, true);
                if (s == null) s = storage(t, false);
                if (s != null) sum += capacity ? s.getMaxEnergyStored() : s.getEnergyStored();
            }
            return (int) Math.clamp(sum, 0, Integer.MAX_VALUE);
        } finally {
            busy = false;
        }
    }
}
