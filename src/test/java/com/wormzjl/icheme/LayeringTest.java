package com.wormzjl.icheme;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The import rules between the top-level layers of {@code com.wormzjl.icheme} (codebase review, CODEBASE_REVIEW
 * section 3.1; DOCS_AND_COLLABORATION_PLAN section 2.4). It reads the sources of {@code src/main/java} as text and
 * loads no production class. A reference is an {@code import} line, or a code line (comments, strings and text
 * blocks stripped) that names another layer's type by its full name. The root layer is the classes directly in
 * {@code com.wormzjl.icheme} ({@code IChemE}).
 *
 * <p>Each rule held with no violation when it was added. A rule never leaves the test: a change that breaks one
 * moves the reference to the layer it belongs to. A rule joins when a cycle of section 3.1 is removed;
 * {@code tools/code-guide/map.mjs} prints the matrix and the rules that do not hold yet ({@code FORBIDDEN} there,
 * kept in step with {@link #RULES}).
 */
class LayeringTest {
    private static final Path ROOT = Path.of("src/main/java/com/wormzjl/icheme");
    private static final String PREFIX = "com.wormzjl.icheme.";
    private static final String ROOT_LAYER = "(root)";

    /** A layer that must not reference another, and why. */
    record Rule(String name, String from, String to, String reason) {}

    static final List<Rule> RULES = List.of(
            new Rule("science-free-of-runtime", "science", "runtime", "science is Minecraft-free and below the engine"),
            new Rule("science-free-of-world", "science", "world", "science is Minecraft-free"),
            new Rule("science-free-of-network", "science", "network", "science is Minecraft-free"),
            new Rule("science-free-of-client", "science", "client", "science is Minecraft-free"),
            new Rule("science-free-of-registry", "science", "registry", "science is Minecraft-free"),
            new Rule("science-free-of-compat", "science", "compat", "science is Minecraft-free"),
            new Rule("science-free-of-mixin", "science", "mixin", "science is Minecraft-free"),
            new Rule("science-free-of-mod-class", "science", ROOT_LAYER, "science is Minecraft-free"),
            new Rule("material-free-of-runtime", "material", "runtime", "the catalog loader sits below the engine"),
            new Rule("material-free-of-world", "material", "world", "the catalog loader sits below the world"),
            new Rule("material-free-of-network", "material", "network", "the catalog loader sits below the wire"),
            new Rule("material-free-of-client", "material", "client", "the catalog loader is common code"),
            new Rule("runtime-free-of-client", "runtime", "client", "the engine runs on the server"),
            new Rule("registry-free-of-client", "registry", "client", "registration is common code"),
            new Rule("compat-free-of-client", "compat", "client", "integrations are common code"));

    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?com\\.wormzjl\\.icheme\\.([A-Za-z_]\\w*)[\\w.*]*\\s*;");
    private static final Pattern QUALIFIED = Pattern.compile("com\\.wormzjl\\.icheme\\.([A-Za-z_]\\w*)");

    /** A reference from one source line to a layer. */
    record Reference(Path file, int line, String layer, String text) {}

    @Test
    void layersReferenceOnlyWhatTheLayeringAllows() {
        Map<Path, List<Reference>> sources = scan();
        assertTrue(sources.size() > 100, "no sources found under " + ROOT.toAbsolutePath());
        var violations = new ArrayList<String>();
        for (var rule : RULES) {
            for (var entry : sources.entrySet()) {
                if (!layerOf(entry.getKey()).equals(rule.from())) continue;
                for (var ref : entry.getValue()) {
                    if (!ref.layer().equals(rule.to())) continue;
                    violations.add(rule.name() + " (" + rule.reason() + "): "
                            + ROOT.relativize(ref.file()).toString().replace('\\', '/') + ":" + ref.line()
                            + " " + ref.text());
                }
            }
        }
        if (!violations.isEmpty()) {
            fail(violations.size() + " forbidden references between layers:\n  " + String.join("\n  ", violations));
        }
    }

    @Test
    void everyRuleNamesALayerThatExists() {
        var layers = new java.util.TreeSet<String>();
        for (var file : scan().keySet()) layers.add(layerOf(file));
        for (var rule : RULES) {
            assertTrue(layers.contains(rule.from()), rule.name() + ": no layer " + rule.from() + " in " + layers);
            assertTrue(layers.contains(rule.to()), rule.name() + ": no layer " + rule.to() + " in " + layers);
        }
    }

    private static Map<Path, List<Reference>> scan() {
        var out = new TreeMap<Path, List<Reference>>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java")).sorted()::iterator) {
                out.put(file, references(file, Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String layerOf(Path file) {
        Path rel = ROOT.relativize(file);
        return rel.getNameCount() == 1 ? ROOT_LAYER : rel.getName(0).toString();
    }

    private static String layerName(String segment) {
        return Character.isUpperCase(segment.charAt(0)) ? ROOT_LAYER : segment;
    }

    static List<Reference> references(Path file, String source) {
        var refs = new ArrayList<Reference>();
        String[] lines = stripJava(source).split("\n", -1);
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            Matcher imp = IMPORT.matcher(line);
            if (imp.find()) {
                refs.add(new Reference(file, k + 1, layerName(imp.group(1)), line.trim()));
                continue;
            }
            if (line.stripLeading().startsWith("package ")) continue;
            Matcher q = QUALIFIED.matcher(line);
            var seen = new java.util.HashSet<String>();
            while (q.find()) {
                String layer = layerName(q.group(1));
                if (seen.add(layer)) refs.add(new Reference(file, k + 1, layer, line.trim()));
            }
        }
        return refs;
    }

    /** The code of a Java source with comments, strings, text blocks and char literals blanked; newlines kept. */
    static String stripJava(String src) {
        var out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && d == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && d == '*') {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '"' && d == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                i += 3;
                while (i < n && !(src.charAt(i) == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    if (src.charAt(i) == '\\') i++;
                    else if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 3;
                out.append("\"\"");
                continue;
            }
            if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                out.append(c).append(c);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
