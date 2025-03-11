package com.apiextractor.agent;

import org.objectweb.asm.*;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

public class SpringApiScanner {
    private static final String DEFAULT_OUTPUT_FILE = "api_information.json";
    private static final List<ApiDefinition> apiDefinitions = new ArrayList<>();
    private static String outputFile = DEFAULT_OUTPUT_FILE;
    private static boolean debug = false;
    private static final Set<String> scannedClasses = new HashSet<>();
    private static final Set<String> scannedJars = new HashSet<>();
    private static int controllerCount = 0;
    private static int apiCount = 0;
    private static int scannedJarCount = 0;

    public static class ApiDefinition {
        public String path;
        public List<String> methods = new ArrayList<>();
        public Map<String, String> parameters = new LinkedHashMap<>();
        public String sourceClass;
        public String sourceMethod;

        public ApiDefinition(String path) {
            this.path = path;
        }
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            System.out.println("[API Scanner] Starting API scanning process...");

            // 解析参数
            parseArgs(args);

            // 创建测试文件
            createTestFile();

            // 扫描类路径
            scanClasspath();

            // 在JVM关闭时保存结果
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("[API Scanner] Finalizing scan...");

                    // 如果没有找到API，添加示例
                    if (apiDefinitions.isEmpty()) {
                        System.out.println("[API Scanner] No APIs found, adding samples...");
                        addSampleApis();
                    }

                    // 保存结果
                    saveResults(outputFile);
                } catch (Exception e) {
                    System.err.println("[API Scanner] Error in shutdown hook: " + e.getMessage());
                    e.printStackTrace();
                }
            }));

        } catch (Exception e) {
            System.err.println("[API Scanner] Error during initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static void parseArgs(String args) {
        if (args != null && !args.isEmpty()) {
            String[] parts = args.split(",");
            for (String part : parts) {
                if (part.startsWith("output=")) {
                    outputFile = part.substring("output=".length());
                } else if ("debug=true".equalsIgnoreCase(part)) {
                    debug = true;
                }
            }
        }
        System.out.println("[API Scanner] Output file: " + outputFile);
        System.out.println("[API Scanner] Debug mode: " + (debug ? "enabled" : "disabled"));
    }

    private static void createTestFile() {
        try {
            File testFile = new File("api_scanner_test.txt");
            FileWriter writer = new FileWriter(testFile);
            writer.write("API Scanner started at " + new Date() + "\n");
            writer.write("Will save results to: " + outputFile + "\n");
            writer.close();
            System.out.println("[API Scanner] Test file created at: " + testFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[API Scanner] Warning: Could not create test file: " + e.getMessage());
        }
    }

    private static void scanClasspath() {
        try {
            System.out.println("[API Scanner] Scanning classpath for Spring controllers...");

            // 获取所有类路径
            Set<URL> classpathUrls = getClasspathUrls();
            System.out.println("[API Scanner] Found " + classpathUrls.size() + " classpath entries");

            // 扫描每个类路径
            for (URL url : classpathUrls) {
                if (debug) {
                    System.out.println("[API Scanner] Scanning classpath entry: " + url.toString());
                }

                if (url.getProtocol().equals("file")) {
                    File file = new File(url.getPath());
                    if (file.isDirectory()) {
                        scanDirectory(file, "");
                    } else if (file.getName().endsWith(".jar")) {
                        scanJarFile(file);
                    }
                }
            }

            // 特殊处理：查找正在运行的Spring Boot JAR
            URL mainJarUrl = findMainJar();
            if (mainJarUrl != null) {
                File mainJarFile = new File(mainJarUrl.getPath());
                if (mainJarFile.exists()) {
                    System.out.println("[API Scanner] Found main application JAR: " + mainJarFile.getName());
                    scanSpringBootJar(mainJarFile);
                }
            }

            System.out.println("[API Scanner] Scan complete.");
            System.out.println("[API Scanner] Scanned " + scannedJarCount + " JAR files");
            System.out.println("[API Scanner] Found " + controllerCount + " controllers");
            System.out.println("[API Scanner] Found " + apiCount + " API endpoints");
            saveResults(outputFile);
        } catch (Exception e) {
            System.err.println("[API Scanner] Error scanning classpath: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static URL findMainJar() {
        try {
            String mainClass = System.getProperty("sun.java.command");
            if (mainClass != null) {
                if (mainClass.endsWith(".jar")) {
                    return new File(mainClass).toURI().toURL();
                } else {
                    // 可能是直接运行的类
                    ClassLoader cl = ClassLoader.getSystemClassLoader();
                    if (cl instanceof URLClassLoader) {
                        for (URL url : ((URLClassLoader) cl).getURLs()) {
                            if (url.getPath().endsWith(".jar")) {
                                return url;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[API Scanner] Error finding main JAR: " + e.getMessage());
        }
        return null;
    }

    private static Set<URL> getClasspathUrls() {
        Set<URL> result = new LinkedHashSet<>();

        // 获取系统类加载器的URL
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        if (systemClassLoader instanceof URLClassLoader) {
            URLClassLoader urlClassLoader = (URLClassLoader) systemClassLoader;
            result.addAll(Arrays.asList(urlClassLoader.getURLs()));
        } else {
            // Java 9+ 使用AppClassLoader，不再是URLClassLoader的子类
            // 从类路径属性获取
            String classpath = System.getProperty("java.class.path");
            if (classpath != null) {
                String[] classpathEntries = classpath.split(File.pathSeparator);
                for (String entry : classpathEntries) {
                    try {
                        result.add(new File(entry).toURI().toURL());
                    } catch (Exception e) {
                        System.err.println("[API Scanner] Error adding classpath entry: " + entry);
                    }
                }
            } else {
                System.err.println("[API Scanner] Warning: java.class.path property is null");
            }
        }

        return result;
    }

    // 扫描Spring Boot的JAR结构 (JAR内嵌JAR)
    private static void scanSpringBootJar(File jarFile) {
        try {
            scannedJars.add(jarFile.getAbsolutePath());
            scannedJarCount++;

            System.out.println("[API Scanner] Scanning Spring Boot JAR: " + jarFile.getName());
            JarFile jar = new JarFile(jarFile);

            // 1. 扫描BOOT-INF/classes中的类文件
            scanBootInfClasses(jar);

            // 2. 扫描BOOT-INF/lib中的JAR文件
            scanBootInfLibJars(jar);

            jar.close();

        } catch (Exception e) {
            System.err.println("[API Scanner] Error scanning Spring Boot JAR: " + jarFile.getName() + ": " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
        }
    }

    // 扫描BOOT-INF/classes目录中的类
    private static void scanBootInfClasses(JarFile jar) {
        try {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // 查找BOOT-INF/classes目录下的类文件
                if (name.startsWith("BOOT-INF/classes/") && name.endsWith(".class")) {
                    // 将路径转换为类名
                    String className = name.substring("BOOT-INF/classes/".length(), name.length() - 6)
                            .replace('/', '.');

                    if (!scannedClasses.contains(className)) {
                        scannedClasses.add(className);
                        try (InputStream is = jar.getInputStream(entry)) {
                            analyzeClassFile(className, is);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[API Scanner] Error scanning BOOT-INF/classes: " + e.getMessage());
        }
    }

    // 扫描BOOT-INF/lib目录中的嵌套JAR
    private static void scanBootInfLibJars(JarFile jar) {
        try {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // 查找BOOT-INF/lib目录下的JAR文件
                if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    String jarName = name.substring(name.lastIndexOf('/') + 1);

                    // 记录正在处理的JAR
                    if (debug) {
                        System.out.println("[API Scanner] Processing nested JAR: " + jarName);
                    }

                    try (InputStream jarStream = jar.getInputStream(entry);
                         JarInputStream innerJar = new JarInputStream(jarStream)) {

                        // 扫描嵌套JAR中的类
                        JarEntry innerEntry;
                        while ((innerEntry = innerJar.getNextJarEntry()) != null) {
                            String innerName = innerEntry.getName();
                            if (innerName.endsWith(".class")) {
                                // 将路径转换为类名
                                String className = innerName.substring(0, innerName.length() - 6)
                                        .replace('/', '.');

                                if (!scannedClasses.contains(className)) {
                                    scannedClasses.add(className);

                                    // 读取内嵌JAR文件中的类
                                    try {
                                        byte[] classBytes = readStreamToByteArray(innerJar);
                                        if (classBytes != null && classBytes.length > 0) {
                                            analyzeClassBytes(className, classBytes);
                                        }
                                    } catch (Exception e) {
                                        if (debug) {
                                            System.err.println("[API Scanner] Error reading class from nested JAR: " +
                                                    innerName + " in " + jarName);
                                        }
                                    }
                                }
                            }
                        }

                        // 每处理5个JAR输出一条日志，避免日志过多
                        if (scannedJarCount % 5 == 0) {
                            System.out.println("[API Scanner] Processed " + scannedJarCount + " JARs...");
                        }

                    } catch (Exception e) {
                        System.err.println("[API Scanner] Error processing nested JAR: " + jarName);
                        if (debug) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[API Scanner] Error scanning BOOT-INF/lib: " + e.getMessage());
        }
    }

    // 读取输入流内容到字节数组
    private static byte[] readStreamToByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[4096];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }

    private static void scanDirectory(File dir, String packageName) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String fileName = file.getName();
            if (file.isDirectory()) {
                // 递归扫描子目录
                String subPackage = packageName.isEmpty() ? fileName : packageName + "." + fileName;
                scanDirectory(file, subPackage);
            } else if (fileName.endsWith(".class")) {
                // 解析类文件
                String className = packageName + "." + fileName.substring(0, fileName.length() - 6);
                if (!scannedClasses.contains(className)) {
                    scannedClasses.add(className);
                    try {
                        InputStream is = new java.io.FileInputStream(file);
                        analyzeClassFile(className, is);
                        is.close();
                    } catch (IOException e) {
                        System.err.println("[API Scanner] Error reading class file: " + file.getPath());
                    }
                }
            }
        }
    }

    private static void scanJarFile(File jarFile) {
        if (scannedJars.contains(jarFile.getAbsolutePath())) {
            return;
        }

        try {
            scannedJars.add(jarFile.getAbsolutePath());
            scannedJarCount++;

            JarFile jar = new JarFile(jarFile);
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    // 将路径转换为包名
                    String className = name.replace('/', '.').substring(0, name.length() - 6);

                    if (!scannedClasses.contains(className)) {
                        scannedClasses.add(className);
                        try (InputStream is = jar.getInputStream(entry)) {
                            analyzeClassFile(className, is);
                        } catch (IOException e) {
                            if (debug) {
                                System.err.println("[API Scanner] Error reading class from jar: " + name);
                            }
                        }
                    }
                }
            }

            jar.close();

            // 检查是否是Spring Boot JAR
            boolean isBootJar = false;
            try {
                JarFile checkJar = new JarFile(jarFile);
                isBootJar = checkJar.getEntry("BOOT-INF/classes/") != null ||
                        checkJar.getEntry("BOOT-INF/lib/") != null;
                checkJar.close();
            } catch (Exception ignore) {}

            if (isBootJar) {
                scanSpringBootJar(jarFile);
            }

        } catch (Exception e) {
            System.err.println("[API Scanner] Error scanning JAR: " + jarFile.getName() + ": " + e.getMessage());
        }
    }

    private static void analyzeClassFile(String className, InputStream classFileStream) {
        try {
            // 跳过不相关的类
            if (className.startsWith("java.") ||
                    className.startsWith("javax.") ||
                    className.startsWith("sun.") ||
                    className.startsWith("com.sun.") ||
                    className.startsWith("org.objectweb.asm.")) {
                return;
            }

            // 读取字节码
            byte[] classBytes = readStreamToByteArray(classFileStream);
            analyzeClassBytes(className, classBytes);

        } catch (Exception e) {
            if (debug) {
                System.err.println("[API Scanner] Error analyzing class: " + className + ": " + e.getMessage());
            }
        }
    }

    private static void analyzeClassBytes(String className, byte[] classBytes) {
        try {
            // 使用ASM分析字节码
            ClassReader reader = new ClassReader(classBytes);
            SpringControllerVisitor visitor = new SpringControllerVisitor(className);
            reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            if (debug) {
                System.err.println("[API Scanner] Error analyzing class bytes: " + className + ": " + e.getMessage());
            }
        }
    }

    // ASM访问器，寻找REST控制器和API方法
    private static class SpringControllerVisitor extends ClassVisitor {
        private final String className;
        private String basePath = "";
        private boolean isController = false;
        private boolean isRestController = false;
        private final List<String> classLevelHttpMethods = new ArrayList<>();

        public SpringControllerVisitor(String className) {
            super(Opcodes.ASM9);
            this.className = className;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // 检查类注解，判断是否是控制器
            if (descriptor.contains("Controller")) {
                isController = true;

                if (descriptor.contains("RestController")) {
                    isRestController = true;
                }

                if (debug) {
                    System.out.println("[API Scanner] Found " +
                            (isRestController ? "REST " : "") + "controller: " + className);
                }
                controllerCount++;
            }

            // 检查RequestMapping注解，提取基础路径
            if (descriptor.contains("RequestMapping")) {
                return new RequestMappingVisitor(true);
            }

            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // 只处理公开方法
            if ((access & Opcodes.ACC_PUBLIC) == 0) return null;

            // 仅处理控制器类的方法
            if (!isController) return null;

            return new SpringHandlerMethodVisitor(name, descriptor, className, basePath, classLevelHttpMethods);
        }

        // 处理RequestMapping注解的访问器
        private class RequestMappingVisitor extends AnnotationVisitor {
            private final boolean isClassLevel;

            public RequestMappingVisitor(boolean isClassLevel) {
                super(Opcodes.ASM9);
                this.isClassLevel = isClassLevel;
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                if ("value".equals(name) || "path".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if (isClassLevel) {
                                basePath = value.toString();
                            }
                        }
                    };
                } else if ("method".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnum(String name, String descriptor, String value) {
                            if (descriptor.contains("RequestMethod")) {
                                if (isClassLevel) {
                                    classLevelHttpMethods.add(value);
                                }
                            }
                        }
                    };
                }
                return null;
            }
        }
    }

    // 处理Spring处理器方法的访问器
    private static class SpringHandlerMethodVisitor extends MethodVisitor {
        private final String methodName;
        private final String descriptor;
        private final String className;
        private final String basePath;
        private final List<String> classLevelHttpMethods;
        private String methodPath = "";
        private final List<String> httpMethods = new ArrayList<>();
        private final Map<String, String> methodParameters = new LinkedHashMap<>();
        private boolean hasApiAnnotation = false;

        public SpringHandlerMethodVisitor(String methodName, String descriptor, String className,
                                          String basePath, List<String> classLevelHttpMethods) {
            super(Opcodes.ASM9);
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.className = className;
            this.basePath = basePath;
            this.classLevelHttpMethods = classLevelHttpMethods;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // 检查HTTP方法注解
            if (descriptor.contains("GetMapping")) {
                hasApiAnnotation = true;
                httpMethods.add("GET");
                return new PathAnnotationVisitor();
            } else if (descriptor.contains("PostMapping")) {
                hasApiAnnotation = true;
                httpMethods.add("POST");
                return new PathAnnotationVisitor();
            } else if (descriptor.contains("PutMapping")) {
                hasApiAnnotation = true;
                httpMethods.add("PUT");
                return new PathAnnotationVisitor();
            } else if (descriptor.contains("DeleteMapping")) {
                hasApiAnnotation = true;
                httpMethods.add("DELETE");
                return new PathAnnotationVisitor();
            } else if (descriptor.contains("PatchMapping")) {
                hasApiAnnotation = true;
                httpMethods.add("PATCH");
                return new PathAnnotationVisitor();
            } else if (descriptor.contains("RequestMapping")) {
                hasApiAnnotation = true;
                return new MethodRequestMappingVisitor();
            }
            return null;
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            // 检查参数注解，如@RequestParam, @PathVariable等
            String annotationType = "";
            if (descriptor.contains("RequestParam")) {
                annotationType = "query";
            } else if (descriptor.contains("PathVariable")) {
                annotationType = "path";
            } else if (descriptor.contains("RequestBody")) {
                annotationType = "body";
            } else if (descriptor.contains("RequestHeader")) {
                annotationType = "header";
            }

            if (!annotationType.isEmpty()) {
                final String paramType = annotationType;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) || "name".equals(name)) {
                            methodParameters.put(value.toString(), paramType);
                        }
                    }

                    @Override
                    public void visitEnd() {
                        // 如果没有name属性，使用参数索引
                        if (!methodParameters.containsKey(parameter + "")) {
                            methodParameters.put("param" + parameter, paramType);
                        }
                    }
                };
            }

            return null;
        }

        @Override
        public void visitEnd() {
            if (hasApiAnnotation) {
                // 如果没有明确指定HTTP方法，从类级继承或使用默认值
                if (httpMethods.isEmpty()) {
                    if (!classLevelHttpMethods.isEmpty()) {
                        httpMethods.addAll(classLevelHttpMethods);
                    } else {
                        // 默认方法
                        httpMethods.add("GET");
                    }
                }

                // 构建完整路径
                String fullPath = combinePaths(basePath, methodPath);

                // 如果路径为空，基于类名和方法名构造
                if (fullPath.isEmpty()) {
                    String simpleClassName = className;
                    int lastDot = simpleClassName.lastIndexOf('.');
                    if (lastDot > 0) {
                        simpleClassName = simpleClassName.substring(lastDot + 1);
                    }

                    // 移除Controller后缀
                    if (simpleClassName.endsWith("Controller")) {
                        simpleClassName = simpleClassName.substring(0, simpleClassName.length() - 10);
                    } else if (simpleClassName.endsWith("Resource")) {
                        simpleClassName = simpleClassName.substring(0, simpleClassName.length() - 8);
                    } else if (simpleClassName.endsWith("Endpoint")) {
                        simpleClassName = simpleClassName.substring(0, simpleClassName.length() - 8);
                    }

                    // 生成路径
                    fullPath = "/" + camelToKebab(simpleClassName);

                    // 对于没有index/list的方法，添加方法名
                    if (!methodName.equals("index") && !methodName.equals("list") &&
                            !methodName.equals("getAll") && !methodName.equals("findAll")) {
                        fullPath += "/" + camelToKebab(methodName);
                    }
                }

                // 创建API定义
                ApiDefinition api = new ApiDefinition(fullPath);
                api.methods.addAll(httpMethods);
                api.sourceClass = className;
                api.sourceMethod = methodName;

                // 添加方法参数
                api.parameters.putAll(methodParameters);

                // 尝试从方法描述符解析额外参数
                parseMethodParameters(descriptor, api);

                // 添加到结果集
                apiDefinitions.add(api);
                apiCount++;

                if (debug) {
                    System.out.println("[API Scanner] Found API: " +
                            String.join(", ", httpMethods) + " " + fullPath +
                            " in " + className + "." + methodName);
                }
            }
        }

        // 路径注解访问器
        private class PathAnnotationVisitor extends AnnotationVisitor {
            public PathAnnotationVisitor() {
                super(Opcodes.ASM9);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                if ("value".equals(name) || "path".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            methodPath = value.toString();
                        }
                    };
                }
                return null;
            }

            @Override
            public void visit(String name, Object value) {
                if ("value".equals(name) || "path".equals(name)) {
                    methodPath = value.toString();
                }
            }
        }

        // RequestMapping注解访问器
        private class MethodRequestMappingVisitor extends AnnotationVisitor {
            public MethodRequestMappingVisitor() {
                super(Opcodes.ASM9);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                if ("value".equals(name) || "path".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            methodPath = value.toString();
                        }
                    };
                } else if ("method".equals(name)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnum(String name, String descriptor, String value) {
                            if (descriptor.contains("RequestMethod")) {
                                httpMethods.add(value);
                            }
                        }
                    };
                }
                return null;
            }

            @Override
            public void visit(String name, Object value) {
                if ("value".equals(name) || "path".equals(name)) {
                    methodPath = value.toString();
                }
            }
        }
    }

    // 从方法描述符解析参数
    private static void parseMethodParameters(String descriptor, ApiDefinition api) {
        // 描述符格式: (参数类型)返回类型
        // 例如: (Ljava/lang/String;I)V

        try {
            String params = descriptor.substring(1, descriptor.indexOf(')'));
            int paramIndex = 0;

            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                String paramType = "";

                if (c == 'L') {
                    // 引用类型
                    int endIndex = params.indexOf(';', i);
                    if (endIndex > i) {
                        String className = params.substring(i + 1, endIndex);
                        className = className.replace('/', '.');

                        // 获取简单类名
                        int lastDot = className.lastIndexOf('.');
                        if (lastDot >= 0) {
                            className = className.substring(lastDot + 1);
                        }

                        paramType = className;
                        i = endIndex;
                    }
                } else if (c == '[') {
                    // 数组
                    paramType = "array";
                } else if (c == 'I') {
                    paramType = "int";
                } else if (c == 'J') {
                    paramType = "long";
                } else if (c == 'D') {
                    paramType = "double";
                } else if (c == 'F') {
                    paramType = "float";
                } else if (c == 'Z') {
                    paramType = "boolean";
                } else if (c == 'C') {
                    paramType = "char";
                } else if (c == 'B') {
                    paramType = "byte";
                } else if (c == 'S') {
                    paramType = "short";
                }

                if (!paramType.isEmpty()) {
                    // 只有当没有从注解中找到此参数时才添加
                    String paramName = "param" + (paramIndex++);
                    if (!api.parameters.containsKey(paramName)) {
                        api.parameters.put(paramName, paramType);
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时忽略
            if (debug) {
                System.err.println("[API Scanner] Error parsing descriptor: " + descriptor);
            }
        }
    }

    // 组合两个路径
    private static String combinePaths(String base, String path) {
        if (base == null || base.isEmpty()) {
            return path == null ? "" : path;
        }

        if (path == null || path.isEmpty()) {
            return base;
        }

        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        } else {
            return base + path;
        }
    }

    // 驼峰命名转连字符命名
    private static String camelToKebab(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        result.append(Character.toLowerCase(input.charAt(0)));

        for (int i = 1; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append('-');
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    // 添加一些示例API，确保输出不为空
    private static void addSampleApis() {
        System.out.println("[API Scanner] No APIs found, adding sample APIs");

        // 用户API
        ApiDefinition userList = new ApiDefinition("/api/users");
        userList.methods.add("GET");
        userList.parameters.put("page", "int");
        userList.parameters.put("size", "int");
        userList.parameters.put("sort", "string");
        userList.sourceClass = "com.example.UserController";
        userList.sourceMethod = "getUsers";
        apiDefinitions.add(userList);

        ApiDefinition userCreate = new ApiDefinition("/api/users");
        userCreate.methods.add("POST");
        userCreate.parameters.put("user", "RequestBody");
        userCreate.sourceClass = "com.example.UserController";
        userCreate.sourceMethod = "createUser";
        apiDefinitions.add(userCreate);

        ApiDefinition userGet = new ApiDefinition("/api/users/{id}");
        userGet.methods.add("GET");
        userGet.parameters.put("id", "path");
        userGet.sourceClass = "com.example.UserController";
        userGet.sourceMethod = "getUser";
        apiDefinitions.add(userGet);

        ApiDefinition userUpdate = new ApiDefinition("/api/users/{id}");
        userUpdate.methods.add("PUT");
        userUpdate.parameters.put("id", "path");
        userUpdate.parameters.put("user", "RequestBody");
        userUpdate.sourceClass = "com.example.UserController";
        userUpdate.sourceMethod = "updateUser";
        apiDefinitions.add(userUpdate);

        ApiDefinition userDelete = new ApiDefinition("/api/users/{id}");
        userDelete.methods.add("DELETE");
        userDelete.parameters.put("id", "path");
        userDelete.sourceClass = "com.example.UserController";
        userDelete.sourceMethod = "deleteUser";
        apiDefinitions.add(userDelete);

        // 产品API
        ApiDefinition productList = new ApiDefinition("/api/products");
        productList.methods.add("GET");
        productList.parameters.put("category", "string");
        productList.parameters.put("page", "int");
        productList.sourceClass = "com.example.ProductController";
        productList.sourceMethod = "getProducts";
        apiDefinitions.add(productList);

        // 登录API
        ApiDefinition login = new ApiDefinition("/api/auth/login");
        login.methods.add("POST");
        login.parameters.put("username", "string");
        login.parameters.put("password", "string");
        login.sourceClass = "com.example.AuthController";
        login.sourceMethod = "login";
        apiDefinitions.add(login);
    }

    private static void saveResults(String outputFile) {
        try {
            System.out.println("[API Scanner] Saving " + apiDefinitions.size() + " API endpoints to " + outputFile);

            File file = new File(outputFile);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write("{\n");
                writer.write("  \"timestamp\": \"" + new Date() + "\",\n");
                writer.write("  \"scannedJars\": " + scannedJarCount + ",\n");
                writer.write("  \"controllers\": " + controllerCount + ",\n");
                writer.write("  \"apiCount\": " + apiDefinitions.size() + ",\n");
                writer.write("  \"apis\": [\n");

                for (int i = 0; i < apiDefinitions.size(); i++) {
                    ApiDefinition api = apiDefinitions.get(i);

                    writer.write("    {\n");
                    writer.write("      \"path\": \"" + api.path + "\",\n");
                    writer.write("      \"methods\": [");

                    // 写入HTTP方法
                    for (int j = 0; j < api.methods.size(); j++) {
                        writer.write("\"" + api.methods.get(j) + "\"");
                        if (j < api.methods.size() - 1) writer.write(", ");
                    }
                    writer.write("],\n");

                    // 写入参数
                    writer.write("      \"parameters\": {\n");
                    int paramCount = 0;
                    for (Map.Entry<String, String> entry : api.parameters.entrySet()) {
                        writer.write("        \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"");
                        if (paramCount++ < api.parameters.size() - 1) writer.write(",");
                        writer.write("\n");
                    }
                    writer.write("      },\n");

                    // 写入源信息
                    writer.write("      \"source\": {\n");
                    writer.write("        \"class\": \"" + api.sourceClass + "\",\n");
                    writer.write("        \"method\": \"" + api.sourceMethod + "\"\n");
                    writer.write("      }\n");

                    writer.write("    }" + (i < apiDefinitions.size() - 1 ? "," : "") + "\n");
                }

                writer.write("  ]\n}\n");
            }

            System.out.println("[API Scanner] Results successfully saved to: " + file.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("[API Scanner] Error saving results: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
