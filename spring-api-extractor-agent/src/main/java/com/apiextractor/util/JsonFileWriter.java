package com.apiextractor.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;

public class JsonFileWriter {
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void writeToFile(Object data, String filePath) {
        try {
            File file = new File(filePath);
            objectMapper.writeValue(file, data);
            System.out.println("[API Extractor] API information saved to " + file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[API Extractor] Failed to write API info to file: " + e.getMessage());
        }
    }
}
