package com.initialone.jhumanify.commands;

import picocli.CommandLine;

@CommandLine.Command(name="decompile", description="Use jadx/cfr to decompile to Java sources")
public class DecompileCmd implements Runnable {
    @CommandLine.Parameters(index="0", description="Input APK/JAR/DEX") String input;
    @CommandLine.Parameters(index="1", description="Output source dir") String outSrc;
    @CommandLine.Option(names="--tool", defaultValue="jadx", description="jadx|cfr") String tool;

    private static boolean commandExists(String cmd) {
        try { new ProcessBuilder("which", cmd).start().waitFor(); return true; }
        catch (Exception e) { return false; }
    }

    public void run() {
        try {
            if ("jadx".equalsIgnoreCase(tool)) {
                if (!commandExists("jadx")) {
                    System.err.println("jadx not found. Install: https://github.com/skylot/jadx");
                    return;
                }
                new ProcessBuilder("jadx","-d",outSrc,input).inheritIO().start().waitFor();
            } else {
                // cfr: 需要本地有 cfr.jar
                if (!java.nio.file.Files.exists(java.nio.file.Path.of("cfr.jar"))) {
                    System.err.println("cfr.jar not found. Download from: https://www.benf.org/other/cfr/");
                    return;
                }
                new ProcessBuilder("java","-jar","cfr.jar",input,"--outputdir",outSrc)
                        .inheritIO().start().waitFor();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}