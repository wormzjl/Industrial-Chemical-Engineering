package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * How wide a text is, where it is cut and where it wraps: the game font in the game ({@link GuiCanvas#measure}), a
 * fixed-width font in tests and the render tool.
 */
public interface Measure {
    /** A line's height, px. */
    int LINE = 10;

    int width(String text);

    /** The longest start of {@code text} no wider than {@code width}. */
    default String cut(String text, int width) {
        if (width(text) <= width) return text;
        int n = text.length();
        while (n > 0 && width(text.substring(0, n)) > width) n--;
        return text.substring(0, n);
    }

    /** {@code text} in lines no wider than {@code width}, broken at spaces where it can; a word too long is cut. */
    default List<String> wrap(String text, int width) {
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (width(trial) <= width) {
                line.setLength(0);
                line.append(trial);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (rest.length() > 1 && width(rest) > width) {
                String head = cut(rest, width);
                if (head.isEmpty()) head = rest.substring(0, 1);
                lines.add(head);
                rest = rest.substring(head.length());
            }
            line.append(rest);
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** Every character {@code charWidth} px wide. */
    static Measure fixed(int charWidth) {
        return text -> text.length() * charWidth;
    }
}
