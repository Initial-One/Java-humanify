package com.initialone.jhumanify.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.description.JavadocDescription;
import com.initialone.jhumanify.llm.DocClient;
import com.initialone.jhumanify.llm.RuleDocClient;
import com.initialone.jhumanify.util.Formatter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Injects Javadoc comments for classes and methods. */
public class DocAnnotator {

    private final JavaParser parser;
    private final DocClient docClient; // can be RuleDocClient (offline) or LLM client
    private final boolean overwrite;
    private final String lang;
    private final String style;

    public DocAnnotator(DocClient client, boolean overwrite, String lang, String style) {
        ParserConfiguration cfg = new ParserConfiguration();
        this.parser = new JavaParser(cfg);
        this.docClient = client == null ? new RuleDocClient() : client;
        this.overwrite = overwrite;
        this.lang = lang == null ? "zh" : lang;
        this.style = style == null ? "concise" : style;
    }

    public void annotate(List<Path> sourceRoots) throws Exception {
        List<Path> javaFiles = new ArrayList<>();
        for (Path root : sourceRoots) {
            try {
                Files.walk(root).filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
            } catch (IOException ioe) { System.err.println("[annotate] io " + root + " -> " + ioe.getMessage()); }
        }
        for (Path p : javaFiles) {
            processFile(p);
        }
        Formatter.formatJava(sourceRoots);
    }

    private void processFile(Path p) {
        try {
            var res = parser.parse(p);
            if (res.getResult().isEmpty()) return;
            CompilationUnit cu = res.getResult().get();

            List<BodyDeclaration<?>> targets = new ArrayList<>();
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(targets::add);
            cu.findAll(EnumDeclaration.class).forEach(targets::add);
            cu.findAll(RecordDeclaration.class).forEach(targets::add);
            cu.findAll(MethodDeclaration.class).forEach(targets::add);
            cu.findAll(ConstructorDeclaration.class).forEach(targets::add);

            // Build snippets for elements that need docs
            List<BodyDeclaration<?>> needDoc = targets.stream().filter(this::needDoc).collect(Collectors.toList());
            List<String> snippets = needDoc.stream().map(this::snippetOf).collect(Collectors.toList());

            // Generate comments
            List<String> comments;
            try {
                comments = docClient.summarizeDocs(snippets, lang, style);
            } catch (Exception e) {
                System.err.println("[annotate] LLM failed, falling back to heuristics: " + e);
                comments = new RuleDocClient().summarizeDocs(snippets, lang, style);
            }

            for (int i = 0; i < needDoc.size(); i++) {
                BodyDeclaration<?> bd = needDoc.get(i);
                String cmt = comments.get(i);
                if (bd instanceof MethodDeclaration md) {
                    String tags = BehaviorExtractor.renderTags(md, lang);
                    setJavadoc(bd, cmt, tags);
                } else if (bd instanceof ConstructorDeclaration cd) {
                    String tags = BehaviorExtractor.renderTags(cd, lang);
                    setJavadoc(bd, cmt, tags);
                } else {
                    setJavadoc(bd, cmt, "");
                }
            }

            Files.writeString(p, cu.toString());
        } catch (Exception e) {
            System.err.println("[annotate] error at " + p + " -> " + e.getMessage());
        }
    }

    private boolean needDoc(BodyDeclaration<?> bd) {
        boolean has = bd.getComment().isPresent() && bd.getComment().get().isJavadocComment();
        return overwrite || !has;
    }

    private String snippetOf(BodyDeclaration<?> bd) {
        if (bd instanceof MethodDeclaration md) {
            return md.getDeclarationAsString() + "\n" + md.getBody().map(Object::toString).orElse("{}");
        }
        if (bd instanceof ConstructorDeclaration cd) {
            return cd.getDeclarationAsString() + "\n" + cd.getBody().toString();
        }
        if (bd instanceof ClassOrInterfaceDeclaration c) {
            return c.getNameAsString() + " " + c.getExtendedTypes() + " " + c.getImplementedTypes() + "\n" + c.toString();
        }
        return bd.toString();
    }

    private void setJavadoc(BodyDeclaration<?> bd, String main, String tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        // 用 \\R 兼容 \n / \r\n
        for (String line : main.split("\\R")) {
            if (line.isEmpty()) sb.append(" *\n");
            else sb.append(" * ").append(line.trim()).append("\n");
        }
        if (tags != null && !tags.isEmpty()) {
            for (String line : tags.split("\\R")) {
                if (line.isEmpty()) sb.append(" *\n");
                else if (line.startsWith(" *")) sb.append(line).append("\n");
                else sb.append(" * ").append(line).append("\n");
            }
        }
        sb.append(" */");
        String jdoc = sb.toString();

        if (bd instanceof NodeWithJavadoc<?>) {
            ((NodeWithJavadoc<?>) bd).setJavadocComment(jdoc);
        } else {
            // 一些少见节点没实现 NodeWithJavadoc，就直接当作注释挂上去
            bd.setComment(new JavadocComment(jdoc));
        }
    }
}