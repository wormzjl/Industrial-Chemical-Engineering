package com.wormzjl.icheme.science.fluid.conversion;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The fluid conversion rules the last datapack reload published (batch 2026-11-12-fluid-conversion); none before the
 * first. The client keeps its own copy, the rules the server last sent ({@link #client}, batch 2026-11-15-fluid-interfaces,
 * W5): at login and after each reload; none while it is in no world. A client listener (JEI) hears each new copy.
 */
public final class ConversionRuntime {
    private static volatile ConversionRules active=ConversionRules.NONE;
    private static volatile ConversionRules client=ConversionRules.NONE;
    private static final List<Consumer<ConversionRules>> CLIENT_LISTENERS=new CopyOnWriteArrayList<>();
    private ConversionRuntime() {}
    public static ConversionRules active(){return active;}
    public static void publish(ConversionRules rules){active=Objects.requireNonNull(rules);}
    public static void reset(){active=ConversionRules.NONE;}

    /** The rules the server last sent this client; none outside a world. */
    public static ConversionRules client(){return client;}
    /** The client takes the rules the server sent, and tells its listeners. */
    public static void publishClient(ConversionRules rules){client=Objects.requireNonNull(rules);for(var l:CLIENT_LISTENERS)l.accept(rules);}
    /** The client left its world: no rules. */
    public static void resetClient(){publishClient(ConversionRules.NONE);}
    /** {@code listener} hears every copy the client takes from now on. */
    public static void listenClient(Consumer<ConversionRules> listener){CLIENT_LISTENERS.add(Objects.requireNonNull(listener));}
}
