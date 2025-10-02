package com.initialone.jhumanify.ast;

import com.initialone.jhumanify.model.Mapping;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.initialone.jhumanify.util.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 JavaParser + SymbolSolver 的精确改名器：
 * - classes: oldFqn -> newFqn（更新类声明、构造器名、类型引用；文件名随顶级类改名变更）
 * - fields : a.b.C#field -> newName（声明与引用处）
 * - methods: a.b.C.m(T1,T2) -> newName（声明与调用点）
 */
public class AstRenamer {
    private final JavaParser parser;
    private final TypeSolver typeSolver;

    public AstRenamer(Path srcRoot, List<Path> classpath) {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        // 反射求解（JRE 类型等）
        ts.add(new ReflectionTypeSolver());
        // 源码：项目本身
        ts.add(new JavaParserTypeSolver(srcRoot.toFile()));
        // 额外 classpath：目录 / jar
        if (classpath != null) {
            for (Path cp : classpath) {
                try {
                    if (cp != null && Files.exists(cp)) {
                        if (Files.isDirectory(cp)) {
                            ts.add(new JavaParserTypeSolver(cp.toFile()));
                        } else if (cp.toString().endsWith(".jar")) {
                            ts.add(new JarTypeSolver(cp.toString()));
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[AstRenamer] skip classpath entry: " + cp + " -> " + t.getMessage());
                }
            }
        }
        this.typeSolver = ts;

        JavaSymbolSolver solver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration cfg = new ParserConfiguration().setSymbolResolver(solver);
        this.parser = new JavaParser(cfg);
        // 同步配置 StaticJavaParser（部分辅助解析用）
        StaticJavaParser.getConfiguration().setSymbolResolver(solver);
    }

    public void apply(Path srcRoot, Mapping mapping, Path outRoot) throws IOException {
        Files.createDirectories(outRoot);
        List<Path> files = Files.walk(srcRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());

        for (Path p : files) {
            try {
                var res = parser.parse(p);
                if (!res.isSuccessful() || res.getResult().isEmpty()) {
                    System.err.println("[AstRenamer] parse fail: " + p);
                    continue;
                }
                CompilationUnit cu = res.getResult().get();
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

                /* ========= 1) 类重命名：优先用符号求解拿 FQN ========= */
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cd -> {
                    String oldSimple = cd.getNameAsString();
                    String fqn = Tools.fqnOfClassDecl(cd, pkg);
                    String newFqn = mapping.classFqn.get(fqn);
                    if (newFqn != null) {
                        String newSimple = Tools.simpleName(newFqn);
                        if (!Objects.equals(oldSimple, newSimple)) {
                            cd.setName(newSimple);
                            // 构造器同名更新
                            cu.findAll(ConstructorDeclaration.class).forEach(cons -> {
                                if (cons.getNameAsString().equals(oldSimple)) {
                                    cons.setName(newSimple);
                                }
                            });
                        }
                    }
                });

                /* ========= 2) 类型引用同步（ClassOrInterfaceType） ========= */
                cu.findAll(ClassOrInterfaceType.class).forEach(t -> {
                    try {
                        ResolvedReferenceType rt = t.resolve().asReferenceType();
                        String refFqn = rt.getQualifiedName();
                        String newFqn = mapping.classFqn.get(refFqn);
                        if (newFqn != null) {
                            t.setName(Tools.simpleName(newFqn));
                        }
                    } catch (Throwable ignore) {
                    }
                });

                /* ========= 3) 字段声明与引用 ========= */
                cu.findAll(FieldDeclaration.class).forEach(fd -> {
                    fd.getVariables().forEach(v -> {
                        try {
                            ResolvedValueDeclaration rvd = v.resolve(); // 返回的是 ResolvedValueDeclaration
                            if (rvd.isField()) {                        // 确认它确实是“字段”
                                ResolvedFieldDeclaration decl = rvd.asField(); // 转成 ResolvedFieldDeclaration
                                String fqn = Tools.fieldFqn(decl);                  // a.b.C#field
                                String newName = mapping.fieldFqn.get(fqn);
                                if (newName != null && !newName.equals(v.getNameAsString())) {
                                    v.setName(newName);
                                }
                            }
                        } catch (UnsolvedSymbolException use) {
                            // 没解析到符号（少 classpath 等），这里先忽略，流程不中断
                        } catch (Throwable ignore) {}
                    });
                });

                cu.findAll(NameExpr.class).forEach(ne -> {
                    try {
                        var d = ne.resolve();
                        if (d.isField()) {
                            String fqn = Tools.fieldFqn(d.asField());
                            String newName = mapping.fieldFqn.get(fqn);
                            if (newName != null && !Objects.equals(ne.getNameAsString(), newName)) {
                                ne.setName(newName);
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                });

                /* ========= 4) 方法声明与调用点 ========= */
                cu.findAll(MethodDeclaration.class).forEach(md -> {
                    try {
                        ResolvedMethodDeclaration r = md.resolve();
                        String sig = Tools.qualifiedSignature(r);
                        String newName = mapping.methodSig.get(sig);
                        if (newName != null && !Objects.equals(md.getNameAsString(), newName)) {
                            md.setName(newName);
                        }
                    } catch (Throwable ignore) {
                    }
                });

                cu.findAll(MethodCallExpr.class).forEach(mc -> {
                    try {
                        ResolvedMethodDeclaration r = mc.resolve();
                        String sig = Tools.qualifiedSignature(r);
                        String newName = mapping.methodSig.get(sig);
                        if (newName != null && !Objects.equals(mc.getNameAsString(), newName)) {
                            mc.setName(newName);
                        }
                    } catch (Throwable ignore) {
                    }
                });

                /* ========= 5) 写出：若顶级类改名则改文件名 ========= */
                Path rel = srcRoot.relativize(p);
                Path target = outRoot.resolve(rel);

                Optional<String> topTypeNewName = cu.getTypes().stream()
                        .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                        .map(t -> (ClassOrInterfaceDeclaration) t)
                        .filter(cd -> cd.isPublic() || cu.getTypes().size() == 1)
                        .map(ClassOrInterfaceDeclaration::getNameAsString)
                        .findFirst();

                if (topTypeNewName.isPresent()) {
                    String newFile = topTypeNewName.get() + ".java";
                    if (target.getParent() != null) target = target.getParent().resolve(newFile);
                }

                Files.createDirectories(target.getParent());
                Files.writeString(target, cu.toString());
            } catch (Exception e) {
                System.err.println("[AstRenamer] error at " + p + " -> " + e.getMessage());
            }
        }
    }
}