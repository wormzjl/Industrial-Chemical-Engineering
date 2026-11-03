#!/usr/bin/perl
# Scratch: one line per attempt of a job in a StepTrace file, then totals by outcome. perl attempts.pl <trace> <job>
use strict; use warnings;
my ($file,$want)=@ARGV;open my $in,'<',$file or die;
my ($on,%by,%pass,$att,$np,$nf,$nc,$prop,$ref,$rate,$note,$ms)=(0);
my @rows;my $stages='';
while(<$in>){
    if(/^JOB (\d+)/){$on=$1==$want;next}
    next unless $on;
    if(/^STAGES/){$stages=$_;next}
    if(/^RUN (.*)/){push @rows,"RUN $1";next}
    if(/^ATT (\d+) t=(\S+) step=(\S+) h=(\S+)/){$att={a=>$1,t=>$2,step=>$3,h=>$4};$np=0;$nf=0;$nc=0;$prop=0;$ref=0;$rate=0;next}
    next unless $att;
    if(/^SOLVE rate=true/){$rate++}
    if(/^PASS \d+ (\S+)(.*)/){$np++;$pass{$1}++;$prop++ if $1 eq 'proposal';$ref++ if $1 eq 'refuted'}
    if(/^NEWTON ok=false/){$nf++}
    if(/^CHECK/){$nc++;$ref++ if / ref=[1-9]/}
    if(/^END (\S+) change=(\S+) ms=(\S+) newton=(\d+) solves=(\d+) jac=(\d+) note=(.*)/){
        my($o,$c,$m,$n,$s,$j,$nt)=($1,$2,$3,$4,$5,$6,$7);
        (my $key=$o)=~s/[-+]?\d+(\.\d+)?([eE][-+]?\d+)?/#/g;$key=substr($key,0,70);
        $by{$key}[0]++;$by{$key}[1]+=$m;$by{$key}[2]+=$n;$by{$key}[3]+=$s;$by{$key}[4]+=$att->{step} if $key=~/^accepted/;
        push @rows,sprintf("%3d t=%9.5f step=%9.5g h=%9.5g %-40s chg=%-9s %8.1fms nwt=%4d solves=%2d rate=%d passes=%2d nfail=%d prop=%d ref=%d checks=%d %s",$att->{a},$att->{t},$att->{step},$att->{h},substr($o,0,40),$c,$m,$n,$s,$rate,$np,$nf,$prop,$ref,$nc,substr($nt,0,70));
        $att=undef;
    }
}
print "$_\n" for @rows;print "\n";
printf "%-72s %5s %9s %6s %6s %9s\n",'outcome','n','ms','newton','solves','adv s';
for(sort{$by{$b}[0]<=>$by{$a}[0]}keys %by){printf "%-72s %5d %9.1f %6d %6d %9.4f\n",$_,@{$by{$_}}[0..3],$by{$_}[4]//0}
print "pass exits: ",join(' ',map{"$_=$pass{$_}"}sort keys %pass),"\n";print $stages;
