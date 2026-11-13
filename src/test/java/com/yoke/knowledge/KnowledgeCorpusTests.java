package com.yoke.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.yoke.knowledge.retrieve.KeywordRetrievePort;
import com.yoke.model.Passage;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeCorpusTests {

    @Test
    void 一级标题当文档标题_二级标题切片段_片段带上完整标题() {
        List<Passage> passages = KnowledgeCorpus.split("demo.md", "# 文档标题\n\n## 第一节\n内容一\n\n## 第二节\n内容二\n");

        assertThat(passages).hasSize(2);
        assertThat(passages.get(0).source()).isEqualTo("文档标题");
        assertThat(passages.get(0).text()).startsWith("文档标题 / 第一节\n").contains("内容一");
        assertThat(passages.get(1).text()).startsWith("文档标题 / 第二节\n").contains("内容二");
        assertThat(passages).extracting(Passage::id).doesNotHaveDuplicates();
    }

    @Test
    void 片段id跟着正文走_前面插入新小节不改变后面片段的id() {
        // id 会作为引用编号留在 Redis 的对话历史里，按位置编号的话，插一节之后旧引用就指到了别的正文上
        String before = "# 标题\n## 第一节\n内容一\n## 第二节\n内容二\n";
        String inserted = "# 标题\n## 新加的一节\n新内容\n## 第一节\n内容一\n## 第二节\n内容二\n";

        List<Passage> original = KnowledgeCorpus.split("demo.md", before);
        List<Passage> afterInsert = KnowledgeCorpus.split("demo.md", inserted);

        assertThat(original).extracting(Passage::id).allMatch(id -> id.matches("demo\\.md#[0-9a-f]{8}"));
        assertThat(afterInsert.subList(1, 3)).extracting(Passage::id)
                .containsExactlyElementsOf(original.stream().map(Passage::id).toList());
    }

    @Test
    void 正文改了片段id也跟着变() {
        String id = KnowledgeCorpus.split("demo.md", "# 标题\n## 一节\n旧内容").getFirst().id();
        String changed = KnowledgeCorpus.split("demo.md", "# 标题\n## 一节\n新内容").getFirst().id();

        assertThat(changed).isNotEqualTo(id);
    }

    @Test
    void 代码块里井号开头的行不当作标题_原样留在正文里() {
        String markdown = "# 构建\n## 命令\n先切 JDK：\n\n```bash\n# 先切 JDK\nsdk env\n## 再构建\n./gradlew build\n```\n\n## 下一节\n内容\n";

        List<Passage> passages = KnowledgeCorpus.split("build.md", markdown);

        assertThat(passages).extracting(Passage::source).containsOnly("构建");
        assertThat(passages).hasSize(2);
        assertThat(passages.getFirst().text()).startsWith("构建 / 命令\n")
                .contains("# 先切 JDK").contains("## 再构建").contains("./gradlew build");
        assertThat(passages.get(1).text()).startsWith("构建 / 下一节\n");
    }

    @Test
    void 过长的小节按段落再切() {
        String paragraph = "字".repeat(KnowledgeCorpus.MAX_CHUNK_LENGTH - 50);
        List<Passage> passages = KnowledgeCorpus.split("long.md", "# 长文\n## 一节\n" + paragraph + "\n\n" + paragraph + "\n");

        assertThat(passages).hasSize(2);
    }

    @Test
    void 没有一级标题时用文件名当标题() {
        assertThat(KnowledgeCorpus.split("x.md", "## 节\n内容").getFirst().source()).isEqualTo("x.md");
    }

    @Test
    void 空小节不产生片段() {
        assertThat(KnowledgeCorpus.split("x.md", "# 标题\n## 空节\n## 有内容\n正文")).hasSize(1);
    }

    @Test
    void 随应用发布的知识文档被完整加载() {
        List<Passage> passages = KnowledgeCorpus.loadFromClasspath("classpath*:knowledge/*.md");

        assertThat(passages).extracting(Passage::source)
                .contains("Yoke 概览", "会话与存储", "工具与权限", "接口使用");
        assertThat(passages).extracting(Passage::id).doesNotHaveDuplicates();
    }

    @Test
    void 不同classpath根里有同名文档时直接失败() {
        // classpath*: 会把依赖 jar 里的同名文件也找出来，两篇文档的片段 id 都以 api-usage.md 开头
        List<Resource> resources = List.of(
                named("api-usage.md", "# 接口使用\n## 一节\n内容一"),
                named("api-usage.md", "# 别的接口\n## 一节\n内容二"),
                named("yoke-overview.md", "# 概览\n## 一节\n内容三"));

        assertThatThrownBy(() -> KnowledgeCorpus.load(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-usage.md")
                .hasMessageNotContaining("yoke-overview.md");
    }

    private static Resource named(String fileName, String markdown) {
        return new ByteArrayResource(markdown.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    @Test
    void 没有匹配的文档时返回空列表() {
        assertThat(KnowledgeCorpus.loadFromClasspath("classpath*:no-such-dir/*.md")).isEmpty();
    }

    @Test
    void 关键词检索在真实语料上能回答关于Yoke的问题() {
        KeywordRetrievePort port = new KeywordRetrievePort(KnowledgeCorpus.loadFromClasspath("classpath*:knowledge/*.md"));

        assertThat(port.retrieve("对话历史存在哪里", 3).block()).first()
                .satisfies(passage -> assertThat(passage.text()).contains("Redis"));
        assertThat(port.retrieve("为什么不允许 stdio 传输", 3).block()).first()
                .satisfies(passage -> assertThat(passage.text()).contains("stdio"));
        assertThat(port.retrieve("流式接口有哪些事件", 3).block()).first()
                .satisfies(passage -> assertThat(passage.text()).contains("TEXT_MESSAGE_CONTENT"));
    }
}
