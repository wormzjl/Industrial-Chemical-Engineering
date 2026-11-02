# Campaign worlds whose end state differs from the baseline: the SEED line without its time, and the size of the crystal change.
# perl ends.pl <base dir> <head dir>
use strict;
my ($bd,$hd)=@ARGV;
sub rd{my $p=shift;open(F,'<',$p) or die $p;my %s;while(<F>){s/\r?\n$//;next unless /^SEED (\d+) /;my $n=$1;my $l=$_;$l=~s/ ms=\d+//;$s{$n}=$l}close F;\%s}
for my $name (qw(cold-random bundled-random bundled-defaults)){
  my $b=rd("$bd/campaign-$name-report.txt");my $h=rd("$hd/campaign-$name-report.txt");
  my ($same,$diff,$held)=(0,0,0);my @show;
  for my $s (sort{$a<=>$b}keys %$h){
    if($b->{$s} eq $h->{$s}){$same++;next}
    $diff++;
    my ($x,$y)=($b->{$s},$h->{$s});
    my ($hx)=$x=~/holds=(\d+)/;my ($hy)=$y=~/holds=(\d+)/;
    my ($ex)=$x=~/ end=(\d+)/;my ($ey)=$y=~/ end=(\d+)/;
    my ($cx)=$x=~/crystalKg=([\d.]+)/;my ($cy)=$y=~/crystalKg=([\d.]+)/;
    my ($ix)=$x=~/islands=(.*?); crystalKg/;my ($iy)=$y=~/islands=(.*?); crystalKg/;
    push @show,sprintf("  seed %d holds %d->%d end %d->%d crystalKg %s->%s islands%s",$s,$hx,$hy,$ex,$ey,$cx,$cy,($ix//'') eq ($iy//'')?' same':' differ');
  }
  print "== $name same=$same differ=$diff\n";
  print "$_\n" for @show[0..($#show>24?24:$#show)];
}
