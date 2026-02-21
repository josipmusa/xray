package com.xray.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.xray.model.SpringBeanAnnotationAttributes;
import com.xray.parse.AstIndex;
import com.xray.parse.JavaParserFactory;
import com.xray.parse.ParsePipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BeanDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsServiceBean_mergesTags_andExtractsAttributes() throws IOException {
        AstIndex astIndex = detect(
                """
                        @Primary
                        @Qualifier("fast")
                        @Service
                        class OrderService {}
                        """,
                "OrderService.java"
        );

        AstIndex.NodeDraft classDraft = classDraft(astIndex, "OrderService");

        // tags from BeanDetector SpringComponentAnnotation.SERVICE
        assertNotNull(classDraft.tags());
        assertTrue(classDraft.tags().contains("spring.beanCandidate"));
        assertTrue(classDraft.tags().contains("spring.service"));

        // attributes: compare against AnnotationHelper output (don’t hardcode keys)
        assertAttributesMatchHelper(astIndex, "OrderService", "Service", classDraft);
    }

    @Test
    void detectsComponentWithExplicitName_andScopeProfile() throws IOException {
        AstIndex astIndex = detect(
                """
                        @Profile({"dev", "test"})
                        @Scope("prototype")
                        @Component("paymentSvc")
                        class PaymentService {}
                        """,
                "PaymentService.java"
        );

        AstIndex.NodeDraft classDraft = classDraft(astIndex, "PaymentService");

        assertNotNull(classDraft.tags());
        assertTrue(classDraft.tags().contains("spring.beanCandidate"));
        assertTrue(classDraft.tags().contains("spring.component"));

        assertAttributesMatchHelper(astIndex, "PaymentService", "Component", classDraft);
    }

    @Test
    void detectsRepositoryBean() throws IOException {
        AstIndex astIndex = detect(
                """
                        @Repository
                        class UserRepository {}
                        """,
                "UserRepository.java"
        );

        AstIndex.NodeDraft classDraft = classDraft(astIndex, "UserRepository");

        assertTrue(classDraft.tags().contains("spring.beanCandidate"));
        assertTrue(classDraft.tags().contains("spring.repository"));

        assertAttributesMatchHelper(astIndex, "UserRepository", "Repository", classDraft);
    }

    @Test
    void skipsClassWithoutStereotypeAnnotation() throws IOException {
        AstIndex astIndex = detect(
                """
                        class PlainPojo {}
                        """,
                "PlainPojo.java"
        );

        AstIndex.NodeDraft classDraft = classDraft(astIndex, "PlainPojo");

        // BeanDetector should not touch it -> no spring tags/attrs expected
        // (ParsePipeline may have already set other tags/attrs, so we only assert the spring ones are absent)
        if (classDraft.tags() != null) {
            assertFalse(classDraft.tags().contains("spring.beanCandidate"));
            assertFalse(classDraft.tags().contains("spring.service"));
            assertFalse(classDraft.tags().contains("spring.component"));
            assertFalse(classDraft.tags().contains("spring.repository"));
            assertFalse(classDraft.tags().contains("spring.configuration"));
        }

        if (classDraft.attributes() != null) {
            // We don't know your exact keys, so assert no obvious spring-bean keys if you use them.
            // If you prefer, delete these checks and keep only the tag assertions above.
            assertFalse(classDraft.attributes().containsKey("spring.beanName"));
            assertFalse(classDraft.attributes().containsKey("spring.beanName.explicit"));
        }
    }

    // ---------------- test harness (same style as your other tests) ----------------

    private AstIndex detect(String source, String fileName) throws IOException {
        AstIndex astIndex = parse(source, fileName);
        BeanDetector.annotateBeans(astIndex);
        return astIndex;
    }

    private AstIndex parse(String source, String fileName) throws IOException {
        Path sourceFile = tempDir.resolve(fileName);
        Files.writeString(sourceFile, source);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize());
        return parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();
    }

    private AstIndex.NodeDraft classDraft(AstIndex astIndex, String className) {
        return astIndex.nodeDrafts().values().stream()
                .filter(d -> d.name().equals(className))
                // avoid accidentally matching method drafts with same name
                .filter(d -> d.fqcn() != null && (d.fqcn().endsWith("." + className) || d.fqcn().equals(className)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class draft not found: " + className));
    }

    private void assertAttributesMatchHelper(
            AstIndex astIndex,
            String className,
            String stereotypeSimpleName,
            AstIndex.NodeDraft updatedDraft
    ) {
        ClassOrInterfaceDeclaration clazzDecl = classDecl(astIndex, className);
        AnnotationExpr stereotype = findRequiredAnnotation(clazzDecl, stereotypeSimpleName);

        SpringBeanAnnotationAttributes expected =
                AnnotationHelper.extractSpringBeanClassAttributes(clazzDecl, stereotype);

        Map<String, Object> expectedAttrs = expected.toNodeAttributes();

        assertNotNull(updatedDraft.attributes(), "Expected class draft to have attributes after bean detection");

        for (Map.Entry<String, Object> e : expectedAttrs.entrySet()) {
            assertTrue(updatedDraft.attributes().containsKey(e.getKey()), "Missing attr: " + e.getKey());
            assertEquals(e.getValue(), updatedDraft.attributes().get(e.getKey()), "Mismatch for attr: " + e.getKey());
        }
    }

    private ClassOrInterfaceDeclaration classDecl(AstIndex astIndex, String className) {
        return astIndex.fileToCu().values().stream()
                .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> c.getNameAsString().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class declaration not found in AST: " + className));
    }

    private AnnotationExpr findRequiredAnnotation(ClassOrInterfaceDeclaration clazz, String simpleName) {
        return clazz.getAnnotations().stream()
                .filter(a -> {
                    String n = a.getNameAsString();
                    return n.equals(simpleName) || n.endsWith("." + simpleName);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing annotation @" + simpleName + " on " + clazz.getNameAsString()));
    }
}
