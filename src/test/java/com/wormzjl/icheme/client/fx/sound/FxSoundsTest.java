package com.wormzjl.icheme.client.fx.sound;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The one loop budget (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md section 2, S3 A): sources of one
 * kind within 6 m merge at their loudness-weighted centre with their levels summed by energy; at most 12 loops play, the
 * loudest at the listener; a playing loop keeps its place against a rival less than 3 dB louder; the level-to-volume map.
 */
class FxSoundsTest {
    private static SoundBudget.Emitter at(String kind,long id,double x,double level){return new SoundBudget.Emitter(kind,id,x,64,0,level,1);}

    @Test void sourcesOfOneKindWithin6mMergeByEnergy() {
        var groups=SoundBudget.merge(List.of(at("valve_gas",1,0,90),at("valve_gas",2,4,90),at("valve_gas",3,20,80),at("fan_whoosh",4,1,90)));
        assertEquals(3,groups.size(),"the two valves 4 m apart merge; the far valve and the fan stand alone");
        var merged=groups.stream().filter(g->g.kind().equals("valve_gas")&&g.anchor()!=3).findFirst().orElseThrow();
        assertEquals(93.01,merged.level(),.01,"two equal sources: +3 dB, by energy");
        assertEquals(2,merged.x(),1e-9,"at their loudness-weighted centre");
        var unequal=SoundBudget.merge(List.of(at("valve_gas",1,0,100),at("valve_gas",2,5,90)));
        assertEquals(1,unequal.size());
        assertEquals(100.41,unequal.get(0).level(),.01);
        assertEquals(5/11.0,unequal.get(0).x(),1e-9,"weighted 10:1 by energy");
        assertEquals(1,unequal.get(0).anchor(),"named by its loudest member");
        assertEquals(2,SoundBudget.merge(List.of(at("valve_gas",1,0,90),at("valve_gas",2,6.5,90))).size(),"6.5 m apart: two loops");
    }

    @Test void atMostTwelvePlayTheLoudestAtTheListener() {
        // Twenty loops of their own kinds (no merging), 0 to 19 blocks away, all within their 16-block reach but the last three.
        var emitters=new ArrayList<SoundBudget.Emitter>();
        for(int i=0;i<20;i++)emitters.add(at("k"+i,i,i,95));
        var chosen=SoundBudget.choose(SoundBudget.merge(emitters),0,64,0,Set.of());
        assertEquals(SoundBudget.LOOPS,chosen.size());
        for(int i=0;i<SoundBudget.LOOPS;i++)assertEquals(i,chosen.get(i).anchor(),"nearest, so loudest, first");
        var faint=SoundBudget.choose(SoundBudget.merge(List.of(at("valve_gas",1,15,40))),0,64,0,Set.of());
        assertEquals(List.of(),faint,"heard below 30 dB: not played");
        var far=SoundBudget.choose(SoundBudget.merge(List.of(at("valve_gas",1,20,95))),0,64,0,Set.of());
        assertEquals(List.of(),far,"beyond its reach of 16 blocks (95 dB, volume 0.18)");
        assertEquals(1,SoundBudget.choose(SoundBudget.merge(List.of(at("flare_roar",1,25,120))),0,64,0,Set.of()).size(),"120 dB reaches 28 blocks");
        assertEquals(List.of(),SoundBudget.choose(SoundBudget.merge(List.of(at("flare_roar",1,40,120))),0,64,0,Set.of()),"but not 40");
    }

    @Test void aPlayingLoopKeepsItsPlaceUnlessARivalIs3dBLouder() {
        // Twelve playing loops 1 to 12 blocks away; the weakest is heard at 100 − 20 log10 12 = 78.4 dB.
        var twelve=new ArrayList<SoundBudget.Emitter>();
        for(int i=0;i<12;i++)twelve.add(at("k"+i,i,i+1,100));
        var playing=new HashSet<String>();for(var g:SoundBudget.merge(twelve))playing.add(g.key());
        var weakest=SoundBudget.merge(List.of(twelve.get(11))).get(0);
        // A rival at the weakest one's distance, 2 dB louder: the playing loop stays.
        var close=new ArrayList<>(twelve);close.add(at("fan_whoosh",99,12,102));
        var kept=SoundBudget.choose(SoundBudget.merge(close),0,64,0,playing);
        assertTrue(kept.stream().anyMatch(g->g.key().equals(weakest.key())),"2 dB is inside the hysteresis");
        assertTrue(kept.stream().noneMatch(g->g.kind().equals("fan_whoosh")));
        // 4 dB louder: it takes the place.
        var loud=new ArrayList<>(twelve);loud.add(at("fan_whoosh",99,12,104));
        var taken=SoundBudget.choose(SoundBudget.merge(loud),0,64,0,playing);
        assertTrue(taken.stream().anyMatch(g->g.kind().equals("fan_whoosh")));
        assertTrue(taken.stream().noneMatch(g->g.key().equals(weakest.key())));
        assertEquals(SoundBudget.LOOPS,taken.size());
    }

    @Test void levelsMapToVolumes() {
        // Owner 2026-11-10, "too loud": full gain at 110 dB, so a 1 MPa valve (104 dB) plays at half.
        assertEquals(1,SoundBudget.volume(110),1e-6);
        assertEquals(.5,SoundBudget.volume(104),.01);
        assertEquals(3,SoundBudget.volume(130),1e-6,"48 blocks of reach");
        assertEquals(.316,SoundBudget.volume(100),1e-3);
        assertEquals(3,SoundBudget.volume(150),1e-6,"at most 3");
        assertEquals(0,SoundBudget.volume(Double.NaN));
        assertEquals(.6,SoundBudget.volume(SoundBudget.level(.6)),1e-5,"a mover's gain plays at its old volume");
        assertEquals(48,SoundBudget.reach(130),1e-4);
        assertEquals(16,SoundBudget.reach(90),1e-9);
    }
}
