package com.yoke.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceInitializerTests {

    @TempDir
    Path dir;

    private static YokeProperties props(Path workspace) {
        return new YokeProperties(new YokeProperties.Redis("redis://127.0.0.1:6379", "yoke:"),
                new YokeProperties.Compaction(30, 10), workspace.toString(),
                new YokeProperties.Knowledge(new YokeProperties.Knowledge.Embedding(null, null, null, null)),
                new YokeProperties.Mcp(List.of()));
    }

    @Test
    void 工作区没有AGENTS_md时创建默认文件() throws Exception {
        Path ws = dir.resolve("ws");
        new WorkspaceInitializer(props(ws)).afterPropertiesSet();

        assertThat(Files.readString(ws.resolve("AGENTS.md"))).isEqualTo(WorkspaceInitializer.DEFAULT_AGENTS_MD);
    }

    @Test
    void 已有的AGENTS_md不会被覆盖() throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "我自己的约定");
        new WorkspaceInitializer(props(dir)).afterPropertiesSet();

        assertThat(Files.readString(dir.resolve("AGENTS.md"))).isEqualTo("我自己的约定");
    }
}
