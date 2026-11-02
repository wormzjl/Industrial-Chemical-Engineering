# Lists the states two trips sweeps treat differently: perl moved.pl <dir> <base stem> <form stem> [trips-mode]
use strict;
my ($dir,$a,$b,$tm)=@ARGV;$tm//='trips';
sub rd{my($p)=@_;my %r;my @o;open(F,'<',$p) or die "$p";my $h=<F>;while(<F>){s/\r?\n$//;my @c=split /\t/,$_,-1;my $k=join('|',@c[0..2]);$r{$k}=\@c;push @o,$k}close F;(\%r,\@o)}
my ($x,$ox)=rd("$dir/$a-$tm.tsv");my ($y,$oy)=rd("$dir/$b-$tm.tsv");
for my $k (@$ox){my ($p,$q)=($x->{$k},$y->{$k});next unless $q;next if $p->[3] eq 'REFUSED'||$q->[3] eq 'REFUSED';
  my $tp=abs($p->[4]-$q->[4])>1e-9*(1+abs($p->[4]));
  printf "TP   %-30s H %.3f->%.3f hcV %.6g->%.6g steam %.6g->%.6g cond %.6g->%.6g\n",$k,@$p[3],@$q[3],$p->[4],$q->[4],$p->[5],$q->[5],$p->[6],$q->[6] if $tp;
  my($ua,$ub)=($p->[13] eq 'true',$q->[13] eq 'true');
  printf "UV-  %-30s hcV %.6g steam %.6g cond %.6g\n",$k,$q->[4],$q->[5],$q->[6] if $ua&&!$ub;
  printf "UV+  %-30s hcV %.6g steam %.6g cond %.6g\n",$k,$q->[4],$q->[5],$q->[6] if !$ua&&$ub;
  if($ub){my($T,$P)=@$q[1,2];my $miss=abs($q->[14]-$T)>1e-5||abs($q->[15]/$P-1)>1e-5;my $was=$ua&&(abs($p->[14]-$T)>1e-5||abs($p->[15]/$P-1)>1e-5);
    printf "UVm  %-30s T %.6f->%.6f P %.1f->%.1f hcV %.6g steam %.6g cond %.6g\n",$k,$T,$q->[14],$P,$q->[15],$q->[4],$q->[5],$q->[6] if $miss&&!$was}
  my($pa,$pb)=($p->[7] eq 'true',$q->[7] eq 'true');
  printf "PH-  %-30s\n",$k if $pa&&!$pb;
  if($pa&&$pb&&abs($p->[8]-$q->[8])>1e-6){printf "PHm  %-30s T %.6f->%.6f\n",$k,$p->[8],$q->[8]}
}
