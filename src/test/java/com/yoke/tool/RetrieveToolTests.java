package com.yoke.tool;

import java.util.ArrayList;
import java.util.List;

import com.yoke.knowledge.Passage;
import com.yoke.knowledge.RetrievePort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieveToolTests {

    private static final List<Passage> HITS = List.of(
            new Passage("a", "会话与存储", "会话与存储 / 对话历史\n历史放在 Redis"),
            new Passage("b", "工具与权限", "工具与权限 / 模型能用哪些工具\n有 calculate"));

    @Test
    void 结果带编号和来源_方便模型按编号引用() {
        RetrieveTool tool = new RetrieveTool((query, topK) -> Mono.just(HITS));

        String result = tool.retrieve("历史", 3).block();

        assertThat(result).contains("检索到 2 条相关资料")
                .contains("[1] 来源：会话与存储").contains("历史放在 Redis")
                .contains("[2] 来源：工具与权限").contains("有 calculate");
        assertThat(result.indexOf("[1]")).isLessThan(result.indexOf("[2]"));
    }

    @Test
    void topK不传用默认值_传大了被钳制到上限_传小了至少为1() {
        List<Integer> requested = new ArrayList<>();
        RetrieveTool tool = new RetrieveTool((query, topK) -> {
            requested.add(topK);
            return Mono.just(List.of());
        });

        tool.retrieve("q", null).block();
        tool.retrieve("q", 1000).block();
        tool.retrieve("q", Integer.MAX_VALUE).block();
        tool.retrieve("q", 0).block();
        tool.retrieve("q", -5).block();
        tool.retrieve("q", 5).block();

        assertThat(requested).containsExactly(RetrieveTool.DEFAULT_TOP_K, RetrieveTool.MAX_TOP_K, RetrieveTool.MAX_TOP_K, 1, 1, 5);
    }

    @Test
    void 过长的问题被截断后再检索() {
        List<String> queries = new ArrayList<>();
        RetrieveTool tool = new RetrieveTool((query, topK) -> {
            queries.add(query);
            return Mono.just(List.of());
        });

        tool.retrieve("问".repeat(5000), 3).block();

        assertThat(queries.getFirst()).hasSize(RetrieveTool.MAX_QUERY_LENGTH);
    }

    @Test
    void 没有命中时明确告诉模型不要编造() {
        RetrieveTool tool = new RetrieveTool((query, topK) -> Mono.just(List.of()));

        assertThat(tool.retrieve("量子纠缠", 3).block()).contains("没有检索到").contains("不要编造");
    }

    @Test
    void 未配置检索端口时返回提示语而不是抛异常() {
        RetrieveTool tool = new RetrieveTool(null);

        assertThat(tool.retrieve("任何问题", 3).block()).contains("知识库尚未配置");
    }

    @Test
    void 空问题返回提示而不触发检索() {
        RetrievePort unreachable = (query, topK) -> {
            throw new AssertionError("不应该被调用");
        };
        RetrieveTool tool = new RetrieveTool(unreachable);

        assertThat(tool.retrieve("  ", 3).block()).contains("query 不能为空");
        assertThat(tool.retrieve(null, 3).block()).contains("query 不能为空");
    }

    @Test
    void 检索失败时只返回通用提示_不泄漏上游细节() {
        RetrieveTool tool = new RetrieveTool((query, topK) -> Mono.error(new IllegalStateException("连接 10.0.0.8:443 被拒绝")));

        String result = tool.retrieve("q", 3).block();

        assertThat(result).contains("暂时不可用").doesNotContain("10.0.0.8");
    }
}
