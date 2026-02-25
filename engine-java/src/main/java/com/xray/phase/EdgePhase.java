package com.xray.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;
import com.xray.model.Enums;
import com.xray.parse.AstIndex;

import java.io.IOException;
import java.util.*;

public final class EdgePhase {

    private static final List<String> BEAN_CANDIDATE_ANNOTATIONS = List.of(
            "Component",
            "Service",
            "Repository",
            "Controller",
            "RestController",
            "Configuration"
    );
    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    public EdgePhase(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.objectMapper = objectMapper;
        this.outputLayout = outputLayout;
    }

    public void processEdges(AstIndex astIndex) throws IOException {
        Map<String, ClassOrInterfaceDeclaration> fqcnToDecl = buildFqcnToClassDecl(astIndex);

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

                    emitConstructorDiEdges(astIndex, nodeDraft, clazz, edgeWriter);
                    emitFieldDiEdges(astIndex, nodeDraft, clazz, edgeWriter);
                }
            }
        }
    }

    private void emitConstructorDiEdges(AstIndex astIndex, AstIndex.NodeDraft classDraft, ClassOrInterfaceDeclaration clazz, JsonlWriter edgeWriter) throws IOException {
        Optional<ConstructorDeclaration> constructorDeclaration = selectInjectionConstructor(clazz);
        if (constructorDeclaration.isEmpty()) {
            return;
        }

        ConstructorDeclaration constructor = constructorDeclaration.get();

        for (Parameter parameter : constructor.getParameters()) {
            // MVP: only handle normal class/interface types
            if (!parameter.getType().isClassOrInterfaceType()) continue;

            Optional<DependencyTargetClassId> dependencyTargetClassId = resolveDependencyTargetClassId(astIndex, parameter.getType());
            if (dependencyTargetClassId.isEmpty()) {
                continue;
            }

            Edge edge = Edge.v1(
                    classDraft.id(),
                    dependencyTargetClassId.get().classId(),
                    Enums.EdgeType.DI,
                    dependencyTargetClassId.get().confidence()
            );

            edgeWriter.writeObject(edge);
        }
    }

    private void emitFieldDiEdges(AstIndex astIndex, AstIndex.NodeDraft classDraft, ClassOrInterfaceDeclaration clazz, JsonlWriter edgeWriter) throws IOException {
        for (FieldDeclaration fieldDeclaration : clazz.getFields()) {
            if (!hasAutowiredAnnotation(fieldDeclaration)) continue;

            Type type = fieldDeclaration.getElementType();
            if (!type.isClassOrInterfaceType()) continue;

            Optional<DependencyTargetClassId> dependencyTargetClassId = resolveDependencyTargetClassId(astIndex, type);
            if (dependencyTargetClassId.isEmpty()) continue;

            Edge edge = Edge.v1(
                    classDraft.id(),
                    dependencyTargetClassId.get().classId(),
                    Enums.EdgeType.DI,
                    dependencyTargetClassId.get().confidence()
            );

            edgeWriter.writeObject(edge);
        }
    }

    private Map<String, ClassOrInterfaceDeclaration> buildFqcnToClassDecl(AstIndex astIndex) {
        Map<String, ClassOrInterfaceDeclaration> map = new HashMap<>();
        for (CompilationUnit cu : astIndex.fileToCu().values()) {
            for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                c.getFullyQualifiedName().ifPresent(fqcn -> map.putIfAbsent(fqcn, c));
            }
        }
        return map;
    }

    private Optional<ConstructorDeclaration> selectInjectionConstructor(ClassOrInterfaceDeclaration clazz) {
        List<ConstructorDeclaration> constructors = clazz.getConstructors();
        if (constructors.isEmpty()) return Optional.empty();

        if (constructors.size() == 1) return Optional.of(constructors.getFirst());

        List<ConstructorDeclaration> autowired = constructors.stream()
                .filter(this::hasAutowiredAnnotation)
                .toList();
        if (autowired.size() == 1) return Optional.of(autowired.getFirst());
        if (!autowired.isEmpty()) {
            // multiple @Autowired: pick max params (and later lower confidence)
            return Optional.of(autowired.stream().max(Comparator.comparingInt(cd -> cd.getParameters().size())).orElseThrow());
        }

        // else: max params
        return Optional.of(constructors.stream().max(Comparator.comparingInt(cd -> cd.getParameters().size())).orElseThrow());
    }

    private Optional<DependencyTargetClassId> resolveDependencyTargetClassId(AstIndex astIndex, Type t) {
        // 1) try symbol solver FQCN
        try {
            String fqcn = t.resolve().describe(); // e.g. com.acme.Foo
            // strip generics if present: java.util.List<com.X>
            String raw = stripGenerics(fqcn);
            String id = astIndex.fqcnToNodeId().get(raw);
            if (id == null) return Optional.empty();
            return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.HIGH));
        } catch (Exception ignored) {}

        // 2) fallback: raw as written -> simple name mapping
        String raw = stripGenerics(t.asString()); // Foo, List<Foo> -> Foo?
        String simple = simpleTypeName(raw);      // Foo from Foo or com.a.Foo

        // if it’s already qualified:
        if (raw.contains(".")) {
            String id = astIndex.fqcnToNodeId().get(raw);
            if (id == null) return Optional.empty();
            return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.MEDIUM));
        }

        List<String> fqcns = astIndex.simpleNameToFqcns().getOrDefault(simple, List.of());
        if (fqcns.size() == 1) {
            String id = astIndex.fqcnToNodeId().get(fqcns.getFirst());
            if (id == null) return Optional.empty();
            return Optional.of(new DependencyTargetClassId(id, Enums.Confidence.MEDIUM));
        }

        // ambiguous or missing
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

    private boolean hasAutowiredAnnotation(NodeWithAnnotations<?> n) {
        return n.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Autowired"));
    }

    private boolean isClassBeanCandidate(AstIndex.NodeDraft classDraft) {
        for (String ann : classDraft.annotations()) {
            String simple = ann.contains(".") ? ann.substring(ann.lastIndexOf('.') + 1) : ann;
            if (BEAN_CANDIDATE_ANNOTATIONS.contains(simple)) return true;
        }
        return false;
    }

    private record DependencyTargetClassId(String classId, Enums.Confidence confidence) {}
}
