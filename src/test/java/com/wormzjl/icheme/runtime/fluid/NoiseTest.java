package com.wormzjl.icheme.runtime.fluid;

import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The plant's noise levels (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md): each law against the
 * review's numbers, and the store the noise watch reads: the 16 loudest at the listener within 48 blocks, an island's
 * levels kept until it publishes again, a knock for one cadence, one answer per half second per player.
 */
class NoiseTest {
    private static final String OVERWORLD="minecraft:overworld";

    @Test void aValveFollowsItsDropAlone() {
        assertEquals(104,Noise.valve(1e6),.1,"1 MPa: about 104 dB (owner, 2026-11-10)");
        assertEquals(70,Noise.valve(20e3),1e-9,"20 kPa: the threshold, 70 dB");
        assertTrue(Double.isNaN(Noise.valve(19.9e3)),"below 20 kPa: silent");
        assertTrue(Double.isNaN(Noise.valve(0)));
        assertEquals(120,Noise.valve(6.4e6),1e-9,"capped at 120 dB (about 6.3 MPa)");
        assertEquals(120,Noise.valve(50e6),1e-9);
        assertEquals(76.02,Noise.valve(40e3),.01,"doubling the drop: +6 dB");
    }

    @Test void theOtherSourcesFollowTheirLaws() {
        // A vent: Lighthill's u⁸, loud at 100 m/s, nothing at 10 m/s (1 m² of air).
        assertEquals(111.2,Noise.vent(120,1.2,1),.2);
        assertEquals(31.2,Noise.vent(12,1.2,1),.2);
        assertEquals(Noise.vent(12,1.2,.1)+10,Noise.vent(120,1.2,.1),1e-9,"no faster than the vent's 100 m/s: ten times the flow, ten times the power (0.1 m², below the 120 dB cap)");
        assertEquals(Noise.CAP,Noise.vent(1200,1.2,1),1e-9,"capped at 120 dB");
        assertEquals(1.18,Noise.ventDensity(.02897,298.15),.01,"air at the ambient pressure");
        // Fans: 50 log10 of the speed share; ten of them +10 dB.
        assertEquals(97,Noise.fans(1,Noise.COOLER_FAN,1),1e-9);
        assertEquals(97-15.05,Noise.fans(1,Noise.COOLER_FAN,.5),.01,"half speed: −15 dB");
        assertEquals(107,Noise.fans(10,Noise.COOLER_FAN,1),1e-9);
        assertTrue(Double.isNaN(Noise.fans(4,Noise.COOLER_FAN,0)),"stopped: silent");
        // Falling water: 10 log10 of the flow.
        assertEquals(115,Noise.water(2e4),.1,"a 20 t/s natural tower");
        assertEquals(97.9,Noise.water(392),.1,"a 14 m cell at 2 kg/(s m²)");
        assertEquals(100,Noise.burner(1e7),1e-9,"10 MW of burners");
        assertEquals(105,Noise.gear(1e6,1),1e-9,"a 1 MW gearbox at full speed");
        assertEquals(75,Noise.kettle(1e6),1e-9,"a 1 MW kettle, muffled by its shell");
        // A water hammer: water stopped from 2 m/s, ρ a Δv = 2.4 MPa (about 24 bar), on the valve's scale.
        assertEquals(Noise.valve(2.4e6),Noise.knock(1000,2),1e-9);
        assertTrue(Double.isNaN(Noise.knock(1000,.1)),"a gentle stop does not knock");
        assertEquals(Noise.sum(100,100),103.01,.01,"two equal sources: +3 dB");
        assertEquals(80,Noise.at(100,10),1e-9,"20 log10 r");
    }

    private static Noise.Source valve(int x,double level){return new Noise.Source(new BlockPos(x,64,0),Noise.Kind.VALVE_GAS,(float)level,1);}

    @Test void theWatchHearsTheSixteenLoudestWithin48Blocks() {
        var store=new Noise.Store();var sources=new ArrayList<Noise.Source>();
        for(int i=0;i<30;i++)sources.add(valve(2*i,90));
        sources.add(valve(40,119));
        sources.add(valve(60,120));
        sources.add(new Noise.Source(new BlockPos(0,64,0),Noise.Kind.FAN,100,1));
        store.put(1,OVERWORLD,10,sources);
        var heard=store.near(OVERWORLD,.5,64.5,.5);
        assertEquals(Noise.MAXIMUM_SOURCES,heard.size());
        assertEquals(Noise.Kind.FAN,heard.get(0).kind(),"the loudest at the listener: 100 dB at 1 block");
        assertTrue(heard.stream().noneMatch(s->s.position().getX()==60),"60 blocks away: outside the watch, however loud");
        assertTrue(heard.contains(valve(40,119)),"119 dB at 40 blocks is still 87 dB there");
        for(int i=1;i<heard.size();i++)assertTrue(Noise.at(heard.get(i-1).level(),dist(heard.get(i-1)))>=Noise.at(heard.get(i).level(),dist(heard.get(i))),"loudest first");
        assertEquals(List.of(),store.near("minecraft:the_nether",.5,64.5,.5),"another dimension hears none");
        // An island's levels stand until it commits a new interval; one it no longer holds goes.
        assertTrue(store.current(1,10));assertFalse(store.current(1,11));
        store.retain(Set.of(2L));
        assertEquals(List.of(),store.near(OVERWORLD,.5,64.5,.5));
    }
    private static double dist(Noise.Source s){var p=s.position();return Math.sqrt(p.getX()*p.getX()+p.getZ()*(double)p.getZ());}

    @Test void aLiquidLineStoppedKnocksOnceAndTheMachinesStandApart() {
        var store=new Noise.Store();var at=new BlockPos(5,64,5);
        assertEquals(List.of(),store.knocks(7,Map.of(3L,2.0),Map.of(3L,1000.0),p->at),"the first publication has nothing to compare");
        var knocks=store.knocks(7,Map.of(3L,0.0),Map.of(3L,1000.0),p->at);
        assertEquals(1,knocks.size());
        assertEquals(Noise.Kind.PIPE_KNOCK,knocks.get(0).kind());
        assertEquals(Noise.knock(1000,2),knocks.get(0).level(),1e-4);
        store.put(7,OVERWORLD,20,knocks);
        assertFalse(store.current(7,20),"a knock stands one cadence: the island is read again");
        assertEquals(List.of(),store.knocks(7,Map.of(3L,0.0),Map.of(3L,1000.0),p->at),"still standing: no second knock");
        assertEquals(List.of(),store.knocks(7,Map.of(3L,1.9),Map.of(3L,1000.0),p->at));
        assertEquals(List.of(),store.knocks(7,Map.of(3L,1.5),Map.of(3L,1000.0),p->at),"slowed, not stopped: no knock");
        // The gearboxes are kept by dimension, apart from the islands.
        store.machines(Map.of(OVERWORLD,List.of(new Noise.Source(new BlockPos(0,64,0),Noise.Kind.GEAR,100,1))));
        store.retain(Set.of());
        assertEquals(1,store.near(OVERWORLD,.5,64.5,.5).size(),"an island retain keeps the machines");
        store.machines(Map.of());
        assertEquals(List.of(),store.near(OVERWORLD,.5,64.5,.5),"machines replaced as a whole");
    }

    @Test void aPlayerIsAnsweredAtMostTwiceASecondAndForgottenWhenItStopsAsking() {
        var store=new Noise.Store();var player=UUID.randomUUID();
        assertTrue(store.ask(player,100));
        assertFalse(store.ask(player,105),"within half a second: no answer");
        assertTrue(store.ask(player,110));
        assertEquals(Set.of(player),store.watchers(150));
        assertEquals(Set.of(),store.watchers(171),"no request for 3 s: no longer a watcher");
        assertTrue(store.ask(player,172),"a returning player is answered at once");
        store.forget(player);
        assertEquals(Set.of(),store.watchers(172));
    }

    @Test void aSourceIsValidated() {
        assertThrows(IllegalArgumentException.class,()->new Noise.Source(BlockPos.ZERO,Noise.Kind.FAN,121,1));
        assertThrows(IllegalArgumentException.class,()->new Noise.Source(BlockPos.ZERO,Noise.Kind.FAN,Float.NaN,1));
        assertThrows(IllegalArgumentException.class,()->new Noise.Source(BlockPos.ZERO,Noise.Kind.FAN,90,3));
        var into=new ArrayList<Noise.Source>();
        Noise.add(into,BlockPos.ZERO,Noise.Kind.VALVE_GAS,Noise.valve(1e3),1);
        assertEquals(List.of(),into,"a silent source is not kept");
    }
}
