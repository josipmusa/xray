package com.xray.engine;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class NodeIdGenerator {

    private NodeIdGenerator() {}

    public static String generateClassNodeId(ClassOrInterfaceDeclaration c) {
        return c.getFullyQualifiedName().orElse(c.getNameAsString());
    }

    public static String generateMethodNodeId(String fqcn, MethodDeclaration m) {
        String params = m.getParameters().stream()
                .map(p -> p.getType().toString()) // later: resolve to FQCN via symbol solver
                .collect(Collectors.joining(","));
        String ret = m.getType().toString();
        return fqcn + "#" + m.getNameAsString() + "(" + params + "):" + ret;
    }

    //Identical to the one with MethodDeclaration - used for static methods when not in same class
    public static String generateMethodNodeId(String ownerFqcn, ResolvedMethodDeclaration r) {
        String params = IntStream.range(0, r.getNumberOfParams())
                .mapToObj(i -> r.getParam(i).getType().describe())
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        String ret = r.getReturnType().describe();

        return ownerFqcn + "#" + r.getName() + "(" + params + "):" + ret;
    }

    public static String prettySignature(MethodDeclaration m) {
        return m.getDeclarationAsString(false, false, true);
    }
}
