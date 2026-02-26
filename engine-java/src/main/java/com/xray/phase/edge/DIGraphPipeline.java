package com.xray.phase.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.parse.AstIndex;
import com.xray.phase.edge.callgraph.CallGraphPipeline;

import java.io.IOException;
import java.util.*;

final class DIGraphPipeline {

    private static final List<String> BEAN_CANDIDATE_ANNOTATIONS = List.of(
            "Component",
            "Service",
            "Repository",
            "Controller",
            "RestController",
            "Configuration"
    );
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> LOMBOK_CONSTRUCTOR_ANNOTATIONS = Set.of("RequiredArgsConstructor", "AllArgsConstructor");
    private static final Set<String> COLLECTION_WRAPPERS = Set.of("List", "Set", "Collection", "java.util.List", "java.util.Set", "java.util.Collection");
    private static final Set<String> OPTIONAL_WRAPPERS = Set.of("Optional", "java.util.Optional");
    private static final Set<String> MAP_WRAPPERS = Set.of("Map", "java.util.Map");
    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    DIGraphPipeline(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.objectMapper = objectMapper;
        this.outputLayout = outputLayout;
    }

    Result emitEdges(AstIndex astIndex, Map<String, ClassOrInterfaceDeclaration> fqcnToDecl) throws IOException {
        Map<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> injectedFieldIndex = new HashMap<>();
        try (JsonlWriter edgeWriter = new JsonlWriter(outputLayout.getEdges(), objectMapper)) {
            for (AstIndex.NodeDraft nodeDraft : astIndex.nodeDrafts().values()) {
                if (nodeDraft.kind() == Enums.NodeKind.METHOD) {
                    Edge edge = Edge.v1(
                            nodeDraft.ownerId(),
                            nodeDraft.id(),
                            Enums.EdgeType.CONTAINS,
                            Enums.Confidence.HIGH //deterministic
                    );
                    edgeWriter.writeObject(edge);
                } else if (nodeDraft.kind() == Enums.NodeKind.CLASS && isClassBeanCandidate(nodeDraft)) {
                    ClassOrInterfaceDeclaration clazz = fqcnToDecl.get(nodeDraft.fqcn());
                    if (clazz == null) continue;

                    emitConstructorDiEdges(astIndex, nodeDraft, clazz, edgeWriter, injectedFieldIndex);
                    emitFieldDiEdges(astIndex, nodeDraft, clazz, edgeWriter, injectedFieldIndex);
                    emitLombokInferredConstructorDiEdges(astIndex, nodeDraft, clazz, edgeWriter, injectedFieldIndex);
                }
            }
        }
        return new Result(toResultIndex(injectedFieldIndex));
    }

    private void emitConstructorDiEdges(
            AstIndex astIndex,
            AstIndex.NodeDraft classDraft,
            ClassOrInterfaceDeclaration clazz,
            JsonlWriter edgeWriter,
            Map<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> injectedFieldIndex
    ) throws IOException {
        Optional<ConstructorSelection> constructorSelectionOpt = selectInjectionConstructor(clazz);
        if (constructorSelectionOpt.isEmpty()) {
            return;
        }

        ConstructorSelection constructorSelection = constructorSelectionOpt.get();
        ConstructorDeclaration constructor = constructorSelection.constructor();

        for (Parameter parameter : constructor.getParameters()) {
            Optional<Type> injectableType = extractInjectableDependencyType(parameter.getType());
            if (injectableType.isEmpty()) continue;
            Optional<DependencyTargetClassId> dependencyTargetClassId = resolveDependencyTargetClassId(astIndex, clazz, injectableType.get());
            if (dependencyTargetClassId.isEmpty()) {
                continue;
            }
            Enums.Confidence confidence = minConfidence(constructorSelection.confidence(), dependencyTargetClassId.get().confidence());

            Edge edge = Edge.v1(
                    classDraft.id(),
                    dependencyTargetClassId.get().classId(),
                    Enums.EdgeType.DI,
                    confidence
            );

            edgeWriter.writeObject(edge);

            Optional<String> assignedFieldName = findAssignedFieldName(constructor, parameter.getNameAsString());
            assignedFieldName.ifPresent(s -> addInjectedField(
                    classDraft.fqcn(),
                    s,
                    resolveDeclaredTypeFqcn(astIndex, clazz, injectableType.get()),
                    injectedFieldIndex
            ));
        }
    }

    private void emitFieldDiEdges(
            AstIndex astIndex,
            AstIndex.NodeDraft classDraft,
            ClassOrInterfaceDeclaration clazz,
            JsonlWriter edgeWriter,
            Map<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> injectedFieldIndex) throws IOException {
        for (FieldDeclaration fieldDeclaration : clazz.getFields()) {
            if (!hasInjectionAnnotation(fieldDeclaration)) continue;
            Optional<Type> injectableType = extractInjectableDependencyType(fieldDeclaration.getElementType());
            if (injectableType.isEmpty()) continue;
            Optional<DependencyTargetClassId> dependencyTargetClassId = resolveDependencyTargetClassId(astIndex, clazz, injectableType.get());
            if (dependencyTargetClassId.isEmpty()) continue;

            Edge edge = Edge.v1(
                    classDraft.id(),
                    dependencyTargetClassId.get().classId(),
                    Enums.EdgeType.DI,
                    dependencyTargetClassId.get().confidence()
            );

            edgeWriter.writeObject(edge);

            for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                addInjectedField(
                        classDraft.fqcn(),
                        variable.getNameAsString(),
                        resolveDeclaredTypeFqcn(astIndex, clazz, injectableType.get()),
                        injectedFieldIndex
                );
            }
        }
    }

    private void emitLombokInferredConstructorDiEdges(
            AstIndex astIndex,
            AstIndex.NodeDraft classDraft,
            ClassOrInterfaceDeclaration clazz,
            JsonlWriter edgeWriter,
            Map<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> injectedFieldIndex
    ) throws IOException {
        if (!clazz.getConstructors().isEmpty()) return;
        if (!hasLombokConstructorAnnotation(clazz)) return;

        for (FieldDeclaration fieldDeclaration : clazz.getFields()) {
            if (fieldDeclaration.isStatic()) continue;
            if (!fieldDeclaration.isFinal() && !hasNonNullAnnotation(fieldDeclaration)) continue;

            for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                Optional<Type> injectableType = extractInjectableDependencyType(variable.getType());
                if (injectableType.isEmpty()) continue;

                Optional<DependencyTargetClassId> dependencyTargetClassId = resolveDependencyTargetClassId(astIndex, clazz, injectableType.get());
                if (dependencyTargetClassId.isEmpty()) continue;
                Enums.Confidence confidence = minConfidence(Enums.Confidence.MEDIUM, dependencyTargetClassId.get().confidence());

                Edge edge = Edge.v1(
                        classDraft.id(),
                        dependencyTargetClassId.get().classId(),
                        Enums.EdgeType.DI,
                        confidence
                );
                edgeWriter.writeObject(edge);

                addInjectedField(
                        classDraft.fqcn(),
                        variable.getNameAsString(),
                        resolveDeclaredTypeFqcn(astIndex, clazz, injectableType.get()),
                        injectedFieldIndex
                );
            }
        }
    }

    private Optional<ConstructorSelection> selectInjectionConstructor(ClassOrInterfaceDeclaration clazz) {
        List<ConstructorDeclaration> constructors = clazz.getConstructors();
        if (constructors.isEmpty()) return Optional.empty();

        if (constructors.size() == 1) {
            return Optional.of(new ConstructorSelection(constructors.getFirst(), Enums.Confidence.HIGH));
        }

        List<ConstructorDeclaration> injected = constructors.stream()
                .filter(this::isInjectableConstructor)
                .toList();
        if (injected.size() == 1) {
            return Optional.of(new ConstructorSelection(injected.getFirst(), Enums.Confidence.HIGH));
        }
        if (injected.size() > 1) {
            return Optional.of(new ConstructorSelection(
                    injected.stream().max(Comparator.comparingInt(cd -> cd.getParameters().size())).orElseThrow(),
                    Enums.Confidence.MEDIUM
            ));
        }
        return Optional.empty();
    }

    private Optional<DependencyTargetClassId> resolveDependencyTargetClassId(
            AstIndex astIndex,
            ClassOrInterfaceDeclaration ownerClass,
            Type dependencyType
    ) {
        // 1) try symbol solver FQCN
        try {
            String fqcn = dependencyType.resolve().describe();
            String raw = stripGenerics(fqcn);
            String id = astIndex.fqcnToNodeId().get(raw);
            if (id == null) return Optional.empty();
            return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.HIGH));
        } catch (Exception ignored) {
        }

        String raw = stripGenerics(dependencyType.asString());

        // 2) written type is FQCN
        if (raw.contains(".")) {
            String id = astIndex.fqcnToNodeId().get(raw);
            if (id == null) return Optional.empty();
            return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.MEDIUM));
        }

        // 3) imports and same package context
        Optional<String> fromCompilationUnit = resolveFqcnFromCompilationUnitContext(raw, ownerClass, astIndex);
        if (fromCompilationUnit.isPresent()) {
            String id = astIndex.fqcnToNodeId().get(fromCompilationUnit.get());
            if (id != null) {
                return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.MEDIUM));
            }
        }

        // 4) project-wide simple-name fallback
        List<String> fqcns = astIndex.simpleNameToFqcns().getOrDefault(raw, List.of());
        if (fqcns.size() == 1) {
            String id = astIndex.fqcnToNodeId().get(fqcns.getFirst());
            if (id != null) {
                return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.MEDIUM));
            }
        }

        return Optional.empty();
    }

    private String stripGenerics(String s) {
        int idx = s.indexOf('<');
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    private String simpleTypeName(String s) {
        // handles com.a.Foo -> Foo
        int idx = s.lastIndexOf('.');
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    private boolean hasInjectionAnnotation(NodeWithAnnotations<?> n) {
        return n.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier())
                .anyMatch(INJECTION_ANNOTATIONS::contains);
    }

    private boolean hasNonNullAnnotation(NodeWithAnnotations<?> n) {
        return n.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier())
                .anyMatch(name -> name.equals("NonNull"));
    }

    private boolean isInjectableConstructor(ConstructorDeclaration constructorDeclaration) {
        if (hasInjectionAnnotation(constructorDeclaration)) return true;
        return constructorDeclaration.getParameters().stream().anyMatch(this::hasInjectionAnnotation);
    }

    private boolean hasLombokConstructorAnnotation(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier())
                .anyMatch(LOMBOK_CONSTRUCTOR_ANNOTATIONS::contains);
    }

    private boolean isClassBeanCandidate(AstIndex.NodeDraft classDraft) {
        for (String ann : classDraft.annotations()) {
            String simple = ann.contains(".") ? ann.substring(ann.lastIndexOf('.') + 1) : ann;
            if (BEAN_CANDIDATE_ANNOTATIONS.contains(simple)) return true;
        }
        return false;
    }

    private record DependencyTargetClassId(String classId, Enums.Confidence confidence) {
    }

    private void addInjectedField(String classFqcn, String fieldName, String declaredTypeFqcn,
            Map<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> injectedFieldIndex) {
        injectedFieldIndex.computeIfAbsent(classFqcn, __ -> new LinkedHashMap<>())
                .putIfAbsent(fieldName, new CallGraphPipeline.Input.InjectedField(fieldName, declaredTypeFqcn));
    }

    private Optional<String> findAssignedFieldName(ConstructorDeclaration constructor, String parameterName) {
        for (AssignExpr assignExpr : constructor.findAll(AssignExpr.class)) {
            if (!isSameNamedParameter(assignExpr.getValue(), parameterName)) continue;
            Optional<String> fieldName = asAssignedFieldName(assignExpr.getTarget());
            if (fieldName.isPresent()) {
                return fieldName;
            }
        }
        return Optional.empty();
    }

    private boolean isSameNamedParameter(Expression expression, String parameterName) {
        return expression.isNameExpr() && expression.asNameExpr().getNameAsString().equals(parameterName);
    }

    private Optional<String> asAssignedFieldName(Expression expression) {
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                return Optional.of(fieldAccessExpr.getNameAsString());
            }
        }
        return Optional.empty();
    }

    private String resolveDeclaredTypeFqcn(AstIndex astIndex, ClassOrInterfaceDeclaration ownerClass, Type type) {
        try {
            return stripGenerics(type.resolve().describe());
        } catch (Exception ignored) {
        }

        String raw = stripGenerics(type.asString());
        if (raw.contains(".")) {
            return raw;
        }

        Optional<String> fromCompilationUnit = resolveFqcnFromCompilationUnitContext(raw, ownerClass, astIndex);
        if (fromCompilationUnit.isPresent()) {
            return fromCompilationUnit.get();
        }

        List<String> fromSimpleName = astIndex.simpleNameToFqcns().getOrDefault(raw, List.of());
        if (fromSimpleName.size() == 1) {
            return fromSimpleName.getFirst();
        }
        return raw;
    }

    private Optional<Type> extractInjectableDependencyType(Type type) {
        if (type instanceof PrimitiveType) return Optional.empty();
        if (!type.isClassOrInterfaceType()) return Optional.empty();

        ClassOrInterfaceType classType = type.asClassOrInterfaceType();
        String name = classType.getNameWithScope();

        if (OPTIONAL_WRAPPERS.contains(name) || COLLECTION_WRAPPERS.contains(name)) {
            if (classType.getTypeArguments().isEmpty()) return Optional.empty();
            List<Type> typeArguments = classType.getTypeArguments().get();
            if (typeArguments.size() != 1) return Optional.empty();
            return extractInjectableDependencyType(typeArguments.getFirst());
        }

        if (MAP_WRAPPERS.contains(name)) {
            if (classType.getTypeArguments().isEmpty()) return Optional.empty();
            List<Type> typeArguments = classType.getTypeArguments().get();
            if (typeArguments.size() < 2) return Optional.empty();
            return extractInjectableDependencyType(typeArguments.get(1));
        }

        return Optional.of(type);
    }

    private Optional<String> resolveFqcnFromCompilationUnitContext(
            String simpleName,
            ClassOrInterfaceDeclaration ownerClass,
            AstIndex astIndex
    ) {
        Optional<CompilationUnit> compilationUnit = ownerClass.findCompilationUnit();
        if (compilationUnit.isEmpty()) return Optional.empty();

        for (ImportDeclaration importDeclaration : compilationUnit.get().getImports()) {
            if (importDeclaration.isAsterisk()) {
                String candidate = importDeclaration.getNameAsString() + "." + simpleName;
                if (astIndex.fqcnToNodeId().containsKey(candidate)) {
                    return Optional.of(candidate);
                }
            } else {
                String imported = importDeclaration.getNameAsString();
                if (simpleTypeName(imported).equals(simpleName) && astIndex.fqcnToNodeId().containsKey(imported)) {
                    return Optional.of(imported);
                }
            }
        }

        String packageName = compilationUnit.get().getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        if (!packageName.isBlank()) {
            String samePackageCandidate = packageName + "." + simpleName;
            if (astIndex.fqcnToNodeId().containsKey(samePackageCandidate)) {
                return Optional.of(samePackageCandidate);
            }
        }

        return Optional.empty();
    }

    private Enums.Confidence minConfidence(Enums.Confidence left, Enums.Confidence right) {
        if (left == Enums.Confidence.LOW || right == Enums.Confidence.LOW) return Enums.Confidence.LOW;
        if (left == Enums.Confidence.MEDIUM || right == Enums.Confidence.MEDIUM) return Enums.Confidence.MEDIUM;
        return Enums.Confidence.HIGH;
    }

    private Map<String, List<CallGraphPipeline.Input.InjectedField>> toResultIndex(
            Map<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> injectedFieldIndex) {
        Map<String, List<CallGraphPipeline.Input.InjectedField>> result = new HashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, CallGraphPipeline.Input.InjectedField>> entry : injectedFieldIndex.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        return Map.copyOf(result);
    }

    record Result(Map<String, List<CallGraphPipeline.Input.InjectedField>> injectedFieldsByClassFqcn) {
    }

    private record ConstructorSelection(ConstructorDeclaration constructor, Enums.Confidence confidence) {
    }
}
