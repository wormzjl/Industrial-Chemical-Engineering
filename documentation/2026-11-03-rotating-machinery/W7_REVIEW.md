# W7: machine pages, review

Commit `75c3fca13` (`W7_BUILD_PLAN.md`, owner decision D31: Q1 option A, own pages). Built by an Opus medium subagent and reviewed here. Sheets: `tmp/gui-prototypes/machine-*.png` (render with `-Donly=machine`).

## 1. What was built

- **Pages.** `client/gui/common/MachineColumn` (Minecraft-free) builds every machine kind's rows. `MachineScreen` draws them on the device page frame: title strip with the device name, message display, door, a nameplate on the glass, and Close, Datasheet, Apply.
  - **Motor:** Off/On, Drive (Fixed/Variable, a design fact shown latched; A101), Regulate (Speed, Flow, Pressure) with Speed in %; Shaft and Supply meters (electric power, FE/t, store %, how long it lasts).
  - **Steam turbine:** Trip/Run, Reset only while tripped; Regulate with speed in rpm; Governor (`GOVERNED by FV-101`, or `UNGOVERNED` on the display); steam flow, inlet and exhaust pressure.
  - **Generator** (its Off and output limit now act, A103), **gearbox** (Ratio only, A99) and **creative motor** (no name entry and no datasheet, since it is not a structure; A104).
- **Opening.** `MachineMenu` opens from a shaft unit's structure (`EquipmentShellBlock.openOwn`) and from the creative motor block.
- **Data.** `MachineView` rides W3's 100-tick driver publication, sent only to open pages and replayed on open (A85).
- **Edits.** `MachineEdit` is checked at receipt and queued as the `/icheme shaft` engine event. The answer and any refusal come with the next publication (A87).
- **Registrars.** New `machine-1`. Fluid goes to `fluid-18`, because `FluidView` carries Drive and Power.
- **Other pages.**
  - The pump and compressor casing gain a Drive group: the driver in two lines, Open driver (A100), and `NO DRIVER` on a standalone casing; head, speed, power and efficiency readings.
  - The heat Control page gains a Power group; `NO POWER` and `UNMETERED` move to the display (A68, A107).
- **Tool.** Option sheets B and C are deleted from `GuiPrototypes`, which now draws option A from the builder; a History line records the pick.

## 2. Review findings (fixed before the commit)

- The turbine page carried a sentence on its door ("The casing's own page holds the target."), against panel rule 1. It moved to the Flow and Pressure keys' hovers.
- The turbine page lacked plan section 3's steam flow, inlet and exhaust pressure, Trip and Reset. `MachineView` now carries them from the turbine's last booking.

## 3. Tests

- `MachineColumnTest` 6 (English lines, widths, boxes in line, stable widgets), `FluidPacketCodecTest` 21.
- Affected `test` 415 and hold gates 58; `ShaftCouplingTest` 4 again after the turbine fix.
- Fluid GameTests 42, including the new machine-page test: a creative motor's page sends a speed edit, which is not applied at once, and the train reaches 1500 rpm on the engine's publication.
- `LanguageFilesTest` fails until the pre-merge translation commit (AGENTS.md, Translations), as expected.
- No campaign: W7 changes no solver code. The final lane runs it.

## 4. For the owner

- **The turbine has no Drive tab** (D31 said a Drive tab on its fluid page). The turbine's fluid page has the Drive group with Open driver, as a casing has, and its controls are on its own machine page like every other driver.
  - Recommended: keep it. One kind of page per machine, and one path to the controls. The owner kept it (D32).
- **The motor page is 347 px at a 464 px wide door,** 9 px over the 338 px budget, so it scrolls 9 px there. At the design widths (640 and 853, A108) it fits. `MachineColumnTest` checks those widths.
- **The casing page is 476 px in its 398 px view at 1920 × 1080** (A106) and scrolls, as the pump page already did at 2560 × 1440.
- The build's readings A101 to A108 stand as above.
  - A102: the Flow or Pressure target is the casing's own set point.
  - A105: refusals are English server strings.

Code guide: no change yet. The client GUI and runtime chapters change at the merge refresh.
