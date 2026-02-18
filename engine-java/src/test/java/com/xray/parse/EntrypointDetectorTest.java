package com.xray.parse;

import com.xray.model.Enums;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EntrypointDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsRestControllerWithClassAndMethodMappings() throws IOException {
        AstIndex astIndex = detect(
                """
                        @RestController
                        @RequestMapping(\"/api\")
                        class DemoController {
                            @GetMapping(\"/users\")
                            String listUsers() { return \"ok\"; }
                        }
                        """,
                "DemoController.java"
        );

        AstIndex.NodeDraft methodDraft = methodDraft(astIndex, "listUsers");

        assertNotNull(methodDraft.tags());
        assertTrue(methodDraft.tags().contains("spring.entrypoint.http"));
        assertTrue(methodDraft.tags().contains("http.GET"));

        assertEquals("HTTP", methodDraft.attributes().get("entrypoint.kind"));
        assertEquals("REST_CONTROLLER", methodDraft.attributes().get("entrypoint.controllerType"));
        assertEquals(List.of("/api/users"), listAttr(methodDraft, "entrypoint.paths"));
        assertEquals(List.of("GET"), listAttr(methodDraft, "entrypoint.httpMethods"));
    }

    @Test
    void detectsControllerRequestMappingWithExplicitMethod() throws IOException {
        AstIndex astIndex = detect(
                """
                        @Controller
                        class DemoController {
                            @RequestMapping(path = \"/submit\", method = RequestMethod.POST)
                            String submit() { return \"ok\"; }
                        }
                        """,
                "ControllerRequestMethod.java"
        );

        AstIndex.NodeDraft methodDraft = methodDraft(astIndex, "submit");
        assertTrue(methodDraft.tags().contains("spring.entrypoint.http"));
        assertTrue(methodDraft.tags().contains("http.POST"));
        assertEquals("CONTROLLER", methodDraft.attributes().get("entrypoint.controllerType"));
        assertEquals(List.of("/submit"), listAttr(methodDraft, "entrypoint.paths"));
    }

    @Test
    void detectsRequestMappingMultipleHttpMethods() throws IOException {
        AstIndex astIndex = detect(
                """
                        @Controller
                        class DemoController {
                            @RequestMapping(value = \"/items\", method = {RequestMethod.GET, RequestMethod.DELETE})
                            String items() { return \"ok\"; }
                        }
                        """,
                "ControllerMultiMethod.java"
        );

        AstIndex.NodeDraft methodDraft = methodDraft(astIndex, "items");
        assertTrue(methodDraft.tags().contains("http.GET"));
        assertTrue(methodDraft.tags().contains("http.DELETE"));
        assertEquals(List.of("GET", "DELETE"), listAttr(methodDraft, "entrypoint.httpMethods"));
    }

    @Test
    void defaultsToAnyForRequestMappingWithoutMethod() throws IOException {
        AstIndex astIndex = detect(
                """
                        @Controller
                        class DemoController {
                            @RequestMapping(\"/search\")
                            String search() { return \"ok\"; }
                        }
                        """,
                "ControllerAnyMethod.java"
        );

        AstIndex.NodeDraft methodDraft = methodDraft(astIndex, "search");
        assertTrue(methodDraft.tags().contains("http.ANY"));
        assertEquals(List.of("ANY"), listAttr(methodDraft, "entrypoint.httpMethods"));
    }

    @Test
    void detectsAllShortcutHttpAnnotations() throws IOException {
        AstIndex astIndex = detect(
                """
                        @RestController
                        class DemoController {
                            @PostMapping(\"/p\") String post() { return \"ok\"; }
                            @PutMapping(\"/u\") String put() { return \"ok\"; }
                            @DeleteMapping(\"/d\") String delete() { return \"ok\"; }
                            @PatchMapping(\"/pa\") String patch() { return \"ok\"; }
                        }
                        """,
                "ControllerShortcutMethods.java"
        );

        assertTrue(methodDraft(astIndex, "post").tags().contains("http.POST"));
        assertTrue(methodDraft(astIndex, "put").tags().contains("http.PUT"));
        assertTrue(methodDraft(astIndex, "delete").tags().contains("http.DELETE"));
        assertTrue(methodDraft(astIndex, "patch").tags().contains("http.PATCH"));
    }

    @Test
    void skipsMethodsInNonControllerClasses() throws IOException {
        AstIndex astIndex = detect(
                """
                        class NotAController {
                            @GetMapping(\"/users\")
                            String users() { return \"ok\"; }
                        }
                        """,
                "NotAController.java"
        );

        AstIndex.NodeDraft methodDraft = methodDraft(astIndex, "users");
        assertNull(methodDraft.tags());
        assertNull(methodDraft.attributes());
    }


    private AstIndex detect(String source, String fileName) throws IOException {
        AstIndex astIndex = parse(source, fileName);
        return EntrypointDetector.annotateEntrypoints(astIndex);
    }

    private AstIndex parse(String source, String fileName) throws IOException {
        Path sourceFile = tempDir.resolve(fileName);
        Files.writeString(sourceFile, source);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize());
        return parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();
    }

    private AstIndex.NodeDraft methodDraft(AstIndex astIndex, String methodName) {
        return astIndex.nodeDrafts().values().stream()
                .filter(nodeDraft -> nodeDraft.kind() == Enums.NodeKind.METHOD)
                .filter(nodeDraft -> nodeDraft.name().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method draft not found: " + methodName));
    }

    @SuppressWarnings("unchecked")
    private List<String> listAttr(AstIndex.NodeDraft methodDraft, String key) {
        Object value = methodDraft.attributes().get(key);
        assertNotNull(value, "Missing attribute: " + key);
        return (List<String>) value;
    }
}
