#!/usr/bin/perl
# Scratch: run totals of chosen counters of jobs tables. perl col.pl <files>
use strict; use warnings;
my @want=qw(accepted rejected stepAttempts implicitSolves newtonIterations solidTransitionRejections equationGateRejections phaseRefusals phaseSeeds flashCalls);
printf "%-14s %s\n",'run',join(' ',map{sprintf "%9s",substr($_,0,9)}@want);
for my $file(@ARGV){open my $in,'<',$file or die;my $h=<$in>;$h=~s/\r?\n//;my @h=split /\t/,$h;my %c;$c{$h[$_]}=$_ for 0..$#h;my %s;
 while(<$in>){s/\r?\n//;my @f=split /\t/;next if $f[$c{status}]=~/HELD/&&0;for(@want){$s{$_}+=$f[$c{$_}]>0?$f[$c{$_}]:0}}
 (my $n=$file)=~s{.*/}{};$n=~s/-jobs.tsv//;printf "%-14s %s\n",$n,join(' ',map{sprintf "%9d",$s{$_}}@want);}
