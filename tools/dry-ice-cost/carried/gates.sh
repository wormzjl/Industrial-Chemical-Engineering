#!/bin/bash
# Scratch: the tests the change can load, each named. gates.sh <extra gradle args...>
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/distillation-column-integration-fef62f"
A=""; for c in ${TESTS:-CarriedCrystalsTest NetworkPhaseEngineTest CrystalDepositionIslandTest PrecipitatedSolidsTest SolidClosureFeasibilityTest CrystalFlushBudgetTest CrystalIslandRuntimeTest G3F7CarbonDioxideInLiquidMethaneTest GasSolidEquilibriumTest DataRangeContinuationTest TangentPlaneStabilityTest FluidTpEquilibriumTest FluidTpEquilibriumPhUvTest LiquidCo2LetDownTest WaterIntoCryogenicMethaneTest PumpCavitationTest SpineNetworkPathTest NearPureCrystalStepTest}; do A="$A --tests *.$c"; done
./gradlew test $A --continue "$@" > tmp/dry-ice-symptoms/run/carried/gates.log 2>&1
echo "gradle exit $?" >> tmp/dry-ice-symptoms/run/carried/gates.log
