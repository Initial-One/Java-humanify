package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.tools.ToolResolver;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * humanify-apk:
 *   1) apktool (bundled) decode APK -> resources/smali (for reference)
 *   2) jadx (bundled)   decompile APK -> raw Java sources
 *   3) humanify         run analyze → suggest → apply → annotate
 *
 * Bundled tools:
 *   - /tools/apktool/apktool.jar
 *   - /tools/jadx/jadx-cli.jar
 *   (Optionally provide .sha256 alongside to enable integrity verification)
 */
@CommandLine.Command(
        name = "humanify-apk",
        aliases = {"apk", "apk-humanify", "from-apk", "apk2human"},
        description = "apktool decode → jadx decompile → humanify (analyze/suggest/apply/annotate) in one go"
)
public class HumanifyApkCmd implements Runnable {

    /* Positional */
    @CommandLine.Parameters(index = "0", description = "Input APK file")
    String apkPath;

    @CommandLine.Parameters(index = "1", description = "Output dir for final humanified sources")
    String outDir;

    /* Tooling override */
    @CommandLine.Option(names = "--apktool", description = "Override apktool path/jar; if absent, use bundled")
    String apktoolOverride;

    @CommandLine.Option(names = "--jadx", description = "Override jadx path/jar; if absent, use bundled")
    String jadxOverride;

    @CommandLine.Option(names = "--stage-tools", description = "Custom staging dir for bundled tools (default: ~/.jhumanify/tools)")
    String stageToolsDir;

    @CommandLine.Option(names = "--verify-tool-checksums", defaultValue = "false",
            description = "Verify SHA-256 using optional *.sha256 resources")
    boolean verifyChecksums;

    /* Flow control */
    @CommandLine.Option(names = "--work", description = "Working dir (default: <OUT_DIR>/.work)")
    String workDir;

    @CommandLine.Option(names = "--skip-apktool", defaultValue = "false", description = "Skip apktool step")
    boolean skipApktool;

    @CommandLine.Option(names = "--skip-decompile", defaultValue = "false", description = "Skip jadx step")
    boolean skipDecompile;

    @CommandLine.Option(names = "--keep-work", defaultValue = "true", description = "Keep working directory (default: ${DEFAULT-VALUE})")
    boolean keepWork;

    /* LLM / pipeline options passthrough */
    @CommandLine.Option(names="--provider", defaultValue="dummy", description="dummy | openai | local | deepseek")
    String provider;

    @CommandLine.Option(names="--model", defaultValue="gpt-4o-mini", description="Model name")
    String model;

    @CommandLine.Option(names="--local-api", defaultValue="ollama", description="When --provider=local: openai | ollama")
    String localApi;

    @CommandLine.Option(names="--endpoint", defaultValue="http://localhost:11434", description="Local endpoint (OpenAI-compat or Ollama)")
    String endpoint;

    @CommandLine.Option(names="--timeout-sec", defaultValue="180", description="HTTP read/call timeout seconds for local provider")
    int timeoutSec;

    @CommandLine.Option(names="--batch", defaultValue="12", description="Max snippets per LLM batch")
    int batch;

    @CommandLine.Option(names="--max-concurrent", defaultValue="100", description="Max concurrent LLM calls")
    int maxConcurrent;

    @CommandLine.Option(names="--jadx-xmx", defaultValue="4096", description="Max heap (MB) for bundled jadx java -Xmx (default: ${DEFAULT-VALUE})")
    int jadxXmxMb;

    /* Apply/Annotate extras */
    @CommandLine.Option(names="--classpath", split = ":", description = "Extra classpath jars/dirs, separated by ':'")
    java.util.List<String> classpath = new java.util.ArrayList<>();

    @CommandLine.Option(names = {"--lang"}, defaultValue = "en", description = "Javadoc language: zh|en (default: ${DEFAULT-VALUE})")
    String lang;

    @CommandLine.Option(names = "--style", defaultValue = "short",
            description = "Javadoc style: short|minimal|detailed (default: ${DEFAULT-VALUE})")
    String style;

    @Override
    public void run() {
        Path apk = Paths.get(apkPath);
        Path out = Paths.get(outDir);
        if (!Files.exists(apk)) {
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "APK not found: " + apk.toAbsolutePath());
        }
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "Cannot create output dir: " + out.toAbsolutePath());
        }

        Path work = (workDir == null || workDir.isEmpty())
                ? out.resolve(".work")
                : Paths.get(workDir);
        Path apktoolOut = work.resolve("apktool");
        Path srcRaw = work.resolve("src_raw");

        long t0 = System.currentTimeMillis();
        System.out.println("[humanify-apk] apk = " + apk.toAbsolutePath());
        System.out.println("[humanify-apk] out = " + out.toAbsolutePath());
        System.out.println("[humanify-apk] work = " + work.toAbsolutePath());

        try {
            Files.createDirectories(work);

            // Resolve tools: prefer overrides; otherwise use bundled
            ToolResolver resolver = new ToolResolver(
                    stageToolsDir == null || stageToolsDir.isEmpty()
                            ? ToolResolver.defaultStageDir()
                            : Paths.get(stageToolsDir),
                    verifyChecksums
            );

            // 1) apktool
            if (!skipApktool) {
                System.out.println("[humanify-apk] 1/3 apktool decode...");
                runApktoolDecode(apk, apktoolOut, resolver);
            } else {
                System.out.println("[humanify-apk] 1/3 apktool decode skipped.");
            }

            // 2) jadx
            if (!skipDecompile) {
                System.out.println("[humanify-apk] 2/3 jadx decompile -> " + srcRaw);
                Files.createDirectories(srcRaw);
                runJadx(apk, srcRaw, resolver);
            } else {
                System.out.println("[humanify-apk] 2/3 jadx decompile skipped.");
            }

            // 3) humanify
            System.out.println("[humanify-apk] 3/3 humanify...");
            List<String> humanifyArgs = new ArrayList<>();
            humanifyArgs.add(srcRaw.toString());
            humanifyArgs.add(out.toString());

            // Pass-through LLM options
            humanifyArgs.add("--provider"); humanifyArgs.add(provider);
            humanifyArgs.add("--model");    humanifyArgs.add(model);
            humanifyArgs.add("--batch");    humanifyArgs.add(Integer.toString(batch));
            humanifyArgs.add("--max-concurrent"); humanifyArgs.add(Integer.toString(maxConcurrent));
            humanifyArgs.add("--local-api"); humanifyArgs.add(localApi);
            humanifyArgs.add("--endpoint");  humanifyArgs.add(endpoint);
            humanifyArgs.add("--timeout-sec"); humanifyArgs.add(Integer.toString(timeoutSec));

            // Apply/Annotate extras
            if (!classpath.isEmpty()) {
                humanifyArgs.add("--classpath"); humanifyArgs.add(String.join(":", classpath));
            }
            if (lang != null) {
                humanifyArgs.add("--lang"); humanifyArgs.add(lang);
            }
            if (style != null) {
                humanifyArgs.add("--style"); humanifyArgs.add(style);
            }

            int rc = new CommandLine(new HumanifyCmd()).execute(humanifyArgs.toArray(new String[0]));
            if (rc != 0) throw new IllegalStateException("humanify failed with code " + rc);

            long t1 = System.currentTimeMillis();
            System.out.printf("[humanify-apk] done in %.2fs → %s%n", (t1 - t0) / 1000.0, out);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        } finally {
            if (!keepWork) {
                try {
                    deleteRecursive(work.toFile());
                } catch (Exception ignore) {}
            }
        }
    }

    private void runApktoolDecode(Path apk, Path out, ToolResolver resolver) throws Exception {
        Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
        List<String> cmd;
        if (apktoolOverride != null && !apktoolOverride.isEmpty()) {
            // honor user override
            if (apktoolOverride.toLowerCase().endsWith(".jar") || new File(apktoolOverride).getName().endsWith(".jar")) {
                cmd = List.of("java", "-jar", apktoolOverride, "d", "-f", "-o", out.toString(), apk.toString());
            } else {
                cmd = List.of(apktoolOverride, "d", "-f", "-o", out.toString(), apk.toString());
            }
        } else {
            // use bundled jar
            ToolResolver.ResolvedTool apktool = resolver.resolveApktool();
            cmd = new ArrayList<>(apktool.baseCmd);
            cmd.add("d"); cmd.add("-f"); cmd.add("-o"); cmd.add(out.toString()); cmd.add(apk.toString());
        }
        System.out.println("[humanify-apk]   > " + String.join(" ", cmd));
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (!Files.exists(out)) {
            throw new IllegalStateException("apktool decode failed. Output not found: " + out);
        }
    }

    private void runJadx(Path inputApk, Path out, ToolResolver resolver) throws Exception {
        List<String> cmd;

        if (jadxOverride != null && !jadxOverride.isEmpty()) {
            // 用户手动指定了 --jadx
            if (jadxOverride.toLowerCase().endsWith(".jar")) {
                // 用 jar -> CLI main
                cmd = new ArrayList<>();
                cmd.add("java");
                cmd.add("-Xmx" + jadxXmxMb + "m");
                cmd.add("-cp");
                cmd.add(jadxOverride);
                cmd.add("jadx.cli.JadxCLI");
                cmd.add("-d");
                cmd.add(out.toString());
                cmd.add(inputApk.toString());
            } else {
                // 假设它是可执行的二进制/脚本，比如官方 zip 里的 bin/jadx
                cmd = new ArrayList<>();
                cmd.add(jadxOverride);
                cmd.add("-d");
                cmd.add(out.toString());
                cmd.add(inputApk.toString());
            }
        } else {
            // 用我们内置的打包版本
            ToolResolver.ResolvedTool bundled = resolver.resolveJadx();
            String stagedJar = bundled.executable.toString();

            cmd = new ArrayList<>();
            cmd.add("java");
            cmd.add("-Xmx" + jadxXmxMb + "m");
            cmd.add("-cp");
            cmd.add(stagedJar);
            cmd.add("jadx.cli.JadxCLI");
            cmd.add("-d");
            cmd.add(out.toString());
            cmd.add(inputApk.toString());
        }

        System.out.println("[humanify-apk]   > " + String.join(" ", cmd));
        new ProcessBuilder(cmd).inheritIO().start().waitFor();

        if (!Files.isDirectory(out) || !Files.exists(out.resolve("sources"))) {
            System.err.println("[humanify-apk] WARNING: Jadx did not produce expected 'sources' folder in: " + out);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        file.delete();
    }
}