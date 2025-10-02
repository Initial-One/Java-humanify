package com.initialone.jhumanify.commands;

import picocli.CommandLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import com.github.javaparser.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.initialone.jhumanify.util.Tools;

@CommandLine.Command(
        name = "apply",
        description = "Apply mapping.json to sources and write to outDir (signature-accurate renames)"
)
public class ApplyCmd implements Runnable {

    @CommandLine.Parameters(index = "0", description = "Input source dir")
    String srcDir;

    @CommandLine.Parameters(index = "1", description = "Input mapping.json")
    String mappingJson;

    @CommandLine.Parameters(index = "2", description = "Output dir")
    String outDir;

    @CommandLine.Option(names = "--classpath", split = ":", description = "Extra classpath jars/dirs, separated by ':'")
    List<String> classpath = new ArrayList<>();

    static class Mapping {
        public Map<String,String> simple    = new LinkedHashMap<>();
        public Map<String,String> classFqn  = new LinkedHashMap<>();
        public Map<String,String> fieldFqn  = new LinkedHashMap<>();
        public Map<String,String> methodSig = new LinkedHashMap<>();
    }

    JavaParser parser;
    JavaSymbolSolver solver;

    @Override
    public void run() {
        try {
            ObjectMapper om = new ObjectMapper();
            Mapping mapping = om.readValue(Path.of(mappingJson).toFile(), new TypeReference<>() {});
            Path inRoot  = Path.of(srcDir);
            Path outRoot = Path.of(outDir);
            Path pass1Root = outRoot.resolveSibling(outRoot.getFileName().toString() + "-pass1");

            Files.createDirectories(outRoot);
            Files.createDirectories(pass1Root);

            // ---- 构造 new->old 映射（两阶段都要用）----
            Map<String,String> newToOldClass = new HashMap<>();
            for (var e : mapping.classFqn.entrySet()) newToOldClass.put(e.getValue(), e.getKey());

            // ========== PASS 1: 只做 "类相关" 的改动，写到 pass1Root ==========
            initParser(inRoot, classpath);
            passApply(inRoot, pass1Root, mapping, newToOldClass, /*pass1=*/true);

            // ========== PASS 2: 在 pass1Root 上做 "方法/字段/局部" 改名 ==========
            initParser(pass1Root, classpath);
            passApply(pass1Root, outRoot, mapping, newToOldClass, /*pass1=*/false);

            //删除pass1
            deleteQuietly(pass1Root);

            System.out.println("[apply] done -> " + outDir);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private void passApply(Path in, Path out, Mapping mapping, Map<String,String> newToOldClass, boolean pass1) throws Exception {
        AtomicInteger hitClasses = new AtomicInteger();
        AtomicInteger hitMethods = new AtomicInteger();
        AtomicInteger hitFields  = new AtomicInteger();

        try (var paths = Files.walk(in)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).collect(Collectors.toList())) {
                var res = parser.parse(p);
                if (!res.isSuccessful() || res.getResult().isEmpty()) {
                    System.err.println("[apply] parse fail: " + p);
                    continue;
                }
                CompilationUnit cu = res.getResult().get();
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

                // ---------------- PASS 1：类相关（声明/构造器/类型引用/import/注解/对象创建/静态作用域） ----------------
                // 1) 类名重命名（声明 + 构造器）
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cd -> {
                    String fqn = pkg.isBlank() ? cd.getNameAsString() : (pkg + "." + cd.getNameAsString());
                    String newFqn = mapping.classFqn.get(fqn);
                    if (newFqn != null) {
                        String oldSimple = cd.getNameAsString();
                        String newSimple = Tools.simpleName(newFqn);
                        if (!newSimple.equals(oldSimple)) {
                            cd.setName(newSimple);
                            hitClasses.incrementAndGet();
                            cu.findAll(ConstructorDeclaration.class).forEach(cons -> {
                                if (cons.getNameAsString().equals(oldSimple)) cons.setName(newSimple);
                            });
                        }
                    }
                });

                // 2) 类型引用同步
                cu.findAll(ClassOrInterfaceType.class).forEach(t -> {
                    try {
                        ResolvedReferenceType rt = t.resolve().asReferenceType();
                        String refFqn = rt.getQualifiedName();
                        String nf = mapping.classFqn.get(refFqn);
                        if (nf != null) t.setName(Tools.simpleName(nf));
                    } catch (Throwable ignore) {}
                });

                // 2.5) 静态调用/常量作用域同步（x0.p(...) / y0.CONST）
                cu.findAll(MethodCallExpr.class).forEach(mc -> mc.getScope().ifPresent(sc -> {
                    if (sc.isNameExpr()) {
                        NameExpr ne = sc.asNameExpr();
                        String nn = pickNewSimpleForScope(ne.getNameAsString(), pkg, cu, mapping.classFqn);
                        if (nn != null && !nn.equals(ne.getNameAsString())) ne.setName(nn);
                    }
                }));
                cu.findAll(FieldAccessExpr.class).forEach(fa -> {
                    if (fa.getScope().isNameExpr()) {
                        NameExpr ne = fa.getScope().asNameExpr();
                        String nn = pickNewSimpleForScope(ne.getNameAsString(), pkg, cu, mapping.classFqn);
                        if (nn != null && !nn.equals(ne.getNameAsString())) ne.setName(nn);
                    }
                });

                // import / 注解 / new 类型
                cu.getImports().forEach(imp -> {
                    if (!imp.isAsterisk()) {
                        String name = imp.getNameAsString();
                        String nf = mapping.classFqn.get(name);
                        if (nf != null && !nf.equals(name)) imp.setName(nf);
                    }
                });
                cu.findAll(MarkerAnnotationExpr.class).forEach(a -> {
                    try {
                        String rt = a.resolve().getQualifiedName();
                        String nf = mapping.classFqn.get(rt);
                        if (nf != null) a.setName(Tools.simpleName(nf));
                    } catch (Throwable ignore) {}
                });
                cu.findAll(SingleMemberAnnotationExpr.class).forEach(a -> {
                    try {
                        String rt = a.resolve().getQualifiedName();
                        String nf = mapping.classFqn.get(rt);
                        if (nf != null) a.setName(Tools.simpleName(nf));
                    } catch (Throwable ignore) {}
                });
                cu.findAll(NormalAnnotationExpr.class).forEach(a -> {
                    try {
                        String rt = a.resolve().getQualifiedName();
                        String nf = mapping.classFqn.get(rt);
                        if (nf != null) a.setName(Tools.simpleName(nf));
                    } catch (Throwable ignore) {}
                });
                cu.findAll(ObjectCreationExpr.class).forEach(n -> {
                    try {
                        String t = n.getType().resolve().asReferenceType().getQualifiedName();
                        String nf = mapping.classFqn.get(t);
                        if (nf != null) n.setType(Tools.simpleName(nf));
                    } catch (Throwable ignore) {}
                });

                // ---------------- PASS 2：方法/字段/局部（解析依赖稳定类名） ----------------
                if (!pass1) {
                    // 字段引用（NameExpr / FieldAccessExpr）+ 声明
                    cu.findAll(NameExpr.class).forEach(ne -> {
                        try {
                            ResolvedValueDeclaration d = ne.resolve();
                            if (d.isField()) {
                                ResolvedFieldDeclaration f = d.asField();
                                String fqn = Tools.fieldFqn(f);
                                String nn = mapping.fieldFqn.get(fqn);
                                if (nn == null) nn = mapping.fieldFqn.get(mapFieldToOld(fqn, newToOldClass));
                                if (nn != null && !nn.equals(ne.getNameAsString())) { ne.setName(nn); hitFields.incrementAndGet(); return; }
                            }
                        } catch (Throwable ignore) {}
                        String plain = mapping.simple.get(ne.getNameAsString());
                        if (plain != null && !plain.equals(ne.getNameAsString())) ne.setName(plain);
                    });
                    cu.findAll(FieldAccessExpr.class).forEach(fa -> {
                        try {
                            var d = fa.resolve();
                            if (d.isField()) {
                                ResolvedFieldDeclaration f = d.asField();
                                String fqn = Tools.fieldFqn(f);
                                String nn = mapping.fieldFqn.get(fqn);
                                if (nn == null) nn = mapping.fieldFqn.get(mapFieldToOld(fqn, newToOldClass));
                                if (nn != null && !nn.equals(fa.getNameAsString())) { fa.setName(nn); hitFields.incrementAndGet(); return; }
                            }
                        } catch (Throwable ignore) {}
                        String plain = mapping.simple.get(fa.getNameAsString());
                        if (plain != null && !plain.equals(fa.getNameAsString())) fa.setName(plain);
                    });
                    cu.findAll(FieldDeclaration.class).forEach(fd -> fd.getVariables().forEach(v -> {
                        try {
                            ResolvedValueDeclaration rvd = v.resolve();
                            if (rvd.isField()) {
                                ResolvedFieldDeclaration decl = rvd.asField();
                                String fqn = Tools.fieldFqn(decl);
                                String newName = mapping.fieldFqn.get(fqn);
                                if (newName == null) newName = mapping.fieldFqn.get(mapFieldToOld(fqn, newToOldClass));
                                if (newName == null) newName = mapping.simple.get(v.getNameAsString());
                                if (newName != null && !newName.equals(v.getNameAsString())) { v.setName(newName); hitFields.incrementAndGet(); }
                            }
                        } catch (Throwable ignore) {}
                    }));

                    // 方法声明 / 调用点（四连匹配）
                    cu.findAll(MethodDeclaration.class).forEach(md -> {
                        try {
                            ResolvedMethodDeclaration r = md.resolve();
                            String sig = qualifiedSignature(r);
                            String nn = lookupMethodName(mapping, newToOldClass, sig);
                            if (nn == null) nn = mapping.simple.get(md.getNameAsString());
                            if (nn != null && !nn.equals(md.getNameAsString())) { md.setName(nn); hitMethods.incrementAndGet(); }
                        } catch (Throwable ignore) {}
                    });
                    cu.findAll(MethodCallExpr.class).forEach(mc -> {
                        try {
                            ResolvedMethodDeclaration r = mc.resolve();
                            String sig = qualifiedSignature(r);
                            String nn = lookupMethodName(mapping, newToOldClass, sig);
                            if (nn == null) nn = mapping.simple.get(mc.getNameAsString());
                            if (nn != null && !nn.equals(mc.getNameAsString())) { mc.setName(nn); hitMethods.incrementAndGet(); }
                        } catch (Throwable e) {
                            // ★ 解析失败的兜底（静态调用 & scope 为简单名时，根据 classFqn + 参数个数猜签名）
                            String guess = guessByScopeAndArity(mapping, mc, pkg, cu);
                            if (guess != null && !guess.equals(mc.getNameAsString())) { mc.setName(guess); hitMethods.incrementAndGet(); }
                        }
                    });

                    // 参数 / 局部（避免冲突）
                    cu.findAll(Parameter.class).forEach(pn -> {
                        String want = mapping.simple.get(pn.getNameAsString());
                        if (want != null && !want.equals(pn.getNameAsString())) {
                            Node scope = pn.findAncestor(MethodDeclaration.class).map(n -> (Node)n)
                                    .orElseGet(() -> pn.findAncestor(ConstructorDeclaration.class).map(n -> (Node)n)
                                            .orElse(pn.getParentNode().orElse(null)));
                            if (scope == null || !existsInScope(scope, want)) pn.setName(want);
                        }
                    });
                    cu.findAll(VariableDeclarator.class).forEach(vd -> {
                        String want = mapping.simple.get(vd.getNameAsString());
                        if (want != null && !want.equals(vd.getNameAsString())) {
                            Node scope = vd.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class).map(n -> (Node)n)
                                    .orElse(vd.getParentNode().orElse(null));
                            if (scope == null || !existsInScope(scope, want)) vd.setName(want);
                        }
                    });
                }

                // 写出（根据 public 顶级类名决定文件名）
                Path rel = in.relativize(p);
                Path target = out.resolve(rel);
                Optional<String> publicTop = cu.getTypes().stream()
                        .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                        .map(t -> (ClassOrInterfaceDeclaration) t)
                        .filter(cd -> cd.isPublic() || cu.getTypes().size() == 1)
                        .map(ClassOrInterfaceDeclaration::getNameAsString)
                        .findFirst();
                if (publicTop.isPresent()) {
                    String newFile = publicTop.get() + ".java";
                    target = target.getParent() != null ? target.getParent().resolve(newFile) : target;
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, cu.toString());
            }
        }

        System.out.printf("[apply] %s hits: classes=%d, methods=%d, fields=%d%n",
                (pass1 ? "pass1" : "pass2"),
                hitClasses.get(), hitMethods.get(), hitFields.get());
    }

    private void initParser(Path srcRoot, List<String> cp) {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver());
        ts.add(new JavaParserTypeSolver(srcRoot.toFile()));
        if (cp != null) {
            for (String entry : cp) {
                Path p = Paths.get(entry);
                try {
                    if (Files.isDirectory(p)) {
                        ts.add(new JavaParserTypeSolver(p.toFile()));
                    } else if (Files.isRegularFile(p) && entry.endsWith(".jar")) {
                        ts.add(new JarTypeSolver(entry));
                    }
                } catch (Throwable t) {
                    System.err.println("[apply] classpath entry skip: " + entry + " -> " + t.getMessage());
                }
            }
        }
        solver = new JavaSymbolSolver(ts);
        parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));
        StaticJavaParser.getConfiguration().setSymbolResolver(solver);
    }

    private static String qualifiedSignature(ResolvedMethodDeclaration m) {
        String cls = m.declaringType().getQualifiedName();
        List<String> params = new ArrayList<>();
        for (int i = 0; i < m.getNumberOfParams(); i++) {
            params.add(m.getParam(i).getType().describe());
        }
        return cls + "." + m.getName() + "(" + String.join(",", params) + ")";
    }

    private static String normalizeSig(String sig) {
        int p = sig.indexOf('('), q = sig.lastIndexOf(')');
        if (p < 0 || q < 0 || q < p) return sig;
        String head = sig.substring(0, p+1);
        String mid = sig.substring(p+1, q);
        String tail = sig.substring(q);
        String[] parts = mid.isBlank()? new String[0] : mid.split("\\s*,\\s*");
        for (int i=0;i<parts.length;i++) {
            String t = parts[i];
            t = t.replace("java.lang.", "").replace("java.util.", "");
            parts[i] = t;
        }
        return head + String.join(",", parts) + tail;
    }

    private static String mapSigToOld(String sig, Map<String,String> newToOldClass) {
        int p = sig.lastIndexOf('(');
        int dot = sig.lastIndexOf('.', p);
        if (dot <= 0) return sig;
        String cls = sig.substring(0, dot);
        String rest = sig.substring(dot);
        String old = newToOldClass.get(cls);
        return (old != null) ? (old + rest) : sig;
    }

    private static String mapFieldToOld(String fieldFqn, Map<String,String> newToOldClass) {
        int h = fieldFqn.lastIndexOf('#');
        if (h <= 0) return fieldFqn;
        String cls = fieldFqn.substring(0, h);
        String tail = fieldFqn.substring(h);
        String old = newToOldClass.get(cls);
        return (old != null) ? (old + tail) : fieldFqn;
    }

    /* 多轮查找方法名：原sig → 旧类回溯 → 归一化 → 归一化后旧类回溯 */
    private static String lookupMethodName(Mapping m, Map<String,String> newToOldClass, String sig) {
        String nn = m.methodSig.get(sig);
        if (nn != null) return nn;
        String old = mapSigToOld(sig, newToOldClass);
        nn = m.methodSig.get(old);
        if (nn != null) return nn;
        String ns = normalizeSig(sig);
        nn = m.methodSig.get(ns);
        if (nn != null) return nn;
        String nso = mapSigToOld(ns, newToOldClass);
        return m.methodSig.get(nso);
    }

    /* 作用域中是否已有同名（用于避免把声明名改成已存在的名字） */
    private static boolean existsInScope(Node scope, String name) {
        Set<String> names = new HashSet<>();
        scope.findAll(VariableDeclarator.class).forEach(v -> names.add(v.getNameAsString()));
        scope.findAll(Parameter.class).forEach(p -> names.add(p.getNameAsString()));
        return names.contains(name);
    }

    private static String pickNewSimpleForScope(
            String simple, String cuPkg, CompilationUnit cu, Map<String,String> classFqnMap) {

        for (var e : classFqnMap.entrySet()) {
            String oldFqn = e.getKey(), newFqn = e.getValue();
            String oldSimple = Tools.simpleName(oldFqn);
            if (!oldSimple.equals(simple)) continue;

            String oldPkg = Tools.pkgName(oldFqn);

            // 依据“同包或已导入”来判断这个 simple 更可能指向哪个 FQN
            boolean samePkg = Objects.equals(cuPkg, oldPkg);
            boolean imported = cu.getImports().stream().anyMatch(imp ->
                    !imp.isAsterisk()
                            ? imp.getNameAsString().equals(oldFqn)
                            : imp.getNameAsString().equals(oldPkg) // 处理 a.b.*
            );
            if (samePkg || imported) {
                return Tools.simpleName(newFqn);
            }
        }
        return null;
    }

    private static String guessByScopeAndArity(Mapping m, MethodCallExpr mc, String pkg, CompilationUnit cu) {
        if (mc.getScope().isEmpty() || !mc.getScope().get().isNameExpr()) return null;
        String scopeSimple = mc.getScope().get().asNameExpr().getNameAsString(); // 例如 KeyEncoderDecoder
        int argc = mc.getArguments().size();
        String name = mc.getNameAsString();

        // 根据 classFqn 映射，找出这个 scopeSimple 对应的旧 FQN（y0）
        String oldFqn = null;
        for (var e : m.classFqn.entrySet()) {
            String oldFqnCand = e.getKey(), newFqn = e.getValue();
            if (Tools.simpleName(newFqn).equals(scopeSimple)) {
                oldFqn = oldFqnCand; break;
            }
        }
        if (oldFqn == null && pkg != null) {
            // 同包猜测（例如 package demo.mix; scopeSimple=KeyEncoderDecoder）
            String nf = pkg + "." + scopeSimple;
            for (var e : m.classFqn.entrySet()) if (e.getValue().equals(nf)) { oldFqn = e.getKey(); break; }
        }
        if (oldFqn == null) return null;

        // 在 methodSig 里找：类匹配 + 方法名匹配 + 参数个数匹配（忽略具体类型）
        String prefix = oldFqn + "." + name + "(";
        for (var e : m.methodSig.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prefix)) continue;
            // 数参数
            int lp = k.indexOf('('), rp = k.lastIndexOf(')');
            if (lp < 0 || rp < lp) continue;
            String mid = k.substring(lp+1, rp).trim();
            int count = mid.isEmpty() ? 0 : (int) Arrays.stream(mid.split("\\s*,\\s*")).count();
            if (count == argc) return e.getValue(); // 命中
        }
        return null;
    }

    private static void deleteQuietly(Path dir) {
        try {
            if (dir == null || !java.nio.file.Files.exists(dir)) return;
            try (var walk = java.nio.file.Files.walk(dir)) {
                java.util.List<java.nio.file.Path> all =
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .collect(java.util.stream.Collectors.toList());
                for (java.nio.file.Path p : all) {
                    try { java.nio.file.Files.deleteIfExists(p); }
                    catch (Exception e) {
                        System.err.println("[humanify] cleanup pass1 failed on " + p + ": " + e.getMessage());
                    }
                }
            }
            System.out.println("[humanify] cleaned pass1 -> " + dir);
        } catch (Exception e) {
            System.err.println("[humanify] cleanup pass1 error: " + e.getMessage());
        }
    }
}