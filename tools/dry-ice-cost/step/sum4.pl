#!/usr/bin/perl
# Scratch: one row per world and form: worst job (CPU ms, checkpoints, job, slice), holds, jobs over 2 s CPU, total Gkp. perl sum4.pl
use strict; use warnings;
my @w=qw(651 768 673 183 683 244 475 855 907);
my %forms=(today=>sub{my $s=shift;$s==683?"../out/683-raised":$s=~/^(244|475|855|907)$/?"out/base-$s":"../out/r-base-$s"},
 bplus=>sub{my $s=shift;$s=~/^(651|768)$/?"../out/r-proto2-$s":"out/bp-$s"},
 closureA=>sub{"out/late-$_[0]"},A=>sub{"out/late2-$_[0]"},F=>sub{"out/f-$_[0]"},G=>sub{"out/g-$_[0]"});
for my $f(qw(today bplus closureA A F G)){print "== $f\n";for my $s(@w){my $p=$forms{$f}->($s)."-jobs.tsv";unless(-e $p){print "  $s -\n";next}
 open my $in,'<',$p or die;my $h=<$in>;$h=~s/\r?\n//;my @h=split /\t/,$h;my %c;$c{$h[$_]}=$_ for 0..$#h;my($n,$held,$over,$sum,$tr,$ck)=(0,0,0,0,0,0);my $w;my $firstHold='';
 while(<$in>){s/\r?\n//;my @x=split /\t/;$n++;if($x[$c{status}]=~/HELD/){$held++;$firstHold||=substr($x[$c{status}],33,60)." @ slice $x[$c{sliceStart}]"}$over++ if $x[$c{cpuMs}]>2000;$sum+=$x[$c{cpuMs}];$tr+=$x[$c{solidTransitionRejections}];$ck+=($c{checkpoints}?$x[$c{checkpoints}]:0);$w=\@x if !$w||$x[$c{cpuMs}]>$w->[$c{cpuMs}]}
 printf "  %s jobs %3d held %2d over2s %2d worst %5.0f ckpt %9s job %3d slice %d..%d acc %d rej %d | cpu %.1f s trans %d total %.0f M %s\n",$s,$n,$held,$over,$w->[$c{cpuMs}],$c{checkpoints}?$w->[$c{checkpoints}]:'-',$w->[$c{job}],$w->[$c{sliceStart}],$w->[$c{sliceEnd}],$w->[$c{accepted}],$w->[$c{rejected}],$sum/1e3,$tr,$ck/1e6,$firstHold;}}
