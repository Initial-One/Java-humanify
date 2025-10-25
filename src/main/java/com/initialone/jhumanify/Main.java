package com.initialone.jhumanify;

import com.initialone.jhumanify.commands.*;
import picocli.CommandLine;

@CommandLine.Command(
        name = "jhumanify",
        version = "0.4.0",
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        sortOptions = false,
        description = {
                "Deobfuscate Java with LLMs. Typical flow:",
                "  analyze → suggest → apply → annotate",
                "",
                "Providers: dummy | openai | local | deepseek",
                "Local provider: --local-api openai|ollama + --endpoint http://host:port",
                "Env: OPENAI_API_KEY / OPENAI_BASE_URL / DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL"
        },
        subcommands = {
                HumanifyApkCmd.class,DecompileCmd.class, AnalyzeCmd.class, SuggestCmd.class,
                ApplyCmd.class, HumanifyCmd.class, AnnotateCmd.class
        }
)
public class Main implements Runnable {
    public void run() { System.out.println("Use a subcommand. Try --help."); }
    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }
}