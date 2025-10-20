package com.initialone.jhumanify.commands;

import picocli.CommandLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(
        name = "analyze",
        description = "Scan Java sources, extract method/ctor/class snippets, write to snippets.json"
)
public class AnalyzeCmd implements Runnable {

    @CommandLine.Parameters(index = "0", description = "Input source dir (decompiled Java)")
    String srcDir;

    @CommandLine.Parameters(index = "1", description = "Output snippets.json path")
    String outJson;

    @CommandLine.Option(names = "--maxBodyLen", defaultValue = "12000",
            description = "Max characters of body captured per snippet")
    int maxBodyLen;

    static class Snippet {
        public String file;          // relative path
        public String pkg;           // package name
        public String classFqn;      // a.b.C
        public String className;     // C
        public String methodName;    // foo or ctor name; empty for class-level snippet
        public List<String> paramTypes = new ArrayList<>(); // as written text
        public String qualifiedSignature; // a.b.C.foo(java.lang.String,int) ; or classFqn for class-level
        public String decl;          // declaration line
        public String code;          // declaration + body (truncated) / class toString truncated
        public List<String> strings = new ArrayList<>();
    }

    @Override
    public void run() {
        try {
            Path root = Paths.get(srcDir);
            JavaParser parser = new JavaParser();

            List<Snippet> out = new ArrayList<>();
            int methodCount = 0, ctorCount = 0, classCount = 0;

            try (var paths = Files.walk(root)) {
                for (Path p : paths.filter(this::isJavaFile).collect(Collectors.toList())) {
                    ParseResult<CompilationUnit> res = parser.parse(p);
                    if (!res.isSuccessful() || res.getResult().isEmpty()) {
                        System.err.println("[analyze] parse fail: " + p);
                        continue;
                    }
                    CompilationUnit cu = res.getResult().get();
                    String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

                    for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                        String className = cd.getNameAsString();
                        String classFqn = pkg.isBlank() ? className : (pkg + "." + className);

                        // -------- methods --------
                        for (MethodDeclaration md : cd.getMethods()) {
                            Snippet s = new Snippet();
                            s.file = root.relativize(p).toString();
                            s.pkg = pkg;
                            s.classFqn = classFqn;
                            s.className = className;
                            s.methodName = md.getNameAsString();
                            s.paramTypes = md.getParameters().stream()
                                    .map(param -> param.getType().toString())
                                    .collect(Collectors.toList());
                            s.decl = md.getDeclarationAsString(false, false, false);
                            s.qualifiedSignature = classFqn + "." + s.methodName + "(" + String.join(",", s.paramTypes) + ")";
                            String body = md.getBody().map(Object::toString).orElse("{}");
                            s.code = truncate(s.decl + "\n" + body, maxBodyLen);
                            if (true) {
                                s.strings.addAll(
                                        md.findAll(StringLiteralExpr.class).stream()
                                                .map(StringLiteralExpr::asString)
                                                .distinct()
                                                .limit(64)
                                                .toList()
                                );
                            }
                            out.add(s);
                            methodCount++;
                        }

                        // -------- ctors --------
                        for (ConstructorDeclaration ctor : cd.getConstructors()) {
                            Snippet s = new Snippet();
                            s.file = root.relativize(p).toString();
                            s.pkg = pkg;
                            s.classFqn = classFqn;
                            s.className = className;
                            s.methodName = ctor.getNameAsString(); // 与类同名
                            s.paramTypes = ctor.getParameters().stream()
                                    .map(param -> param.getType().toString())
                                    .collect(Collectors.toList());
                            s.decl = ctor.getDeclarationAsString(false, false, false);
                            s.qualifiedSignature = classFqn + "." + s.methodName + "(" + String.join(",", s.paramTypes) + ")";
                            String body = ctor.getBody().toString();
                            s.code = truncate(s.decl + "\n" + body, maxBodyLen);
                            if (true) {
                                s.strings.addAll(
                                        ctor.findAll(StringLiteralExpr.class).stream()
                                                .map(StringLiteralExpr::asString)
                                                .distinct()
                                                .limit(64)
                                                .toList()
                                );
                            }
                            out.add(s);
                            ctorCount++;
                        }

                        // -------- class-level (only if no methods/ctors) --------
                        if (cd.getMethods().isEmpty() && cd.getConstructors().isEmpty()) {
                            Snippet s = new Snippet();
                            s.file = root.relativize(p).toString();
                            s.pkg = pkg;
                            s.classFqn = classFqn;
                            s.className = className;
                            s.methodName = ""; // 表示类级
                            s.paramTypes = List.of();
                            s.decl = (cd.isInterface() ? "interface " : "class ") + className;
                            s.qualifiedSignature = classFqn; // 用类全名作为“签名”
                            s.code = truncate(cd.toString(), maxBodyLen);
                            if (true) {
                                s.strings.addAll(
                                        cd.findAll(StringLiteralExpr.class).stream()
                                                .map(StringLiteralExpr::asString)
                                                .distinct()
                                                .limit(64)
                                                .toList()
                                );
                            }
                            out.add(s);
                            classCount++;
                        }
                    }
                }
            }

            ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            om.writeValue(Paths.get(outJson).toFile(), out);
            System.out.println("[analyze] snippets: " + out.size()
                    + " (methods=" + methodCount + ", ctors=" + ctorCount + ", classes=" + classCount + ") -> " + outJson);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private boolean isJavaFile(Path p) {
        return Files.isRegularFile(p) && p.toString().endsWith(".java");
    }

    private boolean isExcluded(Path root, Path p, List<PathMatcher> matchers) {
        if (matchers.isEmpty()) return false;
        Path rel = root.relativize(p);
        for (PathMatcher m : matchers) if (m.matches(rel)) return true;
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}