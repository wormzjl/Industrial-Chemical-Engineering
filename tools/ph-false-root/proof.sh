#!/bin/sh
# Each gate with its rule disabled: one test, one run. The source file is restored from git after each.
F=src/main/java/com/wormzjl/icheme/science/thermo/phase/FluidTpEquilibrium.java
OUT=tmp/ph-false-root/proof.log
: > "$OUT"
one() {
  name=$1; test=$2
  ./gradlew test --tests "*NetworkPhaseEngineTest.$test" > tmp/ph-false-root/proof-$name.log 2>&1
  code=$?
  echo "== $name $test exit $code" >> "$OUT"
  grep -E "AssertionFailedError|IllegalState|BUILD|error:" tmp/ph-false-root/proof-$name.log | cut -c1-200 >> "$OUT"
  xml=build/test-results/test/TEST-com.wormzjl.icheme.science.fluid.thermo.NetworkPhaseEngineTest.xml
  grep -E '<failure message' "$xml" | cut -c1-300 >> "$OUT"
}
git diff --quiet -- "$F" || { echo "source not clean" >> "$OUT"; exit 1; }
one clean aHeavyOilBesideSteamStaysLiquid
one clean2 aPureHydrocarbonBoilsWithItsWaterAtOneTemperature

perl -pi -e 's/^        if \(!root\) \{/        if (Math.abs(pc + (gasVolume > 0.0 ? SteamVirial.partialPressure(water, t, gasVolume) : p) - p) > PARTIAL_PRESSURE_ACCEPTANCE * p) {/' "$F"
git diff --stat -- "$F" | tail -1 >> "$OUT"
one no-bracket-decision aHeavyOilBesideSteamStaysLiquid
git checkout -- "$F"

perl -pi -e 's/double most = Math.min\(1.0, water \/ \(n \* steamPerMole\)\);/double most = 1.0;/' "$F"
git diff --stat -- "$F" | tail -1 >> "$OUT"
one no-water-end aPureHydrocarbonDistilsWithItsSteam
git checkout -- "$F"

perl -pi -e 's/: w.wet \? wetCoexistence\(Specification.PH, s.lower, p, target, closed, competition, w\)/: w.wet ? null/; s/if \(answer == null && w.presentCount == 1\) \{/if (answer == null \&\& w.presentCount == -1) {/' "$F"
git diff --stat -- "$F" | tail -1 >> "$OUT"
one no-ph-coexistence aPureHydrocarbonBoilsWithItsWaterAtOneTemperature
git checkout -- "$F"

git diff --quiet -- "$F" && echo "restored" >> "$OUT"
