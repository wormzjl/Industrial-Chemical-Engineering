package com.wormzjl.icheme.science.fluid.linalg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The threshold pivot rule of {@link UpLookingLu} (batch 2026-11-15-fluid-step-cost, S5; the Newton's, at
 * {@link SparseLuSolver#NEWTON_PIVOT_THRESHOLD}), beside {@link UpLookingLuParityTest}, which holds EJML's rule to EJML:
 * no pivot below the threshold times its column's largest candidate; solutions within 1e-10 of EJML's rule's; the pivots,
 * and so the kept reaches, repeat when every value moves by up to 5 %; a singular matrix rejected at EJML's rule's column;
 * a reused instance gives a fresh one's bits.
 */
class ThresholdPivotTest {
    private static final double THRESHOLD=SparseLuSolver.NEWTON_PIVOT_THRESHOLD;
    record Matrix(int size,int[] columns,int[] rows,double[] values) {
        Matrix times(double[] factors){double[] v=values.clone();for(int i=0;i<v.length;i++)v[i]*=factors[i];return new Matrix(size,columns,rows,v);}
    }
    private record Rule(String name,Supplier<UpLookingLu> lu) {}
    /** The threshold rules under test. */
    private static final List<Rule> RULES=List.of(new Rule("first acceptable",()->new UpLookingLu(THRESHOLD)));

    private static boolean factor(UpLookingLu lu,Matrix m){return lu.factor(m.size(),m.columns(),m.rows(),m.values());}
    private static Matrix matrix(int n,int[] columns,List<Integer> rows,List<Double> values) {
        return new Matrix(n,columns,rows.stream().mapToInt(Integer::intValue).toArray(),values.stream().mapToDouble(Double::doubleValue).toArray());
    }
    /** A diagonal of magnitude 1 to 2, random sign, and up to {@code perColumn} entries off it in each column, Gaussian with
     * standard deviation {@code spread}. */
    private static Matrix sparse(int n,int perColumn,double spread,long seed) {
        var random=new Random(seed);int[] columns=new int[n+1];var rows=new ArrayList<Integer>();var values=new ArrayList<Double>();
        for(int c=0;c<n;c++) {
            var picked=new TreeSet<Integer>();picked.add(c);
            for(int e=0;e<perColumn;e++)picked.add(random.nextInt(n));
            for(int r:picked){rows.add(r);values.add(r==c?(random.nextBoolean()?1:-1)*(1+random.nextDouble()):spread*random.nextGaussian());}
            columns[c+1]=rows.size();
        }
        return matrix(n,columns,rows,values);
    }
    private static List<Matrix> sparseSet(double spread) {
        var set=new ArrayList<Matrix>();
        for(int n:new int[]{5,12,40,90,200})for(int perColumn:new int[]{1,3})for(long seed=1;seed<=3;seed++)set.add(sparse(n,perColumn,spread,seed*7919+n*31L+perColumn));
        return set;
    }
    /**
     * Block upper triangular: 2-by-2 diagonal blocks (a 1-by-1 last where n is odd) and random entries above the blocks.
     * Rows of an earlier block have their pivots before a block's columns, and no L column of an earlier block holds a row
     * of a later one, so a block's candidates are exactly its own entries in its first column, and a single row in its
     * second column (minus or plus the block's determinant over its pivot). Block {@code [[a, b], [c, d]]}: a, b, c in 0.9
     * to 1.1, d in -1.1 to -0.9, so the determinant is at most -1.62; the first block is {@code [[1, 1], [0.99, -1]]}.
     * {@code factors[e]} multiplies entry e for a second matrix of the same pattern: 0.95 to 1.05, and 0.97 for a, 1.02 for
     * c of the first block (which turns its largest candidate). {@code singular >= 0} makes that block
     * {@code [[1, 2], [2, 4]]}, which every rule eliminates exactly to 0.
     */
    private static Matrix blocks(int n,long seed,double[][] factors,int singular) {
        var random=new Random(seed);int[] columns=new int[n+1];var rows=new ArrayList<Integer>();var values=new ArrayList<Double>();var moved=new ArrayList<Double>();
        double[] block=new double[4];
        for(int c=0;c<n;c++) {
            int start=c-c%2;boolean second=c%2==1,alone=start+1>=n;
            if(!second) {
                block[0]=.9+.2*random.nextDouble();block[1]=.9+.2*random.nextDouble();block[2]=.9+.2*random.nextDouble();block[3]=-(.9+.2*random.nextDouble());
                if(start==0){block[0]=1;block[1]=.99;block[2]=1;block[3]=-1;}
                if(start/2==singular){block[0]=1;block[1]=2;block[2]=2;block[3]=4;}
            }
            for(int r=0;r<start;r++)if(random.nextDouble()<.1){rows.add(r);values.add(random.nextGaussian());moved.add(.95+.1*random.nextDouble());}
            if(alone){rows.add(c);values.add(.9+.2*random.nextDouble());moved.add(.95+.1*random.nextDouble());}
            else for(int r=0;r<2;r++) {
                rows.add(start+r);values.add(block[(second?2:0)+r]);
                moved.add(start==0&&!second?(r==0?.97:1.02):.95+.1*random.nextDouble());
            }
            columns[c+1]=rows.size();
        }
        if(factors!=null)factors[0]=moved.stream().mapToDouble(Double::doubleValue).toArray();
        return matrix(n,columns,rows,values);
    }

    /** Test 1, read through L: L(i,k) is a candidate over the pivot, so a pivot of at least {@code THRESHOLD} times its
     * column's largest candidate leaves every L entry at most {@code 1 / THRESHOLD} (1e-12 relative for rounding). */
    @Test void noPivotIsBelowTheThresholdOfItsColumnsLargestCandidate() {
        for(var rule:RULES) {
            double largest=0;int matrices=0;
            for(double spread:new double[]{.2,1})for(var m:sparseSet(spread)) {
                var lu=rule.lu().get();
                assertTrue(factor(lu,m),rule.name()+": factors");
                int[] columns=lu.lowerColumns();double[] values=lu.lowerValues();
                for(int k=0;k<m.size();k++) {
                    assertEquals(1,values[columns[k]],rule.name()+": L's unit diagonal first");
                    for(int p=columns[k]+1;p<columns[k+1];p++) {
                        double l=Math.abs(values[p]);largest=Math.max(largest,l);
                        assertTrue(l<=(1+1e-12)/THRESHOLD,rule.name()+", n "+m.size()+", column "+k+": |L| "+l);
                    }
                }
                matrices++;
            }
            assertTrue(matrices>50);
            // The rule chooses rows other than the largest (EJML's rule keeps every L entry at most 1).
            assertTrue(largest>1,rule.name()+": largest |L| "+largest);
        }
    }
    /** Test 2: the solutions of the threshold rule are within 1e-10 of EJML's rule's, relative to the largest component. */
    @Test void solutionsMatchEjmlsRuleWithinTheBound() {
        for(var rule:RULES)for(var m:sparseSet(.2)) {
            var random=new Random(m.size()*131L+m.values().length);double[] b=new double[m.size()];
            for(int i=0;i<b.length;i++)b[i]=2*random.nextDouble()-1;
            var ejml=new UpLookingLu();var threshold=rule.lu().get();
            assertTrue(factor(ejml,m));assertTrue(factor(threshold,m));
            double[] expected=new double[m.size()],actual=new double[m.size()];ejml.solve(b,expected);threshold.solve(b,actual);
            double scale=0,difference=0;
            for(int i=0;i<b.length;i++){scale=Math.max(scale,Math.abs(expected[i]));difference=Math.max(difference,Math.abs(actual[i]-expected[i]));}
            assertTrue(difference<=1e-10*scale,rule.name()+", n "+m.size()+": difference "+difference+" of "+scale);
        }
    }
    /**
     * Test 3, the property the rule exists for: a pattern factored again with every value moved by 0.95 to 1.05 keeps its
     * pivots, so the second factorization searches no reach. It can fail only where a candidate crosses the threshold;
     * on {@link #blocks} none can: a first column's two candidates are within a factor 1.36 of each other (both acceptable,
     * and the diagonal among them), a second column has one. EJML's rule, on the same pair, changes the first block's pivot
     * and searches again.
     */
    @Test void pivotsRepeatWhenTheValuesMoveByFivePercent() {
        for(int n:new int[]{5,12,41,200})for(long seed=1;seed<=3;seed++) {
            double[][] factors=new double[1][];var first=blocks(n,seed*104729+n,factors,-1);var second=first.times(factors[0]);
            for(var rule:RULES) {
                var lu=rule.lu().get();
                assertTrue(factor(lu,first));long searches=lu.searches();int[] pivots=lu.rowPivots();
                assertTrue(factor(lu,second));
                assertArrayEquals(pivots,lu.rowPivots(),rule.name()+", n "+n+": the pivots repeat");
                assertEquals(searches,lu.searches(),rule.name()+", n "+n+": no reach searched again");
            }
            var ejml=new UpLookingLu();
            assertTrue(factor(ejml,first));long searches=ejml.searches();assertTrue(factor(ejml,second));
            assertEquals(searches+n-1,ejml.searches(),"EJML's rule, n "+n+": the first pivot turns, every later column searches");
        }
    }
    /** Test 4: a singular matrix is rejected by the threshold rule at EJML's rule's column. */
    @Test void aSingularMatrixIsRejectedAtTheSameColumn() {
        var cases=new ArrayList<Matrix>();var expectedColumns=new ArrayList<Integer>();
        // An empty column, a column on rows already pivoted, and an exact zero after elimination.
        cases.add(new Matrix(3,new int[]{0,1,1,2},new int[]{0,2},new double[]{2,3}));expectedColumns.add(1);
        cases.add(new Matrix(3,new int[]{0,1,2,3},new int[]{0,0,2},new double[]{2,5,1}));expectedColumns.add(1);
        cases.add(new Matrix(2,new int[]{0,2,4},new int[]{0,1,0,1},new double[]{1,2,2,4}));expectedColumns.add(1);
        for(int n:new int[]{6,13,40})for(int singular:new int[]{1,n/4}){cases.add(blocks(n,n*17L+singular,null,singular));expectedColumns.add(2*singular+1);}
        for(int i=0;i<cases.size();i++) {
            var m=cases.get(i);var ejml=new UpLookingLu();
            assertFalse(factor(ejml,m),"case "+i+": EJML's rule rejects it");
            assertEquals((int)expectedColumns.get(i),ejml.failedColumn(),"case "+i+": EJML's rule's column");
            for(var rule:RULES) {
                var lu=rule.lu().get();
                assertFalse(factor(lu,m),rule.name()+", case "+i+": rejected");
                assertEquals(ejml.failedColumn(),lu.failedColumn(),rule.name()+", case "+i+": at the same column");
            }
        }
    }
    private static void assertBits(double[] expected,double[] actual,String what) {
        assertEquals(expected.length,actual.length,what+" length");
        for(int i=0;i<expected.length;i++)
            if(Double.doubleToRawLongBits(expected[i])!=Double.doubleToRawLongBits(actual[i]))fail(what+"["+i+"]: "+expected[i]+" against "+actual[i]);
    }
    /** Test 6: an instance that factored other matrices before (another pattern, then the same pattern with other values)
     * gives a fresh instance's L, U, pivots and solution, bit for bit. */
    @Test void aReusedInstanceGivesAFreshOnesBits() {
        for(var rule:RULES)for(int n:new int[]{12,90,200})for(long seed=1;seed<=2;seed++) {
            var x=sparse(n,3,.5,seed*31+n);var other=sparse(n,2,.5,seed*37+n);
            var random=new Random(seed);double[] moved=new double[x.values().length];
            for(int e=0;e<moved.length;e++)moved[e]=random.nextDouble()<.2?.1+3*random.nextDouble():1;
            var y=x.times(moved);
            var reused=rule.lu().get();
            assertTrue(factor(reused,x));assertTrue(factor(reused,other));assertTrue(factor(reused,y));
            var fresh=rule.lu().get();assertTrue(factor(fresh,y));
            String name=rule.name()+", n "+n+", seed "+seed;
            assertArrayEquals(fresh.rowPivots(),reused.rowPivots(),name+": pivots");
            assertArrayEquals(fresh.lowerColumns(),reused.lowerColumns(),name+": L columns");assertArrayEquals(fresh.lowerRows(),reused.lowerRows(),name+": L rows");
            assertBits(fresh.lowerValues(),reused.lowerValues(),name+": L");
            assertArrayEquals(fresh.upperColumns(),reused.upperColumns(),name+": U columns");assertArrayEquals(fresh.upperRows(),reused.upperRows(),name+": U rows");
            assertBits(fresh.upperValues(),reused.upperValues(),name+": U");
            double[] b=new double[n];for(int i=0;i<n;i++)b[i]=random.nextGaussian();
            double[] expected=new double[n],actual=new double[n];fresh.solve(b,expected);reused.solve(b,actual);
            assertBits(expected,actual,name+": solution");
        }
    }
    @Test void theThresholdIsValidated() {
        for(double bad:new double[]{0,-.1,1.5,Double.NaN})assertThrows(IllegalArgumentException.class,()->new UpLookingLu(bad));
        assertDoesNotThrow(()->new UpLookingLu(1));
    }
}
