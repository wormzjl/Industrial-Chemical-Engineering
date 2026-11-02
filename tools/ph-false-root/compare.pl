# Compares each campaign report of a lane folder with main's baseline: clean seeds, worse and better seeds with classes, hold counts, counters.
# perl compare.pl <base dir> <head dir>
use strict;
my ($bd,$hd)=@ARGV;
sub rd{my $p=shift;open(F,'<',$p) or die $p;my(%s,$sum,$c);my $cur;while(<F>){s/\r?\n$//;
  if(/^SEED (\d+) blocks=\d+ ms=(\d+) end=\d+ holds=(\d+) classes=\[(.*?)\] refused=(\d+)/){$cur=$1;$s{$1}={ms=>$2,holds=>$3,cls=>$4,ref=>$5,first=>''}}
  elsif(/^  HELD / && defined $cur && !$s{$cur}{first}){my $t=$_;$t=~s/.*status=//;$s{$cur}{first}=substr($t,0,70)}
  elsif(/^COUNTERS (.*)/){$c=$1}elsif(/^SUMMARY/){$sum=$_}}close F;(\%s,$c)}
sub kv{my %h;for(split ' ',shift){my($k,$v)=split /=/;$h{$k}=$v}\%h}
for my $name (qw(cold-random bundled-random bundled-defaults)){
  my($b,$bc)=rd("$bd/campaign-$name-report.txt");my($h,$hc)=rd("$hd/campaign-$name-report.txt");
  my $cb=grep{$b->{$_}{holds}==0}keys %$b;my $ch=grep{$h->{$_}{holds}==0}keys %$h;
  my $ref=grep{$h->{$_}{ref}!=0}keys %$h;
  my ($hb,$hh)=(0,0);$hb+=$b->{$_}{holds} for keys %$b;$hh+=$h->{$_}{holds} for keys %$h;
  print "== $name clean base=$cb head=$ch holds base=$hb head=$hh refused-seeds=$ref\n";
  my ($same,$msb,$msh)=(0,0,0);
  for my $s (sort{$a<=>$b}keys %$h){my($x,$z)=($b->{$s}{holds},$h->{$s}{holds});
    $same++ if $x==$z;$msb+=$b->{$s}{ms};$msh+=$h->{$s}{ms};
    print "NEW  seed $s holds base=$x head=$z classes=[$h->{$s}{cls}] ms=$h->{$s}{ms} first: $h->{$s}{first}\n" if $x==0&&$z>0;
    print "GONE seed $s holds base=$x head=$z classes=[$b->{$s}{cls}] first: $b->{$s}{first}\n" if $x>0&&$z==0;
    print "MOVE seed $s holds base=$x head=$z classes base=[$b->{$s}{cls}] head=[$h->{$s}{cls}]\n" if $x>0&&$z>0&&$z!=$x;
  }
  print "  seeds with equal hold counts: $same\n";
  printf "  seedMsSum base=%d head=%d %+.1f%%\n",$msb,$msh,100*($msh/$msb-1);
  my($B,$H)=(kv($bc),kv($hc));
  for my $k (sort keys %$H){next unless exists $B->{$k};next if $B->{$k}==0&&$H->{$k}==0;next if $B->{$k}==$H->{$k};
    printf "  %s base=%s head=%s %+.2f%%\n",$k,$B->{$k},$H->{$k},$B->{$k}?100*($H->{$k}/$B->{$k}-1):0}
}
