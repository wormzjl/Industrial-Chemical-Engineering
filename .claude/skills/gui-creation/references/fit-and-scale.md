# Fit and scale

Read this when a page does not fit, when a screen gets its own design height, or when the owner says "too big",
"scale", "slider" or "one page".

## What the owner chose

- Scale the whole screen in whole steps ("This should be scaled to window size", option A of a sheet that also drew a
  stretch and a zoom to fill). Whole scales keep the bitmap font sharp.
- On a big window a device page fits by scale, not by a new layout ("It should scale"; a two-column split was
  dropped). One scale for every device screen, so pages do not change size between devices.
- A minimum scale a player can read; below it, a bar ("Define a minimum scale user can see correctly, use slider below
  that").
- No stretching, no fractional scale, no smaller font, no size taken from the player's GUI scale.

## The calls

| Screen | `init()`, first line | `removed()` |
| --- | --- | --- |
| Designed at 464 by 254 | `WindowScale.apply(minecraft, this)` | `WindowScale.restore(minecraft)`, after `super.removed()` |
| With a taller design | `WindowScale.apply(minecraft, this, designHeight, this::fits)` | the same |

`apply` sets `width` and `height` for that scale, so nothing may read them before it. Forgetting `restore` leaves the
player's window at the screen's scale.

A screen with a design height needs two more pieces, as `FluidDeviceScreen` has them:

```java
// fits: the tallest door column at that GUI size against the view at that GUI size. Never read this.width in it.
private boolean fits(int width, int height) { return FluidGuiLayout.fits(width, height, this::tallest); }

// The first readings can make a column taller after the screen opened: step down, outside a frame.
if (doorView.max() > 0 && WindowScale.lower(minecraft, FluidGuiLayout.HEIGHT, this::fits)) rebuildWidgets();
```

`tallest` places the rows with every line they may show. The scale only steps down while a screen is open; a column
that shrinks leaves it until the screen opens again.

## Numbers

| Window | Design size (254 high) | Device screen (480 high) | Minimum |
| --- | --- | --- | --- |
| 800 by 600 | 1 | 1 | 1 |
| 1366 by 768 | 2 | 2, the door scrolls | 2 |
| 1920 by 1080 | 4 | 2 | 2 |
| 2560 by 1440 | 5 | 3 | 3 |
| 3840 by 2160 | 8 | 4 | 4 |

The minimum is half the design size's scale, rounded up, and never below 2 where that scale is 2 or more. With the
forced Unicode font, scales are even (`WindowScale.of` handles this).

- Design and render every screen at 464 by 254. Check it up to 1.5 times that from scale 2 (533 by 280 is a 1600 by
  840 window). Only a window under 928 by 508 screen px, at scale 1, reaches twice.
- The device door view is 338 px at the design height (`FluidGuiLayout.HEIGHT` 480). `FluidGuiLayout`'s comment records
  a valve's page at 323 px and a pump's at 330.

## When a page does not fit

1. Read the printed line: `column 330 px in a 338 px view`. Render at the window in question:

   ```bash
   java -Dwindow=2560x1440 -Duser.language=en -Djava.awt.headless=true -cp build/classes/java/main tools/heat-gui-renders/GuiPrototypes.java src/main/resources/assets/icheme/lang/en_us.json tmp/gui-prototypes
   ```

   Device pages print `scale 3 (the design size's 5, the minimum 3)`. A sheet that adds the scrolled pair still has a
   bar at that scale.
2. Remove what the rules already forbid: a sentence on the door (it goes to a hover), a status said twice.
3. If the column is still too tall, trimming changes what the player sees. Render two to four trims and let the owner
   pick. The compressor's page went this way: its pressure change moved to a hover.
4. If a screen family cannot fit, say what keeps the bar and at which window, and let the owner choose. Do not raise a
   design height or split a column on your own.

A view with a bar loses `ScrollView.BAR + 4` px of width, so a builder's gates test both widths. `KitDoor.layout` takes
the bar's room only when the tallest rows need it.

The constructor and the calculator take the design size's scale and scroll on small windows. That is an open item,
not a pattern to copy.
