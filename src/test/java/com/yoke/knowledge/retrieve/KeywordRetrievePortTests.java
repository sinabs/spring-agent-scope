package com.yoke.knowledge.retrieve;

import java.util.List;

import com.yoke.model.Passage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRetrievePortTests {

    private static final Passage REDIS = new Passage("a#0", "会话与存储", "对话历史放在 Redis 里，重启之后仍然在。");
    private static final Passage TOOLS = new Passage("b#0", "工具与权限", "calculate 工具做四则运算，支持括号和小数。");
    private static final Passage VIRTUAL = new Passage("c#0", "概览", "应用使用虚拟线程处理请求。");

    private final KeywordRetrievePort port = new KeywordRetrievePort(List.of(REDIS, TOOLS, VIRTUAL));

    @Test
    void 中文问题能命中字面相关的片段() {
        assertThat(port.retrieve("对话历史存在哪里", 3).block()).first().isEqualTo(REDIS);
        assertThat(port.retrieve("四则运算怎么算", 3).block()).first().isEqualTo(TOOLS);
    }

    @Test
    void 英文和数字按单词匹配且不区分大小写() {
        assertThat(port.retrieve("REDIS", 3).block()).containsExactly(REDIS);
        assertThat(port.retrieve("calculate", 3).block()).containsExactly(TOOLS);
    }

    @Test
    void 没有任何字面重叠时返回空列表() {
        assertThat(port.retrieve("量子纠缠", 3).block()).isEmpty();
    }

    @Test
    void 结果数量不超过topK() {
        assertThat(port.retrieve("的 工具 对话 虚拟 线程 Redis 括号", 2).block()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void 空问题_非正topK和空语料都返回空列表() {
        assertThat(port.retrieve("  ", 3).block()).isEmpty();
        assertThat(port.retrieve(null, 3).block()).isEmpty();
        assertThat(port.retrieve("Redis", 0).block()).isEmpty();
        assertThat(new KeywordRetrievePort(List.of()).retrieve("Redis", 3).block()).isEmpty();
    }

    @Test
    void 分词把连续汉字按相邻两字切并保留英文单词() {
        assertThat(KeywordRetrievePort.tokenize("知识库 Redis 检索")).containsExactly("知识", "识库", "redis", "检索");
        assertThat(KeywordRetrievePort.tokenize("库")).containsExactly("库");
    }
}
