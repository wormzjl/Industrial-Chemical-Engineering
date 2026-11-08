# Old turbines (before.txt: STEAM_TURBINE and CONDENSING_TURBINE lines with role=x,y,z pipes) against the new steam
# turbine (after.txt) at the same size and support: per back-pressure turbine whether its exhaust keeps its place beside
# its steam inlet, per condensing turbine how far its steam inlet's pipe block shifts in the box.
# Usage: perl compare-turbines.pl turbines-before.txt turbines-after.txt
my %new;
open my $n, '<', $ARGV[1] or die;
while (<$n>) {
    s/\r?\n//;
    my ($k, $p) = split / \| /;
    my ($kind, $d, $l, $h) = split / /, $k;
    $new{"$d $h"} = {map { my ($r, $v) = split /=/; ($r, [split /,/, $v]) } split / /, $p};
}
open my $o, '<', $ARGV[0] or die;
my %c;
while (<$o>) {
    s/\r?\n//;
    my ($k, $p) = split / \| /;
    my ($kind, $d, $l, $h) = split / /, $k;
    my %old = map { my ($r, $v) = split /=/; ($r, [split /,/, $v]) } split / /, $p;
    my $nw = $new{"$d $h"} or do { print "NO NEW $d $h\n"; next };
    my @t = map { $nw->{STEAM}[$_] - $old{STEAM}[$_] } 0 .. 2;
    if ($kind eq 'STEAM_TURBINE') {
        my $s = join ',', map { $nw->{EXHAUST}[$_] - $old{EXHAUST}[$_] - $t[$_] } 0 .. 2;
        $c{'back pressure'}{$s eq '0,0,0' ? 'kept' : "exhaust moves $s"}++;
    } else {
        $c{condensing}{'steam shifts ' . join(',', @t)}++;
    }
}
for my $g (sort keys %c) { print "$g: ", join('; ', map { "$_ x$c{$g}{$_}" } sort keys %{$c{$g}}), "\n"; }
