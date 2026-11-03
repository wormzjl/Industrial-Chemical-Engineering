#!/usr/bin/perl
# Scratch: first committed tick at which each pipe of the last island shows a blocked mask, and tank 12's state there. perl closures.pl file island
use strict; use warnings; my %seen; my($c,$i); my $want=$ARGV[1];
open my $f,'<',$ARGV[0] or die;
while(<$f>){ if(/^island (\d+) committed=(\d+) status=(\S+)/){$i=$1;$c=$2}
 next unless defined $i && $i==$want;
 if(/pipe (\d+) (\d+)->(\d+) .* blocked=(\d)/){my $k="$1 $2->$3";if(($seen{$k}//0)ne$4){print "tick $c pipe $k blocked=$4\n";$seen{$k}=$4}}}
