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
import java.util.Map;
import java.util.Optional;

final class InjectedFieldCallHandler {

    static Optional<Edge> tryGenerateEdge(
            CallGraphPipeline.Input.ClassData classData,
            MethodCallExpr call,
            String fromNodeId,
            CallGraphPipeline.Input input
    ) {
        Optional<CallGraphPipeline.Input.InjectedField> injectedField = resolveInjectedField(classData, call);
        if (injectedField.isEmpty()) return Optional.empty();

        Optional<CallGraphPipeline.Input.ClassData> targetClass = resolveTargetClass(injectedField.get().declaredTypeFqcn(), input);
        if (targetClass.isEmpty()) return Optional.empty();

        Optional<String> toHigh = tryResolveTargetMethod(call, targetClass.get().fqcn());
        if (toHigh.isPresent()) {
            return Optional.of(Edge.v1(
                    fromNodeId,
                    toHigh.get(),
                    Enums.EdgeType.CALLS,
                    Enums.Confidence.HIGH,
                    List.of(methodCallEvidence(call, "injected-field-resolved"))
            ));
        }

        Optional<String> toMedium = fallbackUniqueNameArity(call, targetClass.get());
        return toMedium.map(s -> Edge.v1(
                        fromNodeId,
                        s,
                        Enums.EdgeType.CALLS,
                        Enums.Confidence.MEDIUM,
                        List.of(methodCallEvidence(call, "injected-field-name-arity-fallback"))
                ))
                .or(() -> Optional.of(buildUnresolvedTargetMethodEdge(fromNodeId, targetClass.get().fqcn(), call, injectedField.get())));
    }

    private static Optional<CallGraphPipeline.Input.InjectedField> resolveInjectedField(
            CallGraphPipeline.Input.ClassData classData,
            MethodCallExpr call
    ) {
        if (call.getScope().isEmpty()) return Optional.empty();
        Optional<String> fieldNameOpt = extractFieldName(call.getScope().get());
        if (fieldNameOpt.isEmpty()) return Optional.empty();

        List<CallGraphPipeline.Input.InjectedField> injectedFields = classData.injectedFields() == null
                ? List.of()
                : classData.injectedFields();
        for (CallGraphPipeline.Input.InjectedField injectedField : injectedFields) {
            if (injectedField.fieldName().equals(fieldNameOpt.get())) {
                return Optional.of(injectedField);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractFieldName(Expression scope) {
        if (scope.isNameExpr()) {
            return Optional.of(scope.asNameExpr().getNameAsString());
        }
        if (scope.isFieldAccessExpr()) {
            var fieldAccessExpr = scope.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                return Optional.of(fieldAccessExpr.getNameAsString());
            }
        }
        return Optional.empty();
    }

    private static Optional<CallGraphPipeline.Input.ClassData> resolveTargetClass(
            String declaredTypeFqcn,
            CallGraphPipeline.Input input
    ) {
        if (declaredTypeFqcn.contains(".")) {
            for (CallGraphPipeline.Input.ClassData classData : input.classData()) {
                if (classData.fqcn().equals(declaredTypeFqcn)) {
                    return Optional.of(classData);
                }
            }
            return Optional.empty();
        }

        CallGraphPipeline.Input.ClassData match = null;
        for (CallGraphPipeline.Input.ClassData classData : input.classData()) {
            if (simpleName(classData.fqcn()).equals(declaredTypeFqcn)) {
                if (match != null) return Optional.empty();
                match = classData;
            }
        }
        return Optional.ofNullable(match);
    }

    private static Optional<String> tryResolveTargetMethod(MethodCallExpr call, String targetClassFqcn) {
        ResolvedMethodDeclaration resolvedMethodDeclaration;
        try {
            resolvedMethodDeclaration = call.resolve();
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        String declaringTypeFqcn;
        try {
            declaringTypeFqcn = resolvedMethodDeclaration.declaringType().getQualifiedName();
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        if (!targetClassFqcn.equals(declaringTypeFqcn)) return Optional.empty();
        return Optional.of(NodeIdGenerator.generateMethodNodeId(targetClassFqcn, resolvedMethodDeclaration));
    }

    private static Optional<String> fallbackUniqueNameArity(MethodCallExpr call, CallGraphPipeline.Input.ClassData targetClass) {
        String name = call.getNameAsString();
        int arity = call.getArguments().size();

        List<MethodDeclaration> candidates = new ArrayList<>();
        for (MethodDeclaration methodDeclaration : targetClass.clazz().getMethods()) {
            if (methodDeclaration.getNameAsString().equals(name) && methodDeclaration.getParameters().size() == arity) {
                candidates.add(methodDeclaration);
            }
        }
        if (candidates.size() != 1) return Optional.empty();
        return Optional.of(NodeIdGenerator.generateMethodNodeId(targetClass.fqcn(), candidates.getFirst()));
    }

    private static Edge buildUnresolvedTargetMethodEdge(
            String fromNodeId,
            String targetClassFqcn,
            MethodCallExpr call,
            CallGraphPipeline.Input.InjectedField injectedField
    ) {
        Evidence evidence = methodCallEvidence(call, "injected-field-unresolved-target-method");
        return Edge.v2(
                fromNodeId,
                targetClassFqcn,
                Enums.EdgeType.CALLS,
                Enums.Confidence.LOW,
                List.of(evidence),
                Map.of(
                        "uncertainTargetMethod", true,
                        "injectedField", injectedField.fieldName(),
                        "targetClass", targetClassFqcn,
                        "targetMethodName", call.getNameAsString(),
                        "targetMethodArity", call.getArguments().size()
                )
        );
    }

    private static Evidence methodCallEvidence(MethodCallExpr call, String detail) {
        int line = call.getRange().map(r -> r.begin.line).orElse(-1);
        String file = call.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> storage.getPath().toString())
                .orElse(null);
        return new Evidence(file, line, "method-call", call.toString(), detail);
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }
}
