# Pairs printed lines of two lanes in order, per test, and reports the largest relative number change per test and key.
my ($a,$b)=@ARGV;
sub rd{my $p=shift;open(F,'<',$p) or die $p;my %h;while(<F>){s/\r?\n$//;my($t)=split ' ';push @{$h{$t}},$_}close F;\%h}
my $A=rd($a);my $B=rd($b);my %max;my %cnt;my %struct;
for my $t (keys %$A){
    next unless $B->{$t};
    my @x=@{$A->{$t}};my @y=@{$B->{$t}};
    if(@x!=@y){$struct{$t}=@x."/".@y}
    my $n=@x<@y?@x:@y;
    for my $i (0..$n-1){
        next if $x[$i] eq $y[$i];
        $cnt{$t}++;
        my @p=split /([\s,=\[\]();:|]+)/,$x[$i];my @q=split /([\s,=\[\]();:|]+)/,$y[$i];
        my $key='';
        for my $k (0..$#p){
            last if $k>$#q;
            my($u,$v)=($p[$k],$q[$k]);
            if($u=~/^[-+]?[0-9.]+(?:[eE][-+]?[0-9]+)?$/&&$v=~/^[-+]?[0-9.]+(?:[eE][-+]?[0-9]+)?$/){
                next if $u==$v;
                my $d=abs($u-$v)/(abs($u)+abs($v)+1e-300)*2;
                my $name=$key=~/[A-Za-z]/?$key:'?';
                if(!defined $max{$t}{$name}||$d>$max{$t}{$name}[0]){$max{$t}{$name}=[$d,$u,$v]}
            } elsif($u ne $v && $u=~/[A-Za-z]/&&$u!~/^[-+]?[0-9]/){$max{$t}{"TEXT:$u"}=[1,$u,$v]}
            $key=$u if $u=~/[A-Za-z]/&&$u!~/^[-+]?[0-9.]+[eE]/;
        }
    }
}
for my $t (sort {($cnt{$b}||0)<=>($cnt{$a}||0)} keys %cnt){
    printf "%-34s lines=%d%s\n",$t,$cnt{$t},$struct{$t}?" count=$struct{$t}":"";
    my @k=sort {$max{$t}{$b}[0]<=>$max{$t}{$a}[0]} keys %{$max{$t}};
    for my $k (@k[0..($#k<4?$#k:4)]){my $m=$max{$t}{$k};printf "    %-28s %.2e  %s -> %s\n",substr($k,0,28),$m->[0],substr($m->[1],0,16),substr($m->[2],0,16)}
}
