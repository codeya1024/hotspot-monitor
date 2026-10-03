package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void escapesStrings() {
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"a\\nb\"", Json.write("a\nb"));
        assertEquals("\"a\\tb\"", Json.write("a\tb"));
    }

    @Test
    void writesNestedMapAndList() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("ok", true);
        root.put("count", 3L);
        root.put("items", Arrays.asList("x", "y"));
        root.put("null", null);
        String json = Json.write(root);
        assertEquals("{\"ok\":true,\"count\":3,\"items\":[\"x\",\"y\"],\"null\":null}", json);
    }

    @Test
    void handlesUnicodeControl() {
        String json = Json.write("a\u0001b");
        assertTrue(json.contains("\\u0001"), "控制字符应转义: " + json);
    }
}
