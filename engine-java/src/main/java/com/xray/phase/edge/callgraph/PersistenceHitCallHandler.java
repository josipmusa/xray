package com.xray.phase.edge.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.model.Evidence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

final class PersistenceHitCallHandler {

    private static final String PERSISTENCE_TERMINAL_ID = "persistence:db";
    private static final Set<String> SPRING_DATA_REPOSITORY_BASE_TYPES = Set.of(
            "JpaRepository",
            "CrudRepository",
            "PagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository"
    );
    private static final Set<String> PERSISTENCE_CLIENT_TYPES = Set.of(
            "JdbcTemplate",
            "org.springframework.jdbc.core.JdbcTemplate",
            "EntityManager",
            "jakarta.persistence.EntityManager",
            "javax.persistence.EntityManager"
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
        if (isPersistenceClientType(declaredTypeFqcn)) {
            return Optional.of(Edge.v1(
                    fromNodeId,
                    PERSISTENCE_TERMINAL_ID,
                    Enums.EdgeType.PERSISTENCE_HIT,
                    Enums.Confidence.MEDIUM,
                    List.of(methodCallEvidence(call, "persistence-client-call"))
            ));
        }

        Optional<CallGraphPipeline.Input.ClassData> targetClass = resolveTargetClass(declaredTypeFqcn, input);
        if (targetClass.isEmpty()) return Optional.empty();
        if (!isSpringDataRepository(targetClass.get().clazz())) return Optional.empty();

        return Optional.of(Edge.v1(
                fromNodeId,
                targetClass.get().fqcn(),
                Enums.EdgeType.PERSISTENCE_HIT,
                Enums.Confidence.HIGH,
                List.of(methodCallEvidence(call, "spring-data-repository-call"))
        ));
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

    private static boolean isPersistenceClientType(String declaredType) {
        if (PERSISTENCE_CLIENT_TYPES.contains(declaredType)) return true;
        return PERSISTENCE_CLIENT_TYPES.stream().anyMatch(type -> declaredType.endsWith("." + type));
    }

    private static boolean isSpringDataRepository(ClassOrInterfaceDeclaration clazz) {
        return clazz.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameWithScope)
                .anyMatch(PersistenceHitCallHandler::isSpringDataRepositoryType)
                || clazz.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameWithScope)
                .anyMatch(PersistenceHitCallHandler::isSpringDataRepositoryType);
    }

    private static boolean isSpringDataRepositoryType(String typeName) {
        if (SPRING_DATA_REPOSITORY_BASE_TYPES.contains(typeName)) return true;
        return SPRING_DATA_REPOSITORY_BASE_TYPES.stream().anyMatch(base -> typeName.endsWith("." + base));
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
