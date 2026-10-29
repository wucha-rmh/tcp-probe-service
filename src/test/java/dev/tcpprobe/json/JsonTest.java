package dev.tcpprobe.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesNestedStructures() {
        JsonValue value = Json.parse("""
                {"service": "tcp-probe", "port": 8080, "ratio": 0.5, "enabled": true, "none": null,
                 "targets": [{"host": "example.com", "port": 443}, "10.0.0.1:22"]}
                """);
        JsonValue.Obj obj = value.asObj();
        assertEquals("tcp-probe", obj.get("service").asString());
        assertEquals(8080L, obj.get("port").asLong());
        assertEquals(0.5, obj.get("ratio").asDouble());
        assertTrue(obj.get("enabled").asBoolean());
        assertTrue(obj.get("none").isNull());
        assertEquals(2, obj.get("targets").asArr().size());
        assertEquals("example.com", obj.get("targets").asArr().values().get(0).asObj().get("host").asString());
    }

    @Test
    void decodesEscapesAndUnicode() {
        JsonValue value = Json.parse("\"line\\nbreak \\u4f60\\u597d \\\"quoted\\\" \\\\\"");
        assertEquals("line\nbreak 你好 \"quoted\" \\", value.asString());
    }

    @Test
    void writesRoundTrippableJson() {
        Map<String, Object> source = Json.map(
                "name", "probe",
                "count", 3,
                "enabled", true,
                "targets", List.of("a:1", "b:2"),
                "nested", Json.map("k", "v \"q\""));
        String encoded = Json.write(source);
        JsonValue decoded = Json.parse(encoded);
        assertEquals("probe", decoded.asObj().get("name").asString());
        assertEquals(3L, decoded.asObj().get("count").asLong());
        assertEquals(2, decoded.asObj().get("targets").asArr().size());
        assertEquals("v \"q\"", decoded.asObj().get("nested").asObj().get("k").asString());
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(JsonException.class, () -> Json.parse("{} extra"));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": 1, \"a\": 2}"));
    }

    @Test
    void rejectsDeepNesting() {
        String deep = "[".repeat(200) + "]".repeat(200);
        JsonException e = assertThrows(JsonException.class, () -> Json.parse(deep));
        assertTrue(e.getMessage().contains("64 levels"), e.getMessage());
    }

    @Test
    void rejectsTruncatedInput() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": "));
    }

    @Test
    void typeMismatchProducesReadableError() {
        JsonValue value = Json.parse("\"text\"");
        JsonException e = assertThrows(JsonException.class, value::asObj);
        assertTrue(e.getMessage().contains("string"), e.getMessage());
    }

    @Test
    void describeNamesTheKind() {
        assertEquals("null", Json.describe(JsonValue.Null.INSTANCE));
        assertEquals("string", Json.describe(new JsonValue.Str("x")));
        assertFalse(Json.describe(new JsonValue.Arr(List.of())).isBlank());
    }
}
