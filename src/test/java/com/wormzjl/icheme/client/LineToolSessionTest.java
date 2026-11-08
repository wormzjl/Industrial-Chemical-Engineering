package com.wormzjl.icheme.client;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class LineToolSessionTest {
    @Test
    void allMediaShareOnePendingCommandAndRetainARefusalUntilTheAimMoves() {
        for (int mode = 0; mode < 3; mode++) {
            var adapter = new Adapter();
            var session = new LineToolSession<String, String, Plan>(adapter);
            var context = context(mode, false, "world");
            session.press(context, false);
            assertEquals("a", session.view().start());
            adapter.aim = "b";
            session.tick(context);
            session.press(context, false);
            session.press(context, false);
            assertEquals(1, adapter.lays);
            int searches = adapter.searches;
            session.answered(Component.literal("permission changed"), false);
            for (int i = 0; i < 40; i++) session.tick(context);
            session.press(context, false);
            assertEquals(searches, adapter.searches);
            assertEquals(1, adapter.lays);
            assertEquals("permission changed", session.view().refusal().getString());
            adapter.aim = "c";
            session.tick(context);
            assertNull(session.view().refusal());
            session.press(context, false);
            assertEquals(2, adapter.lays);
            session.answered(Component.literal("laid"), true);
            assertNull(session.view().start());
        }
    }

    @Test
    void anOldReplyCannotClearANewStartAfterCancellationOrADimensionChange() {
        var adapter = new Adapter();
        var session = new LineToolSession<String, String, Plan>(adapter);
        var context = context(0, false, "world");
        session.press(context, false);
        adapter.aim = "b";
        session.press(context, false);
        session.press(context(0, true, "world"), false);
        assertNull(session.view().start());
        session.answered(Component.literal("old refusal"), false);
        adapter.aim = "c";
        session.press(context, false);
        assertEquals("c", session.view().start());
        adapter.aim = "d";
        session.press(context, false);
        session.tick(context(0, false, "other world"));
        assertNull(session.view().start());
        session.answered(Component.literal("old success"), true);
        assertNull(session.view().start());
        session.press(context(0, false, "other world"), false);
        assertEquals("d", session.view().start());
    }

    @Test
    void cancelRemovalAndSettingsHaveTheSamePrecedenceInEveryMode() {
        for (int mode = 0; mode < 3; mode++) {
            var adapter = new Adapter();
            var session = new LineToolSession<String, String, Plan>(adapter);
            session.press(context(mode, false, "world"), false);
            session.press(context(mode, true, "world"), false);
            assertNull(session.view().start());
            assertEquals(0, adapter.removals);
            session.press(context(mode, true, "world"), false);
            assertEquals(1, adapter.removals);
            adapter.aim = null;
            session.press(context(mode, true, "world"), true);
            assertEquals(1, adapter.settings);
        }
    }

    @Test
    void anUnansweredCommandCannotBlockANewConnection() {
        var adapter = new Adapter();
        var session = new LineToolSession<String, String, Plan>(adapter);
        var c = context(1, false, "world");
        session.press(c, false);
        adapter.aim = "b";
        session.press(c, false);
        assertTrue(session.view().pending());
        session.disconnect();
        assertFalse(session.view().pending());
        session.press(c, false);
        assertEquals("b", session.view().start());
    }

    @Test
    void nativeAdaptersCannotReintroduceIndependentInputOrTickOwners() throws Exception {
        for (var name : List.of("PipeLayingClient", "CableLayingClient")) {
            String source =
                    Files.readString(
                            Path.of("src/main/java/com/wormzjl/icheme/client/" + name + ".java"));
            assertFalse(
                    source.matches("(?s).*static void (use|tick)\\(.*"),
                    name + " must remain a domain/drawing adapter");
            assertFalse(source.contains("boolean armed"));
            assertFalse(source.contains("boolean serverRefused"));
        }
    }

    private record Plan(Component refusal) {}

    private static LineToolSession.Context context(int mode, boolean shift, String dimension) {
        return new LineToolSession.Context(dimension, mode, Vec3.ZERO, false, shift);
    }

    private static final class Adapter implements LineToolSession.Adapter<String, String, Plan> {
        String aim = "a";
        int lays, searches, removals, settings;

        public String aimed() {
            return aim;
        }

        public BlockPos cell(String c) {
            return BlockPos.ZERO;
        }

        public LineToolSession.Selected<String> select(String c) {
            return new LineToolSession.Selected<>(c, null);
        }

        public Plan plan(String start, String end) {
            searches++;
            return new Plan(null);
        }

        public boolean same(String a, String b) {
            return a.equals(b);
        }

        public Component refusal(Plan p) {
            return p.refusal();
        }

        public Component started(String s) {
            return Component.literal("started");
        }

        public Component hud(String s, Plan p) {
            return Component.literal("route");
        }

        public void show(LineToolSession.View<String, String, Plan> view) {}

        public void message(Component c) {}

        public boolean removable(String c) {
            return true;
        }

        public void dismantle(boolean off, String c) {
            removals++;
        }

        public void settings(boolean off) {
            settings++;
        }

        public void lay(boolean off, String c, String start, String end, Plan p) {
            lays++;
        }
    }
}
