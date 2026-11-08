package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.Sensor;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * An installed accessory or an equipment-owned channel; neither choice uses a position as identity.
 */
record ControlInput(
        UUID instance,
        long host,
        long source,
        String channel,
        Sensor.Kind kind,
        String role,
        Sensor.Installed physical) {
    record Key(long host, String mount) {}

    ControlInput {
        Objects.requireNonNull(instance);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(role);
        if (host <= 0 || source < 0 || channel.isBlank() || channel.length() > 160)
            throw new IllegalArgumentException("Invalid control input");
    }

    Key key() {
        return new Key(host, channel);
    }

    static ControlInput installed(Sensor.Installed sensor) {
        return new ControlInput(
                sensor.instance(),
                sensor.key().host(),
                sensor.key().host(),
                sensor.key().mount(),
                sensor.kind(),
                "",
                sensor);
    }

    static ControlInput builtIn(
            long host, long source, String channel, Sensor.Kind kind, String role) {
        var id =
                UUID.nameUUIDFromBytes(
                        ("icheme:equipment-input:" + host + ":" + channel)
                                .getBytes(StandardCharsets.UTF_8));
        return new ControlInput(id, host, source, channel, kind, role, null);
    }

    String label(ControlFacts facts) {
        return physical == null
                ? String.format(Locale.ROOT, "EQ-%04d / %s", host, channel.replace('_', ' '))
                : facts.sensorId(instance).label() + " / " + kind.name();
    }
}
