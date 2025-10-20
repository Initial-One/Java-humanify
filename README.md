# Java Humanify
> Deobfuscate Java code using LLMs ("ChatGPT, Ollama, DeepSeek, etc.")

Java Humanify uses large language models (OpenAI, DeepSeek, Ollama, etc.) to **suggest better names** (classes, methods, fields, locals).  
All actual code changes happen on the **AST** (JavaParser + Symbol Solver), so the output stays **semantically 1:1** with the input.

Now it also supports **auto-Javadoc generation** for classes/constructors/methods via `annotate` (LLM or offline heuristics).

Inspired by **HumanifyJS** — but for Java bytecode turned into decompiled sources.

---

## Why this exists

Decompiled / minified / obfuscated Java is painful to read:

```java
package demo.mix;public final class a{private static final int[] O={0,1,1,2};private a(){}public static int h(String s){long x=0x811c9dc5L;if(s==null)return 0;int i=0,n=s.length(),j=O[2];while(i<n){char c=s.charAt(i++);x^=c;x*=0x01000193L;x&=0xffffffffL;j^=(c<<1);j^=j>>>7;if((i&3)==0)x^=(j&0xff);}return (int)x;}}
```

Java Humanify renames identifiers **and (optionally) adds Javadoc**:

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

## Highlights

- **Pluggable LLMs** for rename & docs: OpenAI / DeepSeek / Local (Ollama, OpenAI-compatible)
- **Auto-Javadoc** via `annotate`:
  - Targets classes/enums/records/constructors/methods
  - Short, safe summaries (no wild guesses)
  - Auto `@param/@return/@throws` based on signatures
  - **Offline heuristics** available (no API key needed)
- **Signature-accurate renames** (classFqn / methodSig / fieldFqn with fallbacks)
- Robust AST transforms keep code compiling

---

## Quick start

### One-shot pipeline (analyze → suggest → apply → annotate)

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

> `humanify` will:
> 1) analyze → 2) suggest → 3) apply → 4) annotate  
> Use `--lang/--style/--overwrite` to control the annotate step.

---

## Performance & Costs

- Rename “Suggest” cost depends on text size; small projects are cheap.  
- Local models are free but typically slower and less accurate.  
- `annotate --provider dummy` is offline & free; LLM doc quality is better but costs tokens.

---

## Contributing

Issues and PRs are welcome! Please:
- Use feature branches
- Keep changes small & tested
- Follow existing code style

---

## License

This project is licensed under the **Apache-2.0 License**.  
See [LICENSE](./LICENSE) for details.

---

## Appendix: CLI help (short)

```bash
java -jar java-humanify.jar analyze  <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest  <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply    <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar annotate --src <dir[,dir2,...]> [--lang/--style/--overwrite ...]
java -jar java-humanify.jar humanify <srcDir> <outDir> [provider/model/annotate opts...]
```