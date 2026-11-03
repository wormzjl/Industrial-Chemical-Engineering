#!/usr/bin/perl
# Scratch (tmp/dry-ice-symptoms/run, 2026-11-02): the stage split of a DryIceProbe run from its JFR execution samples.
# Usage: perl jfrstages.pl <jfr print output> <jobs.tsv> [heavyMs=500]
# Every sample is attributed to one stage by the frames on its stack (outermost stage wins, then its sub-stage), and to
# the job whose [epochStartMs, epochEndMs] holds its time of day; the split is printed for all samples, for the samples
# inside heavy jobs (wall >= heavyMs) and for those inside light jobs, with the hot mod-side leaf methods of the heavy jobs.
use strict; use warnings;
my ($samples,$jobs,$heavyMs)=@ARGV; $heavyMs//=500;
# Jobs: epoch ms windows (local time of day, since jfr print gives time of day).
my @jobs; open(my $J,'<',$jobs) or die $!; my $head=<$J>; chomp $head; my @cols=split /\t/,$head; my %ix; $ix{$cols[$_]}=$_ for 0..$#cols;
while(<$J>){chomp; my @f=split /\t/; push @jobs,{job=>$f[$ix{job}],start=>$f[$ix{epochStartMs}],end=>$f[$ix{epochEndMs}],wall=>$f[$ix{wallMs}],island=>$f[$ix{island}],tick=>$f[$ix{onlineTick}]};}
close $J;
# Epoch ms -> ms of local day: use the first job's epoch and the sample's time of day; offset from the local time zone.
my $tzOffsetMs; { my @lt=localtime(int($jobs[0]{start}/1000)); my $dayMs=(($lt[2]*60+$lt[1])*60+$lt[0])*1000+($jobs[0]{start}%1000); $tzOffsetMs=$dayMs-($jobs[0]{start}%86400000); }
sub dayMs { my $e=shift; my $d=($e+$tzOffsetMs)%86400000; return $d; }
for (@jobs){ $_->{s}=dayMs($_->{start}); $_->{e}=dayMs($_->{end}); }
my @sorted=sort{$a->{s}<=>$b->{s}}@jobs;
sub jobAt { my $t=shift; for my $j (@sorted){ return $j if $t>=$j->{s} && $t<=$j->{e}; } return undef; }

sub stage {
  my @fr=@_; my $all=join("\n",@fr);
  my $engine=($all=~/FluidTpEquilibrium\./)?'+engine':'';
  my $sub='';
  if($all=~/InventoryEquilibrium\.(conversions|completed|refresh)/){
    if($all=~/VesselCrystals\.settle/){ $sub=$all=~/FluidTpEquilibrium\.solveUv|crystalUv|crystalsAtVolume/?'settle-uv':$all=~/FluidTpEquilibrium\.solvePh|crystalPh|crystalsAtPressure/?'settle-ph':$all=~/SparseNewton/?'settle-joint-newton':$all=~/FluidTpEquilibrium/?'settle-tp':'settle-other'; return "refresh/$sub"; }
    return "refresh/close$engine" if $all=~/InventoryEquilibrium\.(close|passes|solve)/;
    return "refresh/other$engine";
  }
  return "replay" if $all=~/PassiveIntervalSolver\.replayStart/;
  if($all=~/PassiveStepSolver\.solve\(/ || $all=~/PassiveStepSolver\./){
    return "step/balance$engine" if $all=~/balanceStart|BalanceNet/;
    return "step/phase$engine" if $all=~/phaseCorrection|flashSeeds|arrivingPhases|initialPhaseSeeds|phaseCheck|crossing|junctionSplit|vesselSplit/;
    return "step/reconstruct$engine" if $all=~/ConservativeTransport\.reconstruct/;
    return "step/polish$engine" if $all=~/polishRefusedPoint/;
    if($all=~/SparseNewton/){
      return "step/newton-jacobian" if $all=~/SparseNewton\.differentiate/;
      return "step/newton-lu" if $all=~/SparseLu|\.factor|\.solveFactor|LinearSolver|org\.ejml/;
      return "step/newton-residual$engine" if $all=~/Equations\.|\.evaluate|residual/i;
      return "step/newton-other";
    }
    return "step/conservation" if $all=~/checkConservation/;
    return "step/other$engine";
  }
  return "solids$engine" if $all=~/SolidEventIntegrator/;
  return "interval/other$engine" if $all=~/PassiveIntervalSolver\./;
  return "runtime" if $all=~/IslandCoordinator|PhysicalRegistry|WorldTopologyLedger|RetainedSolver|ProcessSolveServices/;
  return "harness" if $all=~/DryIceProbe|HoldFuzzTest/;
  return "jvm/other";
}
my (%all,%heavy,%light,%leafHeavy,%engineHeavy); my ($n,$nh,$nl,$nu)=(0,0,0,0);
open(my $S,'<',$samples) or die $!;
my ($inStack,$time,@frames)=(0,undef);
while(<$S>){
  if(/startTime = (\d+):(\d+):(\d+)\.(\d+)/){ $time=(($1*60+$2)*60+$3)*1000+int(substr($4,0,3)); @frames=(); next; }
  if(/stackTrace = \[/){ $inStack=1; next; }
  if($inStack){
    if(/^\s*\]/){ $inStack=0; my $st=stage(@frames); $n++; $all{$st}++;
      my $j=defined $time?jobAt($time):undef;
      if(!$j){$nu++;}
      elsif($j->{wall}>=$heavyMs){ $nh++; $heavy{$st}++; my ($leaf)=grep{/com\.wormzjl/}@frames; $leaf//='(none)'; $leaf=~s/^\s+//; $leaf=~s/\(.*$//; $leafHeavy{$leaf}++;
        my ($eng)=grep{/FluidTpEquilibrium\.(solveTp|solvePh|solveUv|solve\()/}@frames; if($eng){$eng=~s/^\s+//;$eng=~s/\(.*$//;$engineHeavy{$eng}++;} }
      else { $nl++; $light{$st}++; }
      next; }
    if(/^\s*(\S+\(.*?\))\s+line:/ || /^\s*(\S+)\s*$/){ push @frames,$1 if @frames<300; }
  }
}
close $S;
printf "samples=%d heavyJobSamples=%d lightJobSamples=%d outsideJobs=%d (heavy = job wall >= %s ms)\n",$n,$nh,$nl,$nu,$heavyMs;
sub table { my ($title,$h,$tot)=@_; print "== $title (n=$tot)\n"; for (sort{$h->{$b}<=>$h->{$a}}keys %$h){ printf "  %6.1f%%  %6d  %s\n",100*$h->{$_}/($tot||1),$h->{$_},$_; } }
table("all samples",\%all,$n); table("heavy jobs",\%heavy,$nh); table("light jobs",\%light,$nl);
print "== engine entry in heavy jobs\n"; for (sort{$engineHeavy{$b}<=>$engineHeavy{$a}}keys %engineHeavy){ printf "  %6.1f%%  %6d  %s\n",100*$engineHeavy{$_}/($nh||1),$engineHeavy{$_},$_; }
print "== hot mod-side leaf methods in heavy jobs\n"; my $k=0; for (sort{$leafHeavy{$b}<=>$leafHeavy{$a}}keys %leafHeavy){ printf "  %6.1f%%  %6d  %s\n",100*$leafHeavy{$_}/($nh||1),$leafHeavy{$_},$_; last if ++$k>=30; }
