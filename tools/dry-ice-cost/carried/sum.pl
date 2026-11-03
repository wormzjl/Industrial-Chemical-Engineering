#!/usr/bin/perl
# Scratch: one row per world of named runs: worst job CPU, checkpoints of that job, total checkpoints, holds, jobs over 2 s, first hold. perl sum.pl <prefix> [prefix...]; prefixes under out/
use strict; use warnings;
my @w=qw(651 768 673 183 683 244 475 855 907);
for my $f(@ARGV){print "== $f\n";for my $s(@w){my $p="$f-$s-jobs.tsv";$p=~s/%/$s/g;unless(-e $p){print "  $s -\n";next}
 open my $in,'<',$p or die;my $h=<$in>;$h=~s/\r?\n//;my @h=split /\t/,$h;my %c;$c{$h[$_]}=$_ for 0..$#h;my($n,$held,$over,$sum,$tr,$ck)=(0,0,0,0,0,0);my $w;my $fh='';
 while(<$in>){s/\r?\n//;my @x=split /\t/;$n++;if($x[$c{status}]=~/HELD/){$held++;$fh||=substr($x[$c{status}],34,62)." @ $x[$c{sliceStart}]"}$over++ if $x[$c{cpuMs}]>2000;$sum+=$x[$c{cpuMs}];$tr+=$x[$c{solidTransitionRejections}]>0?$x[$c{solidTransitionRejections}]:0;$ck+=$c{checkpoints}?$x[$c{checkpoints}]:0;$w=\@x if !$w||$x[$c{cpuMs}]>$w->[$c{cpuMs}]}
 printf "  %s jobs %3d held %2d over2s %2d worst %5.0f %6.1fM job %3d slice %d cpu %.1f s trans %d total %.1fM %s\n",$s,$n,$held,$over,$w->[$c{cpuMs}],$w->[$c{checkpoints}]/1e6,$w->[$c{job}],$w->[$c{sliceStart}],$sum/1e3,$tr,$ck/1e6,$fh;}}
