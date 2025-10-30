package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.tools.ToolResolver;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * humanify-apk:
 *   1) apktool (bundled or system) decode APK -> resources/smali (for reference)
 *   2) jadx    (bundled or system) decompile APK -> raw Java sources
 *   3) humanify (analyze → suggest → apply → [package-refactor?] → annotate → format)
 */
@CommandLine.Command(
        name = "humanify-apk",
        aliases = {"apk", "apk-humanify", "from-apk", "apk2human"},
        description = "apktool decode → jadx decompile → humanify (analyze/suggest/apply/[package-refactor?]/annotate/format) in one go"
)
public class HumanifyApkCmd implements Runnable {

    /* Positional */
    @CommandLine.Parameters(index = "0", description = "Input APK file")
    String apkPath;

    @CommandLine.Parameters(index = "1", description = "Output dir for final humanified sources")
    String outDir;

    /* Tooling override */
    @CommandLine.Option(names = "--apktool", description = "Override apktool path/jar; if absent, use bundled or system")
    String apktoolOverride;

    @CommandLine.Option(names = "--jadx", description = "Override jadx path/jar; if absent, try bundled, then system PATH")
    String jadxOverride;

    @CommandLine.Option(names = "--stage-tools", description = "Custom staging dir for bundled tools (default: ~/.jhumanify/tools)")
    String stageToolsDir;

    @CommandLine.Option(names = "--verify-tool-checksums", defaultValue = "false",
            description = "Verify SHA-256 using optional *.sha256 resources")
    boolean verifyChecksums;

    /* Flow control */
    @CommandLine.Option(names = "--work", description = "Working dir (default: work; sibling of <OUT_DIR>")
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

    @CommandLine.Option(names="--timeout-sec", defaultValue="180", description="HTTP read/call timeout seconds")
    int timeoutSec;

    @CommandLine.Option(names="--batch", defaultValue="100", description="Max snippets per LLM batch")
    int batch;

    @CommandLine.Option(names="--max-concurrent", defaultValue="100", description="Max concurrent LLM calls")
    int maxConcurrent;

    @CommandLine.Option(names="--jadx-xmx", defaultValue="4096", description="Max heap (MB) for java -Xmx when running jadx (default: ${DEFAULT-VALUE})")
    int jadxXmxMb;

    /* Apply/Annotate extras */
    @CommandLine.Option(names="--classpath", split = ":", description = "Extra classpath jars/dirs, separated by ':'")
    List<String> classpath = new ArrayList<>();

    @CommandLine.Option(names = {"--lang"}, defaultValue = "en", description = "Javadoc language: zh|en (default: ${DEFAULT-VALUE})")
    String lang;

    @CommandLine.Option(names = "--style", defaultValue = "concise",
            description = "Javadoc style: concise|detailed (default: ${DEFAULT-VALUE})")
    String style;

    /* === 新增：包/目录语义化重命名（透传到 humanify） === */
    @CommandLine.Option(names="--rename-packages", defaultValue = "false",
            description = "Rename obfuscated package/folder names via LLM/heuristic (runs between apply & annotate)")
    boolean renamePackages;

    @CommandLine.Option(names="--package-rename-mode", defaultValue = "llm",
            description = "llm|heuristic for package/folder rename (default: ${DEFAULT-VALUE})")
    String packageRenameMode;

    @CommandLine.Option(names="--package-rename-skip",
            description = "Regex to skip packages (e.g. ^(androidx?|com\\.google|org)\\b)")
    String packageRenameSkip;

    @CommandLine.Option(names="--package-rename-leaf-only", defaultValue = "true",
            description = "Only rename the leaf segment of the package path (default: ${DEFAULT-VALUE})")
    boolean packageRenameLeafOnly;

    @Override
    public void run() {
        Path apk = Paths.get(apkPath);
        Path out = Paths.get(outDir);
        if (!Files.exists(apk)) {
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "APK not found: " + apk.toAbsolutePath());
        }
        try { Files.createDirectories(out); }
        catch (IOException e) {
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "Cannot create output dir: " + out.toAbsolutePath());
        }

        Path work = resolveWorkDir(out, workDir);
        Path apktoolOut = work.resolve("apktool");
        Path srcRaw = work.resolve("src_raw"); // 将会包含 'sources' 子目录（jadx 默认）

        long t0 = System.currentTimeMillis();
        System.out.println("[humanify-apk] apk = " + apk.toAbsolutePath());
        System.out.println("[humanify-apk] out = " + out.toAbsolutePath());
        System.out.println("[humanify-apk] work = " + work.toAbsolutePath());

        try {
            Files.createDirectories(work);

            // Resolve tools: prefer overrides; otherwise use bundled or system
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
            Path javaRoot = resolveJavaRoot(srcRaw);
            List<String> humanifyArgs = new ArrayList<>();
            humanifyArgs.add(javaRoot.toString());
            humanifyArgs.add(out.toString());

            // Pass-through LLM options
            humanifyArgs.add("--provider"); humanifyArgs.add(provider);
            humanifyArgs.add("--model");    humanifyArgs.add(model);
            humanifyArgs.add("--batch");    humanifyArgs.add(Integer.toString(batch));
            humanifyArgs.add("--max-concurrent"); humanifyArgs.add(Integer.toString(maxConcurrent));
            humanifyArgs.add("--local-api"); humanifyArgs.add(localApi);
            humanifyArgs.add("--endpoint");  humanifyArgs.add(endpoint);
            humanifyArgs.add("--timeout-sec"); humanifyArgs.add(Integer.toString(timeoutSec));

            // Package rename 开关透传
            if (renamePackages) humanifyArgs.add("--rename-packages");
            humanifyArgs.add("--package-rename-mode"); humanifyArgs.add(packageRenameMode != null ? packageRenameMode : "llm");
            if (packageRenameSkip != null && !packageRenameSkip.isBlank()) {
                humanifyArgs.add("--package-rename-skip"); humanifyArgs.add(packageRenameSkip);
            }
            if (packageRenameLeafOnly) humanifyArgs.add("--package-rename-leaf-only");

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
                try { deleteRecursive(work.toFile()); } catch (Exception ignore) {}
            }
        }
    }

    private void runApktoolDecode(Path apk, Path out, ToolResolver resolver) throws Exception {
        Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
        List<String> cmd;
        if (apktoolOverride != null && !apktoolOverride.isEmpty()) {
            if (apktoolOverride.toLowerCase().endsWith(".jar") || new File(apktoolOverride).getName().endsWith(".jar")) {
                cmd = List.of("java", "-jar", apktoolOverride, "d", "-f", "-o", out.toString(), apk.toString());
            } else {
                cmd = List.of(apktoolOverride, "d", "-f", "-o", out.toString(), apk.toString());
            }
        } else {
            try {
                ToolResolver.ResolvedTool apktool = resolver.resolveApktool();
                cmd = new ArrayList<>(apktool.baseCmd);
                cmd.add("d"); cmd.add("-f"); cmd.add("-o"); cmd.add(out.toString()); cmd.add(apk.toString());
            } catch (Exception e) {
                // 尝试系统 PATH
                cmd = List.of("apktool", "d", "-f", "-o", out.toString(), apk.toString());
            }
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
            if (jadxOverride.toLowerCase().endsWith(".jar")) {
                cmd = new ArrayList<>();
                cmd.add("java"); cmd.add("-Xmx" + jadxXmxMb + "m");
                cmd.add("-cp"); cmd.add(jadxOverride);
                cmd.add("jadx.cli.JadxCLI");
                cmd.add("-d"); cmd.add(out.toString());
                cmd.add(inputApk.toString());
            } else {
                cmd = new ArrayList<>();
                cmd.add(jadxOverride);
                cmd.add("-d"); cmd.add(out.toString());
                cmd.add(inputApk.toString());
            }
        } else {
            try {
                ToolResolver.ResolvedTool bundled = resolver.resolveJadx();
                String stagedJar = bundled.executable.toString();
                cmd = new ArrayList<>();
                cmd.add("java"); cmd.add("-Xmx" + jadxXmxMb + "m");
                cmd.add("-cp"); cmd.add(stagedJar);
                cmd.add("jadx.cli.JadxCLI");
                cmd.add("-d"); cmd.add(out.toString());
                cmd.add(inputApk.toString());
            } catch (Exception e) {
                // fallback: 系统 PATH
                String[] candidates = {"jadx", "jadx.sh", "jadx.bat"};
                String found = null;
                for (String c : candidates) {
                    try {
                        Process p = new ProcessBuilder(c, "--version").redirectErrorStream(true).start();
                        if (p.waitFor() == 0) { found = c; break; }
                    } catch (IOException ignore) {}
                }
                if (found == null) throw new IllegalStateException("No bundled or system jadx found. Please install jadx or provide --jadx.");
                cmd = new ArrayList<>();
                cmd.add(found);
                cmd.add("-d"); cmd.add(out.toString());
                cmd.add(inputApk.toString());
            }
        }

        System.out.println("[humanify-apk]   > " + String.join(" ", cmd));
        new ProcessBuilder(cmd).inheritIO().start().waitFor();

        if (!Files.isDirectory(out)) {
            System.err.println("[humanify-apk] WARNING: Jadx output dir not found: " + out);
        }
    }

    private static Path resolveJavaRoot(Path srcRaw) throws IOException {
        Path candidate = srcRaw.resolve("sources");
        if (Files.isDirectory(candidate)) return candidate;
        // 若 srcRaw 自身包含 .java 则直接用它
        try (var s = Files.walk(srcRaw, 1)) {
            boolean hasJava = s.anyMatch(p -> p.toString().endsWith(".java"));
            if (hasJava) return srcRaw;
        }
        // 否则返回第一个包含 .java 的子目录
        try (var s = Files.walk(srcRaw, 2)) {
            return s.filter(Files::isDirectory)
                    .filter(d -> {
                        try (var s2 = Files.walk(d, 1)) {
                            return s2.anyMatch(p -> p.toString().endsWith(".java"));
                        } catch (IOException e) { return false; }
                    })
                    .findFirst().orElse(candidate);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        file.delete();
    }

    private static Path resolveWorkDir(Path out, String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        // 默认与 out 同级：<outParent>/<outName>.work
        Path parent = out.getParent() != null ? out.getParent() : Paths.get(".");
        String name = out.getFileName() != null ? out.getFileName().toString() : "out";
        return parent.resolve( "work");
    }
}