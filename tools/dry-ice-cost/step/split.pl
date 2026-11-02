#!/usr/bin/perl
# Scratch: kernel evaluations (checkpoints, machine independent) of a job by attempt outcome and flash site. perl split.pl <trace> <job>
use strict; use warnings;
my ($file,$want)=@ARGV;open my $in,'<',$file or die;my $on=0;my (%o,%s,@pend,$tot);
sub site{my $c=shift;my $r=$c=~/solveRate|portRate/?'rate.':'step.';return $r.'balance' if $c=~/balanceStart|mixed</;return $r.'check' if $c=~/phaseCheck</;return $r.'flashSeeds' if $c=~/flashSeeds|settledFlash/;return $r.'arriving' if $c=~/arrivingPhases/;return $r.'refresh' if $c=~/refresh|conversions|completed|InventoryEquilibrium/;return $r.'other'}
my $pre=0;
while(<$in>){
    if(/^JOB (\d+)/){$on=$1==$want;next} next unless $on;
    if(/^FLASH .* kernel=(\d+) us=\S+ site=(.*)/){$pre+=$1;$s{site($2)}+=$1;$tot+=$1;next}
    if(/^END (\S+) /){my $k=$1;$k=~s/\|.*//;$k=~s/-.*// ;$k='gate' if /equation_gate|equation gate/;$k='newton' if /^END newton/&&$k ne 'gate';$o{$k}[0]++;$o{$k}[1]+=$pre;$pre=0;next}
}
$o{'after last attempt'}[1]=$pre if $pre;
printf "%-22s %6s %10s %6s\n",'attempt outcome','n','kernel','%';for(sort{$o{$b}[1]<=>$o{$a}[1]}keys %o){printf "%-22s %6d %10d %5.1f\n",$_,$o{$_}[0]//0,$o{$_}[1],100*$o{$_}[1]/$tot}
print "\n";printf "%-22s %10s %6s\n",'site','kernel','%';for(sort{$s{$b}<=>$s{$a}}keys %s){printf "%-22s %10d %5.1f\n",$_,$s{$_},100*$s{$_}/$tot}printf "total %d\n",$tot;
