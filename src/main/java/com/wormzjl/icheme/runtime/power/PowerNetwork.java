package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.runtime.instrument.*;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private PowerGrid grid;
    private boolean transferring;
    private PowerNetwork(MinecraftServer server){this.server=server;grid=new PowerGrid(bus,ports,this::energy);}
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("FE wiring belongs to the server thread");}
    public static void loaded(BlockEntity be){
        if(be.getLevel() instanceof ServerLevel level&&(PowerPorts.eligible(be)||be instanceof Instrument.Holder h&&h.instruments().powerPort()!=null)){
            var network=of(level.getServer());network.owned();network.pending.add(new Host(level.dimension(),be.getBlockPos().immutable()));
        }
    }
    private void refresh(){
        owned();var world=FluidWorldAuthority.find(server).orElse(null);if(world==null)return;
        var facts=world.controls().facts();
        var physical=new HashMap<Host,List<DataPort>>();
        if(!pending.isEmpty())for(var p:facts.ports().values())if(p.kind()==DataPort.Kind.POWER_INPUT||p.kind()==DataPort.Kind.POWER_OUTPUT)
            physical.computeIfAbsent(new Host(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(p.dimension())),p.owner()),key->new ArrayList<>()).add(p);
        for(var host:List.copyOf(pending)){
            pending.remove(host);var level=server.getLevel(host.dimension());if(level==null||!level.hasChunkAt(host.pos()))continue;
            var be=level.getBlockEntity(host.pos());if(!(be instanceof Instrument.Holder holder))continue;
            if(!PowerPorts.eligible(be)){if(holder.instruments().powerPort()!=null){holder.instruments().powerPort(null);publish(be);}continue;}
            var registered=physical.getOrDefault(host,List.of());var cached=holder.instruments().powerPort();
            var port=cached==null?null:registered.stream().filter(p->p.id().equals(cached.id())).findFirst().orElse(null);
            var mount=PowerPorts.create(be);if(mount==null)continue;
            if(port==null)port=registered.stream().filter(p->p.face()==mount.face()).findFirst().orElse(registered.isEmpty()?mount:registered.getFirst());
            port=DataPorts.remount(port,mount,facts.wired(port.id()));facts.port(port);
            if(!port.equals(holder.instruments().powerPort())){holder.instruments().powerPort(port);publish(be);}
            for(var socket:holder.powerPorts())facts.port(socket);
        }
        if(revision!=facts.topologyRevision()){
            ports=facts.ports();bus=new DataBus(facts.cables(),ports,CableMedium.POWER);grid=new PowerGrid(bus,ports,this::energy);revision=facts.topologyRevision();
        }
    }
    private static void publish(BlockEntity be){
        be.setChanged();InstrumentHosts.loaded(be);
        be.getLevel().sendBlockUpdated(be.getBlockPos(),be.getBlockState(),be.getBlockState(),Block.UPDATE_CLIENTS);
    }
    private IEnergyStorage energy(PowerGrid.Address address){
        var key=ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(address.dimension()));var level=server.getLevel(key);
        if(level==null||!level.hasChunkAt(address.owner())||level.getBlockEntity(address.owner()) instanceof ConduitBlockEntity)return null;
        return level.getCapability(Capabilities.EnergyStorage.BLOCK,address.owner(),address.face());
    }
    public void tick(){owned();if(transferring)return;refresh();transferring=true;try{grid.tick();}finally{transferring=false;}}
    /** Scheduled motor supply check, including an ordinary FE source placed directly beside its terminal. */
    public boolean supplied(ShaftUnit unit){
        owned();if(transferring||unit==null)return false;refresh();
        var at=unit.position();var key=ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(at.dimension()));
        var level=server.getLevel(key);var origin=new BlockPos(at.x(),at.y(),at.z());
        if(level==null||!level.hasChunkAt(origin))return false;
        var vessel=EquipmentStructure.at(level,origin);if(vessel==null)return false;
        var terminal=ShapeRasterizer.terminal(vessel.shape());if(terminal==null)return false;
        var pos=origin.offset(terminal.x(),terminal.y(),terminal.z());if(!level.hasChunkAt(pos))return false;
        if(level.getBlockEntity(pos) instanceof Instrument.Holder holder){
            var port=holder.instruments().powerPort();if(port!=null&&grid.supplied(port.id()))return true;
        }
        for(var face:Direction.values()){
            var source=energy(new PowerGrid.Address(at.dimension(),pos.relative(face),face.getOpposite()));
            if(source!=null&&source.canExtract()&&source.extractEnergy(1,true)>0)return true;
        }
        return false;
    }
    /** No local buffer or alternate energy unit: neighbouring mods see the connected FE stores directly. */
    public IEnergyStorage capability(DataBus.Site site){
        return new IEnergyStorage(){
            private int transfer(int amount,boolean simulate,boolean input){owned();if(transferring)return 0;refresh();transferring=true;try{return grid.transfer(site,amount,simulate,input);}finally{transferring=false;}}
            @Override public int receiveEnergy(int amount,boolean simulate){return transfer(amount,simulate,true);}
            @Override public int extractEnergy(int amount,boolean simulate){return transfer(amount,simulate,false);}
            private int total(boolean capacity){owned();if(transferring)return 0;refresh();transferring=true;try{return grid.total(site,capacity);}finally{transferring=false;}}
            private boolean available(boolean input){owned();if(transferring)return false;refresh();transferring=true;try{return grid.available(site,input);}finally{transferring=false;}}
            @Override public int getEnergyStored(){return total(false);}
            @Override public int getMaxEnergyStored(){return total(true);}
            @Override public boolean canExtract(){return available(false);}
            @Override public boolean canReceive(){return available(true);}
        };
    }
}
