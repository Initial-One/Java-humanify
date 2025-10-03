package com.initialone.jhumanify.llm;

import java.util.List;

/** A minimal interface for generating short Javadoc-style comments for Java code snippets. */
public interface DocClient {
    /**
     * Generate a single-line (or two) summary for each code snippet.
     * @param snippets list of self-contained Java code snippets (class or method body with signature)
     * @param lang "en" or "zh"
     * @param style "concise" or "detailed"
     * @return list of comments (same order as snippets), without markers
     * @throws Exception on network or parsing errors
     */
    List<String> summarizeDocs(List<String> snippets, String lang, String style) throws Exception;
}