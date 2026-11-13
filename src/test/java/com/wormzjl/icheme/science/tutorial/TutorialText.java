package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** The English lines and the teaching rules' counts (TUTORIAL_SYSTEM_PLAN.md section 4), for the tutorial's tests. */
final class TutorialText {
    /** A sentence ends at . ! or ? before a capital letter or the end: "a ? key" is no end. */
    private static final Pattern END = Pattern.compile("[.!?](?=\\s+[A-Z]|\\s*$)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private TutorialText() {
    }

    static Map<String, String> english() {
        try (var in = TutorialText.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            var lang = new LinkedHashMap<String, String>();
            for (var e : json.entrySet()) lang.put(e.getKey(), e.getValue().getAsString());
            return lang;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static long sentences(String text) {
        return END.matcher(text.strip()).results().count();
    }

    static int words(String text) {
        int n = 0;
        for (var token : text.strip().split("\\s+")) if (WORD.matcher(token).find()) n++;
        return n;
    }
}
