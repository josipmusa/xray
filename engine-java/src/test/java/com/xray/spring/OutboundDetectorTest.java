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

class OutboundDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void marksFeignClientWithOutboundTagsAndAttributes() throws IOException {
        Path sourceFile = tempDir.resolve("PaymentsClient.java");
        Files.writeString(sourceFile, """
                @FeignClient(name = "payments-service", url = "${payments.url}")
                interface PaymentsClient {}
                """);

        ParsePipeline parsePipeline = new ParsePipeline(JavaParserFactory.initialize(tempDir), new ObjectMapper(), new OutputLayout(sourceFile));
        AstIndex astIndex = parsePipeline.parseAll(Stream.of(sourceFile)).astIndex();

        OutboundDetector.annotateOutbound(astIndex);

        AstIndex.NodeDraft clientDraft = astIndex.nodeDrafts().get("PaymentsClient");
        assertNotNull(clientDraft);
        assertNotNull(clientDraft.tags());
        assertTrue(clientDraft.tags().contains("spring.feign.client"));
        assertTrue(clientDraft.tags().contains("outbound.client"));
        assertNotNull(clientDraft.attributes());
        assertTrue(clientDraft.attributes().containsKey("outbound.clientName"));
        assertTrue(clientDraft.attributes().containsKey("outbound.url"));
    }
}
