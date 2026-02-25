package com.xray.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.xray.io.OutputLayout;
import com.xray.model.SpringBeanAnnotationAttributes;
import com.xray.parse.AstIndex;
import com.xray.parse.JavaParserFactory;
import com.xray.engine.NodeIdGenerator;
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
        AstIndex astIndex = detect("""
                @Primary
                @Qualifier("fast")
                @Service
                class OrderService {}
                """, "OrderService.java");

        assertClassBeanMatchesHelper(astIndex, "OrderService", "Service", "spring.service");
    }

    @Test
    void detectsComponentWithExplicitName_andScopeProfile() throws IOException {
        AstIndex astIndex = detect("""
                @Profile({"dev", "test"})
                @Scope("prototype")
                @Component("paymentSvc")
                class PaymentService {}
                """, "PaymentService.java");

        assertClassBeanMatchesHelper(astIndex, "PaymentService", "Component", "spring.component");
    }

    @Test
    void detectsRepositoryBean() throws IOException {
        AstIndex astIndex = detect("""
                @Repository
                class UserRepository {}
                """, "UserRepository.java");

        assertClassBeanMatchesHelper(astIndex, "UserRepository", "Repository", "spring.repository");
    }

    @Test
    void detectsBeanMethodInConfigurationClass() throws IOException {
        AstIndex astIndex = detect("""
                @Configuration
                class AppConfig {
                    @Primary
                    @Qualifier("fast")
                    @Scope("prototype")
                    @Profile("dev")
                    @Bean(name = "ordersClient")
                    String client() { return "ok"; }
                }
                """, "AppConfig.java");

        assertClassBeanMatchesHelper(astIndex, "AppConfig", "Configuration", "spring.configuration");
        assertMethodBeanMatchesHelper(astIndex, "AppConfig", "client");
    }

    @Test
    void doesNotDetectBeanMethodOutsideConfigurationClass() throws IOException {
        AstIndex astIndex = detect("""
                @Component
                class AppComponent {
                    @Bean
                    String helper() { return "ok"; }
                }
                """, "AppComponent.java");

        AstIndex.NodeDraft methodDraft = methodDraft(astIndex, "AppComponent", "helper");
        assertSpringTagsAbsent(methodDraft);
        assertSpringBeanAttributesAbsent(methodDraft);
    }

    @Test
    void skipsClassWithoutStereotypeAnnotation() throws IOException {
        AstIndex astIndex = detect("""
                class PlainPojo {}
                """, "PlainPojo.java");

        AstIndex.NodeDraft classDraft = classDraft(astIndex, "PlainPojo");

        assertSpringTagsAbsent(classDraft);
        assertSpringBeanAttributesAbsent(classDraft);
    }


    private AstIndex detect(String source, String fileName) throws IOException {
        AstIndex astIndex = parse(source, fileName);
        BeanDetector.annotateBeans(astIndex);
        return astIndex;
    }

    private AstIndex parse(String source, String fileName) throws IOException {
        Path sourceFile = tempDir.resolve(fileName);
        Files.writeString(sourceFile, source);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), new ObjectMapper(), new OutputLayout(sourceFile));
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

    private AstIndex.NodeDraft methodDraft(AstIndex astIndex, String className, String methodName) {
        ClassOrInterfaceDeclaration clazzDecl = classDecl(astIndex, className);
        MethodDeclaration methodDecl = methodDecl(clazzDecl, methodName);
        String classFqn = clazzDecl.getFullyQualifiedName().orElse(className);
        String nodeId = NodeIdGenerator.generateMethodNodeId(classFqn, methodDecl);

        AstIndex.NodeDraft methodDraft = astIndex.nodeDrafts().get(nodeId);
        assertNotNull(methodDraft, "Method draft not found: " + nodeId);
        return methodDraft;
    }

    private void assertClassBeanMatchesHelper(AstIndex astIndex, String className, String stereotypeSimpleName, String expectedTag) {
        AstIndex.NodeDraft draft = classDraft(astIndex, className);
        assertHasTags(draft, "spring.beanCandidate", expectedTag);
        assertClassAttributesMatchHelper(astIndex, className, stereotypeSimpleName, draft);
    }

    private void assertMethodBeanMatchesHelper(AstIndex astIndex, String className, String methodName) {
        ClassOrInterfaceDeclaration clazzDecl = classDecl(astIndex, className);
        MethodDeclaration methodDecl = methodDecl(clazzDecl, methodName);
        AnnotationExpr beanAnnotation = findRequiredAnnotation(methodDecl, "Bean");
        AstIndex.NodeDraft draft = methodDraft(astIndex, className, methodName);

        assertHasTags(draft, "spring.beanFactory", "spring.beanCandidate");
        assertMethodAttributesMatchHelper(clazzDecl, methodDecl, beanAnnotation, draft);
    }

    private void assertClassAttributesMatchHelper(
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

    private void assertMethodAttributesMatchHelper(
            ClassOrInterfaceDeclaration clazzDecl,
            MethodDeclaration methodDecl,
            AnnotationExpr beanAnnotation,
            AstIndex.NodeDraft updatedDraft
    ) {
        SpringBeanAnnotationAttributes expected =
                AnnotationHelper.extractSpringBeanMethodAttributes(methodDecl, beanAnnotation, clazzDecl);

        assertAttributesContain(updatedDraft, expected.toNodeAttributes());
    }

    private void assertAttributesContain(AstIndex.NodeDraft updatedDraft, Map<String, Object> expectedAttrs) {
        assertNotNull(updatedDraft.attributes(), "Expected draft to have attributes after bean detection");
        for (Map.Entry<String, Object> e : expectedAttrs.entrySet()) {
            assertTrue(updatedDraft.attributes().containsKey(e.getKey()), "Missing attr: " + e.getKey());
            assertEquals(e.getValue(), updatedDraft.attributes().get(e.getKey()), "Mismatch for attr: " + e.getKey());
        }
    }

    private void assertHasTags(AstIndex.NodeDraft draft, String... expectedTags) {
        assertNotNull(draft.tags(), "Expected draft to have tags");
        for (String expectedTag : expectedTags) {
            assertTrue(draft.tags().contains(expectedTag), "Missing tag: " + expectedTag);
        }
    }

    private void assertSpringTagsAbsent(AstIndex.NodeDraft draft) {
        if (draft.tags() == null) {
            return;
        }
        assertFalse(draft.tags().contains("spring.beanCandidate"));
        assertFalse(draft.tags().contains("spring.beanFactory"));
        assertFalse(draft.tags().contains("spring.service"));
        assertFalse(draft.tags().contains("spring.component"));
        assertFalse(draft.tags().contains("spring.repository"));
        assertFalse(draft.tags().contains("spring.configuration"));
    }

    private void assertSpringBeanAttributesAbsent(AstIndex.NodeDraft draft) {
        if (draft.attributes() == null) {
            return;
        }
        assertFalse(draft.attributes().containsKey("spring.source"));
        assertFalse(draft.attributes().containsKey("spring.declaredType"));
        assertFalse(draft.attributes().containsKey("spring.beanName"));
        assertFalse(draft.attributes().containsKey("spring.owner"));
        assertFalse(draft.attributes().containsKey("spring.primary"));
        assertFalse(draft.attributes().containsKey("spring.qualifier"));
        assertFalse(draft.attributes().containsKey("spring.scope"));
        assertFalse(draft.attributes().containsKey("spring.profile"));
        assertFalse(draft.attributes().containsKey("spring.conditional"));
    }

    private ClassOrInterfaceDeclaration classDecl(AstIndex astIndex, String className) {
        return astIndex.fileToCu().values().stream()
                .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> c.getNameAsString().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class declaration not found in AST: " + className));
    }

    private MethodDeclaration methodDecl(ClassOrInterfaceDeclaration clazz, String methodName) {
        return clazz.getMethodsByName(methodName).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method declaration not found in AST: " + methodName));
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

    private AnnotationExpr findRequiredAnnotation(MethodDeclaration method, String simpleName) {
        return method.getAnnotations().stream()
                .filter(a -> {
                    String n = a.getNameAsString();
                    return n.equals(simpleName) || n.endsWith("." + simpleName);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing annotation @" + simpleName + " on method " + method.getNameAsString()));
    }
}
