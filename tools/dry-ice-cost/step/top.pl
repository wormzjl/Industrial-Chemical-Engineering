#!/usr/bin/perl
# Scratch: worst jobs of a jobs table by CPU. perl top.pl <n> <files>
use strict; use warnings; my $n=shift;
for my $file(@ARGV){open my $in,'<',$file or die "$file";my $h=<$in>;$h=~s/\r?\n//;my @h=split /\t/,$h;my %c;$c{$h[$_]}=$_ for 0..$#h;my @r;my($sum,$held,$over)=(0,0,0);
 while(<$in>){s/\r?\n//;my @f=split /\t/;push @r,\@f;$sum+=$f[$c{cpuMs}];$held++ if $f[$c{status}]=~/HELD/;$over++ if $f[$c{cpuMs}]>2000;}
 @r=sort{$b->[$c{cpuMs}]<=>$a->[$c{cpuMs}]}@r;(my $name=$file)=~s{.*/}{};$name=~s{-jobs.tsv}{};printf "%s jobs=%d held=%d over2s=%d cpu=%.1fs\n",$name,scalar @r,$held,$over,$sum/1e3;
 for my $f(@r[0..($n-1<$#r?$n-1:$#r)]){printf "  job %4d isl %3d nodes %2d slice %5d..%5d cpu %7.0f wall %7.0f acc %3d rej %3d solves %4d nwt %5d ckpt %9s %s\n",@$f[$c{job},$c{island},$c{nodes},$c{sliceStart},$c{sliceEnd}],$f->[$c{cpuMs}],$f->[$c{wallMs}],@$f[$c{accepted},$c{rejected},$c{implicitSolves},$c{newtonIterations}],$f->[$c{checkpoints}]//"-",$f->[$c{status}];}
 for my $f(@r){printf "  HELD job %d slice %d..%d cpu %.0f status %s\n",@$f[$c{job},$c{sliceStart},$c{sliceEnd},$c{cpuMs},$c{status}] if $f->[$c{status}]=~/HELD/;}}
