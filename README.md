# Spring API Extractor Agent 项目文档

## 1. 项目介绍
Spring API Extractor Agent 是一个基于 Java Agent 技术的工具，旨在自动提取 API 端点。在现代微服务架构下，API 端点通常由多个模块提供，如何动态发现 API 并自动生成 API 结构信息是一个重要的工程问题。本项目利用字节码分析及 JVM Attach 技术，在不修改应用源码的情况下，实现 API 自动化提取，并将其保存为 JSON 文件，供后续分析与管理。其核心功能包括：

- 自动扫描 Spring Boot 应用的 REST API 端点，包括 `@RequestMapping`、`@GetMapping`、`@PostMapping` 等注解。
- 无侵入式 API 发现，可在应用启动时加载或运行时动态附加。
- 支持多模块 Spring Boot 应用，能够扫描 `BOOT-INF/lib` 目录下的所有 JAR 包，从而提取多个服务模块中的 API。
- 持久化 API 数据，使用 `ApiCollector` 统一管理 API 信息，并通过 `JsonFileWriter` 将数据写入 JSON 文件，便于后续使用。

---
## 2. 部署
Spring API Extractor Agent 需要在 **Java 8 及以上** 运行环境中进行部署，同时要求 **Spring Boot 应用** 作为目标分析对象。项目代码托管在 GitHub，完整仓库地址为：

[https://github.com/jukailin/spring-api-extractor-agent.git](https://github.com/jukailin/spring-api-extractor-agent.git)

### 2.1 运行环境准备
项目的部署主要涉及 **运行环境准备**、**数据库结构**、**Nginx 配置**、**CDN/HTTPS 需求**、**计划任务配置** 及相关 **注意事项**。

首先，运行此项目需要安装 **JDK 8+ 和 Maven**，建议环境配置如下：
```sh
# 安装 JDK 和 Maven
sudo apt update
sudo apt install openjdk-11-jdk maven -y
```

### 2.2 运行方式
关于 Java Agent 的挂载方式：
1. **随 JVM 启动自动加载**（推荐）
```sh
-javaagent:/path/to/spring-api-extractor-agent-1.0.0.jar=output=/var/www/api-data/api_information.json
```
2. **运行时动态附加**
```sh
java -jar api-extractor-attacher.jar <pid> output=/var/www/api-data/api_information.json
```
其中 `<pid>` 需要替换为实际的 Java 进程 ID，通常可以通过 `jps` 获取。

### 2.3 部署注意事项
- **Agent 必须与目标进程使用相同的 JDK 版本**，否则可能出现 `UnsupportedOperationException`。
- **在 Docker 容器中运行时**，建议使用 `--pid=host` 访问宿主机的进程信息，否则无法动态 attach。
- **API 结果的存储目录** 需确保 `write` 权限，否则 JSON 文件无法写入。

---
## 3. 接口
Spring API Extractor 并不直接提供 API 接口，而是用于 **提取 Spring Boot 应用中的 API 端点**，因此接口文档描述的是 `SpringApiScanner` 的输出格式。

### 3.1 JSON 结构
扫描完成后，生成的 `api_information.json` 文件格式如下：
```json
{
  "timestamp": "2025-03-11T10:15:30Z",
  "scannedJars": 12,
  "controllers": 6,
  "apiCount": 18,
  "apis": [
    {
      "path": "/users/{id}",
      "methods": ["GET"],
      "parameters": { "id": "path" },
      "source": { "class": "com.example.UserController", "method": "getUser" }
    },
    {
      "path": "/products",
      "methods": ["POST"],
      "parameters": { "category": "query", "price": "query" },
      "source": { "class": "com.example.ProductController", "method": "addProduct" }
    }
  ]
}
```

### 3.2 异常处理
- **如果 `SpringApiScanner` 无法找到 API**，默认写入空 API 结构，避免 JSON 解析出错。
- **动态 Attach 失败**（如 PID 进程不存在），`AgentAttacher` 返回：
```sh
Failed to attach agent: no such process
```
需要用户检查目标进程是否存在，以及 Agent 版本是否与目标 JVM 兼容。

---
## 4. 设计
Spring API Extractor 采用 **Java Agent + ASM 字节码分析**，其整体工作流程如下：
1. **Agent 加载到 JVM**（premain 或 agentmain 方式）。
2. **遍历类路径中的所有类和 JAR 文件**，解析 `BOOT-INF/classes` 目录中的 Spring Boot 代码。
3. **使用 ASM 解析字节码**，识别 `@RestController` 或 `@Controller` 注解的类，提取 `@RequestMapping` 相关方法。
4. **存储 API 信息**，将 API 端点、HTTP 方法、参数类型等信息保存至 `ApiCollector`。
5. **JVM 关闭时写入 JSON**，确保数据不会丢失。

---
## 5. 故障分析
### 5.1 PID 变更导致 Attach 失败
**错误信息**：
```sh
Failed to attach agent: no such process
```
**原因分析**：
- 目标进程 PID 频繁变化，导致 Attach 失败。
- 解决方案：动态获取 PID，如 `jps | grep Application`。

### 5.2 并发写入 JSON 文件
**问题**：`ApiCollector.saveToFile()` 存在并发写入问题。

**解决方案**：加锁确保原子性。
```java
synchronized (this) {
   JsonFileWriter.writeToFile(getAllApiInfo(), outputFile);
}
```
**后续优化**：加入 **日志追踪系统** 以便快速排查问题。

---
## 6. 结论
本项目通过 Java Agent 技术，实现了 API 端点的自动化扫描、提取与存储，极大提升了 API 文档生成的便捷性和准确性。在实际应用中，该工具可以极大简化 API 端点管理和接口文档维护工作，为开发、测试、运维提供高效、准确的 API 信息提取能力。未来可以结合 OpenAPI 规范，自动生成 Swagger 文档，并进一步增强 API 访问控制与权限管理能力，使其更具实用价值。

