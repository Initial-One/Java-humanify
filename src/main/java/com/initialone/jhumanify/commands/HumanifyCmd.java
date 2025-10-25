package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.llm.LlmOptions;
import com.initialone.jhumanify.util.Formatter;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 一条命令串行执行：analyze -> suggest -> apply -> annotate -> format
 *
 * 用法：
 *   java -jar jhumanify.jar humanify \
 *     --provider local --local-api ollama --endpoint http://localhost:11434 \
 *     --model qwen2.5:1.5b \
 *     --max-concurrent 100 \
 *     samples/src samples/out-humanified
 *
 * 流程：
 *   1. analyze     扫描源码、提取片段 (snippets.json)
 *   2. suggest     调 LLM 生成重命名建议 (mapping.json)
 *   3. apply       把 mapping 应用到源码，输出到 OUT_DIR
 *   4. annotate    生成类/方法/字段的 Javadoc 注释（分批，避免 OOM）
 *   5. format      进行 google-java-format 排版（由 Formatter fork 子进程并自动加 --add-exports）
 */
@CommandLine.Command(
        name = "humanify",
        description = "humanify — one-shot pipeline\n" +
                "Usage:\n" +
                "  jhumanify humanify <SRC_DIR> <OUT_DIR> [options]\n" +
                "\n" +
                "Positional:\n" +
                "  <SRC_DIR>          Decompiled Java source dir\n" +
                "  <OUT_DIR>          Output dir\n" +
                "\n" +
                "Options (同 suggest/annotate + apply):\n" +
                "  --provider STR          dummy | openai | local | deepseek (default: dummy)\n" +
                "  --model STR             Model name (default: gpt-4o-mini)\n" +
                "  --local-api STR         When --provider=local: openai | ollama (default: ollama)\n" +
                "  --endpoint URL          Local endpoint (default: http://localhost:11434)\n" +
                "  --batch INT             Max snippets per LLM batch (default: 12)\n" +
                "  --max-concurrent INT    Max concurrent LLM calls (default: 100)\n" +
                "  --lang STR              zh|en (Javadoc language)\n" +
                "  --style STR             concise|detailed (Javadoc style)\n" +
                "  --classpath CP          ':'-separated classpath for apply\n" +
                "\n" +
                "Flow:\n" +
                "  analyze → suggest → apply → annotate → format"
)
public class HumanifyCmd implements Runnable {
    @CommandLine.Mixin
    LlmOptions llm;

    /* 位置参数 */
    @CommandLine.Parameters(index = "0", description = "Input source dir")
    String srcDir;

    @CommandLine.Parameters(index = "1", description = "Output dir")
    String outDir;

    /* Apply 阶段的额外 classpath （影响重命名/引用修正阶段） */
    @CommandLine.Option(
            names = "--classpath",
            split = ":",
            description = "Extra classpath jars/dirs, separated by ':'"
    )
    List<String> classpath = new ArrayList<>();

    /* Annotate 阶段注释生成相关 */
    @CommandLine.Option(
            names = {"--lang"},
            defaultValue = "en",
            description = "Javadoc language: zh|en (default: ${DEFAULT-VALUE})")
    String lang;

    @CommandLine.Option(
            names = {"--style"},
            defaultValue = "concise",
            description = "Javadoc style: concise|detailed (default: ${DEFAULT-VALUE})")
    String style;

    /* Annotate 阶段的内存控制（分批） */
    @CommandLine.Option(
            names = {"--annotate-batch-size"},
            defaultValue = "300",
            description = "How many .java files per annotate batch (default: ${DEFAULT-VALUE})"
    )
    int annotateBatchSize;

    @CommandLine.Option(
            names = {"--annotate-temp-root"},
            description = "Where to create per-batch temp dirs (default: system tmp)"
    )
    String annotateTempRoot;

    @CommandLine.Option(
            names = {"--annotate-keep-temp"},
            description = "Keep per-batch temp dirs instead of deleting (default: ${DEFAULT-VALUE})"
    )
    boolean annotateKeepTemp = false;

    @Override
    public void run() {
        Path src = Paths.get(srcDir);
        Path out = Paths.get(outDir);

        try {
            if (!Files.isDirectory(src)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Input source dir not found: " + srcDir + " (abs: " + src.toAbsolutePath() + ")"
                );
            }
            Files.createDirectories(out);

            // 临时中间产物
            Path snippets = out.resolve("snippets.json"); // 来自 analyze
            Path mapping  = out.resolve("mapping.json");  // 来自 suggest

            if (snippets.getParent() != null) Files.createDirectories(snippets.getParent());
            if (mapping.getParent()  != null) Files.createDirectories(mapping.getParent());

            long t0 = System.currentTimeMillis();

            /* ========== 1/5 analyze ========== */
            System.out.println("[humanify] 1/5 analyze...");
            List<String> analyzeArgs = List.of(
                    src.toString(),
                    snippets.toString()
            );
            int rc1 = new CommandLine(new AnalyzeCmd()).execute(analyzeArgs.toArray(new String[0]));
            if (rc1 != 0) {
                throw new IllegalStateException("analyze failed with code " + rc1);
            }
            if (!Files.exists(snippets) || Files.size(snippets) == 0) {
                throw new IllegalStateException("snippets.json was not produced: " + snippets);
            }

            /* ========== 2/5 suggest ========== */
            System.out.println("[humanify] 2/5 suggest (" + llm.provider + ")...");
            List<String> suggestArgs = new ArrayList<>();
            suggestArgs.addAll(List.of("--provider", llm.provider));
            suggestArgs.addAll(List.of("--model", llm.model));
            suggestArgs.addAll(List.of("--batch", Integer.toString(llm.batch)));

            // 如果 SuggestCmd 有这些字段就透传
            if (hasField(SuggestCmd.class, "maxConcurrent")) {
                suggestArgs.addAll(List.of("--max-concurrent", Integer.toString(llm.maxConcurrent)));
            }
            if (hasField(SuggestCmd.class, "localApi")) {
                suggestArgs.addAll(List.of("--local-api", llm.localApi));
            }
            if (hasField(SuggestCmd.class, "endpoint")) {
                if (llm.endpoint != null && !llm.endpoint.isEmpty()) {
                    suggestArgs.addAll(List.of("--endpoint", llm.endpoint));
                }
            }
            if (hasField(SuggestCmd.class, "timeoutSec")) {
                suggestArgs.addAll(List.of("--timeout-sec", Integer.toString(llm.timeoutSec)));
            }

            // 位置参数: snippets.json -> mapping.json
            suggestArgs.add(snippets.toString());
            suggestArgs.add(mapping.toString());

            int rc2 = new CommandLine(new SuggestCmd()).execute(suggestArgs.toArray(new String[0]));
            if (rc2 != 0) {
                throw new IllegalStateException("suggest failed with code " + rc2);
            }
            if (!Files.exists(mapping) || Files.size(mapping) == 0) {
                throw new IllegalStateException("mapping.json was not produced: " + mapping);
            }

            /* ========== 3/5 apply ========== */
            System.out.println("[humanify] 3/5 apply...");
            List<String> applyArgs = new ArrayList<>();
            if (classpath != null && !classpath.isEmpty() && hasField(ApplyCmd.class, "classpath")) {
                applyArgs.add("--classpath");
                applyArgs.add(
                        classpath.stream()
                                .collect(Collectors.joining(":"))
                );
            }
            // 位置参数: <SRC_DIR> <mapping.json> <OUT_DIR>
            applyArgs.add(src.toString());
            applyArgs.add(mapping.toString());
            applyArgs.add(out.toString());

            int rc3 = new CommandLine(new ApplyCmd()).execute(applyArgs.toArray(new String[0]));
            if (rc3 != 0) {
                throw new IllegalStateException("apply failed with code " + rc3);
            }

            /* ========== 4/5 annotate ========== */
            System.out.println("[humanify] 4/5 annotate...");

            List<String> annArgs = new ArrayList<>();
            // 我们的 AnnotateCmd 是分批版，参数是 flag 风格
            annArgs.add("--src");   annArgs.add(out.toString());
            annArgs.add("--lang");  annArgs.add(lang != null ? lang : "en");
            annArgs.add("--style"); annArgs.add(style != null ? style : "concise");

            // LLM 相关透传
            annArgs.add("--provider"); annArgs.add(llm.provider != null ? llm.provider : "dummy");
            annArgs.add("--model");    annArgs.add(llm.model != null ? llm.model : "gpt-4o-mini");
            annArgs.add("--batch");    annArgs.add(Integer.toString(llm.batch));
            annArgs.add("--max-concurrent"); annArgs.add(Integer.toString(llm.maxConcurrent));

            // endpoint / local-api / timeoutSec 这些 AnnotateCmd 里的 DocClient 也会需要
            if ("local".equalsIgnoreCase(llm.provider)) {
                if (llm.localApi != null && !llm.localApi.isEmpty()) {
                    annArgs.add("--local-api"); annArgs.add(llm.localApi);
                } else {
                    annArgs.add("--local-api"); annArgs.add("ollama");
                }
                if (llm.endpoint != null && !llm.endpoint.isEmpty()) {
                    annArgs.add("--endpoint"); annArgs.add(llm.endpoint);
                }
                annArgs.add("--timeout-sec"); annArgs.add(Integer.toString(llm.timeoutSec > 0 ? llm.timeoutSec : 180));
            } else {
                if (llm.endpoint != null && !llm.endpoint.isEmpty()) {
                    annArgs.add("--endpoint"); annArgs.add(llm.endpoint);
                }
                annArgs.add("--timeout-sec"); annArgs.add(Integer.toString(llm.timeoutSec > 0 ? llm.timeoutSec : 180));
            }

            // 分批 annotate 的控制参数
            annArgs.add("--annotate-batch-size"); annArgs.add(Integer.toString(annotateBatchSize));
            if (annotateTempRoot != null && !annotateTempRoot.isEmpty()) {
                annArgs.add("--annotate-temp-root"); annArgs.add(annotateTempRoot);
            }
            if (annotateKeepTemp) {
                annArgs.add("--annotate-keep-temp");
            }

            int rc4 = new CommandLine(new AnnotateCmd()).execute(annArgs.toArray(new String[0]));
            if (rc4 != 0) {
                throw new IllegalStateException("annotate failed with code " + rc4);
            }

            /* ========== 5/5 format ========== */
            System.out.println("[humanify] 5/5 format...");
            try {
                // 新版 Formatter 在内部会自己 fork 子进程，并自动加 --add-exports
                // 用户主进程不用再写那些很长的 JVM 参数
                Formatter.formatJava(out);
                System.out.println("[humanify] formatted output.");
            } catch (Throwable t) {
                System.err.println("[humanify] format skipped: " + t.getMessage());
            }

            long t1 = System.currentTimeMillis();
            System.out.printf("[humanify] done in %.2fs -> %s%n",
                    (t1 - t0) / 1000.0,
                    out
            );

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static boolean hasField(Class<?> cls, String name) {
        try {
            cls.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static void safeDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
        }
    }
}