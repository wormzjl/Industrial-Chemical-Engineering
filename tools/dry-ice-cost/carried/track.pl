#!/usr/bin/perl
# Scratch: one node's mass, P, T, solid through a states file. perl track.pl <file> <node id> <every>
use strict; use warnings; my($f,$id,$every)=@ARGV;$every||=1;
open my $in,'<',$f or die;my($i,$c,$n)=(0,0,0);
while(<$in>){if(/island (\d+) committed=(\d+)/){($i,$c)=($1,$2)}if(/node \d+ id=$id RESERVOIR.* P=(\S+) T=(\S+) m=(\S+) liq=(\S+) vap=(\S+).*solidKg=(\S+)/){$n++;printf "isl %d committed %d P %.0f T %.2f m %.1f liq %s vap %s solid %.3f\n",$i,$c,$1,$2,$3,$4,$5,$6 if $n%$every==0;}}
