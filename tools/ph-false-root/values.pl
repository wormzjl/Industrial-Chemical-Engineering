# Two printed.txt files with clocks, durations, object ids and heap sizes removed: the lines whose values still differ.
# perl values.pl <base printed.txt> <head printed.txt>
use strict;
sub norm{my $s=shift;
  $s=~s/\[\d\d:\d\d:\d\d\]//g;                    # clock stamps
  $s=~s/\d\d:\d\d:\d\d(?:[.,]\d+)?//g;            # clock times
  $s=~s/\d{4}-\d\d-\d\d//g;                       # dates
  $s=~s/@[0-9a-f]{5,8}\b//g;                      # object ids
  $s=~s/\b(?:ms|wallMs|nanos|bytes|allocatedMB|seconds|heapMb|heapMB)=[-0-9.eE]+//g;  # named times and heap
  $s=~s/[-0-9.eE]+\s*(?:ms|s|MB|ns|us)\b//g;      # durations and sizes with a unit
  $s=~s/\s+/ /g;$s}
sub rd{my $p=shift;open(F,'<',$p) or die $p;my @l=<F>;close F;s/\r?\n$// for @l;\@l}
my ($x,$y)=(rd($ARGV[0]),rd($ARGV[1]));
die "line counts differ\n" if @$x!=@$y;
my $n=0;my %t;
for my $i (0..$#$x){my($p,$q)=(norm($x->[$i]),norm($y->[$i]));next if $p eq $q;$n++;my($c)=$x->[$i]=~/^(\S+)/;$t{$c}++;
  if($t{$c}<=2){my $k=0;$k++ while $k<length($p)&&substr($p,$k,1) eq substr($q,$k,1);my $from=$k>50?$k-50:0;
    print "$c line ".($i+1).":\n  < ".substr($p,$from,170)."\n  > ".substr($q,$from,170)."\n"}}
print "lines ".scalar(@$x)." differing after normalising: $n\n";
print "  $_ $t{$_}\n" for sort keys %t;
