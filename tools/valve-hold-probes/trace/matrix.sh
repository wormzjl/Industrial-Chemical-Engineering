#!/bin/sh
# Runs the valve-hold gate in the scratch tree with each rule on and off.
cd "$1" || exit 1
L=src/main/java/com/wormzjl/icheme/science/fluid/solver/PhaseLayout.java
N=src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveNetwork.java
R=tmp/trace/gate-matrix.txt
: > "$R"
run() {
    ./gradlew test --offline --tests '*RegulatingValveHoldTest*' > tmp/trace/gate-$1.log 2>&1
    echo "== $1 exit=$?" >> "$R"
    grep -h -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*RegulatingValveHoldTest*.xml | sed 's/.*tests=/tests=/' >> "$R"
    grep -h -A1 '<testcase' build/test-results/test/*RegulatingValveHoldTest*.xml | grep -B1 '<failure' | grep -o 'testcase name="[^"]*"\|failure message="[^"]\{0,120\}' >> "$R"
}
rm -f tmp/trace/singular.txt
run both
perl -i -pe 's/^(\s*)if\(junctionBasis\.length>1\)\{int bulk/$1if(junctionBasis.length<0){int bulk/' "$L"
run no-bulk
perl -i -pe 's/^(\s*)if\(junctionBasis\.length<0\)\{int bulk/$1if(junctionBasis.length>1){int bulk/' "$L"
cp tmp/trace/singular.txt tmp/trace/singular-no-bulk.txt 2>/dev/null
perl -i -pe 's/Identity line\(\)\{return control instanceof/Identity line(){if(true)return this;return control instanceof/' "$N"
run no-line
perl -i -pe 's/Identity line\(\)\{if\(true\)return this;return control instanceof/Identity line(){return control instanceof/' "$N"
cat "$R"
