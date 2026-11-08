# Fit and scale

Read this when a page does not fit, or when the owner says "too big", "scale", "slider" or "one page".

## What the owner chose

- Keep the player's GUI scale (owner, 2026-11-07): "Preserve the player's GUI scale across IChemE screens. Opening,
  resizing, switching or closing a screen must not override the Minecraft window or saved GUI scale. Fit/reflow/scroll
  panel content instead." This replaced the whole-screen scale of `WindowScale` ("This should be scaled to window
  size", 2026-10-28), which `5b84b9ec6` removed.
- No stretching, no fractional scale, no smaller font.

## The rule in code

- Nothing sets the GUI scale: no `setGuiScale` or `guiScale().set` call, in `init()`, `removed()` or anywhere else.
  `KitLayoutTest.productionScreensNeverOverrideThePlayerGuiScale` fails on either call under `client/`.
- A screen sizes its image from the `width` and `height` Minecraft gives it at the player's scale. The device screen
  takes `FluidGuiLayout.imageWidth(width)` (8 px round it, 300 to 790 px wide) and `FluidGuiLayout.imageHeight(height)`
  (8 px round it, at least 180 px high); `ControlColumns.layout(width, height, dcs)` sizes the controller and DCS.
- What does not fit scrolls: a door column in its own view, a table its rows. A view with a bar loses
  `ScrollView.BAR + 4` px of width, so a builder's gates test both widths. `KitDoor.layout` takes the bar's room only
  when the tallest rows need it.
- The smallest GUI window is 320 by 240 GUI px (a 640 by 480 window at scale 2). `FluidGuiConsistencyTest` (up to
  1600 by 900) and `ControlColumnsTest` (up to 1280 by 800) place the shared frames from there.

## When a page does not fit

1. Render it at the window and the player scale in question:

   ```bash
   java -Dwindow=2560x1440 -DguiScale=3 -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/GuiPrototypes.java src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes
   ```

   Device pages print the window, the player scale and the GUI size (`2560 by 1440 window: player scale 3, 854 by 480
   GUI px`). A page whose column needs the bar is drawn scrolled to its end too.
2. Remove what the rules already forbid: a sentence on the door (it goes to a hover), a status said twice.
3. If the column is still too tall at the windows players use, trimming changes what the player sees. Render two to
   four trims and let the owner pick. The compressor's page went this way: its pressure change moved to a hover.
4. If a screen family cannot fit, say what keeps the bar, at which window and scale, and let the owner choose. Do not
   split a column on your own.
