package com.initialone.jhumanify.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class Mapping {
    /** 兜底短名改名（本地变量/参数/私有成员） */
    public Map<String,String> simple    = new LinkedHashMap<>();
    /** 类全名映射：a.b.C -> a.b.UserService */
    public Map<String,String> classFqn  = new LinkedHashMap<>();
    /** 字段映射：a.b.C#field -> newName */
    public Map<String,String> fieldFqn  = new LinkedHashMap<>();
    /** 方法映射：a.b.C.m(T1,T2) -> newName */
    public Map<String,String> methodSig = new LinkedHashMap<>();
}