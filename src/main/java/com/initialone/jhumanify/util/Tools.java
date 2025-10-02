package com.initialone.jhumanify.util;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.ArrayList;
import java.util.List;

public class Tools {
    public static String simpleName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** 类声明 FQN：优先符号求解，失败则用 pkg+简单名 */
    public static String fqnOfClassDecl(ClassOrInterfaceDeclaration cd, String pkg) {
        try {
            return cd.resolve().getQualifiedName();
        } catch (Throwable ignore) {
            return pkg.isBlank() ? cd.getNameAsString() : (pkg + "." + cd.getNameAsString());
        }
    }

    /** 字段 FQN：a.b.C#field */
    public static String fieldFqn(ResolvedFieldDeclaration f) {
        String owner;
        try {
            owner = f.declaringType().getQualifiedName();
        } catch (Throwable t) {
            owner = f.declaringType().getName();
        }
        return owner + "#" + f.getName();
    }

    /** 方法签名：a.b.C.m(T1,T2) */
    public static String qualifiedSignature(ResolvedMethodDeclaration m) {
        try {
            // 新版本通常有 getQualifiedSignature()
            return m.getQualifiedSignature();
        } catch (Throwable ignore) {
            String cls = m.declaringType().getQualifiedName();
            List<String> params = new ArrayList<>();
            for (int i = 0; i < m.getNumberOfParams(); i++) {
                params.add(m.getParam(i).getType().describe());
            }
            return cls + "." + m.getName() + "(" + String.join(",", params) + ")";
        }
    }

    public static String pkgName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }
}
