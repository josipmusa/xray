package com.xray.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xray.config.EngineConfig;
import com.xray.io.OutputLayout;
import com.xray.model.*;
import com.xray.parse.*;
import com.xray.phase.EdgePhase;
import com.xray.phase.NodePhase;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

@Slf4j
public final class Engine {

    private final ParsePipeline parsePipeline;
    private final NodePhase nodePhase;
    private final EdgePhase edgePhase;
    private final ObjectMapper objectMapper;
    private final OutputLayout outputLayout;

    public Engine(ParsePipeline parsePipeline, NodePhase nodePhase, EdgePhase edgePhase, ObjectMapper objectMapper, OutputLayout outputLayout) {
        this.parsePipeline = parsePipeline;
        this.nodePhase = nodePhase;
        this.edgePhase = edgePhase;
        this.objectMapper = objectMapper;
        this.outputLayout = outputLayout;
    }

    public void analyze(EngineConfig engineConfig) throws IOException {
        log.info("Scanning repo: {}", engineConfig.repoRoot().toAbsolutePath());

        try (Stream<Path> files = RepoScanner.findJavaFiles(engineConfig)) {
            ParsePipelineResult parsePipelineResult = parsePipeline.parseAll(files);
            long nodesWritten = nodePhase.processNodes(parsePipelineResult.astIndex());
            edgePhase.processEdges(parsePipelineResult.astIndex());

            writeMeta(parsePipelineResult, nodesWritten, engineConfig);
        }
    }

    private void writeMeta(ParsePipelineResult parsePipelineResult, long nodesWritten, EngineConfig engineConfig) throws IOException {
        Meta.Stats stats = new Meta.Stats(
                parsePipelineResult.javaFilesFound(),
                parsePipelineResult.filesParsedOk(),
                parsePipelineResult.filesParsedFailed(),
                nodesWritten
        );
        Meta meta = new Meta(
                "0.0.1",
                SchemaVersion.V1,
                Instant.now(),
                engineConfig.repoRoot().toAbsolutePath().normalize().toString(),
                engineConfig.options(),
                stats
        );

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputLayout.getMeta().toFile(), meta);
    }
}
