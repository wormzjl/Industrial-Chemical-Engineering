package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.films.StillInside;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The film "inside the still" (batch 2026-11-12-tutorial-system, P0): what each stop shows, and its texts. */
class StillInsideFilmTest {
    @Test
    void theFilmBuildsWithNoMinecraftClass() throws Exception {
        // The mod's classes load in a loader of their own that refuses the game's: a film that reached one fails here.
        var urls = new ArrayList<URL>();
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("net.neoforged.") || name.startsWith("org.lwjgl."))
                    throw new ClassNotFoundException("A film may not load " + name);
                return super.loadClass(name, resolve);
            }
        }) {
            var film = loader.loadClass(StillInside.class.getName());
            assertNotSame(StillInside.class, film, "loaded apart");
            var scene = film.getMethod("build").invoke(null);
            var frame = scene.getClass().getMethod("at", double.class).invoke(scene, 24.0);
            assertNotNull(frame);
            // Every film (P2b: the recorded run read through Series), and the overlays' layout class the render tool shares.
            var films = loader.loadClass(Films.class.getName());
            for (var name : Films.names()) {
                var built = films.getMethod("build", String.class).invoke(null, name);
                for (double t : new double[]{0, 5, 12, 30}) assertNotNull(built.getClass().getMethod("at", double.class).invoke(built, t), name);
            }
            var overlay = loader.loadClass(com.wormzjl.icheme.client.gui.tutorial.FilmOverlay.class.getName());
            assertEquals(3, ((List<?>) overlay.getMethod("legendKeys").invoke(null)).size());
            loader.loadClass(com.wormzjl.icheme.science.tutorial.films.StillRunData.class.getName()).getMethod("load").invoke(null);
            // P5: the crude unit's map draws with the simulator's own sheet painter (client.gui.flowsheet), here on a canvas that keeps nothing.
            var canvas = loader.loadClass(com.wormzjl.icheme.client.gui.common.kit.Canvas.class.getName());
            var measure = loader.loadClass(com.wormzjl.icheme.client.gui.common.kit.Measure.class.getName()).getMethod("fixed", int.class).invoke(null, 6);
            Object c = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{canvas}, (proxy, method, args) -> {
                if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                return method.getName().equals("measure") ? measure : null;
            });
            var rect = loader.loadClass(com.wormzjl.icheme.client.gui.common.kit.Rect.class.getName());
            var area = rect.getConstructor(int.class, int.class, int.class, int.class).newInstance(20, 40, 300, 150);
            var card = loader.loadClass(com.wormzjl.icheme.client.gui.tutorial.cards.Cards.class.getName()).getMethod("build", String.class).invoke(null, "unit_map");
            var cardType = loader.loadClass(com.wormzjl.icheme.client.gui.tutorial.cards.Card.class.getName());
            var stateType = loader.loadClass(com.wormzjl.icheme.client.gui.tutorial.cards.Card.State.class.getName());
            var skin = loader.loadClass(com.wormzjl.icheme.client.gui.common.kit.PanelSkin.class.getName());
            var skinType = loader.loadClass(com.wormzjl.icheme.client.gui.common.kit.Skin.class.getName());
            cardType.getMethod("draw", canvas, rect, stateType, skinType, java.util.function.Function.class).invoke(card, c, area,
                    stateType.getMethod("of", double.class, double.class).invoke(null, 0.0, 0.0), skin.getField("DARK").get(null), (java.util.function.Function<String, String>) k -> "word");
            assertFalse(((List<?>) cardType.getMethod("hits", rect, int.class).invoke(card, area, 0)).isEmpty(), "the map's places, apart from the game");
        }
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/wormzjl/icheme/science/tutorial"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // The cards draw on the kit's canvas (client.gui.common.kit: no Minecraft class); the loader check above stands.
                // The crude unit's map (P5) draws with the simulator's sheet painter (client.gui.flowsheet), drawn above in that loader.
                var source = Files.readString(file).replace("icheme.client.gui.common.kit.", "").replace("icheme.client.gui.flowsheet.", "");
                for (var word : List.of("net.minecraft", "com.mojang", "net.neoforged", "icheme.client", "java.awt"))
                    assertFalse(source.contains(word), file + " names " + word);
            }
        }
    }

    @Test
    void itHasThreeStopsAndEveryTextItsEnglishLine() throws Exception {
        var scene = StillInside.build();
        assertEquals(3, scene.stops().size());
        assertEquals(scene.stops(), Objects.requireNonNull(Films.build(StillInside.NAME)).stops(), "the command and the guide open it by its name");
        assertNull(Films.build("no_such_film"));
        Map<String, String> lang;
        try (var in = StillInsideFilmTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            var json = com.google.gson.JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            lang = new java.util.HashMap<>();
            for (var e : json.entrySet()) lang.put(e.getKey(), e.getValue().getAsString());
        }
        assertTrue(lang.containsKey(scene.title()), scene.title());
        assertEquals(List.of("tutorial.icheme.film.still_inside.s1", "tutorial.icheme.film.still_inside.s2", "tutorial.icheme.film.still_inside.s3"), scene.captionKeys());
        for (double stop : scene.stops()) assertEquals(1, scene.at(stop).captions().size(), "one caption at each stop");
        var sentence = Pattern.compile("[.!?](\\s|$)");
        for (var key : scene.captionKeys()) {
            var text = lang.get(key);
            assertNotNull(text, key);
            assertTrue(text.endsWith("."), key);
            assertEquals(1, sentence.matcher(text).results().count(), key + ": one sentence");
            assertTrue(text.split("\\s+").length <= 25, key + ": at most 25 words");
            for (var word : List.of("distillate", "reflux", "fraction"))
                assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(word), key + " names " + word);
        }
        for (var key : List.of("back", "next", "close", "replay")) assertTrue(lang.containsKey("tutorial.icheme.film." + key), key);
    }

    @Test
    void atStopTwoEveryDotIsInThePot() {
        var scene = StillInside.build();
        var parts = ShapeRasterizer.stillParts(StillInside.shape());
        var frame = scene.at(scene.stops().get(1));
        assertEquals(34, frame.dots().size(), "the three kinds in the pot");
        for (var kind : Track.Kind.values()) assertTrue(frame.dots().stream().anyMatch(d -> d.kind() == kind), kind.name());
        var liquid = StillInside.liquid(parts);
        for (var d : frame.dots()) {
            assertTrue(inside(parts.pot(), d, 0), "in the pot: " + d);
            assertTrue(liquid.contains(d.x(), d.y(), d.z(), 1e-12), "in the liquid: " + d);
        }
        var pot = frame.actors().stream().filter(a -> a.id().equals("pot")).findFirst().orElseThrow();
        assertTrue(pot.twoSided(), "the pot is cut open");
        assertTrue(pot.polygons().size() < scene.actors().getFirst().polygons().size());
    }

    @Test
    void atStopThreeLightDotsReachTheCoolerAndTheHeavyStay() {
        var scene = StillInside.build();
        var parts = ShapeRasterizer.stillParts(StillInside.shape());
        Frame frame = scene.at(scene.length());
        long cooled = frame.dots().stream().filter(d -> d.kind() == Track.Kind.LIGHT && inside(parts.condenser(), d, 0)).count();
        assertTrue(cooled >= 1, "some light dots in the cooler");
        long heavy = frame.dots().stream().filter(d -> d.kind() == Track.Kind.HEAVY).count();
        assertEquals(10, heavy);
        for (var d : frame.dots()) if (d.kind() == Track.Kind.HEAVY) assertTrue(inside(parts.pot(), d, 0), "a heavy dot stays in the pot: " + d);
        long leftLight = frame.dots().stream().filter(d -> d.kind() == Track.Kind.LIGHT && inside(parts.pot(), d, 0)).count();
        assertEquals(0, leftLight, "every light dot has left the pot");
        long middleOut = frame.dots().stream().filter(d -> d.kind() == Track.Kind.MIDDLE && !inside(parts.pot(), d, 0)).count();
        assertTrue(middleOut > 0 && middleOut <= 3, "a few middle ones follow: " + middleOut);
        for (var id : List.of("pot", "neck", "cooler"))
            assertTrue(frame.actors().stream().anyMatch(a -> a.id().equals(id) && a.twoSided()), id + " cut open");
    }

    /** P2b: stop 1 names the pot, the fire and the cooler; the legend and the liquid from stop 2; at stop 3 the fire burns, and the camera is close. */
    @Test
    void theStopsNameShowAndCloseIn() {
        var scene = StillInside.build();
        var one = scene.at(scene.stops().get(0));
        assertEquals(List.of(StillInside.POT, StillInside.FIRE_KEY, StillInside.COOLER), one.labels().stream().map(Frame.LabelFrame::key).toList());
        assertFalse(one.legend());
        var two = scene.at(scene.stops().get(1));
        assertTrue(two.legend() && two.labels().isEmpty());
        assertEquals(1, two.liquids().size());
        assertNotNull(two.liquids().getFirst().cut(), "the liquid shows through the pot's cut");
        assertTrue(two.flames().isEmpty(), "the fire burns at stop 3");
        var three = scene.at(scene.length());
        assertEquals(1, three.flames().size());
        assertTrue(three.legend());
        FilmChecks.everyStopReads(scene);
        // Stop 3: a dot at least three GUI px across on the design glass, and the pot, neck, cooler and the flame's upper part inside the glass.
        var glass = com.wormzjl.icheme.client.gui.tutorial.FilmLayout.DESIGN_GLASS;
        double fit = com.wormzjl.icheme.client.gui.tutorial.FilmLayout.fit(glass);
        assertTrue(2 * Track.Dots.SIZE * three.camera().scale() * fit >= StillInside.LEAST_DOT, "a dot's width: " + 2 * Track.Dots.SIZE * three.camera().scale() * fit);
        var p = com.wormzjl.icheme.science.tutorial.scene.Projection.of(three.camera(), 0, 0, 1, fit, glass.x() + glass.w() / 2.0, glass.y() + glass.h() / 2.0);
        var parts = ShapeRasterizer.stillParts(StillInside.shape());
        var points = new ArrayList<double[]>(parts.vapour());
        var c = parts.condenser();
        points.add(new double[]{(c[0] + c[3]) / 2, c[1], (c[2] + c[5]) / 2});
        points.add(new double[]{(c[0] + c[3]) / 2, c[4], (c[2] + c[5]) / 2});
        var bed = StillInside.fireBed(StillInside.shape());
        points.add(new double[]{bed[0], bed[1] + .8 * StillInside.FIRE, bed[2]}); // the flame's upper part
        points.add(new double[]{(parts.pot()[0] + parts.pot()[3]) / 2, parts.pot()[1], (parts.pot()[2] + parts.pot()[5]) / 2});
        for (var point : points) {
            var s = p.screen(point[0], point[1], point[2]);
            assertTrue(glass.contains(s[0], s[1]), "inside the glass " + glass + ": " + java.util.Arrays.toString(point) + " at " + s[0] + ", " + s[1]);
        }
        for (var d : three.dots()) {
            if (d.y() < parts.pot()[1]) continue; // the cooled ones on their way down to the spout leave under the glass
            var s = p.screen(d.x(), d.y(), d.z());
            assertTrue(glass.contains(s[0], s[1]), "a dot inside the glass: " + d);
        }
    }

    private static boolean inside(double[] box, Frame.DotFrame d, double tolerance) {
        return d.x() >= box[0] - tolerance && d.x() <= box[3] + tolerance && d.y() >= box[1] - tolerance && d.y() <= box[4] + tolerance && d.z() >= box[2] - tolerance && d.z() <= box[5] + tolerance;
    }
}
