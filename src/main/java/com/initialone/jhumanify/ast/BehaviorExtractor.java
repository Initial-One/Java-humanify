package com.initialone.jhumanify.ast;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.description.JavadocDescription;

import java.util.*;
import java.util.stream.Collectors;

/** Lightweight signals used by DocAnnotator to enrich comments without LLM. */
public class BehaviorExtractor {

    public static Map<String, Object> extract(MethodDeclaration md) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("params", md.getParameters().stream().map(Parameter::getNameAsString).collect(Collectors.toList()));
        map.put("returns", md.getType().asString());
        map.put("throws", md.getThrownExceptions().stream().map(Object::toString).collect(Collectors.toList()));

        // control flow signals
        int loops = md.findAll(ForStmt.class).size()
                + md.findAll(ForEachStmt.class).size()
                + md.findAll(WhileStmt.class).size()
                + md.findAll(DoStmt.class).size();
        int conditionals = md.findAll(IfStmt.class).size() + md.findAll(SwitchStmt.class).size();
        map.put("loops", loops);
        map.put("conditionals", conditionals);

        // called method names (simple)
        List<String> calls = md.findAll(MethodCallExpr.class).stream()
                .map(m -> m.getNameAsString())
                .limit(8)
                .collect(Collectors.toList());
        map.put("calls", calls);
        map.put("size", md.getBody().map(b->b.getStatements().size()).orElse(0));

        return map;
    }

    /** Render @param/@return/@throws tags in a basic way. */
    public static String renderTags(MethodDeclaration md, String lang) {
        StringBuilder sb = new StringBuilder();
        for (Parameter p : md.getParameters()) {
            if (lang.equals("zh")) sb.append(" * @param ").append(p.getNameAsString()).append(" 参数\n");
            else sb.append(" * @param ").append(p.getNameAsString()).append(" parameter\n");
        }
        if (!md.getType().isVoidType()) {
            if (lang.equals("zh")) sb.append(" * @return 返回值\n");
            else sb.append(" * @return return value\n");
        }
        md.getThrownExceptions().forEach(t -> {
            if (lang.equals("zh")) sb.append(" * @throws ").append(t).append(" 可能抛出的异常\n");
            else sb.append(" * @throws ").append(t).append(" possible exception\n");
        });
        return sb.toString();
    }

    public static String renderTags(ConstructorDeclaration cd, String lang) {
        StringBuilder sb = new StringBuilder();
        for (Parameter p : cd.getParameters()) {
            if (lang.equals("zh")) sb.append(" * @param ").append(p.getNameAsString()).append(" 参数\n");
            else sb.append(" * @param ").append(p.getNameAsString()).append(" parameter\n");
        }
        cd.getThrownExceptions().forEach(t -> {
            if (lang.equals("zh")) sb.append(" * @throws ").append(t).append(" 可能抛出的异常\n");
            else sb.append(" * @throws ").append(t).append(" possible exception\n");
        });
        return sb.toString();
    }
}