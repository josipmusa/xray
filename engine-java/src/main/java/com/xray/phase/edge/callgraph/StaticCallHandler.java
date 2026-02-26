package com.xray.phase.edge.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.xray.engine.NodeIdGenerator;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.model.Evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class StaticCallHandler {

    static Optional<Edge> tryGenerateEdge(MethodCallExpr call, String fromNodeId, CallGraphPipeline.Input input) {
        if (!looksLikeStaticScope(call)) return Optional.empty();
        Optional<String> toHigh = tryResolveStaticMethod(call);
        if (toHigh.isPresent()) {
            Edge edge = Edge.v1(
                    fromNodeId,
                    toHigh.get(),
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.HIGH,
                    List.of(methodCallEvidence(call, "static-resolved"))
            );
            return Optional.of(edge);
        }

        Optional<String> toMedium = tryHeuristicStaticMethod(call, input);
        if (toMedium.isPresent()) {
            Edge edge = Edge.v1(
                    fromNodeId,
                    toMedium.get(),
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.MEDIUM,
                    List.of(methodCallEvidence(call, "static-name-arity-fallback"))
            );
            return Optional.of(edge);
        }

        return Optional.empty();
    }

    private static boolean looksLikeStaticScope(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return false;
        var scope = call.getScope().get();
        return scope.isNameExpr() || scope.isFieldAccessExpr();
    }

    private static Optional<String> tryHeuristicStaticMethod(MethodCallExpr call, CallGraphPipeline.Input input) {
        // Only applies to static-looking scopes (you already guard with looksLikeStaticScope)
        Optional<String> scopeTypeTextOpt = extractScopeTypeText(call);
        if (scopeTypeTextOpt.isEmpty()) return Optional.empty();

        String scopeTypeText = scopeTypeTextOpt.get(); // "Foo" or "pkg.Foo" or "a.b.Foo"

        // Resolve scope -> owner class (must be unique)
        if (!looksLikeTypeName(scopeTypeText))
            return Optional.empty(); //needed because repo.save() calls can creep in - so filter here
        Optional<CallGraphPipeline.Input.ClassData> ownerOpt = resolveOwnerClass(scopeTypeText, input);
        if (ownerOpt.isEmpty()) return Optional.empty();

        CallGraphPipeline.Input.ClassData owner = ownerOpt.get();

        // Match callee within that class by name + arity (must be unique)
        String name = call.getNameAsString();
        int arity = call.getArguments().size();

        List<MethodDeclaration> candidates = new ArrayList<>();
        for (MethodDeclaration md : owner.clazz().getMethods()) {
            if (md.getNameAsString().equals(name) && md.getParameters().size() == arity) {
                candidates.add(md);
            }
        }

        if (candidates.size() != 1) return Optional.empty();

        MethodDeclaration callee = candidates.getFirst();
        return Optional.of(NodeIdGenerator.generateMethodNodeId(owner.fqcn(), callee));
    }

    private static Optional<String> extractScopeTypeText(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return Optional.empty();
        Expression scope = call.getScope().get();

        // Foo.bar()
        if (scope.isNameExpr()) {
            return Optional.of(scope.asNameExpr().getNameAsString());
        }

        // pkg.Foo.bar() or Outer.Inner.bar()
        if (scope.isFieldAccessExpr()) {
            // toString() yields the dotted chain text we want
            return Optional.of(scope.toString());
        }

        return Optional.empty();
    }

    private static Optional<CallGraphPipeline.Input.ClassData> resolveOwnerClass(
            String scopeTypeText,
            CallGraphPipeline.Input input
    ) {
        // Build matches; require exactly one (no guessing)
        CallGraphPipeline.Input.ClassData match = null;

        boolean looksQualified = scopeTypeText.contains(".");

        for (CallGraphPipeline.Input.ClassData cd : input.classData()) {
            if (looksQualified) {
                // If it looks qualified, only accept exact FQCN match
                if (cd.fqcn().equals(scopeTypeText)) {
                    return Optional.of(cd);
                }
            } else {
                // Simple name match must be unique across all parsed classes
                String simple = simpleName(cd.fqcn());
                if (simple.equals(scopeTypeText)) {
                    if (match != null) return Optional.empty(); // ambiguous
                    match = cd;
                }
            }
        }

        return Optional.ofNullable(match);
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    private static boolean looksLikeTypeName(String scopeTypeText) {
        String lastSegment = scopeTypeText.contains(".")
                ? scopeTypeText.substring(scopeTypeText.lastIndexOf('.') + 1)
                : scopeTypeText;

        return !lastSegment.isEmpty() && Character.isUpperCase(lastSegment.charAt(0));
    }

    private static Optional<String> tryResolveStaticMethod(MethodCallExpr call) {
        ResolvedMethodDeclaration resolvedMethodDeclaration;
        try {
            resolvedMethodDeclaration = call.resolve();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!resolvedMethodDeclaration.isStatic()) {
            return Optional.empty();
        }
        String ownerFqcn = resolvedMethodDeclaration.declaringType().getQualifiedName();
        String toNodeId = NodeIdGenerator.generateMethodNodeId(ownerFqcn, resolvedMethodDeclaration);

        return Optional.of(toNodeId);
    }

    private static Evidence methodCallEvidence(MethodCallExpr call, String detail) {
        int line = call.getRange().map(r -> r.begin.line).orElse(-1);
        String file = call.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> storage.getPath().toString())
                .orElse(null);
        return new Evidence(file, line, "method-call", call.toString(), detail);
    }
}
