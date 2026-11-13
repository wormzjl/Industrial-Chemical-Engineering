package com.wormzjl.icheme;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every format row of {@code documentation/reference/FORMATS.md} states the value its code declares. The first three
 * cells of a row are the format, its value and where it is declared. A row is checked in one of three ways:
 * <ul>
 *   <li>its "Declared at" cell names one or more {@code `Class#FIELD`}: each field, read by reflection, equals the
 *       value in the same position of the row's value cell (values separated by ", "; the text after ";" or " ("
 *       is a note);</li>
 *   <li>the cell says {@code (inline}: the value is a string literal in the first {@code Name.java} the cell names;</li>
 *   <li>the cell says {@code digest tag, no constant}: the backticked tag of the value cell is a string literal in that
 *       file. Each {@code digest tag `T`} in the cell is checked the same way.</li>
 * </ul>
 * A class is found by its simple name under {@code src/main/java} or {@code src/test/java}. The equipment shape-field
 * count has no production constant; its row names the corpus test's {@code EquipmentShapeCompatTest#SHAPE_FIELDS}.
 * A row that names nothing checkable fails, unless it is in {@link #DESCRIBED}.
 */
class FormatsTableTest {
    private static final Path TABLE = Path.of("documentation/reference/FORMATS.md");
    private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("src/test/java"));

    /** Rows whose value is a description, not a literal. */
    private static final Set<String> DESCRIBED = Set.of(
            // The value names three revisions of V3ColumnLabels joined by ';'; NativeColumnSolverTest checks it.
            "Native formulation token");

    private static final Pattern FIELD = Pattern.compile("`([A-Z]\\w*)#([A-Za-z_]\\w*)`");
    private static final Pattern JAVA_FILE = Pattern.compile("([A-Z]\\w*)\\.java");
    private static final Pattern DIGEST = Pattern.compile("digest tag `([^`]+)`");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    @Test
    void everyRowStatesTheValueItsCodeDeclares() throws IOException {
        var classes = sourceIndex();
        var problems = new ArrayList<String>();
        int checked = 0;
        for (String raw : Files.readAllLines(TABLE, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (!line.startsWith("|") || line.startsWith("|---")) continue;
            String[] cells = line.substring(1).split("\\|", -1);
            if (cells.length < 3) continue;
            String format = cells[0].strip(), value = cells[1].strip(), declared = cells[2].strip();
            if (format.equals("Format") || format.equals("Registrar")) continue;
            int before = problems.size();
            boolean any = false;

            var fields = new ArrayList<String[]>();
            Matcher f = FIELD.matcher(declared);
            while (f.find()) fields.add(new String[] {f.group(1), f.group(2)});
            if (!fields.isEmpty()) {
                any = true;
                List<String> values = values(value);
                if (values.size() != fields.size()) {
                    problems.add(fields.size() + " constants but " + values.size() + " values: " + line);
                } else {
                    for (int k = 0; k < fields.size(); k++) {
                        String problem = compare(classes, fields.get(k)[0], fields.get(k)[1], values.get(k));
                        if (problem != null) problems.add(problem + ": " + line);
                    }
                }
            }
            if (declared.contains("(inline")) {
                any = true;
                checkLiteral(classes, declared, strip(value), line, problems);
            }
            if (declared.contains("digest tag, no constant")) {
                any = true;
                Matcher tag = BACKTICKED.matcher(value);
                if (tag.find()) checkLiteral(classes, declared, tag.group(1), line, problems);
                else problems.add("no backticked digest tag in the value: " + line);
            }
            Matcher digest = DIGEST.matcher(declared);
            while (digest.find()) {
                any = true;
                checkLiteral(classes, declared, digest.group(1), line, problems);
            }
            if (!any && !DESCRIBED.contains(format)) problems.add("the row names no constant, inline string or digest tag: " + line);
            if (any && problems.size() == before) checked++;
        }
        assertTrue(checked >= 25, "only " + checked + " rows checked in " + TABLE + "; did the table's layout change?");
        if (!problems.isEmpty()) fail(problems.size() + " rows of " + TABLE + " disagree with the code:\n  " + String.join("\n  ", problems));
    }

    /** The row's values in order: the text before ";" and " (", split at ", ", backticks removed. */
    private static List<String> values(String cell) {
        String head = cell;
        int semi = head.indexOf(';');
        if (semi >= 0) head = head.substring(0, semi);
        int paren = head.indexOf(" (");
        if (paren >= 0) head = head.substring(0, paren);
        var out = new ArrayList<String>();
        for (String part : head.split(", ")) out.add(strip(part));
        return out;
    }

    private static String strip(String text) {
        String head = text;
        int paren = head.indexOf(" (");
        if (paren >= 0) head = head.substring(0, paren);
        return head.replace("`", "").strip();
    }

    private static String compare(Map<String, List<String>> classes, String simpleName, String fieldName, String stated) {
        List<String> names = classes.get(simpleName);
        if (names == null) return "no class " + simpleName + " under " + SOURCES;
        if (names.size() > 1) return "class name " + simpleName + " is ambiguous: " + names;
        Object actual;
        try {
            Field field = Class.forName(names.get(0)).getDeclaredField(fieldName);
            field.setAccessible(true);
            actual = field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "cannot read " + simpleName + "#" + fieldName + " (" + e + ")";
        }
        boolean same;
        if (actual instanceof Number number) {
            try {
                same = Long.decode(stated) == number.longValue();
            } catch (NumberFormatException e) {
                return simpleName + "#" + fieldName + " is " + actual + ", the table states a non-number '" + stated + "'";
            }
        } else {
            same = String.valueOf(actual).equals(stated);
        }
        return same ? null : simpleName + "#" + fieldName + " is " + actual + ", the table states " + stated;
    }

    private static void checkLiteral(Map<String, List<String>> classes, String declared, String literal, String line,
            List<String> problems) {
        Matcher file = JAVA_FILE.matcher(declared);
        if (!file.find()) {
            problems.add("no Name.java for the string '" + literal + "': " + line);
            return;
        }
        List<String> names = classes.get(file.group(1));
        if (names == null || names.size() != 1) {
            problems.add("no single source for " + file.group(1) + ".java: " + line);
            return;
        }
        String source = sourceOf(names.get(0));
        if (!source.contains("\"" + literal + "\"")) problems.add(file.group(1) + ".java has no literal \"" + literal + "\": " + line);
    }

    private static final Map<String, Path> PATHS = new HashMap<>();

    private static String sourceOf(String className) {
        try {
            return Files.readString(PATHS.get(className), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Simple class name to the fully qualified names of the top-level classes of that name. */
    private static Map<String, List<String>> sourceIndex() throws IOException {
        var out = new HashMap<String, List<String>>();
        for (Path root : SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    String name = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    out.computeIfAbsent(simple, k -> new ArrayList<>()).add(name);
                    PATHS.put(name, file);
                }
            }
        }
        return out;
    }
}
