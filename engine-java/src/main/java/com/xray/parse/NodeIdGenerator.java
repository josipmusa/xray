package com.xray.parse;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.stream.Collectors;

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

    public static String prettySignature(MethodDeclaration m) {
        return m.getDeclarationAsString(false, false, true);
    }
}
