package com.xray.phase.edge.callgraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.xray.engine.NodeIdGenerator;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.Edge;

import java.io.IOException;
import java.util.*;

public final class CallGraphPipeline {

    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    public CallGraphPipeline(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.objectMapper = objectMapper;
        this.outputLayout = outputLayout;
    }

    public void emitEdges(Input input) throws IOException {
        try (JsonlWriter writer = new JsonlWriter(outputLayout.getEdges(), objectMapper)) {
            for (Input.ClassData classData : input.classData()) {

                // index declared methods by (name, arity)
                Map<NameArity, List<MethodDeclaration>> declaredIndex = indexDeclaredMethods(classData.clazz().getMethods());

                for (MethodDeclaration method : classData.clazz().getMethods()) {
                    String fromNodeId = NodeIdGenerator.generateMethodNodeId(classData.fqcn(), method);
                    Optional<BlockStmt> body = method.getBody();
                    if (body.isEmpty()) continue;
                    for (MethodCallExpr call : body.get().findAll(MethodCallExpr.class)) {
                        Optional<Edge> primaryCallEdge = SameClassCallHandler.tryGenerateEdge(classData, call, fromNodeId, declaredIndex);
                        if (primaryCallEdge.isEmpty()) {
                            primaryCallEdge = InjectedFieldCallHandler.tryGenerateEdge(classData, call, fromNodeId, input);
                        }
                        if (primaryCallEdge.isEmpty()) {
                            primaryCallEdge = StaticCallHandler.tryGenerateEdge(call, fromNodeId, input);
                        }
                        if (primaryCallEdge.isPresent()) {
                            writer.writeObject(primaryCallEdge.get());
                        }

                        Optional<Edge> persistenceHitEdge = PersistenceHitCallHandler.tryGenerateEdge(classData, call, fromNodeId, input);
                        if (persistenceHitEdge.isPresent()) {
                            writer.writeObject(persistenceHitEdge.get());
                        }

                        Optional<Edge> outboundEdge = OutboundCallHandler.tryGenerateEdge(classData, call, fromNodeId, input);
                        if (outboundEdge.isPresent()) {
                            writer.writeObject(outboundEdge.get());
                        }
                    }
                }
            }
        }
    }

    private static Map<NameArity, List<MethodDeclaration>> indexDeclaredMethods(List<MethodDeclaration> methods) {
        Map<NameArity, List<MethodDeclaration>> index = new HashMap<>();
        for (MethodDeclaration md : methods) {
            NameArity key = new NameArity(md.getNameAsString(), md.getParameters().size());
            index.computeIfAbsent(key, __ -> new ArrayList<>()).add(md);
        }
        return index;
    }

    public record Input(List<ClassData> classData) {

        public record ClassData(String fqcn, ClassOrInterfaceDeclaration clazz, List<InjectedField> injectedFields) {
        }

        public record InjectedField(String fieldName, String declaredTypeFqcn) {
        }
    }

    record NameArity(String name, int arity) {
    }
}
