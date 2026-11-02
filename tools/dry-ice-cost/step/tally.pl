#!/usr/bin/perl
# Scratch: per job of a StepTrace file, engine flashes by site, phase checks by node, repeats. perl tally.pl <trace> <job>
use strict; use warnings;
my ($file,$want)=@ARGV;open my $in,'<',$file or die;
my $on=0;my (%site,%node,%seen,%dup,%rep,%pairs,%ctx);my ($att,$outcome)=(0,'');my @pending;my $stages='';my %attCheck;
sub site{my $c=shift;my $rate=$c=~/solveRate|portRate/?'rate:':'';
    return $rate.'balance' if $c=~/balanceStart|mixed</;
    return $rate.'phaseCheck.crossing' if $c=~/crossing</;
    return $rate.'phaseCheck' if $c=~/phaseCheck</;
    return $rate.'flashSeeds' if $c=~/flashSeeds|settledFlash/;
    return $rate.'arrivingPhases' if $c=~/arrivingPhases/;
    return $rate.'emptyBag' if $c=~/emptyBagSplit/;
    return $rate.'waterTrace' if $c=~/waterTraceSeed/;
    return $rate.'reconstruct' if $c=~/reconstruct/;
    return $rate.'refresh' if $c=~/refresh|conversions|completed|InventoryEquilibrium/;
    return $rate.'other:'.substr($c,0,60);}
while(<$in>){
    if(/^JOB (\d+)/){$on=$1==$want;next}
    next unless $on;
    if(/^STAGES/){$stages=$_;next}
    if(/^ATT /){$att++;next}
    if(/^FLASH (\S+) comp=(\S+) a=(\S+) b=(\S+) n=(\S+) z=(\S+) cls=(\S+) conv=(\S+) kernel=(\d+) us=(\S+) site=(.*)/){
        my($k,$comp,$a,$b,$n,$z,$cls,$conv,$kern,$us,$chain)=($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11);
        my $s=site($chain);my $key="$s $k ".($comp eq 'true'?'comp':'fluid');
        $site{$key}[0]++;$site{$key}[1]+=$us/1e3;$site{$key}[2]+=$kern;$site{$key}[3]++ if $conv ne 'true';
        # a repeat: same request to the 7 printed digits, with amounts per mole (the composition printed), and T,P or P,H per mole
        my $spec=$k eq 'TP'?$a:sprintf("%.5e",$a/$n);
        my $id=join('|',$k,$comp,$spec,$b,$z);
        if($seen{$id}++){$dup{$key}[0]++;$dup{$key}[1]+=$us/1e3;}
        next}
    if(/^CHECK n=(\d+) j=(\S+) T=(\S+) P=(\S+) bits=(\d+) complete=(\S+) tp=(-?\d+) excl=(\d+) prop=(\d+) ph=(-?\d+) own=(-?\d+) ref=(\d+) add=(\d+) changed=(\S+) us=(\S+)/){
        my($n,$j,$T,$P,$bits,$complete,$tp,$excl,$prop,$ph,$own,$ref,$add,$chg,$us)=($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15);
        my $e=$node{$n}//={};$e->{checks}++;$e->{complete}++ if $complete eq 'true';$e->{tp}++ if $tp>=0;$e->{prop}++ if $prop;$e->{ref}++ if $ref;$e->{chg}++ if $chg eq 'true';$e->{us}+=$us;$e->{add}++ if $add;
        $e->{bits}{$bits}++;$e->{tpb}{$tp}++ if $tp>=0;$e->{propb}{$prop}++ if $prop;$e->{phb}{$ph}++ if $prop;$e->{ownb}{$own}++ if $prop;$e->{T}=$T;$e->{P}=$P;$e->{j}=$j;
        next}
}
printf "%-44s %6s %9s %10s %6s %6s %9s\n",'site kind','n','ms','kernel','unconv','repeat','repeat ms';
my ($tn,$tm)=(0,0);for(sort{$site{$b}[1]<=>$site{$a}[1]}keys %site){printf "%-44s %6d %9.1f %10d %6d %6d %9.1f\n",$_,@{$site{$_}}[0..2],$site{$_}[3]//0,$dup{$_}[0]//0,$dup{$_}[1]//0;$tn+=$site{$_}[0];$tm+=$site{$_}[1]}
printf "total flashes %d, %.1f ms\n\n",$tn,$tm;
printf "%4s %-5s %6s %8s %5s %5s %5s %5s %5s %8s  %s\n",'node','junc','checks','complete','TP','prop','ref','add','chg','ms','bits | tp bits | proposed | PH | own | last T P';
for my $n(sort{$a<=>$b}keys %node){my $e=$node{$n};my $f=sub{my $h=shift;join(',',map{"$_:$h->{$_}"}sort keys %$h)};
    printf "%4d %-5s %6d %8d %5d %5d %5d %5d %5d %8.1f  %s | %s | %s | %s | %s | %s %s\n",$n,$e->{j},$e->{checks},$e->{complete}//0,$e->{tp}//0,$e->{prop}//0,$e->{ref}//0,$e->{add}//0,$e->{chg}//0,$e->{us}/1e3,
        $f->($e->{bits}),$f->($e->{tpb}//{}),$f->($e->{propb}//{}),$f->($e->{phb}//{}),$f->($e->{ownb}//{}),$e->{T},$e->{P};}
print "\n$stages";
