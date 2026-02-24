package com.xray.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.xray.model.SpringBeanAnnotationAttributes;
import com.xray.parse.AstIndex;
import com.xray.engine.NodeIdGenerator;

import java.nio.file.Path;
import java.util.*;

public final class BeanDetector {

    public static void annotateBeans(AstIndex astIndex) {
        for (Map.Entry<Path, CompilationUnit> pathToCompilationUnit : astIndex.fileToCu().entrySet()) {
            CompilationUnit compilationUnit = pathToCompilationUnit.getValue();
            for (ClassOrInterfaceDeclaration clazz : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassBeanContext> classBeanContext = identifyClassBeanInformation(clazz);
                if (classBeanContext.isEmpty()) {
                    continue;
                }

                annotateClassBeans(astIndex, clazz, classBeanContext.get());
                if (classBeanContext.get().springComponentAnnotation() == SpringComponentAnnotation.CONFIGURATION) {
                    annotateMethodBeans(astIndex, clazz);
                }
            }
        }

    }

    private static void annotateMethodBeans(AstIndex astIndex, ClassOrInterfaceDeclaration clazz) {
        for (MethodDeclaration method : clazz.getMethods()) {
            Optional<AnnotationExpr> beanAnnotation = AnnotationHelper.findAnnotation(method.getAnnotations(), "Bean");
            if (beanAnnotation.isEmpty()) {
                continue;
            }
            String classFqn = clazz.getFullyQualifiedName().orElse(clazz.getNameAsString());
            String nodeId = NodeIdGenerator.generateMethodNodeId(classFqn, method);
            AstIndex.NodeDraft existingMethodDraft = astIndex.nodeDrafts().get(nodeId);
            if (existingMethodDraft == null) {
                continue;
            }

            Set<String> mergedTags = new HashSet<>();
            if (existingMethodDraft.tags() != null) {
                mergedTags.addAll(existingMethodDraft.tags());
            }
            mergedTags.add("spring.beanFactory");
            mergedTags.add("spring.beanCandidate");

            Map<String, Object> mergedAttributes = mergeMethodAttributes(existingMethodDraft, clazz, method, beanAnnotation.get());

            AstIndex.NodeDraft updatedMethodDraft = new AstIndex.NodeDraft(
                    existingMethodDraft.id(),
                    existingMethodDraft.kind(),
                    existingMethodDraft.name(),
                    existingMethodDraft.fqcn(),
                    existingMethodDraft.signature(),
                    existingMethodDraft.ownerId(),
                    existingMethodDraft.source(),
                    existingMethodDraft.annotations(),
                    existingMethodDraft.modifiers(),
                    List.copyOf(mergedTags),
                    Map.copyOf(mergedAttributes)
            );
            astIndex.updateDraft(nodeId, updatedMethodDraft);
        }
    }

    private static Map<String, Object> mergeMethodAttributes(AstIndex.NodeDraft existingMethodDraft, ClassOrInterfaceDeclaration clazz, MethodDeclaration method, AnnotationExpr beanAnnotation) {
        Map<String, Object> mergedAttributes = new HashMap<>();
        if (existingMethodDraft.attributes() != null) {
            mergedAttributes.putAll(existingMethodDraft.attributes());
        }
        SpringBeanAnnotationAttributes springBeanAnnotationAttributes = AnnotationHelper.extractSpringBeanMethodAttributes(method, beanAnnotation, clazz);
        mergedAttributes.putAll(springBeanAnnotationAttributes.toNodeAttributes());

        return mergedAttributes;
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
