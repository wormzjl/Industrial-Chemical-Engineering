package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The layers of the thermodynamics code (batch 2026-11-13-codebase-review, C1b; SOLVER_DECOUPLING_PLAN section 2): the
 * kernel ({@code science/thermo/*.java}, {@code science/thermo/water}, {@code science/math}) below
 * {@code science/material}, {@code science/material} below the equilibrium engine ({@code science/thermo/phase}), the
 * engine below the network's adapter ({@code science/fluid/thermo}). Plain reading of {@code src/main/java}, no class
 * loading: a source file names another package by an import or by a fully qualified name (in code or in a doc link),
 * and every such name {@code com.wormzjl.icheme.science.<package>} is checked, so a wildcard import is caught too.
 * <ul>
 *   <li>No file under {@code science/thermo/**} names {@code science.fluid} or {@code science.column}.</li>
 *   <li>No kernel file names {@code science.material} or {@code science.thermo.phase}.</li>
 *   <li>In the engine's package only {@code PackageBinding} reads {@code MaterialCatalog} (C1b item 6, S05): the other
 *   engine classes take arrays, interfaces and the records of {@code science/material}.</li>
 * </ul>
 */
class ThermoLayeringTest {
    private static final Path SCIENCE = Path.of("src/main/java/com/wormzjl/icheme/science");
    private static final Pattern NAME = Pattern.compile("com\\.wormzjl\\.icheme\\.science\\.([a-z0-9_]+(?:\\.[a-z0-9_]+)?)");

    @Test
    void nothingUnderThermoNamesTheNetworkOrTheColumn() throws IOException {
        List<String> found = crossings(sources(SCIENCE.resolve("thermo"), file -> true),
                name -> name.startsWith("fluid") || name.startsWith("column"));
        assertEquals(List.of(), found, "science/thermo/** must not name science.fluid or science.column");
    }

    @Test
    void theKernelNamesNeitherTheMaterialsNorTheEngine() throws IOException {
        List<Path> kernel = new ArrayList<>(sources(SCIENCE.resolve("thermo"), ThermoLayeringTest::directlyInThermo));
        kernel.addAll(sources(SCIENCE.resolve("thermo/water"), file -> true));
        kernel.addAll(sources(SCIENCE.resolve("math"), file -> true));
        List<String> found = crossings(kernel, name -> name.startsWith("material") || name.equals("thermo.phase"));
        assertEquals(List.of(), found, "the kernel (science/thermo/*.java, thermo/water, science/math) must not name science.material or science.thermo.phase");
    }

    @Test
    void onlyThePackageBindingOfTheEngineReadsTheCatalog() throws IOException {
        Pattern catalog = Pattern.compile("com\\.wormzjl\\.icheme\\.science\\.material\\.(MaterialCatalog\\b|\\*)");
        List<String> found = new ArrayList<>();
        for (Path file : sources(SCIENCE.resolve("thermo/phase"), source -> !source.getFileName().toString().equals("PackageBinding.java"))) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (catalog.matcher(lines.get(i)).find()) found.add(SCIENCE.relativize(file) + ":" + (i + 1));
            }
        }
        assertEquals(List.of(), found, "in science/thermo/phase only PackageBinding may read MaterialCatalog");
        assertTrue(Files.isRegularFile(SCIENCE.resolve("thermo/phase/PackageBinding.java")), "the engine's binding class");
    }

    private static boolean directlyInThermo(Path file) {
        return file.getParent().equals(SCIENCE.resolve("thermo"));
    }

    /** The Java sources under {@code root} that {@code keep} accepts, in path order; the tree must hold some. */
    private static List<Path> sources(Path root, Predicate<Path> keep) throws IOException {
        assertTrue(Files.isDirectory(root), "no source folder " + root.toAbsolutePath() + " (the test reads src/main/java from the project folder)");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(file -> file.toString().endsWith(".java")).filter(keep).sorted().toList();
            assertTrue(!files.isEmpty(), "no Java source under " + root);
            return files;
        }
    }

    /** Each {@code file:line: name} where a file names a science package that {@code forbidden} matches. */
    private static List<String> crossings(List<Path> files, Predicate<String> forbidden) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = NAME.matcher(lines.get(i));
                while (matcher.find()) {
                    if (forbidden.test(matcher.group(1))) found.add(SCIENCE.relativize(file) + ":" + (i + 1) + ": " + matcher.group());
                }
            }
        }
        return found;
    }
}
