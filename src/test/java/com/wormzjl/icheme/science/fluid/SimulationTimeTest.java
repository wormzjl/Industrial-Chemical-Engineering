package com.wormzjl.icheme.science.fluid;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The time factor's one conversion (batch 2026-11-11-minecraft-time-delta, W1): at factor 1 it is bit-identical to the
 * {@code ticks / 20.0} and {@code ticks * 0.05} it replaced, it scales with the factor, and no new tick-to-second
 * conversion appears in the fluid runtime past it.
 */
class SimulationTimeTest {
    @Test void factorOneIsBitIdenticalToTheConversionsItReplaced() {
        var random=new Random(11);var one=SimulationTime.ONLINE;
        for(int i=0;i<100_000;i++) {
            long ticks=i<1000?i:random.nextLong(1L<<40);double x=random.nextDouble()*1e6;
            assertEquals(Double.doubleToRawLongBits(ticks/20.0),Double.doubleToRawLongBits(one.seconds(ticks)),"ticks "+ticks);
            assertEquals(Double.doubleToRawLongBits(x*ticks/20.0),Double.doubleToRawLongBits(one.seconds(x*ticks)));
            assertEquals(Double.doubleToRawLongBits(ticks*.05),Double.doubleToRawLongBits(ticks*one.tickSeconds()),"ticks "+ticks);
            assertEquals(Double.doubleToRawLongBits(x*20),Double.doubleToRawLongBits(one.ticks(x)));
            assertEquals(Double.doubleToRawLongBits(ticks/20.0),Double.doubleToRawLongBits(SimulationTime.playSeconds(ticks)));
        }
    }

    @Test void processTimeScalesWithTheFactorAndPlayTimeDoesNot() {
        var four=SimulationTime.of(4);var day=SimulationTime.of(72);
        assertEquals(20,four.seconds(100),0,"a 100-tick slice is 20 s of process at 4");
        assertEquals(360,day.seconds(100),0,"and 6 min at 72, where a Minecraft day is 24 h");
        assertEquals(86_400,day.seconds(24_000),1e-9);
        assertEquals(100,four.ticks(20),0);assertEquals(.2,four.tickSeconds(),0);
        assertEquals(5,SimulationTime.playSeconds(100),0);
        assertSame(SimulationTime.ONLINE,SimulationTime.of(1));
        assertThrows(IllegalArgumentException.class,()->SimulationTime.of(0));
        assertThrows(IllegalArgumentException.class,()->SimulationTime.of(73));
    }

    /** A tick-to-second conversion in the runtime goes through {@link SimulationTime}, which says whether it is process or play time. */
    @Test void noTickToSecondConversionInTheRuntimeBypassesTheFactor() throws IOException {
        var pattern=Pattern.compile("/\\s*20(\\.0)?[dD]?\\b|TICK_SECONDS|\\*\\s*0?\\.05\\b");
        var found=new ArrayList<String>();
        for(var root:List.of(Path.of("src/main/java/com/wormzjl/icheme/runtime"),Path.of("src/main/java/com/wormzjl/icheme/science/machine"))) {
            try(var files=Files.walk(root)) {
                for(var file:files.filter(f->f.toString().endsWith(".java")).sorted().toList()) {
                    var lines=Files.readAllLines(file);
                    for(int n=0;n<lines.size();n++) {
                        String line=lines.get(n),code=line.strip();
                        if(code.startsWith("*")||code.startsWith("//")||code.startsWith("/*"))continue;
                        int comment=line.indexOf("//");if(comment>=0)line=line.substring(0,comment);
                        if(!pattern.matcher(line).find())continue;
                        String site=file.getFileName()+": "+code;
                        if(ALLOWED.stream().noneMatch(site::contains))found.add(file.getFileName()+":"+(n+1)+": "+code);
                    }
                }
            }
        }
        assertTrue(found.isEmpty(),"tick-to-second conversions outside SimulationTime:\n"+String.join("\n",found));
    }

    /** Sites that are not a duration: FE per tick, which stays at the play-time rate (owner T2), and an age in whole play seconds. */
    private static final List<String> ALLOWED=List.of(
            "ElectricStores.java: return new FluidView.Power(draw, draw / joulesPerFE() / 20,",
            "ShaftTrains.java: return new View(unit, u.kind(), status, driverSpeed, speed, share, demand, shaft, power, power / jpf / 20,",
            "ColumnOperation.java: :String.format(Locale.ROOT,\"the %s solve failed (%s), the table of %d s ago kept\"");
}
