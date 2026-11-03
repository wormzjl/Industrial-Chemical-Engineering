package com.wormzjl.icheme.science.fluid.thermo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Scratch (tmp/dry-ice-symptoms/run, trace of 651's hold under form (b)): a ring buffer of the last step-level flash
 * decisions (site, specification, request, which answer the adapter took) and, at the first non-finite matrix values, a
 * dump of the buffer with the stack trace to {@code trace.file} (system property; default out/trace.txt). Three dumps at most.
 */
public final class TraceLog {
    static final int SIZE=400;static final String[] RING=new String[SIZE];static int next=0;static long count=0;static int dumps=0;
    static final String FILE=System.getProperty("trace.file","trace.txt");
    public static synchronized void record(String what) {
        RING[next]=String.format(Locale.ROOT,"%8d %s | %s",count++,site(),what);next=(next+1)%SIZE;
    }
    /** The two first frames outside the adapter's entries (as ShadowTally.site). */
    static String site() {
        var names=new ArrayList<String>();
        for(var f:Thread.currentThread().getStackTrace()) {
            String c=f.getClassName(),m=f.getMethodName();
            if(c.equals(TraceLog.class.getName())||c.equals(Thread.class.getName()))continue;
            if(c.endsWith("FluidThermodynamics")&&(m.startsWith("equilibrium")||m.equals("flashTP")||m.equals("adopt")||m.equals("adoptCarried")||m.equals("lambda$equilibriumTP$0")))continue;
            if(c.endsWith("NetworkPhaseEngine"))continue;
            names.add(c.substring(c.lastIndexOf('.')+1)+"."+m+":"+f.getLineNumber());
            if(names.size()==3)break;
        }
        return String.join(" < ",names);
    }
    public static synchronized void dump(String reason) {
        if(dumps++>=3)return;
        try(var out=Files.newBufferedWriter(Path.of(FILE),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
            out.write("==== DUMP "+dumps+": "+reason);out.newLine();
            for(var f:Thread.currentThread().getStackTrace()){out.write("    at "+f);out.newLine();}
            out.write("---- last flash decisions (oldest first)");out.newLine();
            for(int k=0;k<SIZE;k++){String s=RING[(next+k)%SIZE];if(s!=null){out.write(s);out.newLine();}}
        } catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    public static String amounts(double[] a){var b=new StringBuilder("[");for(double x:a)b.append(String.format(Locale.ROOT,"%.4g ",x));return b.append(']').toString();}
}
