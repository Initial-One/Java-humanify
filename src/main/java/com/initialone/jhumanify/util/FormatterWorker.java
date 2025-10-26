package com.initialone.jhumanify.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

// import google-java-format stuff
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;

/**
 * 这个类只在子进程里运行。
 * 目标：对传入的源码目录下的所有 .java 文件进行 google-java-format 格式化。
 *
 * 用法（由父进程通过 ProcessBuilder 调）:
 *   java <exports...> -cp <ourJar> com.initialone.jhumanify.util.FormatterWorker <srcDir>
 */
public class FormatterWorker {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("[formatter-worker] usage: FormatterWorker <srcDir>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("[formatter-worker] not a dir: " + root.toAbsolutePath());
            System.exit(2);
        }

        List<Path> javaFiles = collectJavaFiles(root);
        System.out.println("[formatter-worker] formatting " + javaFiles.size() + " files under " + root);

        Formatter fmt = new Formatter(); // google-java-format formatter

        int ok = 0;
        int fail = 0;

        for (Path file : javaFiles) {
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                String formatted;
                try {
                    formatted = fmt.formatSource(original);
                } catch (FormatterException fe) {
                    // 如果 google-java-format 无法解析/格式化某个奇怪文件（例如不完整代码/语法损坏）
                    // 我们选择跳过，但不让整个流程崩掉
                    System.err.println("[formatter-worker] skip (FormatterException): " + file);
                    fail++;
                    continue;
                }

                if (!formatted.equals(original)) {
                    Files.writeString(file, formatted, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
                ok++;
            } catch (Throwable t) {
                System.err.println("[formatter-worker] fail " + file + " : " + t);
                fail++;
            }
        }

        System.out.println("[formatter-worker] done. ok=" + ok + " fail=" + fail);
    }

    private static List<Path> collectJavaFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>(4096);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (file.toString().endsWith(".java")) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}