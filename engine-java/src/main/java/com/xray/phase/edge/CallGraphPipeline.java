package com.xray.phase.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.xray.engine.NodeIdGenerator;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;
import com.xray.model.Enums;

import java.io.IOException;
import java.util.*;

final class CallGraphPipeline {

    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    CallGraphPipeline(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.objectMapper = objectMapper;
        this.outputLayout = outputLayout;
    }

    void emitCallGraphs(CallGraphPipelineInput input) throws IOException {
        try (JsonlWriter writer = new JsonlWriter(outputLayout.getEdges(), objectMapper)) {
            for (CallGraphPipelineInput.ClassData classData : input.classData()) {

                // index declared methods by (name, arity)
                Map<NameArity, List<MethodDeclaration>> declaredIndex = indexDeclaredMethods(classData.clazz().getMethods());

                for (MethodDeclaration method : classData.clazz().getMethods()) {
                    String fromNodeId = NodeIdGenerator.generateMethodNodeId(classData.fqcn(), method);
                    Optional<BlockStmt> body = method.getBody();
                    if (body.isEmpty()) continue;
                    for (MethodCallExpr call : body.get().findAll(MethodCallExpr.class)) {
                        if (isImplicitThis(call)) {
                            handleSameClassCalls(classData, call, fromNodeId, writer, declaredIndex);
                        }
                        if (looksLikeStaticScope(call)) {
                            handleStaticCalls(call, fromNodeId, writer);
                        }
                    }
                }
            }
        }
    }

    //TODO add medium confidence via heuristics
    private static void handleStaticCalls(MethodCallExpr call, String fromNodeId, JsonlWriter writer) throws IOException {
        ResolvedMethodDeclaration resolvedMethodDeclaration;
        try {
            resolvedMethodDeclaration = call.resolve();
        } catch (RuntimeException e) {
            return; //Skip for now, use heuristics later
        }
        if (!resolvedMethodDeclaration.isStatic()) {
            return;
        }
        String ownerFqcn = resolvedMethodDeclaration.declaringType().getQualifiedName();
        String toNodeId = NodeIdGenerator.generateMethodNodeId(ownerFqcn, resolvedMethodDeclaration);

        Edge edge = Edge.v1(
                fromNodeId,
                toNodeId,
                Enums.EdgeType.CALLS,
                Enums.Confidence.HIGH
        );
        writer.writeObject(edge);
    }

    private static void handleSameClassCalls(CallGraphPipelineInput.ClassData classData, MethodCallExpr call, String fromNodeId, JsonlWriter writer, Map<NameArity, List<MethodDeclaration>> declaredIndex) throws IOException {
        // 1) HIGH confidence - symbol solver resolved exact method
        Optional<String> toHigh = tryResolveSameClass(call, classData.fqcn(), classData.clazz().getMethods());
        if (toHigh.isPresent()) {
            String toNodeId = toHigh.get();
            Edge edge = Edge.v1(
                    fromNodeId,
                    toNodeId,
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.HIGH
            );

            writer.writeObject(edge);
            return;
        }

        // 2) MEDIUM: unique (name+arity) match among declared methods of this class
        Optional<String> toMedium = fallbackUniqueNameArity(call, classData.fqcn(), declaredIndex);
        if (toMedium.isPresent()) {
            String toNodeId = toMedium.get();
            Edge edge = Edge.v1(
                    fromNodeId,
                    toNodeId,
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.MEDIUM
            );
            writer.writeObject(edge);
        }
    }

    private static boolean looksLikeStaticScope(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return false;
        var scope = call.getScope().get();
        return scope.isNameExpr() || scope.isFieldAccessExpr();
    }

    private static boolean isImplicitThis(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return true;
        Expression scope = call.getScope().get();
        return scope.isThisExpr();
    }

    private static Map<NameArity, List<MethodDeclaration>> indexDeclaredMethods(List<MethodDeclaration> methods) {
        Map<NameArity, List<MethodDeclaration>> index = new HashMap<>();
        for (MethodDeclaration md : methods) {
            NameArity key = new NameArity(md.getNameAsString(), md.getParameters().size());
            index.computeIfAbsent(key, __ -> new ArrayList<>()).add(md);
        }
        return index;
    }

    private static Optional<String> fallbackUniqueNameArity(
            MethodCallExpr call,
            String ownerFqcn,
            Map<NameArity, List<MethodDeclaration>> declaredIndex
    ) {
        NameArity key = new NameArity(call.getNameAsString(), call.getArguments().size());
        List<MethodDeclaration> candidates = declaredIndex.getOrDefault(key, List.of());

        if (candidates.size() != 1) return Optional.empty();

        MethodDeclaration callee = candidates.getFirst();
        return Optional.of(NodeIdGenerator.generateMethodNodeId(ownerFqcn, callee));
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
        final ResolvedMethodDeclaration resolved;
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


    private record NameArity(String name, int arity) {}
}
