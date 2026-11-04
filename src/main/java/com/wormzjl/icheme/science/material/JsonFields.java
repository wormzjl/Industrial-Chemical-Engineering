package com.wormzjl.icheme.science.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The one reader of typed fields in the material data's JSON and the template files. Every failure is an {@link IllegalArgumentException}
 * whose message starts with the field's label (its key unless the caller names it), so a loader that prefixes the
 * file it read reports the file and the field.
 */
public final class JsonFields {
    /** The longest text field the material data may carry. */
    static final int MAX_TEXT=2048;

    private JsonFields() {}

    static JsonElement required(JsonObject o,String key){return required(o,key,key);}
    private static JsonElement required(JsonObject o,String key,String label) {
        if(o==null||!o.has(key)||o.get(key).isJsonNull())throw new IllegalArgumentException(label+": required field");
        return o.get(key);
    }
    static JsonObject object(JsonObject o,String key) {
        var e=required(o,key);
        if(!e.isJsonObject())throw new IllegalArgumentException(key+": expected object");
        return e.getAsJsonObject();
    }
    public static JsonArray array(JsonObject o,String key){return array(o,key,key);}
    static JsonArray array(JsonObject o,String key,String label) {
        var e=required(o,key,label);
        if(!e.isJsonArray())throw new IllegalArgumentException(label+": expected array");
        return e.getAsJsonArray();
    }
    /** A nonblank string of at most {@link #MAX_TEXT} characters. */
    public static String text(JsonObject o,String key){return text(required(o,key),key);}
    private static String text(JsonElement e,String label) {
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException(label+": expected string");
        String s=e.getAsString();
        if(s.isBlank()||s.length()>MAX_TEXT)throw new IllegalArgumentException(label+": invalid text");
        return s;
    }
    /**
     * A record's declared revision: a token of letters, digits, '_', '.' and '-' that starts with a letter or digit, at
     * most 64 characters, so the separators of a package identity ({@code @ , ; / =}) read back unambiguously.
     */
    static String revision(JsonObject o,String key){return token(text(o,key),key);}
    static String token(String revision,String label) {
        if(!REVISION.matcher(revision).matches())throw new IllegalArgumentException(label+": \""+revision
                +"\" must be a token of letters, digits, '_', '.' and '-', at most 64 characters");
        return revision;
    }
    private static final java.util.regex.Pattern REVISION=java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    static boolean bool(JsonObject o,String key) {
        var e=required(o,key);
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(key+": expected boolean");
        return e.getAsBoolean();
    }
    /** A finite number. */
    public static double number(JsonObject o,String key){return number(required(o,key),key);}
    private static double number(JsonElement e,String label) {
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(label+": expected number");
        double d=e.getAsDouble();
        if(!Double.isFinite(d))throw new IllegalArgumentException(label+": nonfinite");
        return d;
    }
    /** A finite number above zero. */
    public static double positive(JsonObject o,String key) {
        double d=number(o,key);
        if(d<=0)throw new IllegalArgumentException(key+": must be positive");
        return d;
    }
    /** A finite number with an exact {@code int} value. */
    public static int integer(JsonObject o,String key) {
        number(o,key);
        try{return o.get(key).getAsBigDecimal().intValueExact();}
        catch(ArithmeticException e){throw new IllegalArgumentException(key+": integer required",e);}
    }
    /** An array of texts. */
    static List<String> strings(JsonObject o,String key){return strings(o,key,key);}
    static List<String> strings(JsonObject o,String key,String label) {
        var result=new ArrayList<String>();
        for(var e:array(o,key,label))result.add(text(e,label));
        return List.copyOf(result);
    }
    /** An array of finite numbers. */
    static List<Double> numbers(JsonObject o,String key) {
        var result=new ArrayList<Double>();
        for(var e:array(o,key))result.add(number(e,key));
        return List.copyOf(result);
    }
}
