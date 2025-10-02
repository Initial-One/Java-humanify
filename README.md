# Java Humanify

Deobfuscate & humanize Java code using LLMs (“AI”) + AST-safe rewrites

Java Humanify uses large language models (OpenAI, DeepSeek, Ollama, etc.) to suggest better names (classes, methods, fields, locals), while all actual code changes happen on the AST via JavaParser + Symbol Solver so the output remains 1-to-1 equivalent to the input.

Inspired by HumanifyJS — but for Java bytecode turned into decompiled sources.

⸻

Why this exists

Decompiled / minified / obfuscated Java is painful to read:
public class Foo {
  private int a;
  public int b(int x) { int c = x + a; return c; }
}

Java Humanify renames the identifiers coherently:
public class Adder {
  private int base;
  public int add(int value) { int sum = value + base; return sum; }
}

LLMs do not touch your code structure. They only propose names. The renaming is applied by JavaParser on the AST, with symbol resolution, constructor renames, imports, and file names kept in sync.

⸻

Highlights
	•	⚙️ No Python – pure Java (CLI .jar)
	•	🧠 Pluggable LLMs: OpenAI / DeepSeek / Local (Ollama, OpenAI-compatible)
	•	🧩 Signature-accurate renames
classFqn, methodSig, fieldFqn, and a simple fallback
	•	🧭 Semantic presets to correct common patterns (e.g. --preset adder, --preset kv)
	•	🧪 Works well on small-to-medium projects; robust AST transforms keep code compiling

⸻

Quick start

Run the all-in-one pipeline (analyze → suggest → apply) with one command:
# OpenAI example
export OPENAI_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify \
  --provider openai \
  --model gpt-4o-mini \
  samples/src samples/out

# DeepSeek example
export DEEPSEEK_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify \
  --provider deepseek \
  --model deepseek-chat \
  samples/src samples/out

# Local (Ollama) example
# ollama run llama3.1:8b    
# make sure model is pulled
java -jar target/java-humanify-*.jar humanify \
  --provider local \
  --local-api ollama \
  --endpoint http://localhost:11434 \
  --model llama3.1:8b \
  samples/src samples/out

⸻

CLI commands
You can also run the steps individually.
1) Analyze (extract snippets)
java -jar java-humanify.jar analyze <srcDir> <snippets.json> \
  [--maxBodyLen 1600] [--includeStrings true] [--exclude "glob/**"]

Outputs a snippets.json with per-method code & metadata (package, FQN, signature, strings).

2) Suggest (use LLM or heuristic to produce mapping)
java -jar java-humanify.jar suggest <snippets.json> <mapping.json> \
  [--provider dummy|openai|deepseek|local] \
  [--model gpt-4o-mini|deepseek-chat|<local-model>] \
  [--batch 12] \
  [--endpoint http://localhost:11434] \
  [--local-api ollama|openai] \
  [--timeout-sec 180] 

Auth (depending on provider):
	•	OpenAI: OPENAI_API_KEY env or --apiKey (if your client supports it)
	•	DeepSeek: DEEPSEEK_API_KEY env or --ds-key
(you can also set --ds-base https://api.deepseek.com)
	•	Local: --endpoint + --model (Ollama or OpenAI-compatible)

3) Apply (rename safely on AST)
java -jar java-humanify.jar apply <srcDir> <mapping.json> <outDir> \
  [--classpath jarOrDir[:morePaths]]

  	•	Updates class names, constructors, imports, annotation types, new expressions, method names and calls, fields and references, local names (with conflict checks), and file names (public types).
	•	Symbol solving is improved by --classpath (add your project jars or decompiled deps).

4) Humanify (pipeline)
java -jar java-humanify.jar humanify \
  [--provider dummy|openai|deepseek|local] \
  [--model ...] \
  [--local-api ollama|openai] \
  [--endpoint http://localhost:11434] \
  [--timeout-sec 180] \
  [--classpath ...] \
  <srcDir> <outDir>

Creates an intermediate outDir/_pass1 (after class renames), then finishes in outDir.
By default, _pass1 is removed automatically at the end.

⸻

  How it works
	1.	Analyze – collect per-method “snippets”: declaration, body, strings, exact methodSig, classFqn.
	2.	Suggest – LLM (or heuristic) returns a mapping object:
	•	classFqn: a.b.C -> a.b.NewName
	•	methodSig: a.b.C.m(T1,T2) -> newMethodName
	•	fieldFqn: a.b.C#x -> newFieldName
	•	simple: fallback renames for short locals/params (a,b,c,x,i…)
The tool also locks the keys (only keys extracted from your code are allowed) and runs semantic presets (adder, kv) to correct weak suggestions.
	3.	Apply – perform AST-level renames with JavaParser + SymbolSolver so the output compiles and behavior is unchanged.


⸻

Troubleshooting

🧩 google-java-format / module access error

If you enable code formatting and see:
cannot access class com.sun.tools.javac.parser.Tokens$TokenKind (in module jdk.compiler)
because module jdk.compiler does not export com.sun.tools.javac.parser to unnamed module
Run Java with this JVM flag (or set JAVA_TOOL_OPTIONS):
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
⏱️ Local model timeouts (Ollama)
	•	Increase --timeout-sec, e.g. --timeout-sec 300
	•	Ensure ollama serve is running and the model is pulled (ollama run <model>)

🧭 Symbol resolution misses
	•	Add your jars/dirs with --classpath to improve JavaParser resolution.
	•	For multi-module sources, run per module or include all source roots.

⸻

Performance & Costs
	•	Suggestion cost ≈ depends on your text size; small projects are very cheap.
	•	Local models are free but may be slower and less accurate; presets help.

⸻

Contributing

Issues and PRs are welcome! Please:
	•	Use feature branches
	•	Keep changes small & tested
	•	Follow existing code style

⸻

License

This project is licensed under the Apache-2.0 License.
Copyright (c) [2025] [Initial-One]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
...

⸻

Appendix: CLI help (short)
java -jar java-humanify.jar analyze <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply   <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar humanify [provider/model/...] <srcDir> <outDir>

Happy humanifying!