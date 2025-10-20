package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.llm.LlmOptions;
import com.initialone.jhumanify.util.Formatter;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 一条命令串行执行：analyze -> suggest -> apply -> annotate
 *
 * 用法：
 *   java -jar java-humanify.jar humanify \
 *     --provider local --local-api ollama --endpoint http://localhost:11434 \
 *     --model qwen2.5:1.5b \
 *     --max-concurrent 100 \
 *     samples/src samples/out-humanified
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
                "\n" +
                "Flow:\n" +
                "  analyze → suggest → apply → annotate"
)
public class HumanifyCmd implements Runnable {
    @CommandLine.Mixin
    LlmOptions llm;
    /* 必选参数 */
    @CommandLine.Parameters(index = "0", description = "Input source dir")
    String srcDir;

    @CommandLine.Parameters(index = "1", description = "Output dir")
    String outDir;

    /* 应用阶段参数（与 ApplyCmd 保持一致） */
    @CommandLine.Option(names = "--classpath", split = ":", description = "Extra classpath jars/dirs, separated by ':'")
    List<String> classpath = new ArrayList<>();

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

    @Override
    public void run() {
        Path src = Paths.get(srcDir);
        Path out = Paths.get(outDir);

        try {
            if (!Files.isDirectory(src)) {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        "Input source dir not found: " + srcDir + " (abs: " + src.toAbsolutePath() + ")");
            }
            Files.createDirectories(out);

            // 1) 中间产物
            Path snippets = out.resolve("snippets.json");
            Path mapping  = out.resolve("mapping.json");
            if (snippets.getParent() != null) Files.createDirectories(snippets.getParent());
            if (mapping.getParent()  != null) Files.createDirectories(mapping.getParent());

            long t0 = System.currentTimeMillis();

            /* ========== 1/4 analyze ========== */
            System.out.println("[humanify] 1/4 analyze...");
            List<String> analyzeArgs = List.of(src.toString(), snippets.toString());
            int rc1 = new CommandLine(new AnalyzeCmd()).execute(analyzeArgs.toArray(new String[0]));
            if (rc1 != 0) throw new IllegalStateException("analyze failed with code " + rc1);
            if (!Files.exists(snippets) || Files.size(snippets) == 0)
                throw new IllegalStateException("snippets.json was not produced: " + snippets);

            /* ========== 2/4 suggest ========== */
            System.out.println("[humanify] 2/4 suggest (" + llm.provider + ")...");
            List<String> suggestArgs = new ArrayList<>();
            suggestArgs.addAll(List.of("--provider", llm.provider));
            suggestArgs.addAll(List.of("--model", llm.model));
            suggestArgs.addAll(List.of("--batch", Integer.toString(llm.batch)));

            // 将并发参数传给 suggest（若 SuggestCmd 提供该字段）
            if (hasField(SuggestCmd.class, "maxConcurrent")) {
                suggestArgs.addAll(List.of("--max-concurrent", Integer.toString(llm.maxConcurrent)));
            }

            if (hasField(SuggestCmd.class, "localApi")) {
                suggestArgs.addAll(List.of("--local-api", llm.localApi));
            }
            if (hasField(SuggestCmd.class, "endpoint")) {
                suggestArgs.addAll(List.of("--endpoint", llm.endpoint));
            }

            // 位置参数
            suggestArgs.add(snippets.toString());
            suggestArgs.add(mapping.toString());

            int rc2 = new CommandLine(new SuggestCmd()).execute(suggestArgs.toArray(new String[0]));
            if (rc2 != 0) throw new IllegalStateException("suggest failed with code " + rc2);
            if (!Files.exists(mapping) || Files.size(mapping) == 0)
                throw new IllegalStateException("mapping.json was not produced: " + mapping);

            /* ========== 3/4 apply ========== */
            System.out.println("[humanify] 3/4 apply...");
            List<String> applyArgs = new ArrayList<>();
            if (classpath != null && !classpath.isEmpty() && hasField(ApplyCmd.class, "classpath")) {
                applyArgs.add("--classpath");
                applyArgs.add(classpath.stream().collect(Collectors.joining(":")));
            }
            applyArgs.add(src.toString());
            applyArgs.add(mapping.toString());
            applyArgs.add(out.toString());

            int rc3 = new CommandLine(new ApplyCmd()).execute(applyArgs.toArray(new String[0]));
            if (rc3 != 0) throw new IllegalStateException("apply failed with code " + rc3);

            /* ========== 4/4 annotate ========== */
            System.out.println("[humanify] 4/4 annotate...");

            List<String> annArgs = new ArrayList<>();
            annArgs.add("--src");   annArgs.add(out.toString());
            annArgs.add("--lang");  annArgs.add(lang != null ? lang : "en");
            annArgs.add("--style"); annArgs.add(style != null ? style : "detailed");
            annArgs.add("--provider"); annArgs.add(llm.provider != null ? llm.provider : "dummy");
            annArgs.add("--model");    annArgs.add(llm.model != null ? llm.model : "gpt-4o-mini");
            annArgs.add("--batch");    annArgs.add(Integer.toString(llm.batch));

            // 将并发参数传给 annotate（AnnotateCmd 已支持）
            annArgs.add("--max-concurrent");
            annArgs.add(Integer.toString(llm.maxConcurrent));

            if ("local".equalsIgnoreCase(llm.provider)) {
                annArgs.add("--local-api"); annArgs.add((llm.localApi != null && !llm.localApi.isEmpty()) ? llm.localApi : "ollama");
                if (llm.endpoint != null && !llm.endpoint.isEmpty()) { annArgs.add("--endpoint"); annArgs.add(llm.endpoint); }
                annArgs.add("--timeout-sec"); annArgs.add(Integer.toString(180));
            } else {
                if (llm.endpoint != null && !llm.endpoint.isEmpty()) { annArgs.add("--endpoint"); annArgs.add(llm.endpoint); }
                annArgs.add("--timeout-sec"); annArgs.add(Integer.toString(180));
            }

            int rc4 = new CommandLine(new AnnotateCmd()).execute(annArgs.toArray(new String[0]));
            if (rc4 != 0) throw new IllegalStateException("annotate failed with code " + rc4);

            // 可选格式化
            try {
                Formatter.formatTree(out);
                System.out.println("[humanify] formatted output.");
            } catch (Throwable t) {
                System.err.println("[humanify] format skipped: " + t.getMessage());
            }

            long t1 = System.currentTimeMillis();
            System.out.printf("[humanify] done in %.2fs -> %s%n", (t1 - t0) / 1000.0, out);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static boolean hasField(Class<?> cls, String name) {
        try { cls.getDeclaredField(name); return true; }
        catch (NoSuchFieldException e) { return false; }
    }

    private static void safeDelete(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignore) {}
    }
}