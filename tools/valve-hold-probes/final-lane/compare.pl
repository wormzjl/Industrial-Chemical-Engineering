# Compares each campaign report of tmp/merge-lane/head with base and the old head from the archive: clean seeds, worse and better seeds with classes, hold counts, cost.
use strict;
sub rd{my $p=shift;open(F,'<',$p) or die $p;my(%s,$sum,$c);my $cur;while(<F>){s/\r?\n$//;
  if(/^SEED (\d+) blocks=\d+ ms=(\d+) end=\d+ holds=(\d+) classes=\[(.*?)\] refused=(\d+)/){$cur=$1;$s{$1}={ms=>$2,holds=>$3,cls=>$4,ref=>$5,first=>''}}
  elsif(/^  HELD / && defined $cur && !$s{$cur}{first}){my $t=$_;$t=~s/.*status=//;$s{$cur}{first}=substr($t,0,60);$t=$_;$s{$cur}{tick}=$1 if /tick[= ](\d+)/}
  elsif(/^COUNTERS (.*)/){$c=$1}elsif(/^SUMMARY/){$sum=$_}}close F;(\%s,$c)}
sub kv{my %h;for(split ' ',shift){my($k,$v)=split /=/;$h{$k}=$v}\%h}
for my $name (qw(cold-random bundled-random bundled-defaults)){
  my($b,$bc)=rd("base-archive/base/campaign-$name-report.txt");my($o,$oc)=rd("base-archive/head/campaign-$name-report.txt");my($h,$hc)=rd("head/campaign-$name-report.txt");
  my $cb=grep{$b->{$_}{holds}==0}keys %$b;my $co=grep{$o->{$_}{holds}==0}keys %$o;my $ch=grep{$h->{$_}{holds}==0}keys %$h;
  my $ref=grep{$h->{$_}{ref}!=0}keys %$h;
  print "== $name clean base=$cb oldhead=$co head=$ch refused-seeds=$ref\n";
  for my $s (sort{$a<=>$b}keys %$h){my($x,$y,$z)=($b->{$s}{holds},$o->{$s}{holds},$h->{$s}{holds});
    print "NEW  seed $s holds base=$x oldhead=$y head=$z ms=$h->{$s}{ms} first: $h->{$s}{first}\n" if $x==0&&$z>0;
    print "GONE seed $s holds base=$x oldhead=$y head=$z\n" if $x>0&&$z==0;
    print "MOVE seed $s holds base=$x oldhead=$y head=$z\n" if $x>0&&$z>0&&$z!=$x;
  }
  my($B,$H)=(kv($bc),kv($hc));
  for my $k (qw(newtonIterations jacobianBuilds residualEvaluations equationGateRejections)){printf "  %s base=%s head=%s %+.1f%%\n",$k,$B->{$k},$H->{$k},100*($H->{$k}/$B->{$k}-1)}
}
