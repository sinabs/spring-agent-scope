package com.yoke.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 启动时保证工作区里有 AGENTS.md，没有就写一份默认的，已经存在的绝不覆盖。
 *
 * <p>AgentScope 的 WorkspaceManager 每次请求都会找工作区根目录下的 AGENTS.md（人设和本地约定），
 * 找不到就打 WARN。这里只放最小内容，真正的人设仍然来自 agentscope.agent.sys-prompt，
 * 要改约定直接编辑这个文件，不需要重启代码。
 */
@Slf4j
@Component
public class WorkspaceInitializer implements InitializingBean {

    static final String DEFAULT_AGENTS_MD = """
            # AGENTS.md

            这个文件定义 Agent 的人设和本地约定，由 Yoke 在工作区为空时自动创建，可以直接修改。

            - 用简洁清晰的中文回答。
            - 不确定的事情直接说不确定，不要编造。
            """;

    private final Path workspace;

    public WorkspaceInitializer(YokeProperties yoke) {
        this.workspace = Path.of(yoke.workspace());
    }

    @Override
    public void afterPropertiesSet() {
        Path agentsMd = workspace.resolve("AGENTS.md");
        try {
            Files.createDirectories(workspace);
            if (Files.notExists(agentsMd)) {
                Files.writeString(agentsMd, DEFAULT_AGENTS_MD, StandardCharsets.UTF_8);
                log.info("工作区缺少 AGENTS.md，已创建默认文件 {}", agentsMd);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化工作区 " + workspace, e);
        }
    }
}
