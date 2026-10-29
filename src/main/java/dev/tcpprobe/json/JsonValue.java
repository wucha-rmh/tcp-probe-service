package dev.tcpprobe.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable JSON value model.
 *
 * <p>Hand-rolled on purpose: the whole service ships without a single third-party runtime
 * dependency, so the JSON codec is part of the delivered code and stays small and auditable.</p>
 */
public sealed interface JsonValue
        permits JsonValue.Obj, JsonValue.Arr, JsonValue.Str, JsonValue.Num, JsonValue.Bool, JsonValue.Null {

    /** JSON object; insertion order is preserved so responses stay readable. */
    record Obj(Map<String, JsonValue> values) implements JsonValue {
        public Obj {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public static Obj of() {
            return new Obj(Map.of());
        }

        public static Obj of(Map<String, JsonValue> values) {
            return new Obj(values);
        }

        public JsonValue get(String key) {
            return values.get(key);
        }

        public boolean has(String key) {
            return values.containsKey(key);
        }
    }

    record Arr(List<JsonValue> values) implements JsonValue {
        public Arr {
            values = List.copyOf(values);
        }

        public int size() {
            return values.size();
        }
    }

    record Str(String value) implements JsonValue {
        public Str {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Number kept as its literal text so integers do not silently lose precision. */
    record Num(String raw) implements JsonValue {
        public Num {
            Objects.requireNonNull(raw, "raw");
        }

        public double asDouble() {
            return Double.parseDouble(raw);
        }

        public boolean isIntegral() {
            return raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0;
        }
    }

    record Bool(boolean value) implements JsonValue {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);
    }

    record Null() implements JsonValue {
        public static final Null INSTANCE = new Null();
    }

    // ---------------------------------------------------------------- typed accessors

    default Obj asObj() {
        if (this instanceof Obj o) {
            return o;
        }
        throw typeError("object");
    }

    default Arr asArr() {
        if (this instanceof Arr a) {
            return a;
        }
        throw typeError("array");
    }

    default String asString() {
        if (this instanceof Str s) {
            return s.value();
        }
        throw typeError("string");
    }

    default long asLong() {
        if (this instanceof Num n && n.isIntegral()) {
            try {
                return Long.parseLong(n.raw());
            } catch (NumberFormatException e) {
                throw new JsonException("number out of long range: " + n.raw());
            }
        }
        throw typeError("integer");
    }

    default double asDouble() {
        if (this instanceof Num n) {
            return n.asDouble();
        }
        throw typeError("number");
    }

    default boolean asBoolean() {
        if (this instanceof Bool b) {
            return b.value();
        }
        throw typeError("boolean");
    }

    default boolean isNull() {
        return this instanceof Null;
    }

    private JsonException typeError(String expected) {
        return new JsonException("expected " + expected + " but found " + Json.describe(this));
    }
}
