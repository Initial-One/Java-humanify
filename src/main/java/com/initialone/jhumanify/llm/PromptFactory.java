package com.initialone.jhumanify.llm;

public class PromptFactory {
    public static String renamePrompt(String snippet) {
        return """
                        You are a senior Java reverse-engineering assistant.
                        Given the Java snippet, propose better, meaningful names.

                        Return STRICT JSON array. Each item:
                        {
                          "kind": "class|method|field|var",
                          "old": "<oldName>",
                          "new": "<newName>",
                          "reason": "<one-line>"
                        }

                        Constraints:
                        - Valid Java identifiers only
                        - Avoid name collisions within the snippet
                        - Keep semantics suggested by string literals and call sites

                        Code:
                        ```java
                        %s
                """.formatted(snippet);
    }
}