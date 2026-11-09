package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
public class SpeedFrames {
    public static void main(String[] args)throws Exception {
        var lines=new ArrayList<String>();
        lines.add("rpm,visible_turns_per_second");
        for(double rpm:new double[]{0,60,375,750,1500,3000,6000,12000})lines.add(rpm+","+VisualRotation.turnsPerSecond(rpm));
        Files.write(Path.of("tmp/shaft-motion/speed.csv"),lines);
        var clock=new VisualRotation.Clock();
        var frames=new ArrayList<String>();
        frames.add("seconds,rpm,turns");
        for(int frame=0;frame<=120;frame++) {
            double seconds=frame/30.0,rpm=seconds<1?750:seconds<2?3000:seconds<3?0:1500;
            frames.add(seconds+","+rpm+","+clock.at(seconds,rpm));
        }
        Files.write(Path.of("tmp/shaft-motion/frames.csv"),frames);
        System.out.println(String.join("\n",lines));
    }
}
