package com.wormzjl.icheme.client.fx.sound;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.DriverSounds;
import com.wormzjl.icheme.client.MoverSounds;
import com.wormzjl.icheme.client.fx.FxStats;
import com.wormzjl.icheme.client.fx.state.ProcessStates;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.runtime.fluid.Flares;
import com.wormzjl.icheme.runtime.fluid.Noise;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.science.fluid.network.Flare;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Every IChemE loop through one budget (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md section 2, S2 A to
 * S4 A). Client thread only; it adds no process state.
 * <ul>
 * <li>Sources: the noise watch's ({@link #accept}: valves, vents, fans, falling water, burners, gearboxes, kettles, and
 * the one-shot knock), the flares' roar and pilots and the stills' burner, firebox and pot from their watched states
 * ({@link ProcessStates#flares}, {@link ProcessStates#stills}), and the movers' and drivers' loops
 * ({@link MoverSounds#emitters}, {@link DriverSounds#emitters}), whose watches stay theirs.</li>
 * <li>The noise watch's poll once a second ({@link FluidNetwork#requestNoise}).</li>
 * <li>Four times a second, the choice ({@link SoundBudget#merge}, {@link SoundBudget#choose}): at most 12 loops, by level
 * at the listener, 3 dB of hysteresis, one kind merged within 6 m. A loop keeps its targets between choices: its
 * {@code tick()} only eases its volume and pitch toward them, over {@link #EASE} ticks, with no world or map lookups.</li>
 * <li>Counters (FX_BENCH_PLAN.md, {@link FxStats}): the tick's time, the loops playing, the sources gathered and merged,
 * the noise sources last answered.</li>
 * </ul>
 * The towers' and heaters' sounds come from the noise watch, whose levels carry their water flow and fan speed; their
 * plume and flue states ({@code ProcessStates.towers}, {@code heaters}) carry neither, so they add none.
 */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class FxSounds {
    /** Ticks a loop takes to reach new targets (2 s, the gap between two publications is 5 s), and to start or fade out. */
    static final int EASE=40,FADE=10;
    /** A knock at one place plays once per publication: a repeat within this many ticks is the same one. */
    static final long KNOCK_TICKS=120;
    /** A flare at its rated heat, its pilots, dB (plan section 4.5: a flare's roar reaches about 64 blocks at its rating). */
    static final double FLARE_ROAR=120,FLARE_PILOT=85;
    /** A still's burner or firebox at its rating, its boiling pot, dB. */
    static final double STILL_FIRE=88,POT_BOIL=72;
    private static List<Noise.Source> noise=List.of();
    private static final Map<String,Loop> LOOPS=new HashMap<>();
    private static final Map<BlockPos,Long> KNOCKED=new HashMap<>();
    private static ClientLevel world;
    private static int age;
    private FxSounds(){}

    /** The noise watch's answer: the sources it plays from the next choice on; a knock plays now, once. */
    public static void accept(List<Noise.Source> sources) {
        var mc=Minecraft.getInstance();if(mc.level==null){noise=List.of();return;}
        noise=sources.stream().filter(s->!s.kind().oneShot()).toList();FxStats.set(FxStats.Counter.NOISE,noise.size());
        long now=mc.level.getGameTime();KNOCKED.values().removeIf(t->now-t>KNOCK_TICKS||t>now);
        for(var s:sources) {
            if(!s.kind().oneShot()||KNOCKED.containsKey(s.position())||!audible(mc))continue;
            var event=sound(kind(s.kind()));if(event==null)continue;
            KNOCKED.put(s.position(),now);var p=s.position();
            mc.getSoundManager().play(new SimpleSoundInstance(event,SoundSource.BLOCKS,SoundBudget.volume(s.level()),s.pitch(),RandomSource.create(),p.getX()+.5,p.getY()+.5,p.getZ()+.5));
        }
    }
    /** Logging out: every loop stops, every source goes. */
    public static void clear(){stopAll();noise=List.of();KNOCKED.clear();world=null;age=0;}

    /** The loop a noise source plays: a vent hisses as a gas valve does (lower pitched), a kettle boils as a pot does. */
    static String kind(Noise.Kind kind) {
        return switch(kind){case VALVE_GAS,VENT->"valve_gas";case VALVE_LIQUID->"valve_liquid";case VALVE_CAVITATING->"valve_cavitating";case VALVE_FLASHING->"valve_flashing";case FAN->"fan_whoosh";case WATER_FALL->"water_fall";
            case BURNER->"burner_roar";case GEAR->"gear_whine";case KETTLE->"pot_boil";case PIPE_KNOCK->"pipe_knock";case EJECTOR->"ejector_jet";};
    }
    private static SoundEvent sound(String kind){return BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,kind));}
    private static boolean audible(Minecraft mc){return mc.options.getSoundSourceVolume(SoundSource.BLOCKS)>0&&mc.options.getSoundSourceVolume(SoundSource.MASTER)>0;}
    private static void stopAll(){var manager=Minecraft.getInstance().getSoundManager();for(var loop:LOOPS.values())manager.stop(loop);LOOPS.clear();}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        long started=System.nanoTime();
        try{choose();}finally{FxStats.set(FxStats.Counter.SOUND_NS,System.nanoTime()-started);FxStats.set(FxStats.Counter.LOOPS,LOOPS.size());}
    }
    private static void choose() {
        var mc=Minecraft.getInstance();
        if(world!=mc.level){stopAll();noise=List.of();KNOCKED.clear();world=mc.level;age=0;}
        if(mc.level==null||mc.player==null||mc.getConnection()==null)return;
        int tick=age++;
        if(!audible(mc)){if(!LOOPS.isEmpty())stopAll();return;}
        if(tick%20==0)FluidNetwork.requestNoise();
        if(tick%5!=0)return;
        var point=mc.player.position();var emitters=new ArrayList<SoundBudget.Emitter>();
        for(var s:noise){var p=s.position();emitters.add(new SoundBudget.Emitter(kind(s.kind()),p.asLong(),p.getX()+.5,p.getY()+.5,p.getZ()+.5,s.level(),s.pitch()));}
        flares(emitters);stills(emitters);
        MoverSounds.emitters(point,emitters);DriverSounds.emitters(point,emitters);
        var playing=new HashSet<String>();for(var e:LOOPS.entrySet())if(!e.getValue().fading&&mc.getSoundManager().isActive(e.getValue()))playing.add(e.getKey());
        var merged=SoundBudget.merge(emitters);var chosen=SoundBudget.choose(merged,point.x,point.y,point.z,playing);
        FxStats.set(FxStats.Counter.SOUND_SOURCES,emitters.size());FxStats.set(FxStats.Counter.SOUND_GROUPS,merged.size());
        var keys=new HashSet<String>();
        for(var g:chosen) {
            keys.add(g.key());var loop=LOOPS.get(g.key());
            if(loop!=null&&!loop.fading&&mc.getSoundManager().isActive(loop)){loop.aim(g,EASE);continue;}
            if(loop!=null)mc.getSoundManager().stop(loop);
            var sound=sound(g.kind());if(sound==null)continue;
            loop=new Loop(sound,mc.level,g);LOOPS.put(g.key(),loop);mc.getSoundManager().play(loop);
        }
        for(var it=LOOPS.entrySet().iterator();it.hasNext();) {
            var e=it.next();var loop=e.getValue();
            if(!mc.getSoundManager().isActive(loop)&&(loop.started||loop.fading)){mc.getSoundManager().stop(loop);it.remove();continue;}
            if(!keys.contains(e.getKey())&&!loop.fading)loop.fade();
        }
    }
    /**
     * The watched flares (W3 states): the roar at the tip while it burns, 120 + 10 log10 of its flame (its heat over its
     * rating; plan section 4.5), and the pilots' hiss at the tip unless its flame is out.
     */
    private static void flares(List<SoundBudget.Emitter> into) {
        var statuses=Flares.Status.values();
        for(var w:ProcessStates.flares()) {
            var shape=w.cell().shape();var origin=w.cell().origin();var s=w.state();if(shape==null||origin==null||s==null||s.status()>=statuses.length)continue;
            var status=statuses[s.status()];var p=w.target().position();
            double x=p.getX()+.5,y=origin.getY()+Flare.stackHeight(shape.diameter()),z=p.getZ()+.5;
            if(s.flame()>0)into.add(new SoundBudget.Emitter("flare_roar",p.asLong(),x,y,z,FLARE_ROAR+10*Math.log10(s.flame()),1));
            if(status!=Flares.Status.FLAME_OUT)into.add(new SoundBudget.Emitter("flare_pilot",p.asLong(),x,y,z,FLARE_PILOT,1));
        }
    }
    /**
     * The watched stills (W6 states), at the pot: a liquid burner's roar or a solid firebox's draught, 88 + 10 log10 of its
     * fire (its heat over its rating), none for an electric one; the pot's boil while it boils.
     */
    private static void stills(List<SoundBudget.Emitter> into) {
        var heaters=BatchStill.Heater.values();
        for(var w:ProcessStates.stills()) {
            var s=w.state();if(s==null||s.heater()>=heaters.length)continue;var p=w.target().position();
            double x=p.getX()+.5,y=p.getY()+.5,z=p.getZ()+.5;var heater=heaters[s.heater()];
            if(s.fire()>0&&heater!=BatchStill.Heater.ELECTRIC)into.add(new SoundBudget.Emitter(heater==BatchStill.Heater.SOLID?"fire_draught":"burner_roar",p.asLong(),x,y,z,STILL_FIRE+10*Math.log10(s.fire()),1));
            if(s.boiling())into.add(new SoundBudget.Emitter("pot_boil",p.asLong(),x,y,z,POT_BOIL,1));
        }
    }

    /** One budget loop: it eases its volume and pitch toward the targets its last choice set, and nothing else. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final ClientLevel level;
        private float aimVolume,aimPitch,stepVolume,stepPitch;
        boolean fading,started;
        private Loop(SoundEvent event,ClientLevel level,SoundBudget.Group group) {
            super(event,SoundSource.BLOCKS,RandomSource.create());
            this.level=level;looping=true;delay=0;volume=0;pitch=(float)Math.clamp(group.pitch(),.5,2);
            attenuation=SoundInstance.Attenuation.LINEAR;aim(group,FADE);
        }
        /** New targets from a choice: the group's centre at once, its volume and pitch over {@code ticks}. */
        void aim(SoundBudget.Group group,int ticks) {
            x=group.x();y=group.y();z=group.z();
            aimVolume=SoundBudget.volume(group.level());aimPitch=(float)Math.clamp(group.pitch(),.5,2);
            stepVolume=Math.abs(aimVolume-volume)/ticks;stepPitch=Math.abs(aimPitch-pitch)/ticks;
        }
        /** Dropped from the choice: it fades out and stops. */
        void fade(){fading=true;aimVolume=0;stepVolume=Math.max(volume/FADE,1e-3f);}
        /** Starts silent and eases in (the sound engine would drop it otherwise). */
        @Override public boolean canStartSilent(){return true;}
        @Override public void tick() {
            started=true;
            if(Minecraft.getInstance().level!=level){stop();return;}
            volume=toward(volume,aimVolume,stepVolume);pitch=toward(pitch,aimPitch,stepPitch);
            if(fading&&volume<=0)stop();
        }
        private static float toward(float value,float aim,float step){return value<aim?Math.min(aim,value+step):Math.max(aim,value-step);}
    }
}
