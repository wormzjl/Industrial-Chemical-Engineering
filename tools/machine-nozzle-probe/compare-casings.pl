my (%a, %b);
sub load { my ($f, $h) = @_; open my $x, '<', $f; while (<$x>) { s/\r?\n//; my ($k, $p) = split / \| ?/; $h->{$k} = [map { [split /,/] } split / /, $p // '']; } }
load($ARGV[0], \%a); load($ARGV[1], \%b);
my (%moved, %gone, %same, %shift);
for my $k (sort keys %a) {
  my ($kind, $d, $len, $h) = split / /, $k;
  if (!exists $b{$k}) { push @{$gone{"$kind $d $len"}}, $h; next; }
  my ($p, $q) = ($a{$k}, $b{$k}); my %t;
  for my $i (0..$#$p) { $t{join ',', map { $q->[$i][$_] - $p->[$i][$_] } 0..2}++; }
  if (keys %t == 1) { my ($t) = keys %t; if ($t eq '0,0,0') { $same{$kind}++ } else { $shift{$kind}++ } }
  else { push @{$moved{$kind}}, "$d x$len h$h: " . join(' ', map { my $i = $_; join(',', map { $q->[$i][$_] - $p->[$i][$_] } 0..2) } 0..$#$p); }
}
for my $kind (sort keys %same) { print "$kind: same $same{$kind}, shifted together $shift{$kind}\n"; }
for my $kind (sort keys %moved) { print "$kind MOVED (" . scalar(@{$moved{$kind}}) . "):\n"; print "  $_\n" for @{$moved{$kind}}[0..($#{$moved{$kind}} < 40 ? $#{$moved{$kind}} : 40)]; }
for my $k (sort keys %gone) { print "GONE $k at supports @{$gone{$k}}\n"; }
my %new; for my $k (keys %b) { push @{$new{join ' ', (split / /, $k)[0..2]}}, (split / /, $k)[3] unless exists $a{$k}; }
for my $k (sort keys %new) { print "NEWLY VALID $k at supports @{[sort { $a <=> $b } @{$new{$k}}]}\n"; }
