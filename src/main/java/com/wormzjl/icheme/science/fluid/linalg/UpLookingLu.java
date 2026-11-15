package com.wormzjl.icheme.science.fluid.linalg;

/*
 * The algorithm follows EJML's LuUpLooking_DSCC, TriangularSolver_DSCC (solveColB, searchNzRowsInX, solveL, solveU) and
 * LinearSolverLu_DSCC.solve, version 0.44.0, with no fill-reducing permutation (FillReducing.NONE).
 *
 * EJML: Copyright (c) 2020, Peter Abeles. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import java.util.Arrays;

/**
 * A sparse LU with pivoting by rows, {@code P A = L U}, computed column by column ("up-looking", Gilbert-Peierls), under
 * one of two pivot rules fixed at construction. EJML's rule ({@link #UpLookingLu()}, partial pivoting) is bit for bit EJML
 * 0.44's {@code LuUpLooking_DSCC} on the same compressed-column matrix (finding P-17, batch 2026-11-13-codebase-review);
 * the threshold rule ({@link #UpLookingLu(double)}, the Newton's: batch 2026-11-15-fluid-step-cost, S5) differs from it
 * only in which row is a column's pivot (item 2). Plain arrays, kept and grown by the instance; one worker at a time.
 *
 * <p>What "bit for bit" rests on, read from EJML's sources:</p>
 * <ol>
 * <li><b>Column k's triangular solve</b> ({@code solveColB}): the reach of A(:,k) in the graph of the first k columns of L is
 * found by a depth-first search ({@code searchNzRowsInX}): the start rows are A(:,k)'s rows in their stored (ascending)
 * order, each unmarked one searched in turn; a row i with a pivot ({@code pinv[i] = J >= 0}) descends into L(:,J)'s stored
 * rows in their storage order, the first unmarked child first; a row is pushed onto the reach's front when all its children
 * are done. So the reach {@code xi[top..n)} is in topological order. The dense x is cleared on the reach, A(:,k) is
 * scattered into it, then the reach is visited front to back: a row j with {@code J = pinv[j] >= 0} gives
 * {@code x[j] /= L(J,J)} (exactly 1) and then, for L(:,J)'s entries after its diagonal in storage order,
 * {@code x[r] -= L(r,J) * x[j]}. A row without a pivot is skipped.</li>
 * <li><b>The pivot</b>: over the reach front to back, among rows without a pivot, the largest {@code |x[i]|} by strict
 * {@code >}, starting from {@code -Double.MAX_VALUE}: the first of equal magnitudes wins, NaN never wins. No pivot, or a
 * largest magnitude {@code <= 0}, rejects the matrix (singular) at that column. Under EJML's rule this row is the pivot:
 * no threshold or diagonal preference (EJML's tolerance line is commented out). Under the threshold rule, with {@code a}
 * that largest magnitude and the same rejection, the pivot is the first row of the reach, front to back, without a
 * pivot whose {@code |x[i]|} is at least {@code threshold * a} and above 0 (the first acceptable row; the largest is
 * always acceptable): a second pass over the reach that only reads. It reads the column's values and {@code pinv}, never
 * an earlier factorization's pivots, so it is a function of the matrix alone. At {@code threshold = 1} it is EJML's
 * rule.</li>
 * <li><b>The storage of L and U</b>, column k: U(:,k) holds, in reach order, every reach row with a pivot (as row
 * {@code pinv[i]}, value x[i]), then the diagonal (row k, the pivot value) last. L(:,k) holds the pivot row first (value 1)
 * then, in reach order, every row without a pivot, value {@code x[i] / pivot}. Every reached row is stored, whatever its
 * value (a structural zero stays). After the last column every L row index i becomes {@code pinv[i]}.</li>
 * <li><b>The solve of a dense right-hand side</b> ({@code LinearSolverLu_DSCC.solve}): {@code x[pinv[k]] = b[k]} for k in
 * order; then {@code solveL}: columns ascending, {@code x[c] /= L(c,c)}, then {@code x[r] -= L(r,c) * x[c]} over L(:,c)'s
 * entries after the diagonal in storage order; then {@code solveU}: columns descending, {@code x[c] /= U(c,c)} (the
 * column's last entry), then {@code x[r] -= U(r,c) * x[c]} over its other entries in storage order.</li>
 * </ol>
 * Under EJML's rule every expression is EJML's as written ({@code a -= b * c} is {@code a - (b * c)}, no fused
 * multiply-add). The threshold rule shares items 1, 3 and 4 and the pass of item 2 that finds {@code a} and writes U; only
 * its choice of row differs.
 *
 * <p><b>The kept reach.</b> Column k's reach is a function of A(:,k)'s pattern, the patterns of L(:,0..k-1) in storage order
 * and which rows hold pivots before column k ({@code pinv}); by item 3, L(:,j)'s pattern is its pivot row followed by the
 * rows of column j's reach without a pivot, in reach order. Induction on k: if A has the pattern of the last factorization
 * in this instance and the pivot rows of columns 0..k-1 are the last factorization's, then L(:,0..k-1) have the last
 * patterns, {@code pinv} marks the same rows with the same columns, and the search, deterministic on these inputs alone,
 * returns the last reach of column k, in the same order. So each column's reach is kept, and reused while every earlier
 * pivot repeated; from the first column whose pivot differs, every later column searches again (and keeps its new reach).
 * Values never enter the search. The induction holds under either rule: the search reads the pattern and the earlier
 * pivots, whatever rule chose them.</p>
 *
 * <p><b>Several kept patterns.</b> A transport storage drops exact zeros before it factors ({@link SparseLuSolver.Storage}),
 * and the Newton's hands the LU the entries its matrix stores, the zeros that matrix keeps included
 * ({@link SparseLuSolver.Storage#storedPattern}); either way one storage sees a few patterns in turn (a transport matrix
 * with a pipe at rest, then flowing; a Jacobian whose flow turned). The instance keeps the pattern, pivot
 * rows and reaches of the last completed factorization of each of its last {@value #KEPT_PATTERNS} patterns, the least
 * recently used replaced. The induction above reads only the matrix's pattern and the pivots of this factorization, so it
 * holds per pattern, whatever was factored in between. A pattern not among the kept ones searches every column.</p>
 *
 * <p><b>Reuse across factorizations</b> (P-17b: an island keeps its instances across steps). Nothing numeric carries from
 * one factorization to the next, whatever matrix came before: {@code pinv} is set to -1 over n; the dense column is cleared
 * on each column's reach before A(:,k) is scattered, and only reach rows are read, by either rule's choice too (a row with
 * a pivot leads to the rows of its L column, which the search put in the reach), so a value a rejected factorization left
 * there is never read; the kept pivots are compared with the new ones only after the choice, never read to make it; the
 * search's marks are cleared on the reach it returns and its stack entries are written before they are read; L, U and
 * their column pointers are written from position 0. What carries over is the arrays' capacity, which no value depends on,
 * and the kept reach, used only under the induction above. So a reused instance gives the L, U, pivots, rejection column
 * and solutions of a fresh one.</p>
 */
public final class UpLookingLu {
    private int size;
    private int[] lColumns=new int[1],lRows=new int[0],uColumns=new int[1],uRows=new int[0];
    private double[] lValues=new double[0],uValues=new double[0];
    private int lLength,uLength;
    private int[] pinv=new int[0],xi=new int[0],marks=new int[0];
    private double[] dense=new double[0];
    /** The patterns kept, each with the pivot rows and reaches of its last completed factorization (P-17, P-17c). */
    static final int KEPT_PATTERNS=4;
    private final Kept[] keptPatterns=new Kept[KEPT_PATTERNS];
    private long clock;
    private long searches;
    private int failedColumn=-1;
    /** The pivot rule (class comment, item 2): 0 for EJML's, else the threshold rule's fraction of a column's largest
     * candidate magnitude, in (0, 1]. */
    private final double threshold;

    /** EJML's rule: the largest candidate, the first of equals (partial pivoting; the transport's, the parity gate's). */
    public UpLookingLu(){threshold=0;}
    /** The threshold rule: the first candidate in reach order whose magnitude is at least {@code threshold} times the
     * column's largest, {@code 0 < threshold <= 1}. */
    public UpLookingLu(double threshold) {
        if(!(threshold>0&&threshold<=1))throw new IllegalArgumentException("Pivot threshold outside (0, 1]: "+threshold);
        this.threshold=threshold;
    }

    /**
     * Factors the n-by-n matrix in compressed columns ({@code columns[0..n]}, {@code rows} ascending and unique in each
     * column, {@code values}); false where EJML rejects it as singular, at {@link #failedColumn()}.
     */
    public boolean factor(int n,int[] columns,int[] rows,double[] values) {
        int nonzeros=columns[n];
        Kept slot=keptPattern(n,columns,rows,nonzeros);
        prepare(n,nonzeros);
        // The kept reach (see the class comment): every column reuses its last reach while every earlier pivot repeated.
        boolean reuse=slot.complete;
        slot.complete=false;slot.used=++clock;
        int[] reachStart=slot.reachStart,keptPivots=slot.pivots;
        failedColumn=-1;lLength=0;uLength=0;long searchedBefore=searches;
        for(int k=0;k<n;k++) {
            lColumns[k]=lLength;uColumns[k]=uLength;
            int top,end;int[] reach;
            if(reuse){reach=slot.reachRows;top=reachStart[k];end=reachStart[k+1];}
            else {
                searches++;
                int found=search(columns,rows,k);
                int length=n-found;slot.ensureReach(reachStart[k]+length);
                System.arraycopy(xi,found,slot.reachRows,reachStart[k],length);reachStart[k+1]=reachStart[k]+length;
                reach=slot.reachRows;top=reachStart[k];end=reachStart[k+1];
            }
            ensureCapacity(end-top);
            // solveColB's numeric part: clear on the reach, scatter A(:,k), eliminate in reach order.
            for(int p=top;p<end;p++)dense[reach[p]]=0;
            for(int p=columns[k];p<columns[k+1];p++)dense[rows[p]]=values[p];
            for(int px=top;px<end;px++) {
                int j=reach[px];int J=pinv[j];
                if(J<0)continue;
                dense[j]/=lValues[lColumns[J]];
                int p=lColumns[J]+1,q=lColumns[J+1];
                for(;p<q;p++)dense[lRows[p]]-=lValues[p]*dense[j];
            }
            // The pivot: the largest magnitude among rows without one, the first of equals (EJML's rule; the threshold rule's a).
            int ipiv=-1;double a=-Double.MAX_VALUE;
            for(int p=top;p<end;p++) {
                int i=reach[p];
                if(pinv[i]<0) {
                    double t;
                    if((t=Math.abs(dense[i]))>a){a=t;ipiv=i;}
                } else {
                    uRows[uLength]=pinv[i];uValues[uLength++]=dense[i];
                }
            }
            if(ipiv==-1||a<=0){failedColumn=k;count(k+1,searches-searchedBefore);return false;}
            // The threshold rule chooses among the acceptable rows; EJML's keeps the largest.
            if(threshold>0)ipiv=acceptable(reach,top,end,threshold*a,ipiv);
            double pivot=dense[ipiv];
            uRows[uLength]=k;uValues[uLength++]=pivot;
            pinv[ipiv]=k;
            lRows[lLength]=ipiv;lValues[lLength++]=1;
            for(int p=top;p<end;p++) {
                int i=reach[p];
                if(pinv[i]<0){lRows[lLength]=i;lValues[lLength++]=dense[i]/pivot;}
                dense[i]=0;
            }
            if(reuse&&keptPivots[k]!=ipiv)reuse=false;
            keptPivots[k]=ipiv;
        }
        lColumns[n]=lLength;uColumns[n]=uLength;
        for(int p=0;p<lLength;p++)lRows[p]=pinv[lRows[p]];
        slot.complete=true;count(n,searches-searchedBefore);
        return true;
    }

    /**
     * The threshold rule's pivot (class comment, item 2): the first row of {@code reach[top..end)} without a pivot whose
     * magnitude is at least {@code bound} and above 0 (above 0 by itself: {@code bound} may underflow to 0). The largest
     * candidate, {@code largest}, always qualifies, so the fallback is never taken; it is there so the choice is a row.
     */
    private int acceptable(int[] reach,int top,int end,double bound,int largest) {
        for(int p=top;p<end;p++) {
            int i=reach[p];
            if(pinv[i]<0){double t=Math.abs(dense[i]);if(t>=bound&&t>0)return i;}
        }
        return largest;
    }
    /** EJML's {@code searchNzRowsInX} and its {@code _DFS} on L's first k columns: the reach's start, {@code xi[top..n)}. */
    private int search(int[] columns,int[] rows,int k) {
        int n=size,top=n;
        for(int i=columns[k];i<columns[k+1];i++) {
            int rowB=rows[i];
            if(rowB<n&&marks[rowB]==0)top=depthFirst(rowB,top);
        }
        for(int i=top;i<n;i++)marks[xi[i]]=0;
        return top;
    }
    private int depthFirst(int rowB,int top) {
        int n=size,head=0;
        xi[head]=rowB;
        while(head>=0) {
            int column=xi[head];int pivotColumn=pinv[column];
            if(marks[column]==0) {
                marks[column]=1;
                marks[n+head]=pivotColumn<0||pivotColumn>=n?0:lColumns[pivotColumn];
            }
            boolean done=true;
            int idx0=marks[n+head];
            int idx1=pivotColumn<0||pivotColumn>=n?0:lColumns[pivotColumn+1];
            for(int j=idx0;j<idx1;j++) {
                int row=lRows[j];
                if(row<n&&marks[row]==0) {
                    marks[n+head]=j+1;
                    xi[++head]=row;
                    done=false;
                    break;
                }
            }
            if(done){head--;xi[--top]=column;}
        }
        return top;
    }

    /** Solves {@code A x = b} on the last factorization (EJML's {@code solve}): {@code b} is read, {@code x} written. */
    public void solve(double[] b,double[] x) {
        int n=size;
        for(int k=0;k<n;k++)x[pinv[k]]=b[k];
        int idx0=lColumns[0];
        for(int col=0;col<n;col++) {
            int idx1=lColumns[col+1];
            double xj=x[col]/=lValues[idx0];
            for(int i=idx0+1;i<idx1;i++){int row=lRows[i];x[row]-=lValues[i]*xj;}
            idx0=idx1;
        }
        int idx1=uColumns[n];
        for(int col=n-1;col>=0;col--) {
            int start=uColumns[col];
            double xj=x[col]/=uValues[idx1-1];
            for(int i=start;i<idx1-1;i++){int row=uRows[i];x[row]-=uValues[i]*xj;}
            idx1=start;
        }
    }

    private void prepare(int n,int nonzeros) {
        if(pinv.length<n) {
            pinv=new int[n];dense=new double[n];xi=new int[n];marks=new int[2*n];
        }
        if(lColumns.length<n+1){lColumns=new int[n+1];uColumns=new int[n+1];}
        size=n;
        Arrays.fill(pinv,0,n,-1);
        // EJML's initial L and U capacity, 4 nnz + n; a refill keeps what earlier factorizations grew.
        int capacity=4*nonzeros+n;
        if(lValues.length<capacity){lRows=new int[capacity];lValues=new double[capacity];}
        if(uValues.length<capacity){uRows=new int[capacity];uValues=new double[capacity];}
    }
    /** The kept slot of this pattern; where none holds it, the least recently used slot takes it, with nothing complete. */
    private Kept keptPattern(int n,int[] columns,int[] rows,int nonzeros) {
        int oldest=0;
        for(int i=0;i<KEPT_PATTERNS;i++) {
            Kept slot=keptPatterns[i];
            if(slot==null){oldest=i;break;}
            if(slot.holds(n,columns,rows,nonzeros))return slot;
            if(slot.used<keptPatterns[oldest].used)oldest=i;
        }
        Kept slot=keptPatterns[oldest];
        if(slot==null)slot=keptPatterns[oldest]=new Kept();
        slot.keep(n,columns,rows,nonzeros);
        SolverDiagnostics.count(SolverDiagnostics.luPatternSearches,n);
        return slot;
    }
    /** One pattern with its last pivot rows and reaches; {@code complete} false until a factorization of it completed,
     * and again after a rejected one. */
    private static final class Kept {
        private int size=-1,nonzeros;
        private int[] columns=new int[1],rows=new int[0],pivots=new int[0],reachStart=new int[1],reachRows=new int[0];
        private boolean complete;private long used;
        private boolean holds(int n,int[] c,int[] r,int nz) {
            return n==size&&nz==nonzeros&&Arrays.equals(c,0,n+1,columns,0,n+1)&&Arrays.equals(r,0,nz,rows,0,nz);
        }
        private void keep(int n,int[] c,int[] r,int nz) {
            if(columns.length<n+1){columns=new int[n+1];reachStart=new int[n+1];}
            if(rows.length<nz)rows=new int[nz];
            if(pivots.length<n)pivots=new int[n];
            System.arraycopy(c,0,columns,0,n+1);System.arraycopy(r,0,rows,0,nz);
            size=n;nonzeros=nz;complete=false;reachStart[0]=0;
        }
        private void ensureReach(int length){if(reachRows.length<length)reachRows=Arrays.copyOf(reachRows,Math.max(2*reachRows.length,length));}
    }
    /** Room for {@code more} entries in L and U each (a column stores at most its reach in each). */
    private void ensureCapacity(int more) {
        if(lLength+more>lValues.length){int grown=Math.max(2*lValues.length,lLength+more);lRows=Arrays.copyOf(lRows,grown);lValues=Arrays.copyOf(lValues,grown);}
        if(uLength+more>uValues.length){int grown=Math.max(2*uValues.length,uLength+more);uRows=Arrays.copyOf(uRows,grown);uValues=Arrays.copyOf(uValues,grown);}
    }

    /** Columns factored and reach searches, into the solver's diagnostics (behind its switch). */
    private static void count(int columns,long searched) {
        SolverDiagnostics.count(SolverDiagnostics.luColumnsFactored,columns);SolverDiagnostics.count(SolverDiagnostics.luReachSearches,searched);
    }
    public int size(){return size;}
    /** The column whose pivot was missing in the last rejected factorization, -1 after a completed one. */
    public int failedColumn(){return failedColumn;}
    /** The reach searches this instance has run: one per column of a new pattern or after a changed pivot. */
    public long searches(){return searches;}
    /** The entries L and U store after the last completed factorization (every reached row, structural zeros included, L's
     * unit diagonal too); a rejected factorization leaves a partial count, so read it only after a completed one. */
    public long factorEntries(){return (long)lLength+uLength;}
    /** {@code pinv}: for each original row, the column it is the pivot of (EJML's {@code getPinv()}). */
    public int[] rowPivots(){return Arrays.copyOf(pinv,size);}
    public int[] lowerColumns(){return Arrays.copyOf(lColumns,size+1);}
    public int[] lowerRows(){return Arrays.copyOf(lRows,lLength);}
    public double[] lowerValues(){return Arrays.copyOf(lValues,lLength);}
    public int[] upperColumns(){return Arrays.copyOf(uColumns,size+1);}
    public int[] upperRows(){return Arrays.copyOf(uRows,uLength);}
    public double[] upperValues(){return Arrays.copyOf(uValues,uLength);}
}
