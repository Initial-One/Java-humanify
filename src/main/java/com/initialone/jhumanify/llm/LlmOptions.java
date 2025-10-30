package com.initialone.jhumanify.llm;

import picocli.CommandLine;

// 放在与你的命令类同一包里
public class LlmOptions {

    @CommandLine.Option(names="--provider", defaultValue="dummy",
            description = "dummy | openai | local | deepseek")
    public String provider;

    @CommandLine.Option(names="--model", defaultValue="gpt-4o-mini",
            description = "Model name (OpenAI/DeepSeek/Ollama)")
    public String model;

    @CommandLine.Option(names="--local-api", defaultValue="ollama",
            description = "When --provider=local: openai | ollama")
    public String localApi;

    @CommandLine.Option(
            names = "--endpoint",
            description = "Override base URL, OpenAI-compatible API. " +
                    "e.g., https://api.openai.com or http://localhost:11434"
    )
    public String endpoint; // 不给 defaultValue，保持 null

    @CommandLine.Option(names="--timeout-sec", defaultValue="180",
            description = "HTTP read/call timeout seconds for local provider")
    public int timeoutSec;

    @CommandLine.Option(names="--batch", defaultValue="12",
            description = "Max snippets per LLM batch")
    public int batch;

    @CommandLine.Option(names="--max-concurrent", defaultValue="100",
            description = "Max concurrent LLM calls")
    public int maxConcurrent;
}