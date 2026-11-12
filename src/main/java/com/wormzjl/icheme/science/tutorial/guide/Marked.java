package com.wormzjl.icheme.science.tutorial.guide;

import java.util.ArrayList;
import java.util.List;

/**
 * A tutorial text with its marked terms: the text marks a glossary term as {@code {id:shown words}}; {@link #parse} returns
 * the plain text and where each term stands in it. A brace with no colon, or one not closed, stays as written.
 */
public record Marked(String text, List<Span> spans) {
    /** A term {@code term} shown from {@code start} (inclusive) to {@code end} (exclusive) of the plain text. */
    public record Span(int start, int end, String term) {
    }

    public Marked {
        spans = List.copyOf(spans);
    }

    public static Marked parse(String marked) {
        var text = new StringBuilder();
        var spans = new ArrayList<Span>();
        int i = 0;
        while (i < marked.length()) {
            char ch = marked.charAt(i);
            int close = ch == '{' ? marked.indexOf('}', i) : -1;
            int colon = close < 0 ? -1 : marked.indexOf(':', i);
            if (close > 0 && colon > i + 1 && colon < close - 1) {
                String term = marked.substring(i + 1, colon), shown = marked.substring(colon + 1, close);
                if (Guide.ID.matcher(term).matches()) {
                    spans.add(new Span(text.length(), text.length() + shown.length(), term));
                    text.append(shown);
                    i = close + 1;
                    continue;
                }
            }
            text.append(ch);
            i++;
        }
        return new Marked(text.toString(), spans);
    }

    /** The term marked at character {@code index} of the plain text, or null. */
    public String termAt(int index) {
        for (var span : spans) if (index >= span.start() && index < span.end()) return span.term();
        return null;
    }
}
