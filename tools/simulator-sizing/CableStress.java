import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.power.*;
import com.wormzjl.icheme.science.equipment.*;
import net.minecraft.core.*;
import net.neoforged.neoforge.energy.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.atomic.*;

/** One offline run of the shipped graph/routing/FE engines, with the previous power loop as a bounded comparison. */
public class CableStress {
    static final String DIM="minecraft:overworld";
    static final com.sun.management.ThreadMXBean THREAD=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    static final MemoryMXBean MEMORY=ManagementFactory.getMemoryMXBean();
    static volatile long blackhole;
    static Path out;static int checks;static int size;static boolean representative;static String metrics="metrics.csv";
    static long mix(long z){z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;return z^(z>>>31);}
    static UUID identity(long namespace,long number){return representative?new UUID(mix(number^0x5a917e32b41d00abL^namespace),mix(number+0x9181a31b65c82021L+namespace*0x9e3779b97f4a7c15L)):new UUID(namespace,number);}
    static void require(boolean b,String what){checks++;if(!b)throw new AssertionError(what);}
    static class Source implements IEnergyStorage {
        long produced;
        public int receiveEnergy(int n,boolean sim){return 0;}
        public int extractEnergy(int n,boolean sim){n=Math.max(0,n);if(!sim)produced+=n;return n;}
        public int getEnergyStored(){return Integer.MAX_VALUE;}
        public int getMaxEnergyStored(){return Integer.MAX_VALUE;}
        public boolean canExtract(){return true;}public boolean canReceive(){return false;}
    }
    static class Load extends EnergyStorage {
        long consumed;Load(){super(10000,64,0);}
        void consume(){consumed+=energy;energy=0;}
        void reset(){energy=0;consumed=0;}
    }
    record Fixture(List<DataBus.Cable> wires,Map<UUID,DataPort> ports,UUID[] dataFirst,UUID[] dataLast,UUID[] powerFirst,UUID[] powerLast,
                   Map<PowerGrid.Address,IEnergyStorage> stores,List<Source> sources,List<Load> loads,int length,int channels){}
    static Fixture fixture(int channels,int length,int loose){
        var wires=new ArrayList<DataBus.Cable>(2*channels*length);var ports=new HashMap<UUID,DataPort>();
        var df=new UUID[channels];var dl=new UUID[channels];var pf=new UUID[channels];var pl=new UUID[channels];
        var stores=new HashMap<PowerGrid.Address,IEnergyStorage>();var sources=new ArrayList<Source>();var loads=new ArrayList<Load>();
        for(int c=0;c<channels;c++)for(var medium:CableMedium.values()){
            int z=2*c+(medium==CableMedium.DATA?0:1);UUID first=identity(0,4L*c+medium.ordinal()*2+1),last=identity(0,4L*c+medium.ordinal()*2+2);
            for(int x=0;x<length;x++){
                var cell=new BlockPos(x,0,z);var ends=x==0?Map.of(Direction.WEST,first):x==length-1?Map.of(Direction.EAST,last):Map.<Direction,UUID>of();
                wires.add(new DataBus.Cable(new DataBus.Site(DIM,cell,0),new ConduitGeometry.Key(48,0,medium),0,ends));
            }
            var a=new BlockPos(0,0,z);var b=new BlockPos(length-1,0,z);
            var p=new DataPort(first,medium==CableMedium.DATA?DataPort.Kind.SENSOR:DataPort.Kind.POWER_OUTPUT,medium==CableMedium.DATA?c+1:0,DIM,a.west(),a.getCenter(),a,Direction.WEST,0,0);
            var q=new DataPort(last,medium==CableMedium.DATA?DataPort.Kind.CONTROLLER:DataPort.Kind.POWER_INPUT,0,DIM,b.east(),b.getCenter(),b,Direction.EAST,0,0);
            ports.put(first,p);ports.put(last,q);
            if(medium==CableMedium.DATA){df[c]=first;dl[c]=last;}
            else{
                pf[c]=first;pl[c]=last;var source=new Source();var load=new Load();sources.add(source);loads.add(load);
                stores.put(new PowerGrid.Address(DIM,p.owner(),Direction.EAST),source);stores.put(new PowerGrid.Address(DIM,q.owner(),Direction.WEST),load);
            }
        }
        for(int i=0;i<loose;i++){
            var cell=new BlockPos(i%4096,10+i/4096,2*channels+2);var id=identity(1,i+1);
            var kind=i%2==0?DataPort.Kind.SENSOR:i%4==1?DataPort.Kind.POWER_INPUT:DataPort.Kind.POWER_OUTPUT;
            ports.put(id,new DataPort(id,kind,kind==DataPort.Kind.SENSOR?channels+i+1:0,DIM,cell.west(),cell.getCenter(),cell,Direction.WEST,0,0));
        }
        return new Fixture(wires,Map.copyOf(ports),df,dl,pf,pl,stores,sources,loads,length,channels);
    }
    static void reset(Fixture f){for(var s:f.sources())s.produced=0;for(var l:f.loads())l.reset();}
    static void balance(Fixture f,int ticks,int connected){
        long produced=0,consumed=0;for(var s:f.sources())produced+=s.produced;for(var l:f.loads())consumed+=l.consumed;
        require(produced==consumed,"FE conservation");require(consumed==64L*ticks*connected,"all connected loads served: "+consumed);
    }
    /** The previous PowerNetwork tick/endpoints loop, retaining its per-tick owner/component maps and loose-port scan. */
    static class Previous {
        final Fixture f;final DataBus bus;int rotation;
        record Owner(String dimension,BlockPos owner){}record Endpoint(Owner owner,IEnergyStorage storage){}
        Previous(Fixture f,DataBus bus){this.f=f;this.bus=bus;}
        List<Endpoint> endpoints(Set<UUID> group,boolean input){
            var result=new LinkedHashMap<Owner,Endpoint>();
            for(var id:group){var p=f.ports().get(id);if(p==null||!p.kind().power()||input&&p.kind()==DataPort.Kind.POWER_OUTPUT||!input&&p.kind()==DataPort.Kind.POWER_INPUT)continue;
                var energy=f.stores().get(new PowerGrid.Address(p.dimension(),p.owner(),p.face().getOpposite()));if(energy==null||input&&!energy.canReceive()||!input&&!energy.canExtract())continue;
                var owner=new Owner(p.dimension(),p.owner());result.putIfAbsent(owner,new Endpoint(owner,energy));
            }return List.copyOf(result.values());
        }
        void tick(){var seen=new HashSet<UUID>();rotation++;
            for(var p:f.ports().values())if(p.kind().power()&&!seen.contains(p.id())){
                var group=bus.connected(p.id());seen.addAll(group);var sources=endpoints(group,false);var sinks=endpoints(group,true);
                for(var a:sources)for(int i=0;i<sinks.size();i++){var b=sinks.get(Math.floorMod(i+rotation,sinks.size()));if(!a.owner().equals(b.owner()))EnergyTransfer.move(a.storage(),b.storage(),Integer.MAX_VALUE);}
            }
        }
    }
    static <T>T measure(String phase,int iterations,Supplier<T> work)throws Exception {
        System.gc();Thread.sleep(40);
        System.out.println("BEGIN,"+size+","+phase);System.out.flush();
        var running=new AtomicBoolean(true);var peak=new AtomicLong(MEMORY.getHeapMemoryUsage().getUsed());
        var sampler=new Thread(()->{while(running.get()){peak.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(),Math::max);try{Thread.sleep(5);}catch(InterruptedException e){break;}}},"heap-sampler");sampler.setDaemon(true);sampler.start();
        long allocated=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=THREAD.getCurrentThreadCpuTime(),process=OS.getProcessCpuTime(),wall=System.nanoTime();
        T value;
        try{value=work.get();}finally{running.set(false);}
        long elapsed=System.nanoTime()-wall,usedCpu=THREAD.getCurrentThreadCpuTime()-cpu,processCpu=OS.getProcessCpuTime()-process,bytes=THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
        peak.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(),Math::max);sampler.join();
        var line=String.format(Locale.ROOT,"%d,%s,%d,%.6f,%.6f,%.6f,%d,%d%n",size,phase,iterations,elapsed/1e6,usedCpu/1e6,processCpu/1e6,bytes,peak.get());
        Files.writeString(out.resolve(metrics),line,StandardOpenOption.APPEND);System.out.print("RESULT,"+line);System.out.flush();return value;
    }
    static void warmup(){
        var f=fixture(32,8,128);var data=new DataBus(f.wires(),f.ports());var power=new DataBus(f.wires(),f.ports(),CableMedium.POWER);var previous=new Previous(f,power);var grid=new PowerGrid(power,f.ports(),f.stores()::get);
        for(int i=0;i<1500;i++){previous.tick();grid.tick();for(var l:f.loads())l.consume();blackhole+=data.connected(f.dataFirst()[i%32]).size();}
    }
    static void run(int channels,int loose)throws Exception {
        int length=128;size=2*channels*length;var f=measure("facts",1,()->fixture(channels,length,loose));
        var data=measure("data_rebuild",1,()->new DataBus(f.wires(),f.ports()));
        var power=measure("power_rebuild",1,()->new DataBus(f.wires(),f.ports(),CableMedium.POWER));
        var grid=measure("power_compile",1,()->new PowerGrid(power,f.ports(),f.stores()::get));
        require(data.circuits().size()==channels,"data circuit count");require(power.circuits().size()==channels,"power circuit count");
        for(int i=0;i<channels;i++){
            require(data.connected(f.dataFirst()[i],f.dataLast()[i]),"data reaches controller");require(power.connected(f.powerFirst()[i],f.powerLast()[i]),"power reaches load");
            require(!data.connected(f.powerFirst()[i],f.powerLast()[i]),"power cannot join data");require(!power.connected(f.dataFirst()[i],f.dataLast()[i]),"data cannot join power");
        }
        int queries=1_000_000;
        var facts=measure("facts_index",1,()->{var value=new ControlFacts();for(var c:f.wires())value.cable(c);for(var p:f.ports().values())value.port(p);return value;});
        measure("wired_cache_build",1,()->{require(facts.wired(f.dataFirst()[0]),"data wiring index");require(facts.wired(f.powerFirst()[0]),"power wiring index");return null;});
        int bindings=32;
        measure("wired_lookup_previous",bindings,()->{for(int i=0;i<bindings;i++){int k=(i*1729)%channels;var id=i%2==0?f.dataFirst()[k]:f.powerFirst()[k];require(facts.cables().stream().anyMatch(c->c.endpoints().containsValue(id)),"old wiring lookup");}return null;});
        measure("wired_lookup_current",bindings,()->{for(int i=0;i<bindings;i++){int k=(i*1729)%channels;require(facts.wired(i%2==0?f.dataFirst()[k]:f.powerFirst()[k]),"cached wiring lookup");}return null;});
        var oldIndex=new HashMap<UUID,Set<UUID>>();for(var group:data.circuits())for(var id:group)oldIndex.put(id,group);var immutable=Map.copyOf(oldIndex);
        measure("data_lookup_previous",queries,()->{long count=0;for(int i=0;i<queries;i++){int k=i%channels;if(immutable.getOrDefault(f.dataFirst()[k],Set.of(f.dataFirst()[k])).contains(f.dataLast()[k]))count++;}require(count==queries,"old data membership");blackhole=count;return count;});
        measure("data_lookup_current",queries,()->{long count=0;for(int i=0;i<queries;i++){int k=i%channels;if(data.connected(f.dataFirst()[k],f.dataLast()[k]))count++;}require(count==queries,"data membership");blackhole=count;return count;});
        int ticks=channels>2048?100:300;var previous=new Previous(f,power);
        reset(f);measure("power_tick_previous",ticks,()->{for(int i=0;i<ticks;i++){previous.tick();for(var l:f.loads())l.consume();}balance(f,ticks,channels);return null;});
        reset(f);measure("power_tick_current",ticks,()->{for(int i=0;i<ticks;i++){grid.tick();for(var l:f.loads())l.consume();}balance(f,ticks,channels);return null;});
        reset(f);f.stores().clear();
        measure("power_unloaded_previous",ticks,()->{for(int i=0;i<ticks;i++)previous.tick();balance(f,0,0);return null;});
        measure("power_unloaded_current",ticks,()->{for(int i=0;i<ticks;i++)grid.tick();balance(f,0,0);return null;});
        // Structural removal: registered ports remain, but both media must break their first circuit.
        var cut=new ArrayList<>(f.wires());cut.removeIf(c->c.site().cell().getX()==length/2&&c.site().cell().getZ()<2);
        measure("mixed_rebuild_after_cut",1,()->{var d=new DataBus(cut,f.ports());var p=new DataBus(cut,f.ports(),CableMedium.POWER);require(!d.connected(f.dataFirst()[0],f.dataLast()[0]),"data cut disconnects");require(!p.connected(f.powerFirst()[0],f.powerLast()[0]),"power cut disconnects");return null;});
        System.out.println("CASE_PASS,"+size+",ports="+f.ports().size());System.out.flush();
    }
    static void routing()throws Exception {
        size=0;
        for(var medium:CableMedium.values())measure(medium.name().toLowerCase()+"_routing",400,()->{
            int found=0;for(int i=0;i<400;i++){
                var start=new PipeRoute.End(new PipeRoute.Cell(0,0,0),-1);var end=new PipeRoute.End(new PipeRoute.Cell(20+i%20,0,3+i%9),-1);
                var obstacle=new PipeRoute.Cell(10+i%6,0,0);var space=PipeRoute.Space.of(p->p.equals(obstacle)?PipeRoute.Room.BLOCKED:PipeRoute.Room.FREE,List.of());
                var route=PipeRoute.search(start,5,end,medium.outside(),space,PipeRoute.Limits.DEFAULT);
                require(route.found(),"route found");require(PipeRoute.check(route.cells(),route.options(),start,end,128)==null,"server accepts searched route");
                require(PipeRoute.blocked(route.cells(),route.options(),start,5,end,medium.outside(),space)<0,"route clearance");found++;
            }return found;
        });
    }
    static void emptySupply()throws Exception {
        int generators=256,loads=4096,ticks=500;size=generators+loads;
        var ports=new HashMap<UUID,DataPort>();var wires=new ArrayList<DataBus.Cable>();var stores=new HashMap<PowerGrid.Address,IEnergyStorage>();var probes=new long[2];
        for(int i=0;i<generators+loads;i++){
            boolean source=i<generators;var cell=new BlockPos(i,0,0);var face=source?Direction.WEST:Direction.EAST;var id=identity(3,i+1);
            var p=new DataPort(id,source?DataPort.Kind.POWER_OUTPUT:DataPort.Kind.POWER_INPUT,0,DIM,cell.relative(face),cell.getCenter(),cell,face,0,0);ports.put(id,p);
            wires.add(new DataBus.Cable(new DataBus.Site(DIM,cell,0),new ConduitGeometry.Key(48,0,CableMedium.POWER),0,Map.of(face,id)));
            stores.put(new PowerGrid.Address(DIM,p.owner(),face.getOpposite()),source?new EnergyStorage(1000,0,64){
                @Override public int extractEnergy(int amount,boolean simulate){probes[0]++;return super.extractEnergy(amount,simulate);}
            }:new EnergyStorage(1000,64,0){@Override public boolean canReceive(){probes[1]++;return true;}});
        }
        var grid=new PowerGrid(new DataBus(wires,ports,CableMedium.POWER),ports,stores::get);
        measure("shared_zero_supply",ticks,()->{for(int i=0;i<ticks;i++)grid.tick();require(probes[0]==(long)generators*ticks,"one source check per generator/tick");require(probes[1]==0,"zero supply skips all 4096 loads");return null;});
    }
    public static void main(String[] args)throws Exception {
        out=Path.of(args[0]);representative=args.length>1&&args[1].equals("representative");if(representative)metrics="metrics-representative.csv";Files.createDirectories(out);THREAD.setThreadAllocatedMemoryEnabled(true);
        Files.writeString(out.resolve(metrics),"cables,phase,iterations,wall_ms,thread_cpu_ms,process_cpu_ms,allocated_bytes,sampled_heap_peak_bytes\n");
        System.out.println("ENV,pid="+ProcessHandle.current().pid()+",processors="+OS.getAvailableProcessors()+",heapMax="+Runtime.getRuntime().maxMemory());System.out.flush();
        warmup();if(representative){run(3906,64000);emptySupply();}else{routing();for(int channels:new int[]{1,32,256,2048,3906})run(channels,channels>=2048?64000:channels*4);}
        System.out.println("ALL_PASS,checks="+checks+",blackhole="+blackhole);System.out.flush();
    }
}
