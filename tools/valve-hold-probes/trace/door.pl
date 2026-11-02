my $P='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java';
open(F,'<:raw',$P) or die; my @l=<F>; close F; my $n=0;
for(@l){
    if(/^(\s*)static boolean sameStructure\(PassiveNetwork a,PassiveNetwork b\)/){
        my $i=$1; my $eol=/\r\n$/?"\r\n":"\n";
        $_.="$i/** {\@link #sameStructure} whatever a flow valve's set point: a committed interval starts a job on a new target too. */$eol${i}static boolean sameLines(PassiveNetwork a,PassiveNetwork b){return sameLines(identities(a),identities(b))&&Arrays.equals(nodeIds(a),nodeIds(b));}$eol";
        $n++;
    }
}
die "p$n" unless $n==1; open(F,'>:raw',$P) or die; print F @l; close F;
my $I='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java';
open(F,'<:raw',$I) or die; @l=<F>; close F; $n=0;
for(@l){$n++ if s/boolean continued=committed!=null&&PassiveStepSolver\.sameStructure\(graph,committed\.graph\(\)\)/boolean continued=committed!=null&&PassiveStepSolver.sameLines(graph,committed.graph())/;}
die "i$n" unless $n==1; open(F,'>:raw',$I) or die; print F @l; close F;
print "door closed\n";
