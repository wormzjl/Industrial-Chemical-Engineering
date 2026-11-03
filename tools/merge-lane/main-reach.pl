#!/usr/bin/perl
# Lists the classes of src/main that the game cannot reach: walks class names from the mod's entry points
# (@Mod, @EventBusSubscriber, @Mixin), with comments and strings stripped.
# Usage: perl tools/merge-lane/main-reach.pl <repo root> [class to cut]...
# A cut class counts as gone. Use it on a suspect link: a class that one production line keeps alive shows up only then.
# It works by name, so a reference to a nested type counts, and it does not look at single methods.
use strict; use warnings; use File::Find;
my ($R, @cut) = @ARGV;
die "usage: main-reach.pl <repo root> [class to cut]...\n" unless defined $R && -d "$R/src/main/java";
my (%code, %raw, %other, %path);
sub strip { my $t = shift; $t =~ s{/\*.*?\*/}{ }gs; $t =~ s{//[^\n]*}{ }g; $t =~ s{"(?:[^"\\\n]|\\.)*"}{""}g; return $t; }
find(sub {
    return unless /\.java$/;
    my $n = $_; $n =~ s/\.java$//;
    open(my $i, '<:raw', $_) or die "$_: $!"; local $/; my $t = <$i>; close $i;
    $raw{$n} = $t; $code{$n} = strip($t);
    ($path{$n} = $File::Find::name) =~ s#.*/src/main/java/##;
}, "$R/src/main/java");
for my $root ("$R/src/test/java", "$R/src/fluidGameTest/java", "$R/src/mcpCompat/java") {
    next unless -d $root;
    find(sub {
        return unless /\.java$/;
        open(my $i, '<:raw', $_) or die "$_: $!"; local $/; $other{$File::Find::name} = strip(<$i>); close $i;
    }, $root);
}
my %refs;
for my $n (keys %code) {
    my %s;
    while ($code{$n} =~ /\b([A-Z][A-Za-z0-9_]+)\b/g) { $s{$1} = 1 if exists $code{$1} && $1 ne $n; }
    $refs{$n} = [keys %s];
}
my %x = map { $_ => 1 } @cut;
for my $n (keys %refs) { $refs{$n} = [grep { !$x{$_} } @{$refs{$n}}]; }
my @roots = grep { $raw{$_} =~ /\@Mod\b|\@EventBusSubscriber|\@Mixin\b/ && !$x{$_} } keys %raw;
my %seen; my @q = @roots;
while (@q) { my $c = shift @q; next if $seen{$c}++; push @q, @{$refs{$c}}; }
my ($count, $lines) = (0, 0); my @out;
for my $n (sort { $path{$a} cmp $path{$b} } keys %code) {
    next if $seen{$n};
    my $l = () = $raw{$n} =~ /\n/g;
    my $t = grep { /\b\Q$n\E\b/ } values %other;
    push @out, sprintf("%-6d test-files=%-3d %s", $l, $t, $path{$n});
    $count++; $lines += $l;
}
printf "roots=%d classes=%d reachable=%d unreachable=%d lines=%d\n", scalar(@roots), scalar(keys %code), scalar(keys %seen), $count, $lines;
print "$_\n" for @out;
