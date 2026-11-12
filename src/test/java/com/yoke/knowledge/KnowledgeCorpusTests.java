package com.yoke.knowledge;

import java.util.List;

import com.yoke.knowledge.retrieve.KeywordRetrievePort;
import com.yoke.model.Passage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
