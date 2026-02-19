package com.xray.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xray.model.EntrypointIndex;
import com.xray.model.Node;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public final class IndexWriter {

    private final ObjectMapper objectMapper;

    /**
     * Writes name_to_ids.json
     */
    public void writeNameToIds(OutputLayout layout, Map<String, List<String>> nameToIds) throws IOException {
        writeIndex(layout.getIndexDir().resolve("name_to_ids.json"), nameToIds);
    }

    /**
     * Writes file_to_ids.json
     */
    public void writeFileToIds(OutputLayout layout, Map<String, List<String>> fileToIds) throws IOException {
        writeIndex(layout.getIndexDir().resolve("file_to_ids.json"), fileToIds);
    }

    /**
     * Writes entrypoints.json
     */
    public void writeEntrypoints(OutputLayout layout, EntrypointIndex entrypoints) throws IOException {
        writeIndex(layout.getIndexDir().resolve("entrypoints.json"), entrypoints);
    }

    /**
     * Generic index writer (pretty-printed on purpose, these files are small)
     */
    private void writeIndex(Path file, Object index) throws IOException {
        Files.createDirectories(file.getParent());

        byte[] json = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(index);

        Files.write(
                file,
                json,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }
}
