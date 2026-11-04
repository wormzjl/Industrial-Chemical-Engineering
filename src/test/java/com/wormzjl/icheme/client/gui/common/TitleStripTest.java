package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.equipment.DeviceName;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N1's title strip (the owner 2026-11-03: "You can put the equipment name here"): at every width the device, assembly and
 * datasheet screens take, the kind's plate, the name's place and the theme key stand apart inside the screen; from the design
 * size up the kind stands whole and the name's entry keeps its width, room for a whole name.
 */
class TitleStripTest {
    private static final Measure M = Measure.fixed(6);

    /** A screen: its name, its width from a window's, the theme key's width (0: none), the window widths it takes and the design window's. */
    private record Screen(String name, IntUnaryOperator width, int key, int narrowest, int design) {
        Rect themeAt(int w) {
            return key == 0 ? null : new Rect(w - 10 - key, 5, key, 18);
        }
    }

    private static final List<Screen> SCREENS = List.of(
            new Screen("device", FluidGuiLayout::imageWidth, 18, 316, 464),
            new Screen("assembly", w -> Math.max(320, Math.min(760, w - 16)), 18, 336, 464),
            new Screen("datasheet", w -> Math.min(400, w - 8), 0, 316, 464));

    /** Every kind a strip can name: each block's and each structure's title, and the column's. */
    private static List<String> kinds() throws IOException {
        try (var in = TitleStripTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            var lang = com.google.gson.JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            var out = new ArrayList<String>();
            for (var key : lang.keySet())
                if (key.matches("block\\.icheme\\.[a-z_]+|gui\\.icheme\\.vessel\\.title\\.[a-z_]+|gui\\.icheme\\.assembly\\.title")) out.add(lang.get(key).getAsString());
            return out;
        }
    }

    @Test
    void thePlateTheNameAndTheThemeKeyStandApartAndWhole() throws IOException {
        var kinds = kinds();
        assertTrue(kinds.size() >= 30, "the kinds read: " + kinds.size());
        assertTrue(M.width("W".repeat(DeviceName.MAX)) <= TitleStrip.NAME - 8, "a whole name in the entry");
        var short_ = new TreeSet<String>();
        for (var screen : SCREENS)
            for (int window = screen.narrowest(); window <= 1600; window += 7) {
                int w = screen.width().applyAsInt(window);
                var theme = screen.themeAt(w);
                for (var kind : kinds) {
                    var strip = TitleStrip.place(w, screen.key(), kind, M);
                    String at = screen.name() + " " + w + " px, " + kind;
                    var door = new Rect(0, 0, w, 30);
                    assertTrue(strip.plate().inside(door) && M.width(strip.kind()) <= strip.plate().w() - 8, at + ": the plate " + strip.plate());
                    if (theme != null) assertFalse(strip.plate().overlaps(theme), at + ": the plate off the theme key");
                    var name = strip.name();
                    if (name != null) {
                        assertTrue(name.x() >= strip.plate().right() + TitleStrip.GAP && name.right() <= w - 10 && name.w() >= TitleStrip.NAME_MIN && name.w() <= TitleStrip.NAME, at + ": the name " + name);
                        if (theme != null) assertTrue(name.right() + TitleStrip.GAP <= theme.x(), at + ": the name off the theme key");
                    }
                    if (window < screen.design()) continue;
                    assertEquals(kind, strip.kind(), at + ": the kind whole");
                    assertNotNull(name, at + ": a place for the name");
                    if (name.w() < TitleStrip.NAME) short_.add(screen.name() + " " + w + " px: " + kind + " leaves " + name.w() + " px");
                }
            }
        // The test font is 6 px a letter, wider than the game's: a close call here is read again on the render (STRIP_SHORT).
        for (var s : short_) System.out.println("STRIP_SHORT " + s);
        assertTrue(short_.stream().allMatch(s -> s.startsWith("datasheet 400 px")), "only the datasheet's narrowest shortens the entry: " + short_);
    }

    /** A reader's name is its text in the entry's place, cut to it, whole where it fits; the place's text inset as the LCD's. */
    @Test
    void aReadersNameIsItsTextCutToItsPlace() {
        var place = new Rect(120, 5, TitleStrip.NAME, 18);
        assertEquals("T-101", TitleStrip.text("T-101", place, M));
        String whole = "W".repeat(DeviceName.MAX);
        assertEquals(whole, TitleStrip.text(whole, place, M));
        var narrow = new Rect(120, 5, 60, 18);
        assertTrue(M.width(TitleStrip.text(whole, narrow, M)) <= narrow.w() - 8);
        assertArrayEquals(new int[]{124, 10}, TitleStrip.textAt(place));
        var t = new HeatControlColumnTest.Recording();
        t.get("name");
        t.get("name.hint");
        t.get("apply_name.hint");
        assertEquals(Set.of(), t.missing);
    }
}
