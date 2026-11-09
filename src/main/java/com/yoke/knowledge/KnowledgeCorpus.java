package com.yoke.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 把 {@code classpath*:knowledge/*.md} 切成片段。
 *
 * <p>切法：文档第一个一级标题（{@code # }）当作文档标题，之后每个二级标题（{@code ## }）起一个片段，
 * 片段正文超过 {@link #MAX_CHUNK_LENGTH} 个字符时按段落再切。每个片段正文前面带上「文档标题 / 小节标题」，
 * 这样单看一个片段也知道它在讲什么。
 */
public final class KnowledgeCorpus {

    static final int MAX_CHUNK_LENGTH = 500;

    private KnowledgeCorpus() {
    }

    /** 读取 classpath 下的全部知识文档并切片；没有文档时返回空列表。 */
    public static List<Passage> loadFromClasspath(String pattern) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            List<Passage> passages = new ArrayList<>();
            Arrays.stream(resources)
                    .sorted(Comparator.comparing(Resource::getFilename, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(resource -> {
                        try {
                            String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                            passages.addAll(split(resource.getFilename(), markdown));
                        } catch (IOException e) {
                            throw new UncheckedIOException("读取知识文档失败：" + resource.getFilename(), e);
                        }
                    });
            return passages;
        } catch (IOException e) {
            throw new UncheckedIOException("扫描知识文档失败：" + pattern, e);
        }
    }

    /** 把一篇 Markdown 切成片段；{@code fileName} 只在文档没有一级标题时充当标题。 */
    static List<Passage> split(String fileName, String markdown) {
        String title = Optional.ofNullable(fileName).orElse("未命名文档");
        String section = null;
        StringBuilder body = new StringBuilder();
        List<Passage> passages = new ArrayList<>();
        String docId = Optional.ofNullable(fileName).orElse("doc");

        for (String line : markdown.split("\\R")) {
            if (line.startsWith("# ")) {
                title = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                flush(passages, docId, title, section, body);
                section = line.substring(3).trim();
            } else {
                body.append(line).append('\n');
            }
        }
        flush(passages, docId, title, section, body);
        return passages;
    }

    private static void flush(List<Passage> passages, String docId, String title, String section, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String heading = Optional.ofNullable(section).map(name -> title + " / " + name).orElse(title);
        StringBuilder chunk = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            if (chunk.length() > 0 && chunk.length() + paragraph.length() > MAX_CHUNK_LENGTH) {
                passages.add(new Passage(docId + "#" + passages.size(), title, heading + "\n" + chunk.toString().strip()));
                chunk.setLength(0);
            }
            chunk.append(paragraph.strip()).append("\n\n");
        }
        if (chunk.length() > 0) {
            passages.add(new Passage(docId + "#" + passages.size(), title, heading + "\n" + chunk.toString().strip()));
        }
    }
}
