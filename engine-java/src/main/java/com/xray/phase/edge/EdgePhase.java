package com.xray.phase.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.xray.io.OutputLayout;
import com.xray.parse.AstIndex;
import com.xray.phase.edge.callgraph.CallGraphPipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EdgePhase {

    private final DIGraphPipeline diGraphPipeline;
    private final CallGraphPipeline callGraphPipeline;

    public EdgePhase(ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.diGraphPipeline = new DIGraphPipeline(objectMapper, outputLayout);
        this.callGraphPipeline = new CallGraphPipeline(objectMapper, outputLayout);
    }

    public void executePhase(AstIndex astIndex) throws IOException {
        Map<String, ClassOrInterfaceDeclaration> fqcnToDecl = buildFqcnToClassDecl(astIndex);
        CallGraphPipeline.Input callGraphPipelineInput = buildCallGraphPipelineInput(fqcnToDecl);

        this.diGraphPipeline.emitGraphEdges(astIndex, fqcnToDecl);
        this.callGraphPipeline.emitCallGraphs(callGraphPipelineInput);
    }

    private static Map<String, ClassOrInterfaceDeclaration> buildFqcnToClassDecl(AstIndex astIndex) {
        Map<String, ClassOrInterfaceDeclaration> map = new HashMap<>();
        for (CompilationUnit cu : astIndex.fileToCu().values()) {
            for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                c.getFullyQualifiedName().ifPresent(fqcn -> map.putIfAbsent(fqcn, c));
            }
        }
        return map;
    }

    private static CallGraphPipeline.Input buildCallGraphPipelineInput(Map<String, ClassOrInterfaceDeclaration> fqcnToDecl) {
        List<CallGraphPipeline.Input.ClassData> classDataList = new ArrayList<>();
        for (Map.Entry<String, ClassOrInterfaceDeclaration> entry : fqcnToDecl.entrySet()) {
            classDataList.add(new CallGraphPipeline.Input.ClassData(entry.getKey(), entry.getValue(), null));
        }
        return new CallGraphPipeline.Input(classDataList);
    }
}
