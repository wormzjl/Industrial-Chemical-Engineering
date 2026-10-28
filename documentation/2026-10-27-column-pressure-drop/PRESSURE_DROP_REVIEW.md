# Pressure-drop publication review

Expected behaviour: when a positive column diameter requests tray hydraulics, the marched tray pressure profile is
the profile used by the accepted result and the GUI's per-tray dP and column dP agree with the hydraulic summary.

Observed behaviour: the aggressive interactive route skipped its hydraulic re-solve. With the shipped zero nominal
drop, it retained a flat pressure profile while reporting a positive marched hydraulic summary, so the GUI showed
`Column dP: 0.0 kPa` and no bars.

Fix: use the existing bounded hydraulic correction on the aggressive route as well. This keeps the accepted problem,
inspection nodes and hydraulic summary on one pressure profile. The correction remains advisory on refusal or budget
expiry and does not turn equipment flooding into a solver failure.

Verification: `./gradlew slowGates --tests com.wormzjl.icheme.science.column.v3.V3TrayHydraulicsColumnTest --no-daemon`
passed (`BUILD SUCCESSFUL`, 18 s). Full `test slowGates` remains an open item because the baseline already has the
static-initialization cascade and one independent V3 assertion failure.
