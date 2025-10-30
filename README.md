# Java Humanify
[![English](https://img.shields.io/badge/README-English-blue)](./README.md)
[![简体中文](https://img.shields.io/badge/README-简体中文-brightgreen)](./README_zh.md)

> Humanize decompiled/obfuscated Java code with LLMs (OpenAI, DeepSeek, Ollama, etc.): better names + automatic Javadoc.

Java Humanify uses LLMs to generate more readable, semantic names for **classes / methods / fields / local variables**, and can automatically create Javadoc for **classes / constructors / methods**.  
All rewrites are performed at the **AST layer (JavaParser + Symbol Solver)**, ensuring the output remains **semantically 1:1 equivalent** to the input and stays compilable.

---

## Why it exists

Decompiled / minified / obfuscated Java is hard to read:

```java
package demo.mix;public final class a{private static final int[] O={0,1,1,2};private a(){}public static int h(String s){long x=0x811c9dc5L;if(s==null)return 0;int i=0,n=s.length(),j=O[2];while(i<n){char c=s.charAt(i++);x^=c;x*=0x01000193L;x&=0xffffffffL;j^=(c<<1);j^=j>>>7;if((i&3)==0)x^=(j&0xff);}return (int)x;}
```

Java Humanify renames identifiers into human-friendly ones:

```java
package demo.mix;

/**
 * Computes a 32-bit hash for the input string using FNV-1a with additional state mixing.
 */
public final class HashCalculator {

    private static final int[] O = { 0, 1, 1, 2 };

    /** Private constructor for a utility class to prevent instantiation. */
    private HashCalculator() {}

    /**
     * Calculates a 32-bit hash value for the input string using FNV-1a with additional state mixing.
     *
     * @param inputString the input string
     * @return the computed hash value
     */
    public static int calculateHash(String inputString) {
        long storedValue = 0x811c9dc5L;
        if (inputString == null) return 0;
        int index = 0, stringLength = inputString.length(), hashState = O[2];
        while (index < stringLength) {
            char currentChar = inputString.charAt(index++);
            storedValue ^= currentChar;
            storedValue *= 0x01000193L;
            storedValue &= 0xffffffffL;
            hashState ^= (currentChar << 1);
            hashState ^= hashState >>> 7;
            if ((index & 3) == 0) storedValue ^= (hashState & 0xff);
        }
        return (int) storedValue;
    }
}
```

LLMs do **not** change your code structure.  
They only provide naming / comment suggestions. Renaming is applied on the AST with symbol resolution; constructors/imports/file names are kept in sync.

---

## Key Features

- **Pluggable LLMs**: OpenAI / DeepSeek / Local (Ollama, OpenAI‑compatible endpoints).
- **Semantic package/folder renaming (`package-refactor`)**: rename obfuscated **leaf** package folders (e.g., `ui73`, `controls18`, `a`, `b2`) to meaningful, lowercase segments (e.g., `view`, `controls`, `auth`) and automatically rewrite `package` / `import` lines.
- **Automatic Javadoc (`annotate`)**: supports classes, enums, records, constructors, and methods; auto‑generates `@param/@return/@throws`.
  - Optional **offline heuristic (`dummy`)**: zero cost and no API key, but lower quality than LLMs.
- **Signature‑safe renames**: centered on classFqn / methodSig / fieldFqn; applied at the AST level; constructors/imports/file names updated accordingly.
- **Controllable cost & throughput**: batching (`--batch`) + concurrency (`--max-concurrent`) + snippet truncation (`--head/--tail/--maxBodyLen`).
- **`humanify-apk` one‑shot APK flow**: give it an `.apk` and it will internally decode (apktool/jadx), deobfuscate/rename code, generate Javadoc, and output readable Java source — no extra tools to install.

---

## Pipeline

```
analyze  →  suggest  →  apply  →  annotate
(generate snippets)  (generate names)  (AST apply)  (auto Javadoc)
```

- **analyze**: scans source code to produce `snippets.json` (configurable string‑literal capture and directory exclusion).
- **suggest**: calls LLM/local/heuristics to convert `snippets.json` → `mapping.json` (rename map).
- **apply**: applies the mapping at the AST level, preserving semantics/references and writing to a new directory.
- **annotate**: generates/overwrites Javadoc (supports `--lang zh|en`, `--style concise|detailed`).

> The one‑shot command `humanify` runs these four steps in order on an existing source tree.  
> The one‑shot command `humanify-apk` first decompiles an APK into Java source, then runs the full pipeline automatically and gives you cleaned, renamed, documented code.

---

## Quick Start

### One‑shot (recommended)

```bash
# OpenAI
export OPENAI_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify --provider openai --model gpt-4o-mini samples/src samples/out
```

```bash
# DeepSeek
export DEEPSEEK_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify --provider deepseek --model deepseek-chat samples/src samples/out
```

```bash
# Local (Ollama)
# Make sure the model is pulled: ollama run llama3.1:8b (or any model you prefer)
java -jar target/java-humanify-*.jar humanify --provider local --local-api ollama --endpoint http://localhost:11434 --model llama3.1:8b samples/src samples/out
```

```bash
# APK mode (humanify-apk)
# Input: myapp.apk
# Output: samples/out containing deobfuscated, renamed, documented Java source
export OPENAI_API_KEY=sk-xxxx   # or set DEEPSEEK_API_KEY, or use --provider local
java -jar target/java-humanify-*.jar humanify-apk --provider openai --model gpt-4o-mini myapp.apk samples/out
```

> Execution order of `humanify`: 1) analyze → 2) suggest → 3) apply → 4) annotate  
> Execution order of `humanify-apk`: decode APK → analyze → suggest → apply → annotate  
> `--lang/--style/--overwrite` affect the **annotate** phase. `--provider dummy` uses offline heuristics.  
> **`--package-refactor` — Rename Obfuscated Packages/Folders**  
> (If you want package/folder renaming inside the one‑shot flow, use the `--rename-packages` switch, which is equivalent to running the `package-refactor` subcommand separately.)

---

**Notes**

- Run under version control (git). Commit first so you can revert.
- If you want Chinese Javadoc at other stages in the pipeline, set `--lang zh` in `annotate` / `humanify`.

---

## Providers & Environment Variables

- **OpenAI**: requires `OPENAI_API_KEY`.
- **DeepSeek**: requires `DEEPSEEK_API_KEY`.
- **Local**: use `--provider local` and specify `--local-api openai|ollama` and `--endpoint http://host:port`.

> To produce Chinese Javadoc, **explicitly** set `--lang zh` and choose any of `openai|deepseek|local` providers.

---

## Contributing

Issues and PRs are welcome:
- Use feature branches and keep changes small/testable.
- Follow the existing code style and project structure.

---

## License

Licensed under **Apache-2.0**. See [LICENSE](./LICENSE).

---

## CLI Cheatsheet

```bash
java -jar java-humanify.jar analyze       <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest       <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply         <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar annotate      --src <dir[,dir2,...]> [--lang/--style/--overwrite ...]
java -jar java-humanify.jar humanify      <srcDir> <outDir> [provider/model/annotate opts...]
java -jar java-humanify.jar humanify-apk  <apkFile.apk> <outDir> [provider/model/annotate opts...]
java -jar java-humanify.jar package-refactor --src <dir> [provider/model/opts...]
```
