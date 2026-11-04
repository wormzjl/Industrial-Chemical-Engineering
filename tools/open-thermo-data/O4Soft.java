package com.wormzjl.icheme;

import java.util.Objects;

/**
 * Throwaway helper of batch 2026-11-04-open-thermo-data (O4): a non-throwing stand-in for JUnit's {@code assertEquals}, so a
 * pinned test reports every pinned value in one run (`PIN` lines on standard output: the caller's line, the message, the
 * pinned value, the value now, whether it holds). Used on temporary copies of the sizing tests (`O4SoftPins.mjs` makes
 * them); never committed under {@code src/test}.
 */
public final class O4Soft {
    private O4Soft() {}
    private static void report(Object expected, Object actual, boolean holds, Object message) {
        var caller = StackWalker.getInstance().walk(s -> s.filter(f -> !f.getClassName().equals(O4Soft.class.getName())).findFirst()).orElseThrow();
        System.out.println("PIN\t" + caller.getClassName().replaceAll(".*\\.", "") + ":" + caller.getLineNumber() + "\t" + message
                + "\t" + expected + "\t" + actual + "\t" + (holds ? "ok" : "MOVED"));
    }
    public static void eq(double expected, double actual, double delta) { eq(expected, actual, delta, ""); }
    public static void eq(double expected, double actual, double delta, String message) {
        report(expected, actual, Double.compare(expected, actual) == 0 || Math.abs(expected - actual) <= delta, message);
    }
    public static void eq(Object expected, Object actual) { eq(expected, actual, ""); }
    public static void eq(Object expected, Object actual, java.util.function.Supplier<String> message) { eq(expected, actual, message.get()); }
    public static void eq(double expected, double actual, double delta, java.util.function.Supplier<String> message) { eq(expected, actual, delta, message.get()); }
    public static void eq(Object expected, Object actual, String message) {
        boolean holds = expected instanceof Number e && actual instanceof Number a ? e.doubleValue() == a.doubleValue() : Objects.equals(expected, actual);
        report(expected, actual, holds, message);
    }
}
