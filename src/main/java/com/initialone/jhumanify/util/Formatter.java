package com.initialone.jhumanify.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Formatter {
    public static void formatTree(Path dir) throws IOException {
        // 建议重用一个实例（线程安全、无状态）
        var gjf = new com.google.googlejavaformat.java.Formatter();

        Files.walk(dir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        String src = Files.readString(p);
                        String formatted = gjf.formatSource(src); // ← 实例方法
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