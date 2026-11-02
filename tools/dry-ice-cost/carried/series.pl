#!/usr/bin/perl
# Scratch: per-described job, island committed tick and the two tanks' mass, one form per file. perl series.pl file
use strict; use warnings;
my %s;my($c,$isl);
open my $f,'<',$ARGV[0] or die;
while(<$f>){ if(/^island (\d+) committed=(\d+)/){$isl=$1;$c=$2}
 if(/node (\d+) id=(\d+) RESERVOIR.* P=([\d.]+) T=([\d.]+) m=([\d.e+]+) .*solidKg=([\d.e+-]+)/){$s{$c}{"$2"}=sprintf("%8.0f %7.2f %7.1f %6.2f",$3,$4,$5,$6)} }
for my $k(sort{$a<=>$b}keys %s){print "$k";for my $id(sort{$a<=>$b}keys %{$s{$k}}){print " | $id $s{$k}{$id}"}print "\n"}
