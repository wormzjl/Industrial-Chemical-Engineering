package com.wormzjl.icheme.fluid.gametest;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Offline registrar: preserve each selected test's metadata and call its real body in a fresh world. */
@GameTestHolder("icheme_fluid_focus")
public final class FocusedGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> selected() throws ReflectiveOperationException {
        var result = new ArrayList<TestFunction>();
        for (var selection : System.getProperty("icheme.fluid.focus", "").split(",")) {
            if (selection.isBlank()) continue;
            var parts = selection.split("#", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Expected Class#method: " + selection);
            var method = Class.forName("com.wormzjl.icheme.fluid.gametest." + parts[0]).getMethod(parts[1], GameTestHelper.class);
            var spec = method.getAnnotation(GameTest.class);
            if (spec == null || !spec.template().equals("empty")) throw new IllegalArgumentException("Not an empty-template GameTest: " + selection);
            result.add(new TestFunction(spec.batch(), selection.toLowerCase(Locale.ROOT), "icheme_fluid_focus:empty",
                    StructureUtils.getRotationForRotationSteps(spec.rotationSteps()), spec.timeoutTicks(), spec.setupTicks(),
                    spec.required(), spec.manualOnly(), spec.requiredSuccesses(), spec.attempts(), spec.skyAccess(), helper -> {
                        try { method.invoke(null, helper); }
                        catch (InvocationTargetException failed) {
                            if (failed.getCause() instanceof RuntimeException cause) throw cause;
                            if (failed.getCause() instanceof Error cause) throw cause;
                            throw new RuntimeException(failed.getCause());
                        } catch (ReflectiveOperationException invalid) { throw new RuntimeException(invalid); }
                    }));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No focused tests selected");
        return result;
    }
}
