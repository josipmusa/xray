package com.xray.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.xray.engine.NodeIdGenerator;
import com.xray.parse.AstIndex;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class OutboundDetector {

    private OutboundDetector() {}

    public static void annotateOutbound(AstIndex astIndex) {
        for (Map.Entry<Path, CompilationUnit> pathToCompilationUnit : astIndex.fileToCu().entrySet()) {
            CompilationUnit compilationUnit = pathToCompilationUnit.getValue();
            for (ClassOrInterfaceDeclaration clazz : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                AnnotationExpr feignClientAnnotation = findFeignClientAnnotation(clazz);
                if (feignClientAnnotation == null) continue;
                updateFeignClientTagsAndAttributes(astIndex, clazz, feignClientAnnotation);
            }
        }
    }

    private static AnnotationExpr findFeignClientAnnotation(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr annotation : clazz.getAnnotations()) {
            String name = annotation.getNameAsString();
            String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if ("FeignClient".equals(simple)) return annotation;
        }
        return null;
    }

    private static void updateFeignClientTagsAndAttributes(
            AstIndex astIndex,
            ClassOrInterfaceDeclaration clazz,
            AnnotationExpr feignClientAnnotation
    ) {
        String nodeId = NodeIdGenerator.generateClassNodeId(clazz);
        AstIndex.NodeDraft existingClassDraft = astIndex.nodeDrafts().get(nodeId);
        if (existingClassDraft == null) return;

        Set<String> mergedTags = new LinkedHashSet<>();
        if (existingClassDraft.tags() != null) {
            mergedTags.addAll(existingClassDraft.tags());
        }
        mergedTags.add("spring.feign.client");
        mergedTags.add("outbound.client");

        Map<String, Object> mergedAttributes = new HashMap<>();
        if (existingClassDraft.attributes() != null) {
            mergedAttributes.putAll(existingClassDraft.attributes());
        }
        String clientName = extractAnnotationAttribute(feignClientAnnotation, "name");
        if (clientName == null || clientName.isBlank()) {
            clientName = extractAnnotationAttribute(feignClientAnnotation, "value");
        }
        if (clientName != null && !clientName.isBlank()) {
            mergedAttributes.put("outbound.clientName", clientName);
        }
        String url = extractAnnotationAttribute(feignClientAnnotation, "url");
        if (url != null && !url.isBlank()) {
            mergedAttributes.put("outbound.url", url);
        }
        String path = extractAnnotationAttribute(feignClientAnnotation, "path");
        if (path != null && !path.isBlank()) {
            mergedAttributes.put("outbound.path", path);
        }

        AstIndex.NodeDraft updatedClassDraft = new AstIndex.NodeDraft(
                existingClassDraft.id(),
                existingClassDraft.kind(),
                existingClassDraft.name(),
                existingClassDraft.fqcn(),
                existingClassDraft.signature(),
                existingClassDraft.ownerId(),
                existingClassDraft.source(),
                existingClassDraft.annotations(),
                existingClassDraft.modifiers(),
                java.util.List.copyOf(mergedTags),
                mergedAttributes.isEmpty() ? null : java.util.Map.copyOf(mergedAttributes)
        );
        astIndex.updateDraft(nodeId, updatedClassDraft);
    }

    private static String extractAnnotationAttribute(AnnotationExpr annotation, String attributeName) {
        if ("value".equals(attributeName) && annotation.isSingleMemberAnnotationExpr()) {
            return asString(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (!annotation.isNormalAnnotationExpr()) return null;
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if (attributeName.equals(pair.getNameAsString())) {
                return asString(pair.getValue());
            }
        }
        return null;
    }

    private static String asString(com.github.javaparser.ast.expr.Expression expression) {
        if (expression.isStringLiteralExpr()) return expression.asStringLiteralExpr().asString();
        return expression.toString();
    }
}
