package com.initialone.jhumanify.util;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 对外入口：格式化整个源码树。
 *
 * 新逻辑：
 * - 不在当前 JVM 里直接调用 google-java-format（那会触发模块封装 IllegalAccessError）
 * - 而是 fork 一个子进程，自动附带 --add-exports。
 *
 * 这样主命令的用户就不需要自己手动写 --add-exports 那串参数。
 */
public class Formatter {

    /**
     * 对 <srcRoot> 下所有 .java 做格式化。
     * 调用方式：DocAnnotator.annotate() 最后还是只要调用 Formatter.formatJava(outDir) 就行。
     */
    public static void formatJava(Path srcRoot) throws Exception {
        if (srcRoot == null) {
            System.err.println("[formatter] skip: srcRoot == null");
            return;
        }
        if (!srcRoot.toFile().isDirectory()) {
            System.err.println("[formatter] skip: not a directory: " + srcRoot.toAbsolutePath());
            return;
        }

        // 允许外部通过 -Djhumanify.format.skip=true 来完全跳过格式化（用来debug或规避内存/性能）
        if (Boolean.getBoolean("jhumanify.format.skip")) {
            System.out.println("[formatter] skip formatting due to jhumanify.format.skip=true");
            return;
        }

        // 启动子进程运行 FormatterWorker
        List<String> cmd = buildFormatterSubprocessCmd(srcRoot);

        System.out.println("[formatter] spawn formatter worker:");
        System.out.println("[formatter] " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO(); // 直接把子进程的 stdout/stderr 透到当前控制台，方便调试
        Process p = pb.start();
        int rc = p.waitFor();

        if (rc != 0) {
            throw new IllegalStateException("[formatter] formatter worker exit code " + rc);
        }
    }

    /**
     * 构建子进程命令:
     * java --add-exports jdk.compiler/...=ALL-UNNAMED ... -cp <jar/dir> com.initialone.jhumanify.util.FormatterWorker <srcDir>
     */
    private static List<String> buildFormatterSubprocessCmd(Path srcRoot) {
        List<String> cmd = new ArrayList<>();

        // 1. java 可执行路径
        //    简单策略：用当前进程的 java.home
        String javaHome = System.getProperty("java.home"); // e.g. /usr/lib/jvm/temurin-17-jre
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        cmd.add(javaBin);

        // 2. 内置的 --add-exports 解决 IllegalAccessError
        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");

        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED");

        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED");

        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED");

        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED");

        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED");

        cmd.add("--add-exports");
        cmd.add("jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");

        // (可选) 如果后续 google-java-format 访问别的包，还可以继续在这边补 add-exports

        // 3. 继承一些内存参数：
        //    把当前 JVM 的 -Xmx 复用给子进程比较合理。拿不到精确值的话可以自己给，比如 2g。
        //    这里我们就给个保守的 -Xmx2g；你也可以做成系统属性 jhumanify.format.xmx。
        String childXmx = System.getProperty("jhumanify.format.xmx", "2g");
        cmd.add("-Xmx" + childXmx);

        // 4. 传递一个标记，避免子进程又递归 spawn 自己
        cmd.add("-Djhumanify.format.skip=false");

        // 5. classpath：我们需要确保子进程能访问到
        //    - 当前可执行 fat jar 或
        //    - 当前 CLASSPATH（在开发模式下跑 `gradlew run` 时）
        //
        //    我们优先用 java.class.path 系统属性，它通常包含 fat jar 路径或者编译输出+依赖。
        String cp = System.getProperty("java.class.path");
        cmd.add("-cp");
        cmd.add(cp);

        // 6. 真正要运行的 main class
        cmd.add("com.initialone.jhumanify.util.FormatterWorker");

        // 7. 传入要格式化的源码根目录
        cmd.add(srcRoot.toAbsolutePath().toString());

        return cmd;
    }
}