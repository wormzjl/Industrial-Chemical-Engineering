# GUI toolkit review: LDLib2, or similar tools in our own code (2026-10-28)

Owner question: "Evaluate if you can use ldlib to make better GUI, or implement similar tools into the code." Read-only evaluation: the LDLib2 docs and releases, the mod's 7 screens (about 6,100 lines in `client/gui`), and the heat GUI task's defects.

## 1. What hurts in today's GUI (measured in this repository)

- Every row has a hand-set y coordinate (59, 82, 105, 118, 164 on the fluid screen); a new row moves the others by hand. Found that way: W5 the rail buttons covered the readings; W6 the Other side button sat on the set point's row; W4 a label cut at the rail's width.
- A scroll area answers clicks inside its own bounds even when its page is hidden. The heat Control tab needed its own mouse handlers.
- Widgets and their drawn text are placed by separate code; scrolled, they drift. The Control tab needed one walker that places and draws both.
- Strong point to keep: layout is Minecraft-free records (`FluidGuiLayout`, `AssemblyDiagram`, `HeatDiagram`) checked by unit tests and PNG renders, which is how the owner judges GUI work (no client checks).

## 2. LDLib2 (Low-Drag-MC), as published

- A complete rewrite of LDLib (LDLib 1 archived 2025-02-18). NeoForge 1.21.1; LGPL-3.0; 2.2.44 of 2026-10-24, eight releases since 2026-08-31; `-all` jar 7.7 MB; artifacts on a snapshots Maven.
- UI: flexbox layout (the README names Yoga and Taffy), LSS stylesheets, XML definitions, `ScrollerView`, `TabView`, `TextField`, `Selector`, `Toggle`, `ProgressBar`, an in-game UI editor and debugger, a client UI test harness.
- Data: menu-backed UIs use bindings whose default `CHANGED_PERIODIC` checks "once per tick"; RPC events and messages carry `CompoundTag`s. A client-only UI (`ModularUI.of(ui)`) can sit inside our own screen.
- No flowsheet element: our drawings would stay custom-drawn elements.

## 3. Options

- **A. LDLib2 with its menus and bindings.** Rejected by AGENTS.md: values are checked each server tick, a second wire beside fluid-16, and updates no longer on the engine's schedule.
- **B. LDLib2 client-only inside our screens.** Our menu payloads stay; LDLib2 gives the layout, scroll and tab views and styles. Costs:
  - a required 7.7 MB mod for every player (server side not verified);
  - an API that changes fast (2.2.4 in March 2026, 2.2.44 in October);
  - a rewrite of about 6,100 lines over 7 screens;
  - layout computed only at runtime, so no unit-test gates or PNG renders of it;
  - the MCP bridge sees one widget (clicks by coordinate still work).
- **C. A small toolkit in `client/gui/common` (recommended).**
  - An element tree where only visible elements get events (no more stale scroll areas).
  - Row and column layout records, Minecraft-free and tested like `FluidGuiLayout`.
  - A scroll view and tabs that place and draw from one layout pass (today's Control-tab walker, made general).
  - One palette.
  - One flowsheet model for the assembly, heat and column drawings, and the PNG renderer extended to whole screens.
  - Estimate: some hundreds of lines. Move `FluidDeviceScreen` first, then other screens when a task touches them.
- **D. Tools already on the classpath, in addition to C.**
  - Catnip (inside Ponder) has `GuiGameElement` to draw a unit's block model on its screen.
  - Ponder scenes could show how to build an exchanger's W8 lines.

## 4. Recommendation

C, with D where it helps a player; B only if the owner wants the in-game UI editor and stylesheets enough to take a required, fast-moving dependency. Before B: a probe on a dedicated server with Create and LDLib2 together (one Gradle run, no client check unless the owner asks).

Sources: [LDLib2 repository](https://github.com/Low-Drag-MC/LDLib2), [getting started](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib2/ui/getting_start.html), [data bindings](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib2/ui/preliminary/data_bindings.html), [ModularUI](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib2/ui/preliminary/modularui.html), [Modrinth versions](https://modrinth.com/mod/ldlib), [LDLib 1 (archived)](https://github.com/Low-Drag-MC/LDLib).
