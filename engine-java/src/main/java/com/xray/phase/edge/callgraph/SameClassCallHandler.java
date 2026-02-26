package com.xray.phase.edge.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.xray.engine.NodeIdGenerator;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.model.Evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SameClassCallHandler {


    static Optional<Edge> tryGenerateEdge(CallGraphPipeline.Input.ClassData classData, MethodCallExpr call, String fromNodeId,
                                          Map<CallGraphPipeline.NameArity, List<MethodDeclaration>> declaredIndex) {
        if (!isImplicitThis(call)) return Optional.empty();
        // 1) HIGH confidence - symbol solver resolved exact method
        Optional<String> toHigh = tryResolveSameClass(call, classData.fqcn(), classData.clazz().getMethods());
        if (toHigh.isPresent()) {
            String toNodeId = toHigh.get();
            Edge edge = Edge.v1(
                    fromNodeId,
                    toNodeId,
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.HIGH,
                    List.of(methodCallEvidence(call, "same-class-resolved"))
            );
            return Optional.of(edge);
        }

        // 2) MEDIUM: unique (name+arity) match among declared methods of this class
        Optional<String> toMedium = fallbackUniqueNameArity(call, classData.fqcn(), declaredIndex);
        if (toMedium.isPresent()) {
            String toNodeId = toMedium.get();
            Edge edge = Edge.v1(
                    fromNodeId,
                    toNodeId,
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.MEDIUM,
                    List.of(methodCallEvidence(call, "same-class-name-arity-fallback"))
            );
            return Optional.of(edge);
        }

        return Optional.empty();
    }

    private static boolean isImplicitThis(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return true;
        Expression scope = call.getScope().get();
        return scope.isThisExpr();
    }

    /**
     * HIGH confidence resolution:
     * - resolve call
     * - ensure declaring type matches current class fqcn
     * - map resolved method back to a MethodDeclaration in this class
     * <p>
     * NOTE: mapping resolved -> MethodDeclaration is easiest by (name+arity)
     * plus optionally parameter type names if you have them.
     */
    private static Optional<String> tryResolveSameClass(
            MethodCallExpr call,
            String currentClassFqcn,
            List<MethodDeclaration> declaredMethods
    ) {
        ResolvedMethodDeclaration resolved;
        try {
            resolved = call.resolve();
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            return Optional.empty();
        }

        String declaringType;
        try {
            declaringType = resolved.declaringType().getQualifiedName();
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        if (!currentClassFqcn.equals(declaringType)) return Optional.empty();

        // Map to your AST method declaration(s)
        String name = resolved.getName();
        int arity = resolved.getNumberOfParams();

        List<MethodDeclaration> candidates = new ArrayList<>();
        for (MethodDeclaration md : declaredMethods) {
            if (md.getNameAsString().equals(name) && md.getParameters().size() == arity) {
                candidates.add(md);
            }
        }

        // If resolve says it's Foo.b(int) but class has overloads with same arity,
        // you can optionally refine by param types. For MVP: require unique.
        if (candidates.size() != 1) return Optional.empty();

        MethodDeclaration callee = candidates.getFirst();
        return Optional.of(NodeIdGenerator.generateMethodNodeId(currentClassFqcn, callee));
    }

    private static Optional<String> fallbackUniqueNameArity(
            MethodCallExpr call,
            String ownerFqcn,
            Map<CallGraphPipeline.NameArity, List<MethodDeclaration>> declaredIndex
    ) {
        CallGraphPipeline.NameArity key = new CallGraphPipeline.NameArity(call.getNameAsString(), call.getArguments().size());
        List<MethodDeclaration> candidates = declaredIndex.getOrDefault(key, List.of());

        if (candidates.size() != 1) return Optional.empty();

        MethodDeclaration callee = candidates.getFirst();
        return Optional.of(NodeIdGenerator.generateMethodNodeId(ownerFqcn, callee));
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
