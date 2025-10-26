package com.initialone.jhumanify.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Resolve and stage bundled third-party tools (apktool, jadx) from classpath resources.
 *
 * Layout (inside your project jar/resources):
 *   /tools/apktool/apktool.jar
 *   /tools/jadx/jadx-cli.jar
 *   (optional) same-path .sha256 files for integrity check, e.g. apktool.jar.sha256
 *
 * At runtime, files are extracted into a cache folder:
 *   ~/.jhumanify/tools/<name>/<version>   (default)
 * You can also pass a custom staging dir.
 */
public class ToolResolver {

    public static class ResolvedTool {
        public final String name;
        public final Path executable;   // for jars, this is the jar path
        public final List<String> baseCmd; // command prefix to run, e.g., ["java","-jar", "<jar>"]

        public ResolvedTool(String name, Path executable, List<String> baseCmd) {
            this.name = name;
            this.executable = executable;
            this.baseCmd = baseCmd;
        }

        public List<String> commandWith(String... args) {
            List<String> cmd = new ArrayList<>(baseCmd);
            for (String a : args) cmd.add(a);
            return cmd;
        }
    }

    private final Path stageDir;
    private final boolean verifyChecksums;

    public ToolResolver(Path stageDir, boolean verifyChecksums) {
        this.stageDir = stageDir;
        this.verifyChecksums = verifyChecksums;
    }

    public static Path defaultStageDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".jhumanify", "tools");
    }

    public ResolvedTool resolveApktool() throws IOException {
        // Resource path inside jar
        String resource = "/tools/apktool/apktool.jar";
        return extractJarTool("apktool", resource, List.of("java", "-jar"));
    }

    public ResolvedTool resolveJadx() throws IOException {
        String resource = "/tools/jadx/jadx-cli.jar";
        return extractJarTool("jadx", resource, List.of("java", "-jar"));
    }

    private ResolvedTool extractJarTool(String name, String resourcePath, List<String> runner) throws IOException {
        Objects.requireNonNull(resourcePath);
        Path toolHome = stageDir.resolve(name);
        Files.createDirectories(toolHome);
        String fileName = Paths.get(resourcePath).getFileName().toString();
        Path staged = toolHome.resolve(fileName);

        // Copy resource → staged if missing or size differs
        try (InputStream in = getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Missing bundled resource: " + resourcePath);
            boolean needCopy = true;
            if (Files.exists(staged)) {
                // quick size check
                long sizeExisting = Files.size(staged);
                long sizeResource = availableBytes(resourcePath);
                if (sizeExisting == sizeResource && sizeResource > 0) {
                    needCopy = false;
                }
            }
            if (needCopy) {
                Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Optional checksum verify: resourcePath + ".sha256"
        if (verifyChecksums) {
            String sumRes = resourcePath + ".sha256";
            String expected = readChecksumFromResource(sumRes);
            if (expected != null) {
                String actual = sha256Hex(staged);
                if (!expected.equalsIgnoreCase(actual)) {
                    throw new IOException("Checksum mismatch for " + staged + "\nexpected: " + expected + "\nactual  : " + actual);
                }
            }
        }

        return new ResolvedTool(name, staged, concat(runner, staged.toString()));
    }

    private static List<String> concat(List<String> a, String b) {
        List<String> r = new ArrayList<>(a);
        r.add(b);
        return r;
    }

    private static InputStream getResourceAsStream(String path) {
        return ToolResolver.class.getResourceAsStream(path);
    }

    private static long availableBytes(String resourcePath) throws IOException {
        try (InputStream in = getResourceAsStream(resourcePath)) {
            if (in == null) return -1;
            return in.available();
        }
    }

    private static String readChecksumFromResource(String sumRes) throws IOException {
        try (InputStream in = getResourceAsStream(sumRes)) {
            if (in == null) return null;
            byte[] data = in.readAllBytes();
            String s = new String(data).trim();
            // accept forms like "<sha256>  filename" or just "<sha256>"
            int sp = s.indexOf(' ');
            return (sp > 0) ? s.substring(0, sp).trim() : s;
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(fis, md)) {
                dis.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Failed to compute sha256 for " + file, e);
        }
    }

    // JDK 17 has OutputStream.nullOutputStream()
//    private static final class OutputStream extends java.io.OutputStream {
//        private static final OutputStream NULL = new OutputStream();
//        static OutputStream nullOutputStream() { return NULL; }
//        @Override public void write(int b) { /* drop */ }
//    }
}