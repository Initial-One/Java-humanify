package com.initialone.jhumanify.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SnippetExtractor {
    public static List<String> extractMethodSnippets(Path srcRoot) throws IOException {
        JavaParser parser = new JavaParser();
        List<String> out = new ArrayList<>();
        Files.walk(srcRoot).filter(p->p.toString().endsWith(".java")).forEach(p->{
            try {
                var res = parser.parse(p);
                res.getResult().ifPresent(cu -> {
                    cu.findAll(MethodDeclaration.class).forEach(md -> {
                        String snippet = md.getDeclarationAsString() + "\n" + md.getBody().map(Object::toString).orElse("{}");
                        out.add(snippet);
                    });
                });
            } catch (Exception e) { /* ignore one file */ }
        });
        return out;
    }
}