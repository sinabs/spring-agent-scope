package com.yoke.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.yoke.knowledge.KnowledgeCorpus;
import com.yoke.model.Passage;
import com.yoke.knowledge.RetrievePort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieveToolTests {

    private static final List<Passage> HITS = List.of(
            new Passage("sessions.md#0", "会话与存储", "会话与存储 / 对话历史\n历史放在 Redis"),
            new Passage("tools.md#3", "工具与权限", "工具与权限 / 模型能用哪些工具\n有 calculate"));

    @Test
    void 工具说明要求用户明确让查知识库时必须调用_不能没查就说没有() throws NoSuchMethodException {
        String description = RetrieveTool.class.getMethod("retrieve", String.class, Integer.class)
                .getAnnotation(io.agentscope.core.tool.Tool.class).description();

        assertThat(description).contains("明确要求查知识库").contains("必须调用").contains("不能没查就说知识库里没有");
    }

    @Test
    void 给模型看的编号示例不是真实存在的片段编号() throws Exception {
        // 模型被要求原样照抄方括号里的编号，示例要是真实编号，模型可能把没检索到的片段也引出来
        String description = RetrieveTool.class.getMethod("retrieve", String.class, Integer.class)
                .getAnnotation(io.agentscope.core.tool.Tool.class).description();
        String researcher = new ClassPathResource("workspace/subagents/kb-researcher.md")
                .getContentAsString(StandardCharsets.UTF_8);
        List<Passage> corpus = KnowledgeCorpus.loadFromClasspath("classpath*:knowledge/*.md");
        List<String> modelFacing = new ArrayList<>(List.of(description, researcher));
        corpus.forEach(passage -> modelFacing.add(passage.text()));

        assertThat(description).contains("[文档名.md#片段编号]");
        for (Passage passage : corpus) {
            assertThat(modelFacing).noneMatch(text -> text.contains("[" + passage.id() + "]"));
        }
    }

    @Test
    void 结果带编号和来源_方便模型按编号引用() {
        RetrieveTool tool = new RetrieveTool((_, _) -> Mono.just(HITS));

        String result = tool.retrieve("历史", 3).block();

        assertThat(result).contains("检索到 2 条相关资料")
                .contains("[sessions.md#0] 来源：会话与存储").contains("历史放在 Redis")
                .contains("[tools.md#3] 来源：工具与权限").contains("有 calculate");
        assertThat(result.indexOf("[sessions.md#0]")).isLessThan(result.indexOf("[tools.md#3]"));
    }

    @Test
    void 多次检索的编号不冲突_同一个片段始终是同一个编号() {
        // 模型常常换个说法再查一次。每次从 [1] 开始编号的话，两次结果里的 [1] 指向不同的片段，引用就对不上
        Passage shared = new Passage("api.md#2", "接口使用", "接口使用 / 普通对话\n发送 JSON");
        Passage onlyFirst = new Passage("mcp.md#1", "工具与权限", "工具与权限 / 接入 MCP\n只允许 HTTP");
        Passage onlySecond = new Passage("redis.md#0", "会话与存储", "会话与存储 / Redis\n历史放在 Redis");
        RetrieveTool tool = answering(List.of(List.of(onlyFirst, shared), List.of(onlySecond, shared)));

        String first = tool.retrieve("q1", 3).block();
        String second = tool.retrieve("q2", 3).block();

        assertThat(first).contains("[mcp.md#1] 来源：").contains("[api.md#2] 来源：");
        assertThat(second).contains("[redis.md#0] 来源：").contains("[api.md#2] 来源：");
        // 不再出现每次都从 1 开始的序号
        assertThat(first).doesNotContain("[1]").doesNotContain("[2]");
        assertThat(second).doesNotContain("[1]").doesNotContain("[2]");
        // 同一个编号在两次结果里对应同一段正文
        assertThat(first).contains("[api.md#2] 来源：接口使用\n接口使用 / 普通对话");
        assertThat(second).contains("[api.md#2] 来源：接口使用\n接口使用 / 普通对话");
        // 不同片段的编号互不相同：两次结果一共四条资料，只有共享的那条编号重复
        List<String> labels = Pattern.compile("(?m)^\\[([^]]+)] 来源：").matcher(first + "\n\n" + second)
                .results().map(match -> match.group(1)).toList();
        assertThat(labels).containsExactly("mcp.md#1", "api.md#2", "redis.md#0", "api.md#2");
        assertThat(Set.copyOf(labels)).hasSize(3);
    }

    /** 第 n 次检索返回 answers 里的第 n 组结果。 */
    private static RetrieveTool answering(List<List<Passage>> answers) {
        AtomicInteger call = new AtomicInteger();
        return new RetrieveTool((_, _) -> Mono.just(answers.get(call.getAndIncrement())));
    }

    @Test
    void topK不传用默认值_传大了被钳制到上限_传小了至少为1() {
        List<Integer> requested = new ArrayList<>();
        RetrieveTool tool = new RetrieveTool((_, topK) -> {
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
        RetrieveTool tool = new RetrieveTool((query, _) -> {
            queries.add(query);
            return Mono.just(List.of());
        });

        tool.retrieve("问".repeat(5000), 3).block();

        assertThat(queries.getFirst()).hasSize(RetrieveTool.MAX_QUERY_LENGTH);
    }

    @Test
    void 没有命中时明确告诉模型不要编造() {
        RetrieveTool tool = new RetrieveTool((_, _) -> Mono.just(List.of()));

        assertThat(tool.retrieve("量子纠缠", 3).block()).contains("没有检索到").contains("不要编造");
    }

    @Test
    void 未配置检索端口时返回提示语而不是抛异常() {
        RetrieveTool tool = new RetrieveTool(null);

        assertThat(tool.retrieve("任何问题", 3).block()).contains("知识库尚未配置");
    }

    @Test
    void 空问题返回提示而不触发检索() {
        RetrievePort unreachable = (_, _) -> {
            throw new AssertionError("不应该被调用");
        };
        RetrieveTool tool = new RetrieveTool(unreachable);

        assertThat(tool.retrieve("  ", 3).block()).contains("query 不能为空");
        assertThat(tool.retrieve(null, 3).block()).contains("query 不能为空");
    }

    @Test
    void 检索失败时只返回通用提示_不泄漏上游细节() {
        RetrieveTool tool = new RetrieveTool((_, _) -> Mono.error(new IllegalStateException("连接 10.0.0.8:443 被拒绝")));

        String result = tool.retrieve("q", 3).block();

        assertThat(result).contains("暂时不可用").doesNotContain("10.0.0.8");
    }
}
