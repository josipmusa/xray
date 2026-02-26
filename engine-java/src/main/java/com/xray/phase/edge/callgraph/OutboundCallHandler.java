package com.xray.phase.edge.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.model.Evidence;

import java.util.*;

final class OutboundCallHandler {

    private static final Set<String> OUTBOUND_CLIENT_TYPES = Set.of(
            "RestTemplate",
            "org.springframework.web.client.RestTemplate",
            "RestClient",
            "org.springframework.web.client.RestClient",
            "WebClient",
            "org.springframework.web.reactive.function.client.WebClient"
    );

    static Optional<Edge> tryGenerateEdge(
            CallGraphPipeline.Input.ClassData classData,
            MethodCallExpr call,
            String fromNodeId,
            CallGraphPipeline.Input input
    ) {
        Optional<CallGraphPipeline.Input.InjectedField> injectedField = resolveInjectedField(classData, call);
        if (injectedField.isEmpty()) return Optional.empty();

        String declaredTypeFqcn = injectedField.get().declaredTypeFqcn();

        if (isKnownOutboundClientType(declaredTypeFqcn)) {
            return Optional.of(buildOutboundEdge(
                    fromNodeId,
                    "outbound:http",
                    call,
                    "outbound-http-client",
                    "http-client",
                    classData
            ));
        }

        Optional<CallGraphPipeline.Input.ClassData> targetClass = resolveTargetClass(declaredTypeFqcn, input);
        if (targetClass.isEmpty()) return Optional.empty();
        if (!isFeignClient(targetClass.get().clazz())) return Optional.empty();

        String clientName = feignClientName(targetClass.get().clazz(), targetClass.get().fqcn());
        String toId = "outbound:feign:" + clientName;
        return Optional.of(buildOutboundEdge(
                fromNodeId,
                toId,
                call,
                "outbound-feign-client",
                "feign",
                classData
        ));
    }

    private static Edge buildOutboundEdge(
            String fromNodeId,
            String toId,
            MethodCallExpr call,
            String detail,
            String kind,
            CallGraphPipeline.Input.ClassData classData
    ) {
        Evidence evidence = methodCallEvidence(call, detail);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("outbound.kind", kind);

        Optional<String> urlHint = extractUrlHint(call);
        urlHint.ifPresent(s -> attributes.put("outbound.urlHint", s));

        Optional<String> hostHint = extractHostHintFromValueFieldReference(classData.clazz(), call);
        hostHint.ifPresent(s -> attributes.put("outbound.hostHint", s));

        return Edge.v2(
                fromNodeId,
                toId,
                Enums.EdgeType.OUTBOUND_CALL,
                Enums.Confidence.MEDIUM,
                List.of(evidence),
                attributes
        );
    }

    private static Optional<String> resolveInjectedFieldName(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return Optional.empty();
        Expression scope = call.getScope().get();
        if (scope.isNameExpr()) return Optional.of(scope.asNameExpr().getNameAsString());
        if (scope.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = scope.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                return Optional.of(fieldAccessExpr.getNameAsString());
            }
        }
        return Optional.empty();
    }

    private static Optional<CallGraphPipeline.Input.InjectedField> resolveInjectedField(
            CallGraphPipeline.Input.ClassData classData,
            MethodCallExpr call
    ) {
        Optional<String> fieldNameOpt = resolveInjectedFieldName(call);
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

    private static boolean isKnownOutboundClientType(String declaredType) {
        if (OUTBOUND_CLIENT_TYPES.contains(declaredType)) return true;
        return OUTBOUND_CLIENT_TYPES.stream().anyMatch(t -> declaredType.endsWith("." + t));
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

    private static boolean isFeignClient(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream().anyMatch(a -> "FeignClient".equals(simpleName(a.getNameAsString())));
    }

    private static String feignClientName(ClassOrInterfaceDeclaration clazz, String defaultName) {
        for (AnnotationExpr annotationExpr : clazz.getAnnotations()) {
            if (!"FeignClient".equals(simpleName(annotationExpr.getNameAsString()))) continue;
            String name = annotationAttribute(annotationExpr, "name");
            if (name != null && !name.isBlank()) return sanitizeIdPart(name);
            String value = annotationAttribute(annotationExpr, "value");
            if (value != null && !value.isBlank()) return sanitizeIdPart(value);
        }
        return sanitizeIdPart(simpleName(defaultName));
    }

    private static String annotationAttribute(AnnotationExpr annotationExpr, String attr) {
        if ("value".equals(attr) && annotationExpr.isSingleMemberAnnotationExpr()) {
            Expression value = annotationExpr.asSingleMemberAnnotationExpr().getMemberValue();
            return expressionAsString(value);
        }
        if (!annotationExpr.isNormalAnnotationExpr()) return null;
        for (MemberValuePair pair : annotationExpr.asNormalAnnotationExpr().getPairs()) {
            if (attr.equals(pair.getNameAsString())) {
                return expressionAsString(pair.getValue());
            }
        }
        return null;
    }

    private static String expressionAsString(Expression expression) {
        if (expression.isStringLiteralExpr()) return expression.asStringLiteralExpr().asString();
        return expression.toString();
    }

    private static String sanitizeIdPart(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Optional<String> extractUrlHint(MethodCallExpr call) {
        for (Expression argument : call.getArguments()) {
            if (!argument.isStringLiteralExpr()) continue;
            String value = argument.asStringLiteralExpr().asString();
            if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/")) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractHostHintFromValueFieldReference(
            ClassOrInterfaceDeclaration clazz,
            MethodCallExpr call
    ) {
        Map<String, String> valueFieldMap = valueInjectedFieldHints(clazz);
        if (valueFieldMap.isEmpty()) return Optional.empty();

        for (Expression arg : call.getArguments()) {
            if (arg.isNameExpr()) {
                String fieldName = arg.asNameExpr().getNameAsString();
                if (valueFieldMap.containsKey(fieldName)) {
                    return Optional.of(valueFieldMap.get(fieldName));
                }
            }
            if (arg.isFieldAccessExpr()) {
                FieldAccessExpr fieldAccessExpr = arg.asFieldAccessExpr();
                if (fieldAccessExpr.getScope().isThisExpr() && valueFieldMap.containsKey(fieldAccessExpr.getNameAsString())) {
                    return Optional.of(valueFieldMap.get(fieldAccessExpr.getNameAsString()));
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> valueInjectedFieldHints(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> result = new HashMap<>();
        for (FieldDeclaration field : clazz.getFields()) {
            Optional<AnnotationExpr> valueAnnotation = field.getAnnotations().stream()
                    .filter(a -> "Value".equals(simpleName(a.getNameAsString())))
                    .findFirst();
            if (valueAnnotation.isEmpty()) continue;

            String hint = annotationAttribute(valueAnnotation.get(), "value");
            if (hint == null || hint.isBlank()) continue;

            for (VariableDeclarator variable : field.getVariables()) {
                result.put(variable.getNameAsString(), hint);
            }
        }
        return result;
    }

    private static Evidence methodCallEvidence(MethodCallExpr call, String detail) {
        int line = call.getRange().map(r -> r.begin.line).orElse(-1);
        String file = call.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> storage.getPath().toString())
                .orElse(null);
        return new Evidence(file, line, "method-call", call.toString(), detail);
    }

    private static String simpleName(String fqcnOrName) {
        int idx = fqcnOrName.lastIndexOf('.');
        return idx >= 0 ? fqcnOrName.substring(idx + 1) : fqcnOrName;
    }
}
