package com.apiextractor.model;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ApiInfo {
    private String path;
    private final Set<String> methods = new HashSet<>();
    private String controllerClass;
    private String methodName;

    // Getters and Setters

    @Override
    public String toString() {
        return "ApiInfo{" +
                "path='" + path + '\'' +
                ", methods=" + methods +
                ", controllerClass='" + controllerClass + '\'' +
                ", methodName='" + methodName + '\'' +
                '}';
    }
}
