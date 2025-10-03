package com.initialone.jhumanify.model;

/** Reserved for future: carry structured doc suggestions (not used in v1). */
public class DocSuggestion {
    public String kind;   // class|method|ctor|field
    public String target; // name or signature
    public String comment;
}