package com.apiextractor.attacher;

import com.apiextractor.collector.ApiCollector;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.util.List;

public class AgentAttacher {
    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                // 显示可用 Java 进程列表
                System.out.println("Usage: java -jar api-extractor-attacher.jar <pid> [output=file.json] [timestamp=true|false] [mode=all|static|dynamic]");
                System.out.println("\nAvailable Java processes:");
                List<VirtualMachineDescriptor> vms = VirtualMachine.list();

                System.out.println("PID\tName");
                System.out.println("-----------------");
                for (VirtualMachineDescriptor desc : vms) {
                    System.out.println(desc.id() + "\t" + desc.displayName());
                }
                return;
            }

            // 解析参数
            String pid = args[0];
            StringBuilder agentArgs = new StringBuilder();

            for (int i = 1; i < args.length; i++) {
                if (i > 1) agentArgs.append(",");
                agentArgs.append(args[i]);
            }

            // 获取Agent JAR路径
            String agentPath = new File(AgentAttacher.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath()).getAbsolutePath();

            if (agentPath.endsWith("classes")) {
                agentPath = new File("target/spring-api-extractor-agent-1.0.0.jar").getAbsolutePath();
            }

            System.out.println("Attaching to JVM with PID: " + pid);
            System.out.println("Agent path: " + agentPath);
            System.out.println("Agent arguments: " + agentArgs);

            // 附加到目标JVM
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentPath, agentArgs.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[API Extractor] JVM shutting down, saving API information...");
                ApiCollector.getInstance().saveToFile();
            }));

            vm.detach();

            System.out.println("Agent attached successfully!");

        } catch (Exception e) {
            System.err.println("Failed to attach agent: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
