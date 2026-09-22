package com.dusk4d.interview.testkit;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 自带的 JUnit Platform 启动器（测试入口）。
 *
 * <p>为什么不用 maven-surefire：本机离线仓库里 surefire 的 provider 依赖
 * （junit-platform-launcher 1.11.4）只有 POM 没有 jar，无法在离线模式下解析。
 * 直接使用 junit-platform-launcher API 只需 launcher + engine，两者本地都有，
 * 而且换到任何有网环境也能照常使用（脚本会优先尝试 surefire）。
 *
 * <p>用法：{@code java -cp <classpath> com.dusk4d.interview.testkit.TestRunner [测试类FQN...]}
 * <ul>
 *   <li>不带参数：扫描 target/test-classes 下所有测试类（排除 {@code @Tag("live")}）。</li>
 *   <li>带参数：只运行指定的测试类。</li>
 * </ul>
 */
public final class TestRunner {

    private TestRunner() {
    }

    public static void main(String[] args) {
        Path testClasses = resolveTestClasses();
        List<String> classNames = args.length > 0 ? List.of(args) : discover(testClasses);
        if (classNames.isEmpty()) {
            System.out.println("[TestRunner] 未发现测试类，请先执行 mvn test-compile");
            System.exit(2);
        }

        // 断言失败信息包含中文（用例名/内容），必须把输出流切到 UTF-8，
        // 否则在 GBK 控制台上会看到乱码，无法定位问题。
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, java.nio.charset.StandardCharsets.UTF_8));

        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(classNames.stream().map(DiscoverySelectors::selectClass).toList())
                .filters(TagFilter.excludeTags("live"));
        LauncherDiscoveryRequest request = builder.build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(System.out,
                java.nio.charset.StandardCharsets.UTF_8), true);
        summary.printTo(out);

        long failed = summary.getTotalFailureCount();
        System.out.printf("%n[TestRunner] classes=%d tests=%d succeeded=%d failed=%d skipped=%d time=%dms%n",
                classNames.size(),
                summary.getTestsFoundCount(),
                summary.getTestsSucceededCount(),
                summary.getTestsFailedCount(),
                summary.getTestsSkippedCount(),
                summary.getTimeFinished() - summary.getTimeStarted());

        if (failed > 0) {
            summary.printFailuresTo(out, 40);
            System.out.println("[TestRunner] 结果：失败 " + failed + " 项");
            System.exit(1);
        }
        if (summary.getTestsFoundCount() == 0) {
            System.out.println("[TestRunner] 结果：没有执行任何测试，视为失败");
            System.exit(3);
        }
        System.out.println("[TestRunner] 结果：全部通过");
        System.exit(0);
    }

    /** 定位 target/test-classes 根目录。 */
    private static Path resolveTestClasses() {
        Path direct = Path.of("target", "test-classes").toAbsolutePath();
        if (Files.isDirectory(direct)) {
            return direct;
        }
        throw new IllegalStateException("找不到 target/test-classes，请先执行 mvn test-compile");
    }

    /** 扫描测试类：文件名以 Test 结尾且含 @Test 注解。 */
    private static List<String> discover(Path root) {
        List<String> names = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Test.class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .sorted()
                    .forEach(p -> {
                        String relative = root.relativize(p).toString()
                                .replace('\\', '.')
                                .replace('/', '.');
                        names.add(relative.substring(0, relative.length() - ".class".length()));
                    });
        } catch (Exception e) {
            throw new IllegalStateException("扫描测试类失败：" + e.getMessage(), e);
        }
        return names;
    }
}
