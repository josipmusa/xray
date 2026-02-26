package com.xray.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.xray.engine.NodeIdGenerator;
import com.xray.parse.AstIndex;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PersistenceDetector {

    private static final Set<String> SPRING_DATA_REPOSITORY_BASE_TYPES = Set.of(
            "JpaRepository",
            "CrudRepository",
            "PagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository"
    );

    private PersistenceDetector() {}

    public static void annotatePersistence(AstIndex astIndex) {
        for (Map.Entry<Path, CompilationUnit> pathToCompilationUnit : astIndex.fileToCu().entrySet()) {
            CompilationUnit compilationUnit = pathToCompilationUnit.getValue();
            for (ClassOrInterfaceDeclaration clazz : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!isSpringDataRepository(clazz)) continue;
                updateRepositoryTags(astIndex, clazz);
            }
        }
    }

    private static boolean isSpringDataRepository(ClassOrInterfaceDeclaration clazz) {
        for (ClassOrInterfaceType type : clazz.getExtendedTypes()) {
            if (isSpringDataRepositoryType(type.getNameWithScope())) {
                return true;
            }
        }
        for (ClassOrInterfaceType type : clazz.getImplementedTypes()) {
            if (isSpringDataRepositoryType(type.getNameWithScope())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringDataRepositoryType(String typeName) {
        if (SPRING_DATA_REPOSITORY_BASE_TYPES.contains(typeName)) {
            return true;
        }
        return SPRING_DATA_REPOSITORY_BASE_TYPES.stream().anyMatch(base -> typeName.endsWith("." + base));
    }

    private static void updateRepositoryTags(AstIndex astIndex, ClassOrInterfaceDeclaration clazz) {
        String nodeId = NodeIdGenerator.generateClassNodeId(clazz);
        AstIndex.NodeDraft existingClassDraft = astIndex.nodeDrafts().get(nodeId);
        if (existingClassDraft == null) return;

        Set<String> mergedTags = new LinkedHashSet<>();
        if (existingClassDraft.tags() != null) {
            mergedTags.addAll(existingClassDraft.tags());
        }
        mergedTags.add("spring.data.repository");
        mergedTags.add("persistence.repo");

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
                existingClassDraft.attributes() == null ? null : java.util.Map.copyOf(existingClassDraft.attributes())
        );
        astIndex.updateDraft(nodeId, updatedClassDraft);
    }
}
