# Compare layout tables: old (arg 0) vs new (arg 1), the new lines' duty stripped. Prints changed shapes and pipe shifts.
my %old; my @order;
open my $o, '<', $ARGV[0]; while (<$o>) { s/\r?\n//; next unless /\S/; my ($shape, @rest) = split / \| /; $old{$shape} = [@rest]; push @order, $shape; }
open my $n, '<', $ARGV[1]; my %new; my @norder;
while (<$n>) { s/\r?\n//; next unless /\S/; my ($shape, @rest) = split / \| /; $shape =~ s/, duty=MachineDuty\[[^\]]*\]//; $new{$shape} = [@rest]; push @norder, $shape; }
sub pipes { my ($s) = @_; $s =~ s/^pipes ?//; return [map { [split /,/] } split / /, $s]; }
for my $s (@order) {
  my ($k) = $s =~ /kind=(\w+), diameter=([\d.]+)/; my $d = $2; my ($sup) = $s =~ /support=(\d+)/;
  if (!exists $new{$s}) { print "GONE  $k $d s$sup | @{$old{$s}}[0] | @{$old{$s}}[3]\n"; next; }
  my ($a, $b) = ($old{$s}, $new{$s});
  next if join('|', @$a) eq join('|', @$b);
  my ($pa, $pb) = (pipes($a->[3]), pipes($b->[3]));
  my $shift = '';
  if (@$pa == @$pb) { my %d; for my $i (0..$#$pa) { $d{join ',', map { $pb->[$i][$_] - $pa->[$i][$_] } 0..2}++; } $shift = (keys %d == 1 ? "all pipes shift " . (keys %d)[0] : "pipes apart: " . join(' ', map { my $i = $_; join(',', map { $pb->[$i][$_] - $pa->[$i][$_] } 0..2) } 0..$#$pa)); }
  else { $shift = "pipe count " . scalar(@$pa) . " -> " . scalar(@$pb); }
  printf "CHG   %s %s s%s | %s -> %s | %s -> %s | %s\n", $k, $d, $sup, $a->[0], $b->[0], $a->[3], $b->[3], $shift;
}
for my $s (@norder) { next if exists $old{$s}; my ($k, $d) = $s =~ /kind=(\w+), diameter=([\d.]+)/; my ($sup) = $s =~ /support=(\d+)/; print "NEW   $k $d s$sup | @{$new{$s}}[0] | @{$new{$s}}[3]\n"; }
