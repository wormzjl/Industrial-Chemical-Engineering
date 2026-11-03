# Scratch (tmp/dry-ice-symptoms/run, measurement A): per world, the SHADOW_TALLY lines of out/shadow-<seed>.log summed, and
# the form (b) estimate from them: cost(b) per call = fluid-only + refused fraction x competition.
# perl tallyA.pl out/shadow-673.log out/shadow-183.log ... ; MIN=calls threshold for the per-site lines (default 100).
use strict; use warnings;
my $min = $ENV{MIN} // 100;
for my $file (@ARGV) {
    open my $in, '<', $file or die "$file: $!";
    my ($seed) = $file =~ /shadow-(\d+)/;
    my (%site, $calls, $comp, $fluid, $refused, $b, $gain);
    my @lines;
    while (<$in>) {
        next unless /^\s+(.+?) \| (TP|PH|UV) \| (P[<>]=?[\d.]+bar) : (\d+), ([\d.]+) \(([\d.]+)\), ([\d.]+) \(([\d.]+)\), \{(.*?)\}, \{(.*?)\}/;
        my ($site, $spec, $pr, $n, $cms, $cper, $fms, $fper, $fo, $co) = ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10);
        my ($ll, $three, $other) = (0, 0, 0);
        for (split /, /, $fo) {
            my ($k, $v) = split /=/;
            if ($k =~ /LIQUID_LIQUID/) { $ll += $v } elsif ($k =~ /THREE_PHASES/) { $three += $v } elsif ($k =~ /^refused/) { $other += $v }
        }
        my $ref = $ll + $three + $other; my $f = $ref / $n;
        my $costb = $n * ($fper + $f * $cper);
        $calls += $n; $comp += $cms; $fluid += $fms; $refused += $ref; $b += $costb;
        $site =~ s/PassiveStepSolver\$?//g; $site =~ s/FluidThermodynamics\.//g;
        push @lines, sprintf("  %-46s %-2s %-9s n=%6d  c_c=%6.3f c_f=%6.3f ms  f=%5.1f%% (LL %d, 3P %d, other %d)  break-even f<%4.0f%%  b: %7.1f -> %7.1f ms",
            $site, $spec, $pr, $n, $cper, $fper, 100 * $f, $ll, $three, $other, $cper > $fper ? 100 * (1 - $fper / $cper) : 0, $cms, $costb) if $n >= $min;
    }
    printf "seed %s: %d competing calls, competition %.1f s, fluid-only shadow %.1f s, refused %d (%.1f%%); form b estimate %.1f s (saves %.1f s)\n",
        $seed, $calls, $comp / 1e3, $fluid / 1e3, $refused, 100 * $refused / $calls, $b / 1e3, ($comp - $b) / 1e3;
    print "$_\n" for @lines;
}
