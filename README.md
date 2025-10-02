# Java Humanify
> Deobfuscate Java code using LLMs ("ChatGPT,LLAMA,DeepSeek,etc")

Java Humanify uses large language models (OpenAI, DeepSeek, Ollama, etc.) to suggest better names (classes, methods, fields, locals), while all actual code changes happen on the AST via JavaParser + Symbol Solver so the output remains 1-to-1 equivalent to the input.

Inspired by **HumanifyJS** — but for Java bytecode turned into decompiled sources.

⸻

## Why this exists

Decompiled / minified / obfuscated Java is painful to read:

```java
public class Foo {
  private int a;
  public int b(int x) { int c = x + a; return c; }
}
```

Java Humanify renames the identifiers coherently:

```java
public class Adder {
  private int base;
  public int add(int value) { int sum = value + base; return sum; }
}
```

LLMs do not touch your code structure.  
They only propose names. The renaming is applied by JavaParser on the AST, with symbol resolution, constructor renames, imports, and file names kept in sync.

⸻

## ✨ Highlights

- 🧠 **Pluggable LLMs**: OpenAI / DeepSeek / Local (Ollama, OpenAI-compatible)  
- 🧩 **Signature-accurate renames** (classFqn, methodSig, fieldFqn, with fallback)  
- 🧪 Works well on small-to-medium projects; robust AST transforms keep code compiling  

⸻

## 🚀 Quick start

Run the all-in-one pipeline (analyze → suggest → apply) with one command:

```bash
# OpenAI example
export OPENAI_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify \
--provider openai \
--model gpt-4o-mini \
samples/src samples/out
```

```bash
# DeepSeek example
export DEEPSEEK_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify \
--provider deepseek \
--model deepseek-chat \
samples/src samples/out
```

```bash
# Local (Ollama) example
# make sure model is pulled: ollama run llama3.1:8b    
java -jar target/java-humanify-*.jar humanify \
--provider local \
--local-api ollama \
--endpoint http://localhost:11434 \
--model llama3.1:8b \
samples/src samples/out
```

⸻

## 🔧 CLI commands

You can also run the steps individually:

### 1) Analyze
```bash
java -jar java-humanify.jar analyze \
<srcDir> <snippets.json> \
[--maxBodyLen 1600] \
[--includeStrings true] \
[--exclude "glob/**"]
```

Generates `snippets.json` with per-method code & metadata (package, FQN, signature, strings).

---

### 2) Suggest
```bash
java -jar java-humanify.jar suggest \
<snippets.json> <mapping.json> \
[--provider dummy|openai|deepseek|local] \
[--model gpt-4o-mini|deepseek-chat|<local-model>] \
[--batch 12] [--endpoint http://localhost:11434] \
[--local-api ollama|openai] \
[--timeout-sec 180] 
```

Auth options:  
- OpenAI: `OPENAI_API_KEY` or `--apiKey`  
- DeepSeek: `DEEPSEEK_API_KEY` or `--ds-key`  
- Local: `--endpoint + --model` (Ollama or OpenAI-compatible)  

---

### 3) Apply
```bash
java -jar java-humanify.jar apply \
<srcDir> <mapping.json> <outDir> \
[--classpath jarOrDir[:morePaths]]
```

Updates class names, constructors, imports, annotations, new expressions, methods, fields, locals (with conflict checks), and file names.  
Symbol solving is improved by `--classpath`.

---

### 4) Humanify (pipeline)
```bash
java -jar java-humanify.jar humanify [provider/model/...] <srcDir> <outDir>
```

Creates intermediate `_pass1` (after class renames), then final output in `<outDir>`.

⸻

## ⚙️ How it works

1. **Analyze** – collect per-method “snippets”: declaration, body, strings, exact methodSig, classFqn.  
2. **Suggest** – LLM (or heuristic) returns a mapping object:  
   - `classFqn: a.b.C -> a.b.NewName`  
   - `methodSig: a.b.C.m(T1,T2) -> newMethodName`  
   - `fieldFqn: a.b.C#x -> newFieldName`  
   - `simple: fallback renames for locals/params`  
3. **Apply** – perform AST-level renames with JavaParser + SymbolSolver so the output compiles.

⸻

## 🛠️ Troubleshooting

- **google-java-format / module access error**  
  Add JVM flag:  
  ```bash
  --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
  ```

- **Local model timeouts (Ollama)**  
  Increase `--timeout-sec` (e.g. `--timeout-sec 300`)  
  Ensure `ollama serve` is running and model is pulled.

- **Symbol resolution misses**  
  Add dependencies via `--classpath`.  

⸻

## 📊 Performance & Costs

- Suggestion cost depends on text size; small projects are cheap.  
- Local models are free but slower & less accurate.  

⸻

## 🤝 Contributing

Issues and PRs are welcome! Please:  
- Use feature branches  
- Keep changes small & tested  
- Follow existing code style  

⸻

## 📜 License

This project is licensed under the **Apache-2.0 License**.  
Copyright (c) 2025 Initial-One  

See [LICENSE](./LICENSE) for details.

⸻

## 📎 Appendix: CLI help (short)

```bash
java -jar java-humanify.jar analyze <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply   <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar humanify [provider/model/...] <srcDir> <outDir>
```

Happy humanifying! 🎉
