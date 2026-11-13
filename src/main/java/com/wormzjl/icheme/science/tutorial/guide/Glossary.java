package com.wormzjl.icheme.science.tutorial.guide;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The glossary (TUTORIAL_SYSTEM_PLAN.md section 3.2): a professional term is defined once in plain words, on the step that
 * first needs it; afterwards it is underlined in every tutorial text and its hover shows the definition. A term's word is
 * {@code tutorial.icheme.term.<id>}, its definition {@code tutorial.icheme.term.<id>.def} (at most 25 words); its defining
 * step is the guide's ({@link Guide#terms}).
 */
public final class Glossary {
    public static final String KEY = "tutorial.icheme.term.";

    private Glossary() {
    }

    public static String wordKey(String term) {
        return KEY + term;
    }

    public static String definitionKey(String term) {
        return KEY + term + ".def";
    }

    /**
     * Each term's first marked use in guide order, as {@code lang} gives the steps' sentences: term to step id. A term the
     * guide's sentences never mark is missing.
     */
    public static Map<String, String> firstUses(Guide guide, Function<String, @Nullable String> lang) {
        var first = new LinkedHashMap<String, String>();
        for (var chapter : guide.chapters())
            for (var step : chapter.steps()) {
                var text = lang.apply(chapter.key(step));
                if (text == null) continue;
                for (var span : Marked.parse(text).spans()) first.putIfAbsent(span.term(), chapter.id(step));
            }
        return first;
    }
}
