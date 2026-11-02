my $P='src/main/java/com/wormzjl/icheme/science/fluid/solver/PhaseLayout.java';
open(F,'<:raw',$P) or die; my @l=<F>; close F; my $n=0;
for(@l){
    if(/^(\s*)if\(water\)junctionBasis\[components\.length\]=l\.length;/){
        my $i=$1; my $eol=/\r\n$/?"\r\n":"\n"; s/\s*$//;
        $_.=$eol."${i}if(junctionBasis.length>1){int bulk=0;double most=-1;for(int index=0;index<junctionBasis.length;index++){int c=junctionBasis[index];double amount=c==l.length?seed.waterLiquid()+seed.waterVapor():l[c]+v[c];if(amount>most){most=amount;bulk=index;}}int held=junctionBasis[bulk];junctionBasis[bulk]=junctionBasis[junctionBasis.length-1];junctionBasis[junctionBasis.length-1]=held;}".$eol;
        $n++;
    }
}
die "n$n" unless $n==1; open(F,'>:raw',$P) or die; print F @l; close F;
print "bulk closure in\n";
