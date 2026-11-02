# Seed time and counters of each campaign: the earlier head and base against this head. Seed by seed, so one heavy seed and a load across every seed read apart.
use strict;
sub rd{my $p=shift;open(F,'<',$p) or die $p;my(%s,$c,$sum);while(<F>){s/\r?\n$//;
  if(/^SEED (\d+) blocks=\d+ ms=(\d+) end=\d+ holds=(\d+)/){$s{$1}={ms=>$2,h=>$3}}
  elsif(/^COUNTERS (.*)/){$c=$1}elsif(/^SUMMARY.*wallMs=(\d+) seedMsSum=(\d+)/){$sum=[$1,$2]}}close F;(\%s,$c,$sum)}
sub kv{my %h;for(split ' ',shift){my($k,$v)=split /=/;$h{$k}=$v}\%h}
sub med{my @a=sort{$a<=>$b}@_;@a?$a[int(@a/2)]:0}
for my $name (qw(cold-random bundled-random bundled-defaults)){
  for my $ref (qw(base head)){
    my($o,$oc,$os)=rd("base-archive/$ref/campaign-$name-report.txt");my($h,$hc,$hs)=rd("head/campaign-$name-report.txt");
    my($O,$H)=(kv($oc),kv($hc));
    my @both=grep{exists $h->{$_}&&$o->{$_}{h}==0&&$h->{$_}{h}==0&&$o->{$_}{ms}>=200}keys %$o;
    my @r=map{$h->{$_}{ms}/$o->{$_}{ms}}@both;
    my($so,$sh)=(0,0);for(@both){$so+=$o->{$_}{ms};$sh+=$h->{$_}{ms}}
    my @top=sort{($h->{$b}{ms}-$o->{$b}{ms})<=>($h->{$a}{ms}-$o->{$a}{ms})}keys %$o;
    printf "== %-17s vs %-4s seedMs %d -> %d %+.1f%%  wall %d -> %d  clean seeds >=200 ms: n=%d sum %+.1f%% median ratio %.3f\n",$name,$ref,$os->[1],$hs->[1],100*($hs->[1]/$os->[1]-1),$os->[0],$hs->[0],scalar@both,100*($sh/$so-1),med(@r);
    printf "   %s %d -> %d %+.2f%%\n",$_,$O->{$_},$H->{$_},100*($H->{$_}/$O->{$_}-1) for grep{$O->{$_}}qw(newtonSolves newtonIterations jacobianBuilds residualEvaluations stepAttempts);
    printf "   top: %s\n",join(' ',map{"$_:".$o->{$_}{ms}."->".$h->{$_}{ms}}@top[0..4]);
  }
}
