package com.wormzjl.icheme.fluid.gametest;

import java.lang.management.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

final class PerfSample {
    static final com.sun.management.OperatingSystemMXBean os =
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    static final com.sun.management.ThreadMXBean threads =
            ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    static final MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
    static final List<GarbageCollectorMXBean> gc = ManagementFactory.getGarbageCollectorMXBeans();
    static final Map<Long,Long> lastWorker = new HashMap<>();
    static long workerNanos;
    static PrintWriter out;
    static void at(int tick) {
        if(out==null)try {
            out=new PrintWriter(Files.newBufferedWriter(Path.of("resources.csv")));
            out.println("tick,epochMs,nano,cpuNs,workerNs,serverNs,totalAllocBytes,heapUsed,heapCommitted,gcCount,gcMs");
        }catch(IOException e){throw new UncheckedIOException(e);}
        for (var t : threads.getThreadInfo(threads.getAllThreadIds(),0)) {
            if(t==null || !t.getThreadName().startsWith("icheme-cpu-solve-")) continue;
            long id=t.getThreadId(), current=threads.getThreadCpuTime(id);
            if(current<0)continue;
            Long prev=lastWorker.put(id,current);
            workerNanos+=prev==null?current:current-prev;
        }
        var heap=mem.getHeapMemoryUsage();
        long gcCount=0,gcMs=0;
        for(var bean:gc){gcCount+=bean.getCollectionCount();gcMs+=bean.getCollectionTime();}
        out.printf("%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",tick,System.currentTimeMillis(),System.nanoTime(),
            os.getProcessCpuTime(),workerNanos,threads.getCurrentThreadCpuTime(),threads.getTotalThreadAllocatedBytes(),
            heap.getUsed(),heap.getCommitted(),gcCount,gcMs);out.flush();
    }
}
