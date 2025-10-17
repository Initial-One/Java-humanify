package com.initialone.jhumanify.commands;

import com.initialone.jhumanify.ast.DocAnnotator;
import com.initialone.jhumanify.llm.DocClient;
import com.initialone.jhumanify.llm.OpenAiDocClient;
import com.initialone.jhumanify.llm.RuleDocClient;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@CommandLine.Command(name = "annotate", description = "Add Javadoc summaries for classes and methods.")
public class AnnotateCmd implements Runnable {

    @CommandLine.Option(names = {"-s", "--src"}, required = true,
            description = "One or more source roots (dirs).", split = ",")
    List<Path> sourceRoots = new ArrayList<>();

    @CommandLine.Option(names = {"--lang"},
            description = "en or zh (default: en)")
    String lang = "en";

    @CommandLine.Option(names = {"--style"},
            description = "concise or detailed (default: concise)")
    String style = "concise";

    @CommandLine.Option(names = {"--overwrite"},
            description = "Overwrite existing Javadoc if present.")
    boolean overwrite = false;

    @CommandLine.Option(names = {"--provider"}, defaultValue = "dummy",
            description = "dummy|openai|local|deepseek")
    String provider;

    @CommandLine.Option(names = {"--model"}, defaultValue = "gpt-4o-mini",
            description = "Model name (OpenAI/local/deepseek)")
    String model;

    @CommandLine.Option(names = {"--local-api"}, defaultValue = "ollama",
            description = "When --provider=local: openai|ollama")
    String localApi;

    @CommandLine.Option(names = {"--endpoint"}, defaultValue = "http://localhost:11434",
            description = "Local endpoint. OpenAI-compat: http://localhost:1234/v1; Ollama: http://localhost:11434")
    String endpoint;

    @CommandLine.Option(names = {"--timeout-sec"}, defaultValue = "180",
            description = "HTTP read/call timeout seconds for local provider")
    int timeoutSec;

    @CommandLine.Option(names = "--batch", defaultValue = "12",
            description = "Max snippets per LLM batch")
    int batch;

    @CommandLine.Option(
            names = "--max-concurrent",
            defaultValue = "100",
            description = "Max concurrent LLM calls")
    int maxConcurrent;

    public void run() {
        try {
            int effectiveConcurrent = Math.max(1, maxConcurrent);
            System.setProperty("jhumanify.doc.llmConcurrent", String.valueOf(effectiveConcurrent));

            DocClient client;
            if ("openai".equalsIgnoreCase(provider)) {
                client = new OpenAiDocClient(model, timeoutSec, 256, 0.2);
            } else if ("deepseek".equalsIgnoreCase(provider)) {
                client = new com.initialone.jhumanify.llm.DeepSeekDocClient(null, model, timeoutSec, 256, 0.2);
            } else if ("local".equalsIgnoreCase(provider)) {
                client = new com.initialone.jhumanify.llm.LocalDocClient(localApi, model, timeoutSec, 256, 0.2);
            } else {
                client = new RuleDocClient();
            }

            new DocAnnotator(client, overwrite, lang, style).annotate(sourceRoots);
            System.out.println("[annotate] done.");
        } catch (Exception e) {
            System.err.println("[annotate] failed: " + e.getMessage());
        }
    }
}