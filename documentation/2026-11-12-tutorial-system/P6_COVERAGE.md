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

- `PageFilms` (beside `EquipmentFilms`, which stays at 446 lines): `page_<kind>` for 29 kinds on their reference shapes,
  one stop each: the outside, every nozzle named by its role (36 new `tutorial.icheme.film.part.*` words, the unit films'
  where they fit). Nothing new modelled; `ShapeRasterizer` and the models untouched.
- No kind has an inside stop (correction 2026-11-13, the owner's rule: the cut face seen, a quarter of the drawn body
  taken and inside geometry uncovered; every dot on the body; every leader on a drawn nozzle). The first build gave 14
  kinds a stop 2; the gate (`PageFilmsTest.anInsideStopKeepsItsDotsOnTheBodyAndItsLeadersOnDrawnNozzles`) failed all
  14 (1,121 of 2,652 dots and leaders off: the dots began on the pipe outside the body, and the cut through the middle
  took a named nozzle away in 13). One shared rule tried (the cut in front of both named nozzles, the dots from face to
  face) passed the leaders everywhere but kept the dots on the body only for the cabin heater and the tall cooling
  tower, and their sheets failed (1): the heater's cut uncovers nothing, its dots run down its outer wall; the tower is
  not seen cut, its dots hide at its foot. The others: kettle, stirred reactor, trickle-bed and ebullated-bed reactors,
  cyclone, cracking converter, coker, both boilers, both gasifiers and the fan cooling tower have empty shells or a cut
  that misses the process. Their stop-2 sentences left `en_us.json`; the cut and dots code left `PageFilms`.
- `pages.json` names each film; `Films.build` builds it only when Watch is pressed. Picture only (gate
  `PageFilmsTest.PICTURE_ONLY`): the motor, the generator, the gearbox (no nozzle, no inside) and the control valve.
- Gate `PageFilmsTest` (3): every page has a film or is picture only; every nozzle named by its role in one stop, no cut
  and no dots; `FilmChecks` (one sentence, English lines, labels placed at both sizes); the same frame twice; the body
  within the glass and filling 60 % of a side; every dot of any stop on the body and every leader on a drawn, uncut
  nozzle at both glass sizes (29 films, 236 checked, 0 off).
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
