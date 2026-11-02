my $R='src/main/java/com/wormzjl/icheme/runtime/fluid/RetainedSolver.java';
open(F,'<:raw',$R) or die; my @l=<F>; close F; my $n=0;
for(@l){
 if(s/^(\s*)solver\.replayStart\(snapshot,committed\);/$1LeadProbe.level(snapshot,ports);solver.replayStart(snapshot,committed);/){$n++}
 if(s/^(\s*)finally\{ownership\.release\(\);\}\s*$/$1catch(RuntimeException failure){LeadProbe.run(model,snapshot,committed,duration,settings,reads,ports,failure);throw failure;}\n$1finally{ownership.release();}\n/){$n++}
}
die "r$n" unless $n==2; open(F,'>:raw',$R) or die; print F @l; close F;
my $P='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java';
open(F,'<:raw',$P) or die; @l=<F>; close F; $n=0;
for(@l){
 if(s/^(\s*)if\(needsBalance\(graph\)\) \{/$1boolean traceBalance=needsBalance(graph);com.wormzjl.icheme.science.fluid.solver.TraceDump.start(traceBalance,rateOnly);if(traceBalance) {/){$n++}
 if(s/"dt="\+dt\+" pass="\+pass\+/"dt="+dt+" starts="+com.wormzjl.icheme.science.fluid.solver.TraceDump.starts()+" pass="+pass+/){$n++}
 if(/void replayStart\(/ && s/\{\s*$/{com.wormzjl.icheme.science.fluid.solver.TraceDump.reset();\n/){$n++}
}
die "p$n" unless $n==3; open(F,'>:raw',$P) or die; print F @l; close F;
my $T='src/main/java/com/wormzjl/icheme/science/fluid/solver/TraceDump.java';
open(F,'<:raw',$T) or die; my $s=join('',<F>); close F;
$s=~s/(class TraceDump[^\n]*\n)/$1    private static final ThreadLocal<int[]> STARTS=ThreadLocal.withInitial(()->new int[3]);\n    public static void reset(){STARTS.set(new int[3]);}\n    public static void start(boolean balance,boolean rate){STARTS.get()[rate?2:balance?1:0]++;}\n    public static String starts(){int[] s=STARTS.get();return "carried:"+s[0]+",balance:"+s[1]+",rate:"+s[2];}\n/ or die "t";
open(F,'>:raw',$T) or die; print F $s; close F;
print "patched\n";
