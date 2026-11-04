# W7 screens and controls: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9` at `c2704213a`. Binds: plan 9; D22, D26 to D30; A14, A40, A52, A67, A68, A76; the `gui-creation` skill. W7 turns W3's and W5's status lines and `/icheme shaft` commands into screens. Q1 (section 2) is the owner's pick on rendered sheets. Readings A85 to A100 (section 8) wait on review. Nothing is built before the go.

## 1. What a player can do today

- A motor, generator or gearbox has no fluid registration, so using it opens only its datasheet (`EquipmentShellBlock.openOwn`). The creative motor and shaft blocks open nothing.
- A pump or compressor casing and the steam turbine open the fluid device page. A casing's page shows its set flow and limit. Its train's state is one status line, `SHAFT: status, rpm, %` (A43).
- Start, stop, speed, regulate, ratio, power limit and reset exist only as `/icheme shaft ...` (permission 2). They already queue engine events.
- W5's power reads only on status lines: `NO POWER`, `UNMETERED`, `POWER: kW, FE/t, store %` (A76).

## 2. Q1 (owner pick): where a train's controls live

Rendered as three option sheets (`tools/heat-gui-renders/GuiPrototypes.java`, `machine-a`, `machine-b`, `machine-c`), each with the same 30 kW motor driving the W3 case-1 pump.

- **A (recommended): each machine has its own page.** Using a motor, turbine, generator, gearbox or creative motor opens that machine's page, with its door and its readings. The casing's page gains a **Drive** group: the driver in one line (`MOTOR 30 kW 2-pole · RUNNING · 2950 rpm`) and an Open driver push button.
  - This matches the plant, where each machine has its own local panel.
  - A motor with no casing still has a screen.
- **B: one Train tab on the casing's page.** The glass draws the train (driver, gearbox, casing) and the door holds the selected member's controls. Using a motor opens its casing's page on the Train tab.
  - Everything is in one place, but a lone motor or generator needs a fallback page, which is option A's page anyway.
- **C: a train screen of its own,** like the assembly screen. The train is drawn on the glass with its members as boxes; a click selects one and the door shows its controls. Any member opens it.
  - This is the most to build: a new screen, a drawing with hit tests (`AssemblyBlock`'s pattern), and its own scale and fit.

## 3. The pages (as plan 9 gives them, with W5's readings)

Rows with widgets first, plant readings after them; status said once, on the message display.

- **Motor.** Off and On. Drive: Fixed or Variable, latched. A variable drive adds Speed (%), or Regulate: Flow or Discharge pressure, with its set point as an LCD entry.
  - Readings: speed, shaft power, electric power, FE/t, store (% and the time it lasts at the current draw).
  - Status: `RUNNING`, `STOPPED`, `CURRENT LIMITED`, `TRIPPED: OVERLOAD`, `NO POWER`, `UNMETERED`.
  - Reset: a push button shown only while tripped.
- **Steam turbine** (its fluid device page, a new Drive tab). Trip and Reset. Speed set (rpm), or Regulate, as the motor.
  - Governor: shows the bound speed sensor and valve (`GOVERNED by FV-101`), or `UNGOVERNED`.
  - Readings: steam flow, inlet and exhaust pressure, power, speed. Status: `RUNNING`, `STEAM LIMITED`, `TRIPPED`, `UNGOVERNED`.
- **Generator.** Off and On. Output limit (kW).
  - Readings: speed against its window, electric output, FE/t out, store.
  - Status: `RUNNING`, `OFF SPEED`, `STORE FULL`, `STOPPED`.
- **Gearbox.** Ratio as an LCD entry, with 0 = auto shown as `AUTO 4.27`. Readings: both speeds, power, loss.
- **Creative motor.** Off and On. Speed (rpm), Power limit (kW), Regulate as the motor. Readings: speed, power.
- **Pump and compressor casing.** The set flow (or discharge pressure) stays where it is. The Drive group of Q1 is added.
  - With no driver: `NO DRIVER`, standing closed.
  - On a fixed-speed train: "flow follows the line" in the set flow's hover.
  - Readings: flow, head (or ratio), speed, power, efficiency.
  - The pump curve on the glass with the operating point (plan 9) is in the plan but not in this build (A93).
- **Air cooler and induced tower** (their Control page). A Power group: electric draw, FE/t, store, and `NO POWER` or `UNMETERED` on the display (A68 moves off the status line).

## 4. Data to the screen (AGENTS.md: engine schedule, send on request)

- **A machine view payload** (`MachineView`). It carries one member's view: W3's `ShaftTrains.View` and its W5 store, the train's other members in one line each, the governor binding, and the casing's readings.
  - It is sent only to a client with that machine's screen open. Opening the screen replays the last published view. Updates follow the engine's 100-tick publication (W3's driver publication; A85).
  - Before the first publication the screen says it waits.
- **Heat and tower Power group.** W5's draw and store join `FluidView.Heat`, the record the device page already subscribes to (A86). This is a wire change, with no compatibility work.

## 5. Inputs (the queued route, `server-actions.md`)

- A `MachineEdit` payload with the unit id and new settings, or Reset. It is checked at receipt (the menu open on that block, `stillValid`, may build), then queued as the engine event `/icheme shaft` already queues (A87).
- The answer arrives with the next publication. The screen shows `waiting` until it does, and a refused edit's reason goes on the message display.
- The commands stay, for operators (A88).

## 6. Menus and opening

- A `MachineMenu` on the structure's origin cell (or the creative motor block), opened by `openOwn` for a shape with a shaft unit (A89). The datasheet stays a push button on the page, as a column's does.
- The turbine and casings keep the fluid device menu (the Drive tab and group, Q1).

## 7. Build order and gates

1. The builder `client/gui/common/MachineColumn` (Minecraft-free): one input record, `rows(in, t)` per kind and mode, `cases(t)`.
   - Gates `MachineColumnTest`, copied from `DeviceColumnTest`: English lines, every width 300 to 790 in steps of 7, `assertInLine`, widgets keep their slots while readings change, one widget per key.
2. Prototypes from the builder (each kind, both themes, opened and scrolled to the end); the owner looks.
3. `MachineView` and `MachineEdit` payloads, codec tests (`FluidPacketCodecTest`); `MachineMenu`; `openOwn`.
4. `MachineScreen`: `WindowScale`, `KitDoor`, signature-driven rebuilds, the device page's frame (`FluidGuiLayout`).
5. The casing's Drive group and the turbine's Drive tab (`DeviceColumn`, `FluidDeviceScreen`); `FluidView.Heat` power; the heat Control page's Power group (`HeatControlColumn`).
6. en_us lines only (AGENTS.md, Translations).
7. Tests: `MachineColumnTest`, `DeviceColumnTest`, `HeatControlColumnTest`, `FluidGuiConsistencyTest`, the codec tests, and one GameTest that opens a motor's menu and applies a speed edit through the queued route.

- No dev-client check unless the owner asks for one (skill rule 5).

## 8. Readings (recommended answer: reason)

- **A85.** The machine view rides the 100-tick driver publication, only to open screens. That publication is already on the engine schedule.
- **A86.** The power reading joins `FluidView.Heat`, not a second payload. The device page has one subscription.
- **A87.** Edits reuse the engine event that `/icheme shaft` queues. It is one path, already tested.
- **A88.** The commands stay, for operators and tests.
- **A89.** `MachineMenu` opens on the origin cell; the datasheet becomes a push button on the page. One use opens the controls, as on a column.
- **A90.** Speed entries in % for a motor on a variable drive and in rpm for a turbine and the creative motor, as plan 9 words them.
- **A91.** Power entries in kW; FE/t shown beside electric power. Players compare FE/t with other mods.
- **A92.** The store as a % and the time it lasts at the current draw (`3 min 20 s`). That time is what a player acts on.
- **A93.** The pump curve on the glass is left for W10d, with the two-phase readings that draw on it. It is a drawing with its own gates, and W10d adds the NPSH and gas lines to the same glass.
- **A94.** Reset shows only while tripped. No disabled keys left on the door.
- **A95.** The governor line names the valve by its device name (main 0.20.0's names), or its position when it has none.
- **A96.** Status words stay English (AGENTS.md); the new labels are en_us lines.
- **A97.** The page's design height is the device page's (480, within 338 px); the motor's tallest case is checked against it.
- **A98.** A creative motor's page carries a `CREATIVE` plate in its title strip.
- **A99.** The gearbox page is read-only except Ratio. It has no switch: a gearbox does not run on its own.
- **A100.** Under option A, the casing's Open driver button opens the driver's page directly. Using the driver itself does too.
