package com.wormzjl.icheme.runtime.fluid;

import java.util.*;
import net.minecraft.core.BlockPos;

/**
 * The plant's noise (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md, owner decisions S1 A to S4 A): the
 * sound power level Lw (dB re 1 pW) of each source the review's table 1 names, computed once per presentation publication
 * per island from what the island last accepted, and kept by chunk; one noise watch answers from these levels with no
 * solver run (section 2), the {@link #MAXIMUM_SOURCES} loudest at the listener within {@link #RANGE} blocks.
 * Sounds read state only: nothing here is saved, solved, created or fed back.
 * <ul>
 * <li>{@link #valve}: a valve passing flow, by its pressure drop alone (owner, 2026-11-10); a liquid valve's loop by its
 * cavitation (owner 2026-11-10, option A: plain, cavitating or flashing, {@code PassiveStepSolver.cavitation}).</li>
 * <li>{@link #vent}: an open vessel's vent, a free jet (Lighthill: its acoustic share 1e-4 M⁵ of its stream power).</li>
 * <li>{@link #fans}: axial fans, the fan law 50 log10 of the speed share.</li>
 * <li>{@link #water}: falling water at a tower's air inlets, 10 log10 of the water flow.</li>
 * <li>{@link #burner}: a fired heater's burners, 10 log10 of the heat released.</li>
 * <li>{@link #gear}: a gearbox's mesh, of its rated power and speed share.</li>
 * <li>{@link #kettle}: a kettle reboiler's boiling pool, muffled by its shell.</li>
 * <li>{@link #knock}: a liquid line stopped between two publications, a one-shot by its Joukowsky surge.</li>
 * </ul>
 * Steam ejectors have no live state in the world (only the simulator's sheet), so they make none.
 */
public final class Noise {
    /** What makes the noise; the client picks its loop by this, a one-shot for the knock. */
    public enum Kind { VALVE_GAS, VALVE_LIQUID, VENT, FAN, WATER_FALL, BURNER, GEAR, KETTLE, PIPE_KNOCK, VALVE_CAVITATING, VALVE_FLASHING;
        public boolean oneShot(){return this==PIPE_KNOCK;}
    }
    /** One source: where, what, its sound power level, dB, and its loop's pitch. */
    public record Source(BlockPos position,Kind kind,float level,float pitch) {
        public Source{position=position.immutable();Objects.requireNonNull(kind);
            if(!(level>=0&&level<=CAP)||!(pitch>=.5f&&pitch<=2))throw new IllegalArgumentException("Invalid noise source");}
    }
    /** The loudest level a source has, dB (a valve at about 6.3 MPa of drop). */
    public static final double CAP=120;
    /** A valve below this drop is silent, Pa; there it gives {@link #VALVE_FLOOR} dB. */
    public static final double REFERENCE_DROP=20e3,VALVE_FLOOR=70;
    /** The watch's reach, blocks, and the most sources one answer carries. */
    public static final double RANGE=48;
    public static final int MAXIMUM_SOURCES=16;
    /** Below this flowing density a stream is gas or steam, kg/m³ (liquids run from about 450, LPG, up). */
    public static final double GAS_DENSITY=300;
    /** The wave speed of a liquid line, m/s: water's 1,480 lowered by the steel wall's give (Korteweg). */
    public static final double LIQUID_WAVE_SPEED=1200;
    /** A surge below this makes no knock, Pa (water stopped from about 0.17 m/s). */
    public static final double KNOCK_FLOOR=2e5;
    /** One fan's level at full speed, dB: an air cooler's (95 to 100 dB) and a tower cell's larger fan. */
    public static final double COOLER_FAN=97,TOWER_FAN=100;
    /** The speed of sound in air, m/s, and the ambient pressure a vent leaves at, Pa. */
    static final double AIR_SOUND=340,AMBIENT=101325,GAS_CONSTANT=8.314462618;
    /** Ticks between two answers to one player, and after which a player that stopped asking is no longer a watcher. */
    public static final long ANSWER_TICKS=10,WATCH_TICKS=60;

    private Noise(){}

    /** The level {@code level} dB gives at {@code distance} blocks, dB: spherical spreading, 20 log10 r (r at least 1). */
    public static double at(double level,double distance){return level-20*Math.log10(Math.max(1,distance));}
    /** Levels summed by energy, dB: 10 log10 Σ 10^(L/10). */
    public static double sum(double... levels){double e=0;for(double l:levels)e+=Math.pow(10,l/10);return e>0?10*Math.log10(e):Double.NEGATIVE_INFINITY;}
    private static double capped(double level){return Double.isFinite(level)?Math.min(CAP,level):Double.NaN;}

    /** A valve passing flow with a drop of {@code drop} Pa: 70 + 20 log10(ΔP / 20 kPa) dB, NaN (silent) below 20 kPa, at most 120 dB. */
    public static double valve(double drop){return drop>=REFERENCE_DROP?capped(VALVE_FLOOR+20*Math.log10(drop/REFERENCE_DROP)):Double.NaN;}
    /**
     * A vent of {@code massFlow} kg/s of gas of density {@code density} kg/m³ through {@code area} m² (its roof): a free jet
     * at u = ṁ / (ρ A), its acoustic power 1e-4 (u/a)⁵ of its stream power ṁ u² / 2 (Lighthill's u⁸). 100 m/s through 1 m²
     * of air gives about 111 dB, 10 m/s about 31 dB. NaN for no flow.
     */
    public static double vent(double massFlow,double density,double area) {
        if(!(massFlow>0)||!(density>0)||!(area>0))return Double.NaN;
        double u=Math.min(com.wormzjl.icheme.science.fluid.network.OpenVessel.VENT_SPEED,massFlow/(density*area));
        double watt=1e-4*Math.pow(u/AIR_SOUND,5)*.5*massFlow*u*u;
        return watt>0?capped(10*Math.log10(watt/1e-12)):Double.NaN;
    }
    /** {@code count} fans of {@code full} dB each at full speed, at speed share {@code speed}: full + 10 log10 n + 50 log10 speed (fan law). NaN for none or stopped. */
    public static double fans(int count,double full,double speed){return count>0&&speed>0?capped(full+10*Math.log10(count)+50*Math.log10(Math.min(speed,1.5))):Double.NaN;}
    /** Falling water at a tower's air inlets, {@code flow} kg/s: 72 + 10 log10 flow (a 20 t/s natural tower about 115 dB, a 14 m cell's 392 kg/s about 98 dB). */
    public static double water(double flow){return flow>1?capped(72+10*Math.log10(flow)):Double.NaN;}
    /** Burners releasing {@code watt} W: a combustion efficiency of 1e-9 behind the casing, 30 + 10 log10 Q (10 MW: 100 dB). NaN unfired. */
    public static double burner(double watt){return watt>1e4?capped(30+10*Math.log10(watt)):Double.NaN;}
    /** A burner's forced-draft fan, its air following the firing share: {@link #COOLER_FAN} − 7 dB at full firing, the fan law below. */
    public static double draftFan(double firing){return fans(1,COOLER_FAN-7,firing);}
    /** A gearbox rated {@code watt} W at speed share {@code speed}: 75 + 10 log10(P / 1 kW) + 30 log10 speed (1 MW at full speed: 105 dB). */
    public static double gear(double watt,double speed){return watt>0&&speed>0?capped(75+10*Math.log10(watt/1e3)+30*Math.log10(Math.min(speed,1.5))):Double.NaN;}
    /** A kettle reboiler boiling {@code watt} W in its pool, muffled by its shell: 75 + 10 log10(Q / 1 MW). NaN below 10 kW. */
    public static double kettle(double watt){return watt>1e4?capped(75+10*Math.log10(watt/1e6)):Double.NaN;}
    /**
     * A liquid line of density {@code density} kg/m³ stopped from {@code speed} m/s: the Joukowsky surge ΔP = ρ a Δv (water
     * at 2 m/s about 24 bar) on the valve's scale, 70 + 20 log10(ΔP / 20 kPa); NaN below {@link #KNOCK_FLOOR}.
     */
    public static double knock(double density,double speed) {
        double surge=density*LIQUID_WAVE_SPEED*Math.abs(speed);
        return surge>=KNOCK_FLOOR?valve(surge):Double.NaN;
    }
    /** A gas vent's density at the ambient pressure, kg/m³: P M / (R T), of its mean molar mass {@code molarMass} kg/mol. */
    public static double ventDensity(double molarMass,double temperature){return molarMass>0&&temperature>0?AMBIENT*molarMass/(GAS_CONSTANT*temperature):Double.NaN;}

    /** A source of {@code level} dB, or nothing for a silent (NaN) one. */
    static void add(List<Source> into,BlockPos position,Kind kind,double level,double pitch) {
        if(Double.isFinite(level)&&level>0)into.add(new Source(position,kind,(float)Math.min(CAP,level),(float)Math.clamp(pitch,.5,2)));
    }

    /**
     * The levels as published, by chunk, and the watchers that ask for them (presentation state: never saved).
     * {@link #put} replaces an island's sources at its publication; {@link #near} reads them for one listener.
     */
    public static final class Store {
        private record ChunkKey(String dimension,long chunk){}
        private record Placed(String dimension,long tick,List<Source> sources,boolean knock){}
        private final Map<Long,Placed> islands=new HashMap<>();
        private final Map<ChunkKey,Map<Long,List<Source>>> chunks=new HashMap<>();
        /** Each island's liquid lines at its last publication, pipe to its speed, m/s: what a stop is judged against. */
        private final Map<Long,Map<Long,Double>> speeds=new HashMap<>();
        /** The keys the machines' sources (gearboxes) take, one per dimension, apart from the islands' (from Long.MIN_VALUE up). */
        private final Map<String,Long> machineKeys=new HashMap<>();
        private final Map<UUID,Long> asked=new HashMap<>(),answered=new HashMap<>();

        /** Whether {@code island}'s levels were computed at its committed tick {@code tick} and hold no knock (a one-shot stands one cadence). */
        public boolean current(long island,long tick){var p=islands.get(island);return p!=null&&p.tick==tick&&!p.knock;}
        /** Island {@code island}'s sources at its publication of tick {@code tick}, replacing what it had. */
        public void put(long island,String dimension,long tick,List<Source> sources) {
            remove(island);var placed=new Placed(dimension,tick,List.copyOf(sources),sources.stream().anyMatch(s->s.kind().oneShot()));islands.put(island,placed);
            for(var s:placed.sources)chunks.computeIfAbsent(new ChunkKey(dimension,chunk(s.position())),k->new HashMap<>()).computeIfAbsent(island,k->new ArrayList<>()).add(s);
        }
        /** The machines' sources (gearboxes) by dimension, replacing all they had. */
        public void machines(Map<String,List<Source>> byDimension) {
            for(long key:machineKeys.values())remove(key);
            byDimension.forEach((dimension,sources)->put(machineKeys.computeIfAbsent(dimension,d->Long.MIN_VALUE+machineKeys.size()),dimension,0,sources));
        }
        /** Keeps only the islands in {@code keep} (and the machines); the others' levels and line speeds go. */
        public void retain(Set<Long> keep) {
            for(long island:List.copyOf(islands.keySet()))if(!keep.contains(island)&&!machineKeys.containsValue(island))remove(island);
            speeds.keySet().retainAll(keep);
        }
        private void remove(long island) {
            var old=islands.remove(island);if(old==null)return;
            for(var s:old.sources){var key=new ChunkKey(old.dimension,chunk(s.position()));var in=chunks.get(key);if(in!=null){in.remove(island);if(in.isEmpty())chunks.remove(key);}}
        }
        /**
         * The knocks of {@code island}'s liquid lines: each line ({@code now}, pipe to its speed, m/s, NaN for a line that is no
         * longer liquid) that ran at its last publication and stands now; {@code at} places a pipe. Keeps {@code now} for the next.
         */
        public List<Source> knocks(long island,Map<Long,Double> now,Map<Long,Double> densities,java.util.function.LongFunction<BlockPos> at) {
            var before=speeds.put(island,Map.copyOf(now));var out=new ArrayList<Source>();if(before==null)return out;
            for(var e:before.entrySet()) {
                Double v=now.get(e.getKey());double was=Math.abs(e.getValue());
                if(v==null||!Double.isFinite(v)||Math.abs(v)>.1*was)continue;
                var pos=at.apply(e.getKey());Double density=densities.get(e.getKey());
                if(pos!=null&&density!=null)add(out,pos,Kind.PIPE_KNOCK,knock(density,was-Math.abs(v)),1);
            }
            return out;
        }
        /**
         * The sources within {@link #RANGE} blocks of ({@code x}, {@code y}, {@code z}) in {@code dimension}, the
         * {@link #MAXIMUM_SOURCES} loudest at that point ({@link Noise#at}) first; a read of the published levels.
         */
        public List<Source> near(String dimension,double x,double y,double z) {
            record Heard(Source source,double level){}
            var heard=new ArrayList<Heard>();int reach=(int)Math.ceil(RANGE/16);int cx=Math.floorDiv((int)Math.floor(x),16),cz=Math.floorDiv((int)Math.floor(z),16);
            for(int i=-reach;i<=reach;i++)for(int j=-reach;j<=reach;j++) {
                var in=chunks.get(new ChunkKey(dimension,net.minecraft.world.level.ChunkPos.asLong(cx+i,cz+j)));if(in==null)continue;
                for(var list:in.values())for(var s:list) {
                    var p=s.position();double dx=p.getX()+.5-x,dy=p.getY()+.5-y,dz=p.getZ()+.5-z,r=Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if(r<=RANGE)heard.add(new Heard(s,at(s.level(),r)));
                }
            }
            heard.sort(Comparator.comparingDouble((Heard h)->-h.level).thenComparingLong(h->h.source.position().asLong()).thenComparing(h->h.source.kind()));
            return heard.stream().limit(MAXIMUM_SOURCES).map(Heard::source).toList();
        }
        /** Player {@code player} asks at tick {@code tick}: whether to answer it now (at most once per {@link #ANSWER_TICKS}). */
        public boolean ask(UUID player,long tick) {
            asked.put(player,tick);Long last=answered.get(player);
            if(last!=null&&tick-last<ANSWER_TICKS&&tick>=last)return false;
            answered.put(player,tick);return true;
        }
        /** The players that asked within {@link #WATCH_TICKS} of {@code tick}; the others are forgotten. */
        public Set<UUID> watchers(long tick){asked.values().removeIf(t->tick-t>WATCH_TICKS);answered.keySet().retainAll(asked.keySet());return Set.copyOf(asked.keySet());}
        public void forget(UUID player){asked.remove(player);answered.remove(player);}
        /** Nobody asks: every level and line speed goes (they are rebuilt at the next publication someone asks for). */
        public void clear(){islands.clear();chunks.clear();speeds.clear();machineKeys.clear();}
        public boolean empty(){return islands.isEmpty();}
        private static long chunk(BlockPos p){return net.minecraft.world.level.ChunkPos.asLong(p.getX()>>4,p.getZ()>>4);}
    }
}
