package com.xray.io;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JsonlWriter implements AutoCloseable {

    private final BufferedWriter writer;
    private final ObjectMapper objectMapper;

    public JsonlWriter(Path file, ObjectMapper objectMapper) throws IOException {
        this.writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        this.objectMapper = objectMapper;
    }

    public void writeObject(Object object) throws IOException {
        // one JSON object per line
        writer.write(objectMapper.writeValueAsString(object));
        writer.newLine();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
