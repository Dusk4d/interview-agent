package com.dusk4d.interview.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 测试夹具读取工具。 */
public final class Fixtures {

    private Fixtures() {
    }

    /** 读取 classpath 下的文本夹具。 */
    public static String text(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("夹具不存在：" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(String name) {
        return text(name).getBytes(StandardCharsets.UTF_8);
    }
}
