#!/usr/bin/perl
# Scratch: per run, the first job over 2 s (wall, CPU), the first held job, and the last committed tick. perl first.pl <jobs.tsv>...
use strict; use warnings;
for my $f(@ARGV){open my $in,'<',$f or die "$f";my $h=<$in>;$h=~s/\r?\n//;my @h=split /\t/,$h;my %c;$c{$h[$_]}=$_ for 0..$#h;my($o,$c2,$hd,$n)=('-','-','-',0);
 while(<$in>){s/\r?\n//;my @x=split /\t/;$n++;
  $o=sprintf('job %d slice %d wall %.0f',$x[$c{job}],$x[$c{sliceStart}],$x[$c{wallMs}]) if $o eq '-'&&$x[$c{wallMs}]>2000;
  $c2=sprintf('job %d slice %d cpu %.0f',$x[$c{job}],$x[$c{sliceStart}],$x[$c{cpuMs}]) if $c2 eq '-'&&$x[$c{cpuMs}]>2000;
  $hd=sprintf('job %d island %d slice %d',$x[$c{job}],$x[$c{island}],$x[$c{sliceStart}]) if $hd eq '-'&&$x[$c{status}]=~/HELD/;}
 printf "%-34s jobs %3d | first wall>2s %-34s | first cpu>2s %-30s | first held %s\n",$f,$n,$o,$c2,$hd;}
