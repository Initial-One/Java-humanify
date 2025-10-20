# Java Humanify
[![English](https://img.shields.io/badge/README-English-blue)](./README.md)
[![简体中文](https://img.shields.io/badge/README-简体中文-brightgreen)](./README_zh.md)
> Humanize decompiled/obfuscated Java code with LLMs (OpenAI, DeepSeek, Ollama, etc.): better names + automatic Javadoc.

Java Humanify uses LLMs to generate more readable names for **classes / methods / fields / local variables**, and can automatically create Javadoc for **classes / constructors / methods**.  
All rewrites are performed at the **AST layer (JavaParser + Symbol Solver)**, ensuring the output remains **semantically 1:1 equivalent** to the input and stays compilable.

---

## Why this exists

Decompiled / minified / obfuscated Java is painful to read:

```java
package demo.mix;public final class a{private static final int[] O={0,1,1,2};private a(){}public static int h(String s){long x=0x811c9dc5L;if(s==null)return 0;int i=0,n=s.length(),j=O[2];while(i<n){char c=s.charAt(i++);x^=c;x*=0x01000193L;x&=0xffffffffL;j^=(c<<1);j^=j>>>7;if((i&3)==0)x^=(j&0xff);}return (int)x;}}
```

Java Humanify renames identifiers:

```java
package demo.mix;

/**
 * Computes a 32-bit hash for the input string using FNV-1a with additional state mixing.
 */
public final class HashCalculator {

    private static final int[] O = { 0, 1, 1, 2 };

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private HashCalculator() {}

    /**
     * Calculates a 32-bit hash value for the input string using FNV-1a with additional state mixing.
     *
     * @param inputString parameter
     * @return return value
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

LLMs do **not** touch your code structure.  
They only propose names / comments. Renaming is applied on the AST with symbol resolution; constructors/imports/file names kept in sync.

---

## Key Features

- **Pluggable LLMs**: OpenAI / DeepSeek / Local (Ollama, OpenAI-compatible endpoints).
- **Automatic Javadoc (`annotate`)**: supports classes, enums, records, constructors, and methods; auto-generates `@param/@return/@throws`.  
  - Optional **offline heuristics (`dummy`)**: zero cost and no API key, but lower quality than LLMs.
- **Signature-safe renames**: centered on classFqn / methodSig / fieldFqn; applied at the AST level; constructors/imports/file names updated accordingly.
- **Controllable cost & throughput**: batching (`--batch`) + concurrency (`--max-concurrent`) + snippet truncation (`--head/--tail/--maxBodyLen`).

---

## Pipeline

```
analyze  →  suggest  →  apply  →  annotate
(generate snippets)  (generate names)  (AST apply)  (auto Javadoc)
```

- **analyze**: scans source code to produce `snippets.json` (configurable string-literal capture and directory exclusion).
- **suggest**: calls LLM/local/heuristics to convert `snippets.json` → `mapping.json` (rename map).
- **apply**: applies the mapping at the AST level, preserving semantics/references and writing to a new directory.
- **annotate**: generates/overwrites Javadoc (supports `--lang zh|en`, `--style concise|detailed`).

> The one-shot command `humanify` runs these four steps in order.

---

## Quick Start

### One-shot (recommended)

```bash
# OpenAI
export OPENAI_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify   --provider openai   --model gpt-4o-mini   --lang zh   samples/src samples/out
```

```bash
# DeepSeek
export DEEPSEEK_API_KEY=sk-xxxx
java -jar target/java-humanify-*.jar humanify   --provider deepseek   --model deepseek-chat   --lang zh   samples/src samples/out
```

```bash
# Local (Ollama)
# Make sure the model is pulled: ollama run llama3.1:8b (or any model you prefer)
java -jar target/java-humanify-*.jar humanify   --provider local   --local-api ollama   --endpoint http://localhost:11434   --model llama3.1:8b   --lang zh   samples/src samples/out
```

> Execution order of `humanify`: 1) analyze → 2) suggest → 3) apply → 4) annotate  
> `--lang/--style/--overwrite` affect the **annotate** phase. `--provider dummy` uses offline heuristics.

---

## Subcommands & Common Options

### 1) analyze
```bash
java -jar xxx.jar analyze <SRC_DIR> <snippets.json>   [--maxBodyLen 6400]   [--includeStrings true|false]   [--include-ctors true|false]   [--include-class true|false]   [--exclude <glob1,glob2,...>]
```
- `--maxBodyLen`: maximum characters captured per snippet body (helps avoid excessive tokens).  
- `--includeStrings`: capture string literals (URLs/SQL/logs often help naming).  
- `--exclude`: **glob** exclusions (e.g., `**/test/**,**/generated/**`).

### 2) suggest
```bash
java -jar xxx.jar suggest <snippets.json> <mapping.json>   --provider dummy|openai|local|deepseek   --model gpt-4o-mini   [--local-api openai|ollama]   [--endpoint http://host:port]   [--timeout-sec 180]   [--batch 12]   [--max-concurrent 100]   [--head 40 --tail 30]
```

### 3) apply
```bash
java -jar xxx.jar apply <SRC_DIR> <mapping.json> <OUT_DIR>   [--classpath <jarOrDir:jarOrDir:...>]   [--format | --no-format]
```
- On Windows, use `;` as the path separator; on *nix, use `:`. Providing a classpath can improve symbol resolution.

### 4) annotate
```bash
java -jar xxx.jar annotate   --src <DIR> [--src <DIR2> ...]   --lang zh|en   --style concise|detailed   --provider dummy|openai|local|deepseek   --model gpt-4o-mini   [--local-api openai|ollama]   [--endpoint http://host:port]   [--timeout-sec 180]   [--batch 12]   [--max-concurrent 100]   [--overwrite]
```

### 5) humanify (one-shot)
```bash
java -jar xxx.jar humanify <SRC_DIR> <OUT_DIR>   --provider dummy|openai|local|deepseek   --model <name>   --lang zh|en   [other suggest/annotate related options...]
```

---

## Providers & Environment Variables

- **OpenAI**: `OPENAI_API_KEY` (required).  
- **DeepSeek**: `DEEPSEEK_API_KEY` (required).  
- **Local**: `--provider local` + `--local-api openai|ollama` + `--endpoint http://host:port`.

> To produce Chinese Javadoc, **explicitly** set `--lang zh` and use one of `openai|deepseek|local`.

---

## Contributing

Issues and PRs are welcome:
- Use feature branches and keep changes small/testable.
- Follow the existing code style and layout conventions.

---

## License

Licensed under **Apache-2.0**. See [LICENSE](./LICENSE).

---

## CLI Cheatsheet

```bash
java -jar java-humanify.jar analyze  <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest  <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply    <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar annotate --src <dir[,dir2,...]> [--lang/--style/--overwrite ...]
java -jar java-humanify.jar humanify <srcDir> <outDir> [provider/model/annotate opts...]
```
