package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.ast.DocAnnotator;
import com.initialone.jhumanify.llm.DocClient;
import com.initialone.jhumanify.llm.LlmOptions;
import com.initialone.jhumanify.llm.OpenAiDocClient;
import com.initialone.jhumanify.llm.RuleDocClient;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * AnnotateCmd (分批版):
 *
 * 原始行为：直接对整个 srcDir 跑 DocAnnotator -> 会把几千上万类全进内存，用 JavaParser 构 AST，超大 APK 时直接 OOM。
 *
 * 新行为：
 * 1. 先列出 srcDir 下面所有 .java 文件。
 * 2. 按批次 (annotateBatchSize) 处理：
 *    - 把这一批文件复制到临时工作目录 (保持相对路径结构)
 *    - 对该临时目录调用 DocAnnotator.annotate()
 *    - 把临时目录里的更新结果复制回原目录，覆盖
 *    - 删除临时目录 (除非 --annotate-keep-temp)
 *    - System.gc() 释放 AST
 *
 * 这样每次只让 DocAnnotator 看到几百个文件，内存峰值大幅下降，避免 OOM。
 *
 * 现有 CLI 兼容：保留 --lang / --style / llm.provider 等。
 * 新增：
 *   --annotate-batch-size
 *   --annotate-temp-root
 *   --annotate-keep-temp
 */
@CommandLine.Command(
        name = "annotate",
        description = "Add Javadoc summaries for classes and methods."
)
public class AnnotateCmd implements Runnable {

    @CommandLine.Mixin
    LlmOptions llm;

    @CommandLine.Option(
            names = {"-s", "--src"},
            required = true,
            description = "Input source dir (already deobfuscated Java)"
    )
    String srcDir;

    @CommandLine.Option(
            names = {"--lang"},
            description = "en or zh (default: en)"
    )
    String lang = "en";

    @CommandLine.Option(
            names = {"--style"},
            description = "concise or detailed (default: concise)"
    )
    String style = "concise";

    // ==== 新增的内存友好参数 ====

    @CommandLine.Option(
            names = {"--annotate-batch-size"},
            description = "Max .java files per batch (default: ${DEFAULT-VALUE})"
    )
    int annotateBatchSize = 300;

    @CommandLine.Option(
            names = {"--annotate-temp-root"},
            description = "Directory to place temporary per-batch workspace (default: system temp)"
    )
    String annotateTempRoot;

    @CommandLine.Option(
            names = {"--annotate-keep-temp"},
            description = "Keep per-batch temp dirs for debugging (default: ${DEFAULT-VALUE})"
    )
    boolean keepTemp = false;

    public void run() {
        try {
            Path srcRoot = Paths.get(srcDir);
            if (!Files.isDirectory(srcRoot)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Source dir is not a directory: " + srcRoot.toAbsolutePath()
                );
            }

            // 控制并发：这是你原本就有的逻辑
            int effectiveConcurrent = Math.max(1, llm.maxConcurrent);
            System.setProperty("jhumanify.doc.llmConcurrent", String.valueOf(effectiveConcurrent));

            // 选择 DocClient 实现
            DocClient client = buildDocClient(llm);

            // 收集所有 .java 文件（用相对路径方便后面镜像结构）
            List<Path> javaFiles = listAllJavaFiles(srcRoot);
            System.out.println("[annotate] total java files: " + javaFiles.size());

            if (javaFiles.isEmpty()) {
                System.out.println("[annotate] nothing to annotate.");
                return;
            }

            // 分批处理
            int batchSize = Math.max(1, annotateBatchSize);
            int total = javaFiles.size();
            int processed = 0;
            int batchIndex = 0;

            for (int start = 0; start < total; start += batchSize) {
                int end = Math.min(start + batchSize, total);
                List<Path> batch = javaFiles.subList(start, end);

                batchIndex++;
                System.out.printf(
                        "[annotate] batch #%d: files %d..%d / %d%n",
                        batchIndex, start, end - 1, total
                );

                // 为这个 batch 创建一个临时工作目录
                Path workRoot = createBatchWorkspaceRoot(batchIndex);
                try {
                    // 1. 把 batch 里的文件复制到 workRoot，保持相对路径结构
                    mirrorBatchIntoWorkspace(srcRoot, workRoot, batch);

                    // 2. 只对 workRoot 跑 DocAnnotator（小范围，内存压力低）
                    DocAnnotator annotator = new DocAnnotator(client, lang, style);
                    annotator.annotate(workRoot);

                    // 3. 把 workRoot 里的文件拷回 srcRoot，覆盖原文件
                    syncWorkspaceBackToSource(srcRoot, workRoot);

                } finally {
                    // 4. 清理临时目录，释放文件 & AST
                    if (!keepTemp) {
                        safeRecursiveDelete(workRoot);
                    } else {
                        System.out.println("[annotate] keep temp dir: " + workRoot);
                    }
                }

                processed += batch.size();

                // 主动 hint GC，减少堆峰值累积
                System.gc();
            }

            System.out.println("[annotate] done. processed files=" + processed);

        } catch (Exception e) {
            System.err.println("[annotate] failed: " + e.getMessage());
            e.printStackTrace();
            // 如果你希望命令行返回非 0，可以保留 exit
            // System.exit(2);
        }
    }

    /**
     * 根据 llm.provider 创建具体的 DocClient 实现。
     * （照搬你原始代码的逻辑，外加 deepseek/local 两种）
     */
    private static DocClient buildDocClient(LlmOptions llm) {
        DocClient client;
        if ("openai".equalsIgnoreCase(llm.provider)) {
            client = new OpenAiDocClient(llm.model, llm.timeoutSec, 256, 0.2);
        } else if ("deepseek".equalsIgnoreCase(llm.provider)) {
            client = new com.initialone.jhumanify.llm.DeepSeekDocClient(
                    null,        // baseUrl or apiKey override? 你的旧代码传的是 null
                    llm.model,
                    llm.timeoutSec,
                    256,
                    0.2
            );
        } else if ("local".equalsIgnoreCase(llm.provider)) {
            client = new com.initialone.jhumanify.llm.LocalDocClient(
                    llm.localApi,
                    llm.model,
                    llm.timeoutSec,
                    256,
                    0.2
            );
        } else {
            client = new RuleDocClient();
        }
        return client;
    }

    /**
     * 遍历 srcRoot，列出所有 .java 文件，以相对路径形式存储。
     */
    private static List<Path> listAllJavaFiles(Path srcRoot) throws IOException {
        List<Path> out = new ArrayList<>(4096);
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // 存相对路径，后面好重建结构
                if (file.toString().endsWith(".java")) {
                    out.add(srcRoot.relativize(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /**
     * 为当前批次创建工作根目录:
     * - 如果用户通过 --annotate-temp-root 指定了目录，就在那下面建一个子目录
     * - 否则用系统临时目录
     *
     * 目录名示例: jhumanify-annotate-batch-3-<random>
     */
    private Path createBatchWorkspaceRoot(int batchIndex) throws IOException {
        Path baseRoot;
        if (annotateTempRoot != null && !annotateTempRoot.isBlank()) {
            baseRoot = Paths.get(annotateTempRoot);
        } else {
            baseRoot = Paths.get(System.getProperty("java.io.tmpdir"));
        }
        String dirName = String.format("jhumanify-annotate-batch-%d-%s",
                batchIndex, UUID.randomUUID().toString().substring(0, 8));
        Path workRoot = baseRoot.resolve(dirName);
        Files.createDirectories(workRoot);
        return workRoot;
    }

    /**
     * 把一批相对路径的 .java 文件从 srcRoot 复制到 workRoot，并确保父目录存在。
     */
    private static void mirrorBatchIntoWorkspace(Path srcRoot, Path workRoot, List<Path> batchRelPaths)
            throws IOException {
        for (Path rel : batchRelPaths) {
            Path srcFile = srcRoot.resolve(rel);
            Path dstFile = workRoot.resolve(rel);
            Files.createDirectories(dstFile.getParent());
            Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 把 workRoot 下（批次中的那些 .java）复制回 srcRoot，覆盖原文件。
     * 我们只复制 .java 文件，避免把一些临时目录里的别的东西（比如 caches）污染源目录。
     */
    private static void syncWorkspaceBackToSource(Path srcRoot, Path workRoot) throws IOException {
        Files.walkFileTree(workRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                Path rel = workRoot.relativize(file);
                Path dstFile = srcRoot.resolve(rel);
                Files.createDirectories(dstFile.getParent());
                Files.copy(file, dstFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 递归删除目录，用于清理本批次的工作目录。
     */
    private static void safeRecursiveDelete(Path root) {
        if (root == null) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            System.err.println("[annotate] WARN: failed to delete temp dir " + root + ": " + e);
        }
    }
}