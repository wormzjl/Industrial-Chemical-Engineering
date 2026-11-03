# Scratch (tmp/dry-ice-symptoms/run, measurement B): one line per probe run from out/<name>-jobs.tsv: jobs, holds, wall and
# CPU seconds, the heaviest job's wall and CPU, checkpoints (total and heaviest job), Newton iterations and step attempts.
# perl summaryB.pl out/r-base-673-jobs.tsv out/r-proto-673-jobs.tsv ...
use strict; use warnings;
printf "%-22s %5s %5s %8s %8s %8s %8s %12s %10s %10s %8s\n", 'run', 'jobs', 'holds', 'wall s', 'cpu s', 'maxWall', 'maxCpu', 'checkpoints', 'maxCkpt', 'newtonIt', 'attempts';
for my $file (@ARGV) {
    open my $in, '<', $file or die "$file: $!";
    my $head = <$in>; $head =~ s/\r?\n//; my @h = split /\t/, $head;
    my %col; $col{$h[$_]} = $_ for 0 .. $#h;
    my ($n, $holds, $w, $c, $mw, $mc, $ck, $mck, $it, $at) = (0) x 10;
    while (<$in>) {
        s/\r?\n//; my @f = split /\t/;
        $n++; $holds++ if $f[$col{status}] =~ /HELD/;
        $w += $f[$col{wallMs}]; $c += $f[$col{cpuMs}];
        $mw = $f[$col{wallMs}] if $f[$col{wallMs}] > $mw; $mc = $f[$col{cpuMs}] if $f[$col{cpuMs}] > $mc;
        if (defined $col{checkpoints}) { $ck += $f[$col{checkpoints}]; $mck = $f[$col{checkpoints}] if $f[$col{checkpoints}] > $mck; }
        $it += $f[$col{newtonIterations}]; $at += $f[$col{stepAttempts}];
    }
    my ($name) = $file =~ m{([^/\\]+)-jobs\.tsv$};
    printf "%-22s %5d %5d %8.1f %8.1f %8.0f %8.0f %12s %10s %10d %8d\n", $name, $n, $holds, $w / 1e3, $c / 1e3, $mw, $mc,
        defined $col{checkpoints} ? $ck : '-', defined $col{checkpoints} ? $mck : '-', $it, $at;
}
