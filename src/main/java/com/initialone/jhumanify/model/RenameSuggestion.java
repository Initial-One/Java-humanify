package com.initialone.jhumanify.model;

public class RenameSuggestion {
    public String kind;  // method|field|var|class
    public String oldName;
    public String newName;
    // 可加定位信息：classFqn, paramTypes[], file, line, etc.
}