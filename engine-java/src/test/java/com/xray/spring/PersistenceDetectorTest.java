package com.xray.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xray.io.OutputLayout;
import com.xray.parse.AstIndex;
import com.xray.parse.JavaParserFactory;
import com.xray.parse.ParsePipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void marksSpringDataRepositoryWithPersistenceTags() throws IOException {
        Path sourceFile = tempDir.resolve("OrderRepository.java");
        Files.writeString(sourceFile, """
                interface OrderRepository extends JpaRepository<Order, Long> {}
                class Order {}
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), new ObjectMapper(), new OutputLayout(sourceFile));
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();

        PersistenceDetector.annotatePersistence(astIndex);

        AstIndex.NodeDraft repositoryDraft = astIndex.nodeDrafts().get("OrderRepository");
        assertNotNull(repositoryDraft);
        assertNotNull(repositoryDraft.tags());
        assertTrue(repositoryDraft.tags().contains("spring.data.repository"));
        assertTrue(repositoryDraft.tags().contains("persistence.repo"));
    }
}
