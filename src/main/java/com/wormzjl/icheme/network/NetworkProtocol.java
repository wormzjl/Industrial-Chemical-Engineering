package com.wormzjl.icheme.network;

/**
 * The mod's one wire protocol version (codebase review P3-22). Every payload registrar of the mod is opened with it
 * ({@code event.registrar(NetworkProtocol.VERSION)}), one registrar per network class as before.
 *
 * <p>NeoForge negotiates each payload channel (the payload's type id) at login: a channel's version is the string of the
 * registrar it was registered on, and a client and a server whose versions differ on any channel, or that lack a
 * channel the other side requires, are refused with the channels and the reasons listed. So client and server must
 * be built from the same version of the mod. Wire formats need no compatibility work (AGENTS.md): bump this at any
 * change of any payload's bytes, type ids or registration, and change its rows in
 * {@code documentation/reference/FORMATS.md}.</p>
 *
 * <p>History: before {@code icheme-1} each registrar carried its own string ({@code fluid-24}, {@code equipment-23},
 * {@code assembly-6}, {@code machine-4}, {@code control-5}, {@code lines-1}, {@code rotation-1}, {@code tutorial-1},
 * {@code sight-glass-3}, {@code mover-4}, {@code flare-2}, {@code still-1}, {@code instruments-1},
 * {@code pipeline-tool-5} and the process simulator's {@code 3}); the first value differs from all of them.
 * {@code icheme-2}: a steam turbine's inlet class gained LP, declared first, so every class's ordinal on the wire moved
 * (batch 2026-11-15-equipment-visual-design).
 * {@code icheme-3}: the conversion rules sync, {@code FluidNetwork.ConversionRulesSync} (batch 2026-11-15-fluid-interfaces, W5), and the alarm beacon's
 * edit and view (same batch, W9).</p>
 */
public final class NetworkProtocol {
    /** The version every registrar of the mod carries. */
    public static final String VERSION = "icheme-3";

    private NetworkProtocol() {
    }
}
