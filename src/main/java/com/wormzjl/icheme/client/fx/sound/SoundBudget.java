package com.wormzjl.icheme.client.fx.sound;

import java.util.*;

/**
 * The client's one loop budget (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md section 2, S3 A, S4 A),
 * free of the game so it can be tested: the sources of one kind within {@link #MERGE} blocks merge into one loop at their
 * loudness-weighted centre, their levels summed by energy ({@link #merge}); at most {@link #LOOPS} loops play, chosen by
 * their level at the listener, Lw − 20 log10 r, a playing loop kept until another beats it by {@link #HYSTERESIS} dB
 * ({@link #choose}); a level becomes a Minecraft volume by {@link #volume}.
 */
public final class SoundBudget {
    /** The most IChemE loops that play at once. */
    public static final int LOOPS=12;
    /** Sources of one kind within this distance of a group's loudest member merge into it, blocks. */
    public static final double MERGE=6;
    /** A playing loop stays chosen unless a silent one is this much louder at the listener, dB. */
    public static final double HYSTERESIS=3;
    /** A loop heard below this level at the listener is not played, dB. */
    public static final double AUDIBLE=30;
    private SoundBudget(){}

    /** One sounding source: its loop ({@code kind}), its place, its sound power level, dB, and its loop's pitch; {@code id} names it (its position). */
    public record Emitter(String kind,long id,double x,double y,double z,double level,double pitch) {
        public Emitter{Objects.requireNonNull(kind);}
    }
    /** One loop: the sources merged into it, named by its kind and its loudest member's id; its centre, summed level and pitch. */
    public record Group(String kind,long anchor,double x,double y,double z,double level,double pitch) {
        public String key(){return kind+"@"+anchor;}
        /** The level heard at ({@code x}, {@code y}, {@code z}), dB: spherical spreading from the group's centre. */
        public double heard(double px,double py,double pz){double dx=x-px,dy=y-py,dz=z-pz;return heardAt(level,Math.sqrt(dx*dx+dy*dy+dz*dz));}
    }
    /** {@code level} dB heard at {@code distance} blocks: Lw − 20 log10 r, r at least 1. */
    public static double heardAt(double level,double distance){return level-20*Math.log10(Math.max(1,distance));}

    /**
     * The groups of {@code emitters}: by kind, the loudest first; each joins the first group of its kind whose loudest
     * member lies within {@link #MERGE} blocks, else starts one. A group's level is its members' summed by energy,
     * 10 log10 Σ 10^(L/10); its centre and pitch are weighted by each member's energy 10^(L/10).
     */
    public static List<Group> merge(List<Emitter> emitters) {
        var sorted=new ArrayList<>(emitters);
        sorted.removeIf(e->!Double.isFinite(e.level())||!Double.isFinite(e.x()+e.y()+e.z()));
        sorted.sort(Comparator.comparing(Emitter::kind).thenComparingDouble(e->-e.level()).thenComparingLong(Emitter::id));
        final class Building{final Emitter first;double energy,x,y,z,pitch;Building(Emitter e){first=e;}}
        var groups=new ArrayList<Building>();var byKind=new HashMap<String,List<Building>>();
        for(var e:sorted) {
            Building into=null;
            for(var g:byKind.getOrDefault(e.kind(),List.of())){double dx=g.first.x()-e.x(),dy=g.first.y()-e.y(),dz=g.first.z()-e.z();if(dx*dx+dy*dy+dz*dz<=MERGE*MERGE){into=g;break;}}
            if(into==null){into=new Building(e);groups.add(into);byKind.computeIfAbsent(e.kind(),k->new ArrayList<>()).add(into);}
            double w=Math.pow(10,e.level()/10);into.energy+=w;into.x+=w*e.x();into.y+=w*e.y();into.z+=w*e.z();into.pitch+=w*e.pitch();
        }
        var out=new ArrayList<Group>(groups.size());
        for(var g:groups)out.add(g.energy>0?new Group(g.first.kind(),g.first.id(),g.x/g.energy,g.y/g.energy,g.z/g.energy,10*Math.log10(g.energy),g.pitch/g.energy)
                :new Group(g.first.kind(),g.first.id(),g.first.x(),g.first.y(),g.first.z(),g.first.level(),g.first.pitch()));
        return out;
    }

    /**
     * The groups to play for a listener at ({@code x}, {@code y}, {@code z}): the {@link #LOOPS} loudest there, those
     * {@code playing} (by {@link Group#key}) counted {@link #HYSTERESIS} dB louder, none heard below {@link #AUDIBLE} dB
     * or beyond its reach ({@link #reach}). Loudest first.
     */
    public static List<Group> choose(List<Group> groups,double x,double y,double z,Set<String> playing) {
        record Ranked(Group group,double score){}
        var ranked=new ArrayList<Ranked>();
        for(var g:groups) {
            double heard=g.heard(x,y,z),dx=g.x()-x,dy=g.y()-y,dz=g.z()-z;
            if(heard<AUDIBLE||dx*dx+dy*dy+dz*dz>reach(g.level())*reach(g.level()))continue;
            ranked.add(new Ranked(g,heard+(playing.contains(g.key())?HYSTERESIS:0)));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r)->-r.score).thenComparing(r->r.group.key()));
        return ranked.stream().limit(LOOPS).map(Ranked::group).toList();
    }

    /**
     * A sound power level's Minecraft volume. At and below 100 dB, 10^((Lw − 100) / 20): the amplitude (100 dB → 1, 90 dB
     * → 0.32, 80 dB → 0.1; a mover's casing gain g is the level 100 + 20 log10 g). Above, Minecraft caps the gain at 1 and
     * lengthens the reach to 16 × volume blocks, so the louder sources carry farther: 3^((Lw − 100) / 20), 120 dB → 3
     * (48 blocks), at most 3.
     */
    public static float volume(double level) {
        if(!Double.isFinite(level))return 0;
        return (float)(level<=100?Math.pow(10,(level-100)/20):Math.min(3,Math.pow(3,(level-100)/20)));
    }
    /** The level whose volume is {@code gain} (gain at most 1): 100 + 20 log10 gain, dB; for a mover's or driver's casing gain. */
    public static double level(double gain){return gain>0?100+20*Math.log10(Math.min(gain,1)):Double.NEGATIVE_INFINITY;}
    /** How far a loop of {@code level} dB reaches, blocks: Minecraft's linear attenuation, 16 × max(volume, 1). */
    public static double reach(double level){return 16*Math.max(1,volume(level));}
}
