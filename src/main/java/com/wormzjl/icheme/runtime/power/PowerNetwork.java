package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-thread-owned FE wiring. Only immutable structural facts cross into the shared connectivity builder. */
public final class PowerNetwork {
    private static final Map<MinecraftServer,PowerNetwork> SERVERS=new ConcurrentHashMap<>();
    public static PowerNetwork of(MinecraftServer server){return SERVERS.computeIfAbsent(server,PowerNetwork::new);}
    public static void forget(MinecraftServer server){SERVERS.remove(server);}
    private record Host(ResourceKey<Level> dimension,BlockPos pos){}
    private final MinecraftServer server;
    private final Set<Host> pending=new LinkedHashSet<>();
    private long revision=Long.MIN_VALUE;
    private DataBus bus=new DataBus(List.of(),Map.of(),CableMedium.POWER);
    private Map<UUID,DataPort> ports=Map.of();
    private boolean transferring;
    private int rotation;
    private PowerNetwork(MinecraftServer server){this.server=server;}
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("FE wiring belongs to the server thread");}
    public static void loaded(BlockEntity be){
        if(be.getLevel() instanceof ServerLevel level&&(PowerPorts.eligible(be)||be instanceof Instrument.Holder h&&h.instruments().powerPort()!=null)){
            var network=of(level.getServer());network.owned();network.pending.add(new Host(level.dimension(),be.getBlockPos().immutable()));
        }
    }
    private void refresh(){
        owned();var world=FluidWorldAuthority.find(server).orElse(null);if(world==null)return;
        var facts=world.controls().facts();
        var physical=new HashMap<Host,DataPort>();
        if(!pending.isEmpty())for(var p:facts.ports().values())if(p.kind()==DataPort.Kind.POWER_INPUT||p.kind()==DataPort.Kind.POWER_OUTPUT)
            physical.put(new Host(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(p.dimension())),p.owner()),p);
        for(var host:List.copyOf(pending)){
            pending.remove(host);var level=server.getLevel(host.dimension());if(level==null||!level.hasChunkAt(host.pos()))continue;
            var be=level.getBlockEntity(host.pos());if(!(be instanceof Instrument.Holder holder))continue;
            if(!PowerPorts.eligible(be)){if(holder.instruments().powerPort()!=null){holder.instruments().powerPort(null);publish(be);}continue;}
            var port=physical.get(host);
            if(port==null){port=PowerPorts.create(be);if(port==null)continue;facts.port(port);}
            else{
                var identity=port.id();boolean wired=facts.cables().stream().anyMatch(c->c.endpoints().containsValue(identity));
                var changed=PowerPorts.refresh(port,be,wired);if(!changed.equals(port)){port=changed;facts.port(port);}
            }
            if(!port.equals(holder.instruments().powerPort())){holder.instruments().powerPort(port);publish(be);}
        }
        if(revision!=facts.topologyRevision()){
            ports=facts.ports();bus=new DataBus(facts.cables(),ports,CableMedium.POWER);revision=facts.topologyRevision();
        }
    }
    private static void publish(BlockEntity be){
        be.setChanged();InstrumentHosts.loaded(be);
        be.getLevel().sendBlockUpdated(be.getBlockPos(),be.getBlockState(),be.getBlockState(),Block.UPDATE_CLIENTS);
    }
    private record Endpoint(Host host,IEnergyStorage storage){}
    private List<Endpoint> endpoints(Set<UUID> group,boolean input){
        var result=new LinkedHashMap<Host,Endpoint>();
        for(var id:group){
            var port=ports.get(id);if(port==null||!port.kind().power()||input&&port.kind()==DataPort.Kind.POWER_OUTPUT||!input&&port.kind()==DataPort.Kind.POWER_INPUT)continue;
            var key=ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(port.dimension()));var level=server.getLevel(key);
            if(level==null||!level.hasChunkAt(port.owner())||level.getBlockEntity(port.owner()) instanceof ConduitBlockEntity)continue;
            var energy=level.getCapability(Capabilities.EnergyStorage.BLOCK,port.owner(),port.face().getOpposite());
            if(energy==null||input&&!energy.canReceive()||!input&&!energy.canExtract())continue;
            var host=new Host(key,port.owner());result.putIfAbsent(host,new Endpoint(host,energy));
        }return List.copyOf(result.values());
    }
    public void tick(){
        refresh();if(transferring)return;transferring=true;
        try{
            var seen=new HashSet<UUID>();rotation++;
            for(var port:ports.values())if(port.kind().power()&&!seen.contains(port.id())){
                var group=bus.connected(port.id());seen.addAll(group);var sources=endpoints(group,false);var sinks=endpoints(group,true);
                for(var source:sources)for(int i=0;i<sinks.size();i++){
                    var sink=sinks.get(Math.floorMod(i+rotation,sinks.size()));
                    if(!source.host().equals(sink.host()))EnergyTransfer.move(source.storage(),sink.storage(),Integer.MAX_VALUE);
                }
            }
        }finally{transferring=false;}
    }
    /** No local buffer or alternate energy unit: neighbouring mods see the connected FE stores directly. */
    public IEnergyStorage capability(DataBus.Site site){
        return new IEnergyStorage(){
            private List<IEnergyStorage> stores(boolean input){return endpoints(bus.connected(site),input).stream().map(Endpoint::storage).toList();}
            private int transfer(int amount,boolean simulate,boolean input){
                owned();if(amount<=0||transferring)return 0;refresh();transferring=true;
                try{return input?EnergyTransfer.receive(stores(true),amount,simulate):EnergyTransfer.extract(stores(false),amount,simulate);}finally{transferring=false;}
            }
            @Override public int receiveEnergy(int amount,boolean simulate){return transfer(amount,simulate,true);}
            @Override public int extractEnergy(int amount,boolean simulate){return transfer(amount,simulate,false);}
            private int sum(boolean capacity){
                owned();if(transferring)return 0;refresh();transferring=true;
                try{
                    var all=new LinkedHashMap<Host,IEnergyStorage>();var group=bus.connected(site);
                    for(boolean input:new boolean[]{true,false})for(var e:endpoints(group,input))all.putIfAbsent(e.host(),e.storage());
                    long total=0;for(var s:all.values())total+=capacity?s.getMaxEnergyStored():s.getEnergyStored();
                    return (int)Math.clamp(total,0,Integer.MAX_VALUE);
                }finally{transferring=false;}
            }
            @Override public int getEnergyStored(){return sum(false);}
            @Override public int getMaxEnergyStored(){return sum(true);}
            private boolean available(boolean input){owned();if(transferring)return false;refresh();transferring=true;try{return !stores(input).isEmpty();}finally{transferring=false;}}
            @Override public boolean canExtract(){return available(false);}
            @Override public boolean canReceive(){return available(true);}
        };
    }
}
