package dev.tcpprobe.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 不可变的 JSON 值模型。
 *
 * <p>刻意手写实现：整个服务交付时不带任何第三方运行时依赖，所以 JSON 编解码器
 * 本身就是交付代码的一部分，保持精简且可审计。</p>
 */
public sealed interface JsonValue
        permits JsonValue.Obj, JsonValue.Arr, JsonValue.Str, JsonValue.Num, JsonValue.Bool, JsonValue.Null {

    /** JSON 对象；保留插入顺序，因此响应保持可读。 */
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

    /** 数字以其字面量文本保存，使整数不会静默丢失精度。 */
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

    // ---------------------------------------------------------------- 类型化访问器

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
