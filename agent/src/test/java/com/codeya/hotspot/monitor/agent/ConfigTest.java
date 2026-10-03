package com.codeya.hotspot.monitor.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void defaultExcludesCoverFrameworks() {
        Config c = Config.from(null);
        assertTrue(c.excludePackages.contains("org.springframework."), "默认排除 Spring");
        assertTrue(c.excludePackages.contains("com.alibaba."), "默认排除 Druid");
        assertTrue(c.excludePackages.contains("org.apache."), "默认排除 Tomcat/Apache");
        assertTrue(c.excludePackages.contains("java."), "默认排除 JDK");
        // 业务包不受影响（packages 推导独立于 exclude）
        assertFalse(c.excludePackages.contains("com.example.app"));
    }

    @Test
    void agentArgSemicolonPackagesExpandToComma() {
        Config c = Config.from("packages=com.foo;com.bar,port=9999");
        assertEquals("com.foo,com.bar", c.packages, "agent 参数多包用分号，应展开为逗号分隔");
        assertEquals(9999, c.port, "分号后的其它参数仍应解析");
    }

    @Test
    void emptyExcludeDisablesExclusion() {
        System.setProperty("hotspot.excludePackages", "");
        try {
            Config c = Config.from(null);
            assertEquals("", c.excludePackages, "显式空串 = 不排除任何包");
        } finally {
            System.clearProperty("hotspot.excludePackages");
        }
    }
}
