package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.ast.DocAnnotator;
import com.initialone.jhumanify.llm.DocClient;
import com.initialone.jhumanify.llm.LlmOptions;
import com.initialone.jhumanify.llm.OpenAiDocClient;
import com.initialone.jhumanify.llm.RuleDocClient;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@CommandLine.Command(name = "annotate", description = "Add Javadoc summaries for classes and methods.")
public class AnnotateCmd implements Runnable {
    @CommandLine.Mixin
    LlmOptions llm;
    @CommandLine.Option(names = {"-s", "--src"}, required = true,
            description = "Input source dir (decompiled Java)")
    String srcDir;
    @CommandLine.Option(names = {"--lang"},
            description = "en or zh (default: en)")
    String lang = "en";

    @CommandLine.Option(names = {"--style"},
            description = "concise or detailed (default: concise)")
    String style = "concise";

    public void run() {
        try {
            int effectiveConcurrent = Math.max(1, llm.maxConcurrent);
            System.setProperty("jhumanify.doc.llmConcurrent", String.valueOf(effectiveConcurrent));

            DocClient client;
            if ("openai".equalsIgnoreCase(llm.provider)) {
                client = new OpenAiDocClient(llm.model, llm.timeoutSec, 256, 0.2);
            } else if ("deepseek".equalsIgnoreCase(llm.provider)) {
                client = new com.initialone.jhumanify.llm.DeepSeekDocClient(null, llm.model, llm.timeoutSec, 256, 0.2);
            } else if ("local".equalsIgnoreCase(llm.provider)) {
                client = new com.initialone.jhumanify.llm.LocalDocClient(llm.localApi, llm.model, llm.timeoutSec, 256, 0.2);
            } else {
                client = new RuleDocClient();
            }

            new DocAnnotator(client, lang, style).annotate(Path.of(srcDir));
            System.out.println("[annotate] done.");
        } catch (Exception e) {
            System.err.println("[annotate] failed: " + e.getMessage());
        }
    }
}