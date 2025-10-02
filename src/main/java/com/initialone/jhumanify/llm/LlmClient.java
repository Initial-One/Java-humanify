package com.initialone.jhumanify.llm;

import java.util.List;
import com.initialone.jhumanify.model.RenameSuggestion;

public interface LlmClient {
    List<RenameSuggestion> suggestRenames(List<String> snippets) throws Exception;
}