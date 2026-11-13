# P1: field guide, help key and tours, card view

Built 2026-11-12 on `claude/tutorial-system-chem-mod-950ca9` (plan sections 3.2, 3.3, 4, 7). Commits a78faaed0 (A),
4ae9e8371 (B), 41e1e81ae (C and D: they share `GuideScreen`), 31b5ba02a (E), and this report.

## Built
- `science/tutorial/guide/` (no Minecraft type): `Guide` (chapters, steps, `Media`, `Check`, derived keys), `GuideFile`
  (`assets/icheme/tutorial/guide.json`; refuses unknown fields, duplicate ids, unknown media), `Progress`, `Glossary`,
  `Marked` (`{id:words}`), `Tour`, `TourFile` (`tours.json`). `science/tutorial/cards/`: `Card`, `Cards`, `ThreeParts`.
- Server: `runtime/tutorial/TutorialTriggers` (`icheme:guide_step` with `step`; `icheme:equipment_built` with optional
  `kind`, fired once in `EquipmentNetwork.build` after a placed structure); `network/TutorialNetwork` (`tutorial-1`:
  `StepDone`, `Hello`, `Progress`; sends again on `AdvancementEarnEvent` under `icheme:tutorial/`). Data: root (tick,
  reward loot `icheme:tutorial/field_guide`), five chapter 0 steps in a chain, `still/build` (`BATCH_STILL`). Item
  `icheme:field_guide` (`FieldGuideItem`, main tab): use opens the guide; texture a PLACEHOLDER (`FieldGuideIcon.java`).
- `client/gui/tutorial/`: `GuidePage` (builder: door chapters, glass steps, marks, underlined terms, keys, open card),
  `GuideScreen`, `GuideClient`, `GuideHud` (layer `icheme:guide_hud`), `CardView`, `TourLayout` (builder), `HelpTour`.
  Key `key.icheme.guide` (H). `/icheme-film` kept.
- Existing, small edits: `Skin.Icon.HELP`; `TitleStrip.keys/keysWidth/placeKeys`; `FluidGuiLayout.titleKeys`;
  `FluidDeviceScreen` (the `?` key on the still's Overview only, tour hosting, no signature change); `FilmScreen`
  (parent, `onFinished` once); `ClientConfig.guide_hud`; `IChemE`, `IChemEClient`, `ModItems`, `ModCreativeTabs`.
  `StillInsideFilmTest`'s source check lets `client.gui.common.kit` through (the cards draw on the kit canvas; the class
  loader check, now also over the guide classes, stands).

## Decisions
1. Step ids across the guide are `<chapter>/<step>` (the advancement path), sent as `StepDone` and `guide_step.step`.
2. Terms are declared in `guide.json` with their defining step; the gate checks that it is the first marked use.
3. `Card.draw` takes a translator besides the brief's arguments: the legend's words are lang keys.
4. `GuidePage.Page.draw` draws the card and slider too, so the render tool shows what the game shows; `CardView` keeps
   only phase, control and drag. Tour stops anchor `reading:<meter key>` for meters, whose slots are indexed.
5. Step keys stand beside the sentence where 100 px of text remain, under it otherwise (the 320 by 240 glass).
6. No stop waits in P1; "Show me" opens the film with the device screen as parent. The key is read on `InputEvent.Key`.

## Tests (filtered, all pass)
New: GuideFileTest 7, ProgressTest 3, MarkedTest 2, TutorialTextTest 3, GuideCoverageTest 4, TutorialNetworkCodecTest
2, GuidePageTest 5, HelpCoverageTest 5, TourLayoutTest 2, CardViewTest 3; TitleStripTest 3 (one added). Reached: SceneTest
8, StillInsideFilmTest 4, KitLayoutTest 17, FluidGuiConsistencyTest 6, DeviceColumnTest 10, StillDoorTest 4,
AssemblyColumnTest 8, DrawCallTest 6, HeatDiagramTest 5. Not run: LanguageFilesTest, UnitTextTest (rule). The fluid
GameTests that load `EquipmentNetwork` wait for the final lane.

## Renders (`renderTutorial`, new `GuideRender.java`, game font, into `tmp/tutorial-renders/`)
`guide-new.png`, `guide-step-3-current.png`, `guide-chapter-done.png` (light, dark, 464 by 254); `guide-small.png`
(320 by 240 window); `tour-guide.png` (3 stops at both sizes); `tour-fluid_device-still.png` (the page and 7 stops at
464 by 254); `card-three_parts.png` (heat 0, 0.5, 1; dark small). Every bubble stands clear of its control.

## In-game check (owner)
Field Guide given once on a new world and not again; H opens the guide, Escape closes; a CLIENT step's mark fills at
once and stays after relog (advancements); `still/build` earned on building a batch still; the HUD line hides under a
screen and with `guide_hud` off; the units choice changes the screens; the film returns to the guide; the `?` tour on
the still scrolls the door, keeps its stop when the page rebuilds, and "Show me" returns to the still's page; the
advancement tab's look; the game font's wrap in the bubbles.
