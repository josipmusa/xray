package com.xray.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xray.config.EngineConfig;
import com.xray.io.IndexWriter;
import com.xray.io.JsonlWriter;
import com.xray.io.OutputLayout;
import com.xray.model.Meta;
import com.xray.model.ParsePipelineResult;
import com.xray.model.ParseProblem;
import com.xray.model.SchemaVersion;
import com.xray.parse.AstIndex;
import com.xray.parse.NodeBuilder;
import com.xray.parse.ParsePipeline;
import com.xray.parse.RepoScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Slf4j
public final class Engine {

    private final ParsePipeline parsePipeline;
    private final ObjectMapper objectMapper;

    public void analyze(EngineConfig engineConfig) throws IOException {
        OutputLayout outputLayout = new OutputLayout(engineConfig.outputDir());

        log.info("Scanning repo: {}", engineConfig.repoRoot().toAbsolutePath());

        try (Stream<Path> files = RepoScanner.findJavaFiles(engineConfig)) {
            ParsePipelineResult parsePipelineResult = parsePipeline.parseAll(files);
            AstIndex astIndex = parsePipelineResult.astIndex();

            long nodesWritten = writeNodes(astIndex, outputLayout);

            writeProblems(parsePipelineResult.parseProblems(), outputLayout);
            writeMeta(parsePipelineResult, nodesWritten, engineConfig, outputLayout);
        }
    }

    private void writeProblems(List<ParseProblem> parseProblems, OutputLayout outputLayout) throws IOException {
        if (parseProblems.isEmpty()) return;
        try (JsonlWriter writer = new JsonlWriter(outputLayout.getParseProblems(), objectMapper)) {
            for (ParseProblem parseProblem : parseProblems) {
                writer.writeObject(parseProblem);
            }
        }
    }

    private long writeNodes(AstIndex astIndex, OutputLayout outputLayout) throws IOException {
        Map<String, List<String>> nameToIds = new HashMap<>();
        Map<String, List<String>> fileToIds = new HashMap<>();
        try (JsonlWriter nodeWriter = new JsonlWriter(outputLayout.getNodes(), objectMapper)) {
            long nodesWritten = NodeBuilder.buildNodes(astIndex)
                    .map(node -> {
                        try {
                            nodeWriter.writeObject(node);
                            nameToIds.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(node.id());
                            nameToIds.computeIfAbsent(node.name().toLowerCase(), k -> new ArrayList<>()).add(node.id());
                            nameToIds.computeIfAbsent(node.fqcn(), k -> new ArrayList<>()).add(node.id());
                            fileToIds.computeIfAbsent(node.source().file(), k -> new ArrayList<>()).add(node.id());
                            return true;
                        } catch (IOException e) {
                            log.error("Error writing node, skipping", e);
                            return false;
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();

            IndexWriter indexWriter = new IndexWriter(objectMapper);
            indexWriter.writeNameToIds(outputLayout, nameToIds);
            indexWriter.writeFileToIds(outputLayout, fileToIds);

            return nodesWritten;
        }
    }

    private void writeMeta(ParsePipelineResult parsePipelineResult, long nodesWritten, EngineConfig engineConfig, OutputLayout outputLayout) throws IOException {
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
