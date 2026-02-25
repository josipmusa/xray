package com.xray.phase.edge;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.List;

public record CallGraphPipelineInput(List<ClassData> classData) {

    public record ClassData(String fqcn, ClassOrInterfaceDeclaration clazz, List<InjectedFields> injectedFields) {}

    public record InjectedFields(String fieldName, String declaredTypeFqcn) {}
}
