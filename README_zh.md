# Java Humanify
[![English](https://img.shields.io/badge/README-English-blue)](./README.md)
[![简体中文](https://img.shields.io/badge/README-简体中文-brightgreen)](./README_zh.md)

> 使用 LLM（OpenAI、DeepSeek、Ollama 等）为反编译/混淆后的 Java 代码「人性化」命名，并自动生成 Javadoc。

Java Humanify 利用大模型为**类 / 方法 / 字段 / 局部变量**生成更可读的语义化名称，并可为**类 / 构造器 / 方法**自动生成 Javadoc。  
所有改写都在 **AST 层（JavaParser + Symbol Solver）** 完成，确保输出与输入在语义上 **1:1 等价**，且可继续编译。

---

## 为什么需要它

反编译 / 压缩 / 混淆后的 Java 代码很难读：

```java
package demo.mix;public final class a{private static final int[] O={0,1,1,2};private a(){}public static int h(String s){long x=0x811c9dc5L;if(s==null)return 0;int i=0,n=s.length(),j=O[2];while(i<n){char c=s.charAt(i++);x^=c;x*=0x01000193L;x&=0xffffffffL;j^=(c<<1);j^=j>>>7;if((i&3)==0)x^=(j&0xff);}return (int)x;}}
```

Java Humanify 会把标识符重命名：

```java
package demo.mix;

/**
 * 使用带额外状态混合的 FNV-1a 计算输入字符串的 32 位哈希值。
 */
public final class HashCalculator {

    private static final int[] O = { 0, 1, 1, 2 };

    /** 工具类的私有构造方法，防止被实例化。 */
    private HashCalculator() {}

    /**
     * 使用带额外状态混合的 FNV-1a 算法计算输入字符串的 32 位哈希值。
     *
     * @param inputString 参数
     * @return 返回值
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

LLM **不会**改变你的代码结构；  
它们只提供“命名 / 注释”的建议。重命名在 AST 层并配合符号解析进行；构造器 / import / 文件名会保持一致。

---

## 主要特性

- **可插拔的 LLM**：OpenAI / DeepSeek / Local（Ollama、OpenAI 兼容端点）。
- **语义化包/文件夹重命名（`package-refactor`）**：把混淆的叶子包名（如 `ui73`、`controls18`、`a`、`b2`）重命名为有意义的小写段（如 `view`、`controls`、`auth`），并自动重写 `package` / `import`。
- **自动 Javadoc（`annotate`）**：支持类、枚举、record、构造器、方法；自动生成 `@param/@return/@throws`。
    - 可选 **离线启发式（`dummy`）**：无需 API Key、零成本，但质量低于 LLM。
- **签名安全的重命名**：以 classFqn / methodSig / fieldFqn 为核心；在 AST 层生效；同步更新构造器、imports 与文件名。
- **可控成本与吞吐**：批处理（`--batch`）+ 并发（`--max-concurrent`）+ 片段截断（`--head/--tail/--maxBodyLen`）。
- **`humanify-apk` 一站式 APK 流程**：输入 `.apk`，内部自动解包（apktool/jadx）、去混淆/重命名、生成 Javadoc，输出可读的 Java 源码——无需额外安装工具。

---

## 流水线

```
analyze  →  suggest  →  apply  →  annotate
(生成片段)     (生成命名)     (AST 应用)   (自动 Javadoc)
```

- **analyze**：扫描源码生成 `snippets.json`（可配置字符串捕获与目录排除）。
- **suggest**：调用 LLM / 本地 / 启发式，把 `snippets.json` → `mapping.json`（重命名映射）。
- **apply**：在 AST 层应用映射，保持语义/引用关系，输出到新目录。
- **annotate**：生成/覆盖 Javadoc（支持 `--lang zh|en`、`--style concise|detailed`）。

> 一站式命令 `humanify` 会在已有源码树上按上述顺序执行四步。  
> 一站式命令 `humanify-apk` 会先反编译 APK 到 Java 源码，再自动跑完整流程，产出更可读、带注释的源码。

---

## 快速开始

### 一站式（推荐）

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
# 本地（Ollama）
# 确保模型已拉取：ollama run llama3.1:8b（或任意你喜欢的模型）
java -jar target/java-humanify-*.jar humanify --provider local --local-api ollama --endpoint http://localhost:11434 --model llama3.1:8b samples/src samples/out
```

```bash
# APK 模式（humanify-apk）
# 输入：myapp.apk
# 输出：samples/out，包含去混淆、重命名、带文档的 Java 源码
export OPENAI_API_KEY=sk-xxxx   # 或设置 DEEPSEEK_API_KEY，或改用 --provider local
java -jar target/java-humanify-*.jar humanify-apk --provider openai --model gpt-4o-mini myapp.apk samples/out
```

> `humanify` 执行顺序：1) analyze → 2) suggest → 3) apply → 4) annotate  
> `humanify-apk` 执行顺序：解包 APK → analyze → suggest → apply → annotate  
> `--lang/--style/--overwrite` 影响 **annotate** 阶段。`--provider dummy` 使用离线启发式。  
> **`--package-refactor` — 重命名混淆包/文件夹**  
> （如果你在 one‑shot 流程中启用包/文件夹重命名，请使用 `--rename-packages` 开关，等价于单独运行 `package-refactor` 子命令。）

---

**注意事项**

- 请在版本控制（git）下运行。先提交一次，便于回滚。
- 若在流水线其他位置需要中文 Javadoc，请在 `annotate`/`humanify` 中显式设置 `--lang zh`。

---

## 服务提供方与环境变量

- **OpenAI**：需要 `OPENAI_API_KEY`。
- **DeepSeek**：需要 `DEEPSEEK_API_KEY`。
- **Local**：使用 `--provider local`，并指定 `--local-api openai|ollama` 与 `--endpoint http://host:port`。

> 若需要生成中文 Javadoc，请**显式**设置 `--lang zh`，并任选 `openai|deepseek|local` 中的提供方。

---

## 参与贡献

欢迎提 Issue / PR：
- 使用特性分支，尽量保持改动小且可测试。
- 遵循现有的代码风格与工程结构。

---

## 许可证

使用 **Apache-2.0** 许可证。详见 [LICENSE](./LICENSE)。

---

## CLI 速查

```bash
java -jar java-humanify.jar analyze       <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest       <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply         <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar annotate      --src <dir[,dir2,...]> [--lang/--style/--overwrite ...]
java -jar java-humanify.jar humanify      <srcDir> <outDir> [provider/model/annotate opts...]
java -jar java-humanify.jar humanify-apk  <apkFile.apk> <outDir> [provider/model/annotate opts...]
java -jar java-humanify.jar package-refactor --src <dir> [provider/model/opts...]
```
