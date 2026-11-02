package com.wormzjl.icheme.science.fluid.solver;

import com.wormzjl.icheme.science.fluid.linalg.SparseMatrix;
import java.nio.file.*;
import java.util.*;

/** Scratch only: the failing Jacobian of a solve, with its pivots under full pivoting. */
public final class TraceDump {
    private static final ThreadLocal<int[]> STARTS=ThreadLocal.withInitial(()->new int[3]);
    public static void reset(){STARTS.set(new int[3]);}
    public static void start(boolean balance,boolean rate){STARTS.get()[rate?2:balance?1:0]++;}
    public static String starts(){int[] s=STARTS.get();return "carried:"+s[0]+",balance:"+s[1]+",rate:"+s[2];}
    private TraceDump() {}
    private static final ThreadLocal<SparseMatrix> LAST=new ThreadLocal<>();
    private static final Map<String,Integer> SEEN=new HashMap<>();
    private static final Path FILE=Path.of("tmp/trace/singular.txt");
    static void matrix(SparseMatrix m){LAST.set(m);}
    public static synchronized void write(String key,String head,double[] x) {
        int count=SEEN.merge(key,1,Integer::sum);if(count>2)return;
        var nl=System.lineSeparator();var b=new StringBuilder("==== "+key+" #"+count+nl+head+nl+"x="+Arrays.toString(x)+nl);
        var m=LAST.get();
        if(m!=null) {
            int n=m.size();double[][] a=new double[n][n];
            for(int c=0;c<n;c++)for(int e=m.columnStart(c);e<m.columnEnd(c);e++)a[m.rowAt(e)][c]=m.valueAt(e);
            for(int r=0;r<n;r++){b.append("J").append(r).append(' ').append(Arrays.toString(a[r])).append(nl);}
            double[] scale=new double[n];
            for(int r=0;r<n;r++){for(int c=0;c<n;c++)scale[r]=Math.max(scale[r],Math.abs(a[r][c]));if(scale[r]==0){b.append("ZERO ROW ").append(r).append(nl);scale[r]=1;}for(int c=0;c<n;c++)a[r][c]/=scale[r];}
            int[] rowOf=new int[n],colOf=new int[n];for(int i=0;i<n;i++){rowOf[i]=i;colOf[i]=i;}
            for(int k=0;k<n;k++) {
                int pr=k,pc=k;double best=-1;
                for(int r=k;r<n;r++)for(int c=k;c<n;c++)if(Math.abs(a[r][c])>best){best=Math.abs(a[r][c]);pr=r;pc=c;}
                b.append("pivot ").append(k).append(" row ").append(rowOf[pr]).append(" col ").append(colOf[pc]).append(" = ").append(a[pr][pc]).append(nl);
                double[] t=a[k];a[k]=a[pr];a[pr]=t;int s=rowOf[k];rowOf[k]=rowOf[pr];rowOf[pr]=s;
                for(int r=0;r<n;r++){double v=a[r][k];a[r][k]=a[r][pc];a[r][pc]=v;}s=colOf[k];colOf[k]=colOf[pc];colOf[pc]=s;
                if(a[k][k]==0)continue;
                for(int r=k+1;r<n;r++){double f=a[r][k]/a[k][k];if(f!=0)for(int c=k;c<n;c++)a[r][c]-=f*a[k][c];}
            }
        }else b.append("no matrix").append(nl);
        try{Files.writeString(FILE,b.toString(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){System.err.println("TRACEFAIL "+e);}
    }
}
