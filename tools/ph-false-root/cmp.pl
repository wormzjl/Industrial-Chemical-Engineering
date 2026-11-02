# Compares two PhProbe sweeps row by row on their keys, not their line numbers. perl cmp.pl <dir> <base stem> <form stem> [trips-mode gap-mode]
# trips rows: feed T P H hcV steam cond phOk phT phHcV phSteam phBal phCalls uvOk uvT uvP uvHcV uvBal uvCalls, or feed T P REFUSED.
# gap rows: feed P Tsat fraction target ok T hcV steam cond bal calls detail.
use strict;
my ($dir,$a,$b,$tm,$gm)=@ARGV;$tm//='trips';$gm//='gap';
sub rd{my($p,$n)=@_;my(%r,@o);open(F,'<',$p) or die "$p";my $h=<F>;while(<F>){s/\r?\n$//;my @c=split /\t/,$_,-1;my $k=join('|',@c[0..$n-1]);$r{$k}=\@c;push @o,$k}close F;(\%r,\@o)}
sub off{my($x,$y)=@_;abs($x-$y)}
my ($ta,$oa)=rd("$dir/$a-$tm.tsv",3);my ($tb,$ob)=rd("$dir/$b-$tm.tsv",3);
my (%S,@feeds,%seen);
for my $k (@$oa){my($f)=split /\|/,$k;push @feeds,$f unless $seen{$f}++;my($x,$y)=($ta->{$k},$tb->{$k});next unless $y;my $s=$S{$f}//={};
  $s->{n}++;my $rx=$x->[3] eq 'REFUSED';my $ry=$y->[3] eq 'REFUSED';$s->{ra}++ if $rx;$s->{rb}++ if $ry;$s->{newtp}++ if $rx&&!$ry;$s->{losttp}++ if !$rx&&$ry;
  for my $side (['a',$x,$rx],['b',$y,$ry]){my($t,$c,$r)=@$side;next if $r;my($T,$P)=@$c[1,2];
    if($c->[7] ne 'true'){$s->{"pf$t"}++}else{$s->{"pm$t"}++ if off($c->[8],$T)>1e-5;$s->{"pc$t"}+=$c->[12];$s->{"pn$t"}++;$s->{"px$t"}=$c->[12] if $c->[12]>($s->{"px$t"}//0)}
    if($c->[13] ne 'true'){$s->{"uf$t"}++}else{$s->{"um$t"}++ if off($c->[14],$T)>1e-5||off($c->[15]/$P,1)>1e-5;$s->{"uc$t"}+=$c->[18];$s->{"un$t"}++}}
  if(!$rx&&!$ry&&$x->[7] eq 'true'&&$y->[7] eq 'true'&&off($x->[8],$y->[8])>1e-6){$s->{mv}++}
  if(!$rx&&!$ry&&off($x->[4],$y->[4])>1e-9*(1+abs($x->[4]))){$s->{tpmv}++}
}
printf "%-22s %4s | %8s %5s %5s | %8s %8s %11s %4s | %8s %8s %11s\n",'trips','n','refused','newTP','lost','PHmiss','PHfail','PHcalls','max','UVmiss','UVfail','UVcalls';
for my $f (@feeds){my $s=$S{$f};next unless $s;my $d=sub{my $k=shift;$s->{$k}//0};
  printf "%-22s %4d | %3d->%-3d %5d %5d | %3d->%-3d %3d->%-3d %4.1f->%-5.1f %4d | %3d->%-3d %3d->%-3d %5.1f->%-5.1f%s\n",$f,$s->{n},$d->('ra'),$d->('rb'),$d->('newtp'),$d->('losttp'),
   $d->('pma'),$d->('pmb'),$d->('pfa'),$d->('pfb'),$d->('pca')/($d->('pna')||1),$d->('pcb')/($d->('pnb')||1),$d->('pxb'),
   $d->('uma'),$d->('umb'),$d->('ufa'),$d->('ufb'),$d->('uca')/($d->('una')||1),$d->('ucb')/($d->('unb')||1),$d->('tpmv')?"  TPmoved ".$d->('tpmv'):''}
exit unless -f "$dir/$a-$gm.tsv" && -f "$dir/$b-$gm.tsv";
my ($ga,$ga_o)=rd("$dir/$a-$gm.tsv",1);
# Gap targets are keyed by feed, pressure and fraction; each side keeps its own target when its ends differ.
my (%G,@gf,%gs);
for my $side ([$a,'a'],[$b,'b']){my($stem,$t)=@$side;open(F,'<',"$dir/$stem-$gm.tsv") or die;my $h=<F>;while(<F>){s/\r?\n$//;my @c=split /\t/,$_,-1;my $f=$c[0];push @gf,$f unless $gs{$f}++;my $g=$G{$f}//={};
  $g->{"n$t"}++;$g->{"ok$t"}++ if $c[5] eq 'true';$g->{"bal$t"}++ if $c[10] eq 'true';$g->{"calls$t"}+=$c[11];
  my $d=$c[12]//'';if($c[5] ne 'true'){(my $e=$d)=~s/[-+]?[0-9][0-9.eE+-]*/#/g;$g->{"why$t"}{substr($e,0,52)}++}}close F}
printf "\n%-22s | %9s | %9s | %9s | %11s\n",'gap','targets','fail','balance','calls';
for my $f (@gf){my $g=$G{$f};my $d=sub{$g->{$_[0]}//0};
  printf "%-22s | %3d->%-3d | %3d->%-3d | %3d->%-3d | %5.1f->%-5.1f\n",$f,$d->('na'),$d->('nb'),$d->('na')-$d->('oka'),$d->('nb')-$d->('okb'),$d->('bala'),$d->('balb'),$d->('callsa')/($d->('na')||1),$d->('callsb')/($d->('nb')||1);
  for my $t (qw(b)){next unless $g->{"why$t"};printf "    %s %3d x %s\n",$t,$g->{"why$t"}{$_},$_ for sort keys %{$g->{"why$t"}}}}
