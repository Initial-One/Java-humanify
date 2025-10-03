package com.initialone.jhumanify.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class Formatter {

    // 可选：跳过一些不需要格式化的目录
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", "target", "build", "out", "generated-sources", "maven-status"
    );

    /** 供 DocAnnotator.annotate(List<Path>) 直接调用 */
    public static void formatJava(List<Path> roots) {
        if (roots == null) return;
        for (Path root : roots) {
            try {
                formatTree(root);
            } catch (IOException ioe) {
                System.err.println("[format] io " + root + " -> " + ioe.getMessage());
            }
        }
    }

    /** 便于其他地方按需调用 */
    public static void formatJava(Path... roots) {
        if (roots == null) return;
        for (Path root : roots) {
            try {
                formatTree(root);
            } catch (IOException ioe) {
                System.err.println("[format] io " + root + " -> " + ioe.getMessage());
            }
        }
    }

    /** 保留你现有的单目录格式化实现，并做了点健壮化 */
    public static void formatTree(Path dir) throws IOException {
        if (dir == null) return;

        // google-java-format 的实例（无状态、线程安全）
        var gjf = new com.google.googlejavaformat.java.Formatter();

        try (Stream<Path> stream = Files.walk(dir)) {
            stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !shouldSkip(p))
                    .forEach(p -> {
                        try {
                            String src = Files.readString(p);
                            String formatted = gjf.formatSource(src);
                            if (!formatted.equals(src)) {
                                Files.writeString(p, formatted);
                            }
                        } catch (com.google.googlejavaformat.java.FormatterException fe) {
                            System.err.println("[format] skip " + p + " -> " + fe.getMessage());
                        } catch (IOException ioe) {
                            System.err.println("[format] io " + p + " -> " + ioe.getMessage());
                        }
                    });
        }
    }

    private static boolean shouldSkip(Path p) {
        for (Path part : p) {
            if (SKIP_DIRS.contains(part.toString())) return true;
        }
        return false;
    }
}