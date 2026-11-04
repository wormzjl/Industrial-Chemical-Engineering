package com.wormzjl.icheme.science.flowsheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jetbrains.annotations.Nullable;

/**
 * What goes between a client and a block (batch 2026-11-02-process-simulator, plan section 3.4): one gzip body, a sheet in {@link FlowsheetFile}'s
 * format and its results in whatever format they keep, at most {@link #MAX_UNPACKED} unpacked, in parts where a packet needs them. A body comes from
 * another player, so nothing is believed: {@link Assembly} bounds the parts as they come, and {@link #unpack} bounds the whole before it inflates a
 * byte, then again as it inflates, since a gzip trailer can lie. Minecraft-free.
 */
public final class SheetBody {
    /** The format of the unpacked body. The sheet and the results each have their own number. */
    public static final int FORMAT = 1;
    public static final int MAX_UNPACKED = 1 << 20;
    /** Gzip's worst case on {@link #MAX_UNPACKED} that does not shrink: a stored block's five bytes per 64 KiB, and the header and trailer. */
    public static final int MAX_PACKED = MAX_UNPACKED + 1024;
    /** A part fits a client's packet, which is the smaller way. */
    public static final int PART = 30_000;
    public static final int MAX_PARTS = (MAX_PACKED + PART - 1) / PART;
    /** Translation key suffixes of a refusal. */
    public static final String TOO_LARGE = "store.too_large", DAMAGED = "store.damaged", PARTS = "store.parts";
    private static final int HEADER = 5, GZIP_LEAST = 18;

    private SheetBody() {
    }

    /** Why a body was not taken; {@code key} is a translation key suffix, as in a note. */
    public static final class Refused extends Exception {
        private final String key;

        public Refused(String key) {
            super(key);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** A body opened: the sheet's bytes, and the results' bytes, empty where it holds none. */
    public record Contents(byte[] sheet, byte[] results) {
    }

    /** Whether a sheet and results of these sizes fit one body. */
    public static boolean fits(int sheet, int results) {
        return (long) HEADER + sheet + results <= MAX_UNPACKED;
    }

    public static byte[] pack(byte[] sheet, byte[] results) {
        if (!fits(sheet.length, results.length)) throw new IllegalArgumentException("A sheet body over the bound");
        var packed = new ByteArrayOutputStream(1 << 14);
        try (var zip = new GZIPOutputStream(packed)) {
            zip.write(ByteBuffer.allocate(HEADER).put((byte) FORMAT).putInt(sheet.length).array());
            zip.write(sheet);
            zip.write(results);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return packed.toByteArray();
    }

    /** The body's parts in order; one part at the least. */
    public static List<byte[]> parts(byte[] packed) {
        var parts = new ArrayList<byte[]>();
        for (int at = 0; at == 0 || at < packed.length; at += PART) parts.add(Arrays.copyOfRange(packed, at, Math.min(packed.length, at + PART)));
        return parts;
    }

    /** Opens a body, or refuses it: over the bound without inflating, on a false trailer once it has inflated to the bound, and damaged if it does not read. */
    public static Contents unpack(byte[] packed) throws Refused {
        if (packed.length > MAX_PACKED) throw new Refused(TOO_LARGE);
        if (packed.length < GZIP_LEAST) throw new Refused(DAMAGED);
        // The trailer's size is the sender's word: it refuses a body at no cost, and proves nothing.
        long claimed = ByteBuffer.wrap(packed, packed.length - 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        if (claimed > MAX_UNPACKED) throw new Refused(TOO_LARGE);
        byte[] body;
        try (var zip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            body = zip.readNBytes(MAX_UNPACKED + 1);
        } catch (IOException damaged) {
            throw new Refused(DAMAGED);
        }
        if (body.length > MAX_UNPACKED) throw new Refused(TOO_LARGE);
        if (body.length < HEADER || body[0] != FORMAT) throw new Refused(DAMAGED);
        int sheet = ByteBuffer.wrap(body, 1, 4).getInt();
        if (sheet < 0 || sheet > body.length - HEADER) throw new Refused(DAMAGED);
        return new Contents(Arrays.copyOfRange(body, HEADER, HEADER + sheet), Arrays.copyOfRange(body, HEADER + sheet, body.length));
    }

    /** One body's parts as they come. A part out of its place, or a part or a sum over the bound, refuses the body, and keeps nothing of it. */
    public static final class Assembly {
        private final ByteArrayOutputStream got = new ByteArrayOutputStream();
        private int next, count;

        /** The body once its last part lands, or null. Part 0 starts a new one, whatever came before. */
        public byte @Nullable [] add(int index, int count, byte[] part) throws Refused {
            if (index == 0) {
                got.reset();
                next = 0;
                this.count = count;
            }
            boolean over = count > MAX_PARTS || got.size() + part.length > MAX_PACKED;
            if (over || count < 1 || count != this.count || index != next || part.length > PART) {
                got.reset();
                next = 0;
                throw new Refused(over ? TOO_LARGE : PARTS);
            }
            got.write(part, 0, part.length);
            if (++next < count) return null;
            var whole = got.toByteArray();
            got.reset();
            next = 0;
            return whole;
        }
    }
}
