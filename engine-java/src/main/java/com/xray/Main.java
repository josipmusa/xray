package com.xray;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.javaparser.JavaParser;
import com.xray.config.ArgsParser;
import com.xray.config.EngineConfig;
import com.xray.engine.Engine;
import com.xray.parse.JavaParserFactory;
import com.xray.parse.ParsePipeline;

public class Main {

    public static void main(String[] args) throws Exception {
        EngineConfig engineConfig = ArgsParser.parse(args);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JavaParser javaParser = JavaParserFactory.initialize();
        ParsePipeline parsePipeline = new ParsePipeline(javaParser);
        Engine engine = new Engine(parsePipeline, objectMapper);

        engine.analyze(engineConfig);
    }
}
