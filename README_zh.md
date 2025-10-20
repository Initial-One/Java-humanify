# Java Humanify
[![English](https://img.shields.io/badge/README-English-blue)](./README.md)
[![简体中文](https://img.shields.io/badge/README-简体中文-brightgreen)](./README_zh.md)
> 用大语言模型（OpenAI、DeepSeek、Ollama 等）**人性化**反编译/混淆后的 Java 代码：更好的命名 + 自动 Javadoc。

Java Humanify 使用 LLM 为 **类 / 方法 / 字段 / 局部变量**生成更可读的名称，并可为 **类 / 构造器 / 方法**自动生成 Javadoc。  
所有重写都在 **AST 层（JavaParser + Symbol Solver）**完成，保证输出与输入**语义 1:1 等价**，可继续编译。

---

## 为什么需要它

反编译 / 压缩 / 混淆后的 Java 代码非常难读：

```java
package demo.mix;public final class a{private static final int[] O={0,1,1,2};private a(){}public static int h(String s){long x=0x811c9dc5L;if(s==null)return 0;int i=0,n=s.length(),j=O[2];while(i<n){char c=s.charAt(i++);x^=c;x*=0x01000193L;x&=0xffffffffL;j^=(c<<1);j^=j>>>7;if((i&3)==0)x^=(j&0xff);}return (int)x;}
```

Java Humanify 会对标识符进行重命名：

```java
package demo.mix;

/**
 * 使用 FNV-1a 并混入额外状态，计算输入字符串的 32 位哈希值。
 */
public final class HashCalculator {

    private static final int[] O = { 0, 1, 1, 2 };

    /**
     * 私有构造器，防止实例化该工具类。
     */
    private HashCalculator() {}

    /**
     * 基于 FNV-1a 并混入额外状态，计算输入字符串的 32 位哈希值。
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

LLM **不会**触碰你的代码结构。  
它只提出名称/注释建议；实际重命名在 AST 层结合符号解析完成；构造器/imports/文件名等会同步更新。

---

## 主要特性

- **可插拔 LLM**：OpenAI / DeepSeek / 本地（Ollama、OpenAI 兼容端点）。
- **自动 Javadoc（annotate）**：支持类、枚举、record、构造器、方法；自动生成 `@param/@return/@throws`。  
  - 可选 **离线启发式（dummy）**：无需 API Key，零成本，但质量不及 LLM。
- **签名级安全重命名**：以 classFqn / methodSig / fieldFqn 为核心，AST 落地，构造器/导入/文件名协同更新。
- **可控成本与吞吐**：批量（batch）+ 并发（max-concurrent）+ 片段截断（head/tail/maxBodyLen）。

---

## 工作流程（Pipeline）

```
analyze  →  suggest  →  apply  →  annotate
(生成片段)   (生成命名)   (AST应用)   (自动Javadoc)
```

- **analyze**：扫描源码生成 `snippets.json`（可配置包含字符串字面量、排除某些目录）。
- **suggest**：调用 LLM/本地/启发式，将 `snippets.json` → `mapping.json`（重命名映射）。
- **apply**：AST 级应用映射，保证语义/引用一致，输出到新目录。
- **annotate**：生成/覆盖 Javadoc（支持 `--lang zh|en`、`--style concise|detailed`）。

> 推荐使用一条龙命令 `humanify`，内部按顺序执行以上四步。

---

## 快速开始

### 一条龙（推荐）
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
# 本地（Ollama）
# 确保模型已拉取：ollama run llama3.1:8b（或你喜欢的任意模型）
java -jar target/java-humanify-*.jar humanify   --provider local   --local-api ollama   --endpoint http://localhost:11434   --model llama3.1:8b   --lang zh   samples/src samples/out
```

> `humanify` 执行：1) analyze → 2) suggest → 3) apply → 4) annotate  
> `--lang/--style/--overwrite` 影响 **annotate** 阶段。`--provider dummy` 为离线启发式。

---

## 子命令速览与常用参数

### 1) analyze
```bash
java -jar xxx.jar analyze <SRC_DIR> <snippets.json>   [--maxBodyLen 6400]   [--includeStrings true|false]   [--include-ctors true|false]   [--include-class true|false]   [--exclude <glob1,glob2,...>]
```
- `--maxBodyLen`：每个片段正文最大字符数（避免 token 过大）。  
- `--includeStrings`：采集字符串字面量（URL/SQL/日志有助于命名）。  
- `--exclude`：**glob** 排除（例：`**/test/**,**/generated/**`）。

### 2) suggest
```bash
java -jar xxx.jar suggest <snippets.json> <mapping.json>   --provider dummy|openai|local|deepseek   --model gpt-4o-mini   [--local-api openai|ollama]   [--endpoint http://host:port]   [--timeout-sec 180]   [--batch 12]   [--max-concurrent 100]   [--head 40 --tail 30]
```

### 3) apply
```bash
java -jar xxx.jar apply <SRC_DIR> <mapping.json> <OUT_DIR>   [--classpath <jarOrDir:jarOrDir:...>]   [--format | --no-format]
```
- Windows 的分隔符用 `;`，*nix 用 `:`。提供 classpath 有助于符号解析。

### 4) annotate
```bash
java -jar xxx.jar annotate   --src <DIR> [--src <DIR2> ...]   --lang zh|en   --style concise|detailed   --provider dummy|openai|local|deepseek   --model gpt-4o-mini   [--local-api openai|ollama]   [--endpoint http://host:port]   [--timeout-sec 180]   [--batch 12]   [--max-concurrent 100]   [--overwrite]
```

### 5) humanify（一条龙）
```bash
java -jar xxx.jar humanify <SRC_DIR> <OUT_DIR>   --provider dummy|openai|local|deepseek   --model <name>   --lang zh|en   [其它 suggest/annotate 相关参数...]
```

---

## Provider & 环境变量

- **OpenAI**：`OPENAI_API_KEY`（必需）；
- **DeepSeek**：`DEEPSEEK_API_KEY`（必需）；
- **本地**：`--provider local` + `--local-api openai|ollama` + `--endpoint http://host:port`。

> 生成中文注释：请**显式** `--lang zh`，并使用 `openai|deepseek|local` 之一。

---

## 参与贡献

欢迎提交 Issue / PR：
- 使用特性分支，保持改动小且可测；
- 遵循现有代码风格与目录约定。

---

## 许可证

本项目使用 **Apache-2.0 License**。详见 [LICENSE](./LICENSE)。

---

## CLI 速查

```bash
java -jar java-humanify.jar analyze  <srcDir> <snippets.json> [opts]
java -jar java-humanify.jar suggest  <snippets.json> <mapping.json> [opts]
java -jar java-humanify.jar apply    <srcDir> <mapping.json> <outDir> [--classpath ...]
java -jar java-humanify.jar annotate --src <dir[,dir2,...]> [--lang/--style/--overwrite ...]
java -jar java-humanify.jar humanify <srcDir> <outDir> [provider/model/annotate opts...]
```
