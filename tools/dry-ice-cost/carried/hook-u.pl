#!/usr/bin/perl
# Scratch: hook-t.pl plus a LAID line where arrivingPhases lays a dry vessel out. perl hook-u.pl <worktree> <overlay folder>
use strict; use warnings;
my ($W,$OUT)=@ARGV;
system('perl',"$W/tmp/dry-ice-symptoms/run/carried/hook-t.pl",@ARGV)==0 or die "hook-t";
sub slurp{open my $f,'<:raw',$_[0] or die $_[0];local $/;my $s=<$f>;$s}
sub spew{my($p,$s)=@_;open my $f,'>:raw',$p or die $p;print $f $s;close $f}
sub sub1{my($sr,$from,$to)=@_;my $n=()=$$sr=~/\Q$from\E/g;die "match $n: $from" unless $n==1;$$sr=~s/\Q$from\E/$to/;}
my $p="$OUT/com/wormzjl/icheme/science/fluid/network/PassiveStepSolver.java";
my $s=slurp($p);
sub1(\$s,q(var arrived=own==null?delivered:own;var held=node.inventory().solids();),
 q(var arrived=own==null?delivered:own;var held=node.inventory().solids();if(com.wormzjl.icheme.science.fluid.diagnostics.StepTrace.on)com.wormzjl.icheme.science.fluid.diagnostics.StepTrace.line("LAID j="+j+" vesselsOnly="+vesselsOnly+" mass="+mass+" heat="+heat+" Tw="+(weighted/mass)+" own="+(own!=null)+" delivered=["+delivered.temperature()+"K "+delivered.pressure()+"Pa code="+phaseCode(delivered)+" wl="+delivered.waterLiquid()+" wv="+delivered.waterVapor()+" V="+delivered.liquidVolume()+"/"+delivered.vaporVolume()+"/"+delivered.waterVolume()+"] arrived=["+arrived.temperature()+"K "+arrived.pressure()+"Pa code="+phaseCode(arrived)+" wl="+arrived.waterLiquid()+" wv="+arrived.waterVapor()+" V="+arrived.liquidVolume()+"/"+arrived.vaporVolume()+"/"+arrived.waterVolume()+" vap="+java.util.Arrays.stream(arrived.vapor()).sum()+" H="+arrived.enthalpy()+"] codes="+current.stream().map(x->phaseCode(x)+"/"+String.format(java.util.Locale.ROOT,"%.4g",x.vaporVolume())+"/"+String.format(java.util.Locale.ROOT,"%.4g",x.waterVolume())).toList()+" n="+java.util.Arrays.toString(n));));
spew($p,$s);
