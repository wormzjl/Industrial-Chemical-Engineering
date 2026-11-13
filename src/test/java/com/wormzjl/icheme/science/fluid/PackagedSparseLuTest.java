package com.wormzjl.icheme.science.fluid;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * The built mod jar on its own: its sparse LU (the mod's own {@code UpLookingLu}, derived from EJML) solves with no other
 * library on the class path; it ships no EJML (finding P-17b, batch 2026-11-13-codebase-review: EJML moved to the tests,
 * the parity gate's reference) and keeps EJML's Apache-2.0 notice for the derived class; and it ships no test mod.
 */
class PackagedSparseLuTest {
    @Test void solvesUsingOnlyTheBuiltModJar() throws Exception {
        Path mod = Path.of(System.getProperty("fluid.modJar"));
        try (var jar = new ZipFile(mod.toFile())) {
            assertNotNull(jar.getEntry("META-INF/licenses/ejml-Apache-2.0.txt"), "EJML's license for the derived UpLookingLu");
            assertNotNull(jar.getEntry("META-INF/licenses/ejml-NOTICE.txt"), "EJML's notice for the derived UpLookingLu");
            assertNull(jar.getEntry("com/wormzjl/icheme/fluid/gametest/FluidTestMod.class"),
                    "Disposable test mod must not ship in the production artifact");
            var shipped = jar.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(name -> name.startsWith("org/ejml/") || name.startsWith("META-INF/jarjar/ejml-")).toList();
            assertEquals(java.util.List.of(), shipped, "EJML is a test dependency only");
        }
        // Platform parent prevents the Gradle/JUnit dependency classpath (which holds EJML) from hiding a reference to it.
        try (var loader = new URLClassLoader(new URL[] {mod.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> matrixType = loader.loadClass("com.wormzjl.icheme.science.fluid.linalg.SparseMatrix");
            Object matrix = matrixType.getConstructor(int.class, int[].class, int[].class, double[].class)
                    .newInstance(2, new int[] {0, 1, 3}, new int[] {1, 0, 1}, new double[] {1, 2, 3});
            Class<?> solver = loader.loadClass("com.wormzjl.icheme.science.fluid.linalg.SparseLuSolver");
            double[] result = (double[]) solver.getMethod("solve", matrixType, double[].class)
                    .invoke(null, matrix, new double[] {4, 7});
            assertArrayEquals(new double[] {1, 2}, result, 1e-12);
        }
    }
}
