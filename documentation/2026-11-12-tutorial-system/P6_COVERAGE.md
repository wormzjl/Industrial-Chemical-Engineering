# P6: coverage (help tours on every screen, a film on every equipment page)

Status: built 2026-11-13 on `claude/tutorial-system-chem-mod-950ca9`. Not checked in the game.

## Help tours (part 1, 52d31ab45 and the render commit)

- 32 new tours in `tours.json`, 160 lines: the fluid device screen (one tour a tab, shared by every device; on the
  Overview one a door layout: converter, tower, heat, pump_curve, mover, valve, pipe, vessel, besides the still's two),
  the gauge, the pipeline tools, the datasheet, the machine page, the control panel's six pages and the constructor's
  six (a column's design page has its own). `HelpCoverageTest`: every screen has a tour or stands in `EXEMPT`
  (`FilmScreen` only); `LATER` is gone.
- `TourHost` (client) holds a screen's tour, its `?` key and the forwarding; the guide, assembly and simulator screens
  moved to it. `TourAnchors` (no Minecraft class) finds a slot or a group and scrolls a door to it; a lit row taller
  than its view is lit only where the view shows it (found on the renders: the pipe's readings lit the footer).
- Anchor maps: `DeviceTour`, `ScreenTours`; gates `DeviceTourTest` (3) and `ScreenToursTest` (7) at every width, with
  optional rows present and absent. While a tour is open the screens also take typed characters.
- Decisions: the constructor's title room 122 to 100 px, a short title "Constructor" on its choice page; the control
  panel's tour waits for its page's data; the DCS's column and converter pages light their own first row (`page`).
- Renders: `tools/tutorial-renders/CoverageRender.java` (`renderTutorialGuide -PtutorialGuideOnly=p6`), 32 sheets in
  `tmp/tutorial-renders/p6/tour-*.png`, dark for `fluid_device.vessel` and `control.loop`. Every sheet read; six
  sentences rewritten (constructor size and connections, valve readings, fan speed and solids without a unit, the solids
  page's first stop now says what the page is for).

## Page films (part 2)

- `PageFilms` (beside `EquipmentFilms`, which stays at 446 lines): `page_<kind>` for 29 kinds on their reference shapes.
  Stop 1: the outside, every nozzle named by its role (36 new `tutorial.icheme.film.part.*` words, the unit films'
  where they fit). Stop 2 for 14 kinds whose inside holds the process and that have an inlet and an outlet: the body
  cut through the middle between them (the body's centre where that cut would keep under 35 % of it), dots from inlet to
  outlet. One stop for machines' casings, stacks and the flare (no outlet nozzle), the sphere and the gas converter (no
  inlet), the batch still (the cut takes its pot and fire) and the adsorber (its nozzles stand on headers beside its
  vessels). Nothing new modelled; `ShapeRasterizer` and the models untouched.
- `pages.json` names each film; `Films.build` builds it only when Watch is pressed. Picture only (gate
  `PageFilmsTest.PICTURE_ONLY`): the motor, the generator, the gearbox (no nozzle, no inside) and the control valve.
- Gate `PageFilmsTest` (2): every page has a film or is picture only; every nozzle named at stop 1 by its role; the cut
  and the dots only at an inside stop; `FilmChecks` (one sentence, English lines, labels placed at both sizes); the
  same frame twice; the body within the glass and filling 60 % of a side.
- Renders: the 29 films' sheets and `pages-1..6.png`, `pages-dark.png` in `tmp/tutorial-renders/p6/`, all read.

## Tests (once each, `--tests`, on the final code)

ScreenTours 7, DeviceTour 3, HelpCoverage 6, TutorialText 6, PageFilms 2, UnitFilms 3, GuidePage 6, GuideCoverage 11,
GuideFile 10, DrawCall 8, AssemblyTour 1, SimulatorTour 3, UnitMapCard 1, EquipmentInfoCoverage 6, StillBuildFilm 3,
StillInsideFilm 5, StillRunFilm 4, TowerFilms 2; `compileJava compileTestJava` pass.

## Open

- The owner's review of the tour and film sheets; one in-game look at a tour on the fluid device screen.
- A cut shell with nothing inside reads as whole from some angles (kettle, trickle bed); the dots carry the idea.
- Seen, not changed: a tank with contents and controls places Copy and Apply on the same footer slot
  (`FluidDeviceScreen`, Apply is added last and drawn on top).
- The flare's page picture is a thin line at the glass's height (as before P6).
