package com.xray.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.xray.model.SpringBeanAnnotationAttributes;
import com.xray.parse.AstIndex;
import com.xray.parse.NodeIdGenerator;

import java.nio.file.Path;
import java.util.*;

public final class BeanDetector {

    //TODO method mapping
    public static void annotateBeans(AstIndex astIndex) {
        for (Map.Entry<Path, CompilationUnit> pathToCompilationUnit : astIndex.fileToCu().entrySet()) {
            CompilationUnit compilationUnit = pathToCompilationUnit.getValue();
            for (ClassOrInterfaceDeclaration clazz : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassBeanContext> classBeanContext = identifyClassBeanInformation(clazz);
                if (classBeanContext.isEmpty()) {
                    continue;
                }

                annotateClassBeans(astIndex, clazz, classBeanContext.get());
            }
        }

    }

    private static void annotateClassBeans(AstIndex astIndex, ClassOrInterfaceDeclaration clazz, ClassBeanContext classBeanContext) {
        String nodeId = NodeIdGenerator.generateClassNodeId(clazz);
        AstIndex.NodeDraft existingClassDraft = astIndex.nodeDrafts().get(nodeId);
        if (existingClassDraft == null) {
            return;
        }

        Set<String> mergedTags = mergeClassTags(existingClassDraft, classBeanContext.springComponentAnnotation());
        Map<String, Object> mergedAttributes = mergeClassAttributes(existingClassDraft, clazz, classBeanContext.annotation());

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
                List.copyOf(mergedTags),
                Map.copyOf(mergedAttributes)
        );
        astIndex.updateDraft(nodeId, updatedClassDraft);
    }

    private static Map<String, Object> mergeClassAttributes(AstIndex.NodeDraft existingClassDraft, ClassOrInterfaceDeclaration clazz, AnnotationExpr componentAnnotation) {
        Map<String, Object> mergedAttributes = new HashMap<>();
        if (existingClassDraft.attributes() != null) {
            mergedAttributes.putAll(existingClassDraft.attributes());
        }
        SpringBeanAnnotationAttributes springBeanAnnotationAttributes = AnnotationHelper.extractSpringBeanClassAttributes(clazz, componentAnnotation);
        mergedAttributes.putAll(springBeanAnnotationAttributes.toNodeAttributes());

        return mergedAttributes;
    }

    private static Set<String> mergeClassTags(AstIndex.NodeDraft nodeDraft, SpringComponentAnnotation springComponentAnnotation) {
        Set<String> mergedTags = new LinkedHashSet<>();

        if (nodeDraft.tags() != null) {
            mergedTags.addAll(nodeDraft.tags());
        }

        mergedTags.addAll(springComponentAnnotation.tags());
        return mergedTags;
    }


    private static Optional<ClassBeanContext> identifyClassBeanInformation(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr annotation : clazz.getAnnotations()) {
            String foundName = annotation.getNameAsString();
            for (SpringComponentAnnotation springComponentAnnotation : SpringComponentAnnotation.values()) {
                if (foundName.equals(springComponentAnnotation.value()) || foundName.endsWith("." + springComponentAnnotation.value())) {
                    return Optional.of(new ClassBeanContext(springComponentAnnotation, annotation));
                }
            }
        }
        return Optional.empty();
    }


    private enum SpringComponentAnnotation {
        SERVICE("Service", Set.of("spring.beanCandidate", "spring.service")),
        COMPONENT("Component", Set.of("spring.beanCandidate", "spring.component")),
        REPOSITORY("Repository", Set.of("spring.beanCandidate", "spring.repository")),
        CONFIGURATION("Configuration", Set.of("spring.beanCandidate", "spring.configuration"));

        private final String value;
        private final Set<String> tags;

        SpringComponentAnnotation(String value, Set<String> tags) {
            this.value = value;
            this.tags = tags;
        }

        private String value() {
            return value;
        }

        private Set<String> tags() {
            return tags;
        }
    }

    private record ClassBeanContext(SpringComponentAnnotation springComponentAnnotation, AnnotationExpr annotation) {
    }
}
