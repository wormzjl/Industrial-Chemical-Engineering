package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The frame and what the screen loads (W6_SCREEN_PLAN.md sections 3, 7): the slots keep apart at the sizes the screen admits, the screen's early well is the skin's, the committed atlas has the size
 * {@code Symbol} computes with a mask in every cell, and a save name cannot leave its folder.
 */
class SimulatorFrameTest {
    private static Canvas nothing() {
        return (Canvas) Proxy.newProxyInstance(Canvas.class.getClassLoader(), new Class<?>[]{Canvas.class}, (proxy, method, args) -> {
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            var type = method.getReturnType();
            return type == int.class ? (Object) 0 : type == boolean.class ? (Object) false : null;
        });
    }

    @Test
    void theEarlyWellIsTheSkins() {
        var well = new Rect(5, 71, 934, 300);
        assertEquals(PanelSkin.LIGHT.well(nothing(), well), SimulatorFrame.inner(well));
        assertEquals(PanelSkin.DARK.well(nothing(), well), SimulatorFrame.inner(well));
    }

    @Test
    void theFramesKeysStandApartAtEverySize() {
        for (int[] size : new int[][]{{464, 254}, {624, 344}, {790, 524}, {944, 524}, {1280, 720}}) {
            var frame = new SimulatorFrame(size[0], size[1]);
            var keys = new java.util.ArrayList<Rect>();
            keys.addAll(frame.tabs());
            keys.addAll(frame.fileKeys());
            for (int i = 0; i < keys.size(); i++) {
                assertTrue(keys.get(i).right() <= size[0] - 5, "inside the window at " + size[0]);
                for (int k = i + 1; k < keys.size(); k++) assertFalse(keys.get(i).overlaps(keys.get(k)), "tabs and file keys apart at " + size[0]);
            }
            assertTrue(frame.well().bottom() <= frame.palette().y(), "the canvas ends above the palette");
        }
    }

    @Test
    void theTabsKeysStandInTwoGroupsBetweenTheTabsAndTheFileKeysOrOnTheTitleRow() {
        // 0, the English title in the game's font, the French one (the widest), as the screen measures them.
        for (int title : new int[]{0, 90, 125})
            for (int[] size : new int[][]{{464, 254}, {624, 344}, {661, 344}, {662, 344}, {700, 400}, {790, 524}, {855, 524}, {856, 524}, {944, 524}, {1280, 720}}) {
                int w = size[0];
                var frame = new SimulatorFrame(w, size[1], title);
                if (frame.size() == SimulatorFrame.Size.WIDE) assertTrue(frame.keysOnTabRow(), "a wide window holds them on the tab row at " + w);
                var tabs = frame.tabs();
                var files = frame.fileKeys();
                for (var tab : SimulatorFrame.Tab.values()) {
                    var keys = frame.keys(frame.third(tab));
                    assertEquals(5, keys.size(), "Close and four keys");
                    var top = keys.subList(1, 5);
                    var recesses = frame.recesses(tab);
                    for (int i = 0; i < top.size(); i++) {
                        var key = top.get(i);
                        assertEquals(frame.keysOnTabRow() ? 29 : 5, key.y(), "on its row at " + w);
                        if (frame.keysOnTabRow()) assertTrue(key.x() > tabs.getLast().right() && key.right() < files.getFirst().x(), "between the tabs and the file keys at " + w);
                        else assertTrue(key.x() - 3 >= SimulatorFrame.TITLE_RIGHT + title + 6, "its recess clear of a " + title + " px title at " + w);
                        assertTrue(key.inside(recesses.get(1 + i / 2)), "in its pair's recess at " + w);
                        assertFalse(key.overlaps(frame.theme()), "clear of the theme key at " + w + ", title " + title);
                        for (var r : tabs) assertFalse(key.overlaps(r), "clear of the tabs at " + w);
                        for (var r : files) assertFalse(key.overlaps(r), "clear of the file keys at " + w);
                        for (int k = i + 1; k < top.size(); k++) assertFalse(key.overlaps(top.get(k)), "apart at " + w);
                    }
                    // Centred in the room they have: between the tabs and the file keys, or across the window.
                    int from = frame.keysOnTabRow() ? tabs.getLast().right() : 0, to = frame.keysOnTabRow() ? files.getFirst().x() : w;
                    int left = top.getFirst().x() - from, right = to - top.getLast().right();
                    if (frame.keysOnTabRow() || title == 0) assertTrue(Math.abs(left - right) <= 1, "centred at " + w + ": " + left + " | " + right);
                    for (int i = 0; i < recesses.size(); i++) {
                        var r = recesses.get(i);
                        assertTrue(r.x() >= 5 && r.right() <= w - 5 && r.y() >= 0 && r.bottom() <= frame.status().y() - 5, "recess " + i + " on the door's top rows at " + w);
                        for (int k = i + 1; k < recesses.size(); k++) assertFalse(r.overlaps(recesses.get(k)), "recesses " + i + " and " + k + " apart at " + w + ", " + tab);
                    }
                    var foot = new java.util.ArrayList<Rect>(frame.softKeys());
                    foot.add(keys.getFirst());
                    for (int i = 0; i < foot.size(); i++) for (int k = i + 1; k < foot.size(); k++) assertFalse(foot.get(i).overlaps(foot.get(k)), "the footer apart at " + w);
                }
            }
    }

    @Test
    void theAtlasHasTheSizeSymbolComputesAndAMaskInEveryCell() throws IOException {
        BufferedImage atlas;
        try (var in = SimulatorFrameTest.class.getResourceAsStream("/assets/icheme/textures/gui/flowsheet/symbols.png")) {
            atlas = ImageIO.read(in);
        }
        assertEquals(Symbol.ATLAS_WIDTH, atlas.getWidth());
        assertEquals(Symbol.atlasHeight(), atlas.getHeight());
        for (var symbol : Symbol.values())
            for (var layer : symbol.layers()) {
                int[] cell = symbol.cell(layer);
                long lit = 0;
                for (int y = cell[1]; y < cell[1] + cell[3]; y++) for (int x = cell[0]; x < cell[0] + cell[2]; x++) if (atlas.getRGB(x, y) >>> 24 != 0) lit++;
                assertTrue(lit > 0, symbol + " " + layer + " is empty");
            }
    }

    @Test
    void aSaveNameStaysInItsFolder() {
        for (var name : new String[]{"CDU train", "Vacuum tower 2", "a.b"}) assertTrue(FileColumns.nameAllowed(name), name);
        for (var name : new String[]{"", "  ", "../up", "a/b", "a\\b", "CON", "nul", "com1.txt", "ends.", "ends ", "a:b", "a*b", "a?b", ".."}) assertFalse(FileColumns.nameAllowed(name), name);
    }
}
