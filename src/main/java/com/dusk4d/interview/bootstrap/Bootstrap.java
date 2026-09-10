package com.dusk4d.interview.bootstrap;

import com.dusk4d.interview.InterviewAgentApplication;
import org.springframework.boot.SpringApplication;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可执行 jar 的启动器。
 *
 * <p>shade 打包出来的 fat jar 里，Spring Boot 的类与依赖类处于同一层级，
 * 直接使用 Spring Boot 的 JarLauncher 并不适用，因此这里用普通 main 启动，
 * 同时把自身所在目录作为 {@code app.home} 暴露给配置使用，
 * 保证以任意工作目录启动时，data/ 目录的落点都是可预期的。
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public static void main(String[] args) {
        Path home = resolveHome();
        System.setProperty("app.home", home.toString());
        SpringApplication.run(InterviewAgentApplication.class, args);
    }

    /** 解析应用主目录：优先 jar 所在目录，其次项目根目录，最后当前工作目录。 */
    static Path resolveHome() {
        try {
            var source = Bootstrap.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(location)) {
                    Path parent = location.getParent();
                    if (parent != null) {
                        return parent.toAbsolutePath().normalize();
                    }
                }
                if (Files.isDirectory(location)) {
                    Path dir = location.toAbsolutePath().normalize();
                    Path cursor = dir;
                    while (cursor != null) {
                        if (Files.isRegularFile(cursor.resolve("pom.xml"))) {
                            return cursor;
                        }
                        cursor = cursor.getParent();
                    }
                    return dir;
                }
            }
        } catch (Exception ignored) {
            // 忽略并回退到工作目录
        }
        return new File(".").getAbsoluteFile().toPath().normalize();
    }
}
