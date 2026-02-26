package com.xray.engine;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class NodeIdGenerator {

    private NodeIdGenerator() {}

    public static String generateClassNodeId(ClassOrInterfaceDeclaration c) {
        return c.getFullyQualifiedName().orElse(c.getNameAsString());
    }

    public static String generateMethodNodeId(String fqcn, MethodDeclaration m) {
        String params = m.getParameters().stream()
                .map(p -> bestEffortTypeString(p.getType()))
                .collect(Collectors.joining(","));

        String ret = bestEffortTypeString(m.getType());

        return fqcn + "#" + m.getNameAsString() + "(" + params + "):" + ret;
    }

    // Used for edges when you only have a resolved method (e.g. static calls)
    public static String generateMethodNodeId(String ownerFqcn, ResolvedMethodDeclaration r) {
        String params = IntStream.range(0, r.getNumberOfParams())
                .mapToObj(i -> bestEffortResolvedTypeString(() -> r.getParam(i).getType()))
                .collect(Collectors.joining(","));

        String ret = bestEffortResolvedTypeString(r::getReturnType);

        return ownerFqcn + "#" + r.getName() + "(" + params + "):" + ret;
    }

    private static String bestEffortTypeString(Type t) {
        // Try symbol-solver resolution; fall back to syntactic string
        try {
            ResolvedType rt = t.resolve();
            return safeDescribe(rt);
        } catch (Throwable ignored) {
            return t.toString();
        }
    }

    private static String safeDescribe(ResolvedType rt) {
        // rt.describe() can throw in some corner cases; be defensive
        try {
            return rt.describe();
        } catch (Throwable ignored) {
            return rt.toString();
        }
    }

    @FunctionalInterface
    private interface ResolvedTypeSupplier {
        ResolvedType get();
    }

    private static String bestEffortResolvedTypeString(ResolvedTypeSupplier supplier) {
        try {
            ResolvedType rt = supplier.get();
            return safeDescribe(rt);
        } catch (Throwable ignored) {
            // last resort: something stable-ish so it doesn't crash the pipeline
            return "<?>";
        }
    }

    public static String prettySignature(MethodDeclaration m) {
        return m.getDeclarationAsString(false, false, true);
    }
}
