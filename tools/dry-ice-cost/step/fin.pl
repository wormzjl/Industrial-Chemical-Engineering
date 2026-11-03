#!/usr/bin/perl
# Scratch: final RESERVOIR lines of two states files side by side. perl fin.pl a b
use strict; use warnings;
sub lastn{my $f=shift;open my $in,'<',$f or die;my %n;my %o;my $i=0;while(<$in>){if(/island (\d+) committed=(\d+)/){$i=$1}if(/node (\d+) id=(\d+) RESERVOIR.* P=(\S+) T=(\S+) m=(\S+) liq=(\S+) vap=(\S+).*solidKg=(\S+)/){$n{"$i:$2"}=[$3,$4,$5,$8];}}return \%n}
my($a,$b)=(lastn($ARGV[0]),lastn($ARGV[1]));
for my $k(sort keys %$a){next unless $b->{$k};printf "  %-8s P %9.0f %9.0f  T %7.2f %7.2f  m %8.1f %8.1f  solid %7.3f %7.3f\n",$k,$a->{$k}[0],$b->{$k}[0],$a->{$k}[1],$b->{$k}[1],$a->{$k}[2],$b->{$k}[2],$a->{$k}[3],$b->{$k}[3];}
