package com.apiextractor.collector;

import com.apiextractor.model.ApiInfo;
import com.apiextractor.util.JsonFileWriter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiCollector {
    private static final ApiCollector INSTANCE = new ApiCollector();
    private final Map<String, ApiInfo> apiInfoMap = new ConcurrentHashMap<>();
    private String outputFile = "api_info.json";

    private ApiCollector() {
    }

    public static ApiCollector getInstance() {
        return INSTANCE;
    }

    public List<ApiInfo> getAllApiInfo() {
        return new ArrayList<>(apiInfoMap.values());
    }

    public void saveToFile() {
        JsonFileWriter.writeToFile(getAllApiInfo(), outputFile);
    }
}
