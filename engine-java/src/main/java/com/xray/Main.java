package com.xray;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.javaparser.JavaParser;
import com.xray.config.ArgsParser;
import com.xray.config.EngineConfig;
import com.xray.engine.Engine;
import com.xray.io.OutputLayout;
import com.xray.parse.JavaParserFactory;
import com.xray.parse.ParsePipeline;
import com.xray.phase.edge.EdgePhase;
import com.xray.phase.NodePhase;

public final class Main {

    public static void main(String[] args) throws Exception {
        EngineConfig engineConfig = ArgsParser.parse(args);
        OutputLayout outputLayout = new OutputLayout(engineConfig.outputDir());
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JavaParser javaParser = JavaParserFactory.initialize(engineConfig.repoRoot());
        ParsePipeline parsePipeline = new ParsePipeline(javaParser, objectMapper, outputLayout);
        NodePhase nodePhase = new NodePhase(objectMapper, outputLayout);
        EdgePhase edgePhase = new EdgePhase(objectMapper, outputLayout);

        Engine engine = new Engine(parsePipeline, nodePhase, edgePhase, objectMapper, outputLayout);

        engine.analyze(engineConfig);
    }
}
