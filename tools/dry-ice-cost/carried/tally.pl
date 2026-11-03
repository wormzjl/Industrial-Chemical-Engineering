#!/usr/bin/perl
# Scratch: FluidThermodynamics with the tally hooks, to the overlay folder. perl tally.pl <worktree> <overlay folder>
use strict; use warnings;
my ($W,$OUT)=@ARGV;
sub slurp{open my $f,'<:raw',$_[0] or die $_[0];local $/;my $s=<$f>;$s=~s/\r\n/\n/g;$s}
sub sub1{my($sr,$from,$to)=@_;my $n=()=$$sr=~/\Q$from\E/g;die "match $n: $from" unless $n==1;$$sr=~s/\Q$from\E/$to/;}
my $p="com/wormzjl/icheme/science/fluid/thermo/FluidThermodynamics.java";
my $s=slurp("$W/src/main/java/$p");
sub1(\$s,'var result=phaseEngine().tp(t,p,overall,stepCompetition(t,overall));','var result=phaseEngine().tp(t,p,overall,stepCompetition(t,overall));CarriedTally.note("TP",t,p,overall,result);');
sub1(\$s,'var result=phaseEngine().ph(p,target,overall,competition);','var result=phaseEngine().ph(p,target,overall,competition);CarriedTally.note(competition.carried()?"PH":"PH-"+competition,t,p,overall,result);');
sub1(\$s,'var result=phaseEngine().crystalUv(engineEnergy(u,overall),v,overall,stepCompetition(t,overall),t,p);','var result=phaseEngine().crystalUv(engineEnergy(u,overall),v,overall,stepCompetition(t,overall),t,p);CarriedTally.note("UV",t,p,overall,result);');
system('mkdir','-p',"$OUT/com/wormzjl/icheme/science/fluid/thermo");
open my $f,'>:raw',"$OUT/$p" or die;print $f $s;close $f;
