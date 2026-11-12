package com.yoke.knowledge.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cn.hutool.core.util.StrUtil;
import com.yoke.model.Passage;
import com.yoke.knowledge.RetrievePort;
import reactor.core.publisher.Mono;

/**
 * 进程内的关键词检索（BM25），不依赖任何外部服务，是检索链路最里层的兜底。
 *
 * <p>分词不引入中文分词库：连续的汉字按相邻两字切（「检索」切成「检索」，「知识库」切成「知识」「识库」），
 * 英文和数字按单词切并转小写。对这种几十篇文档的小语料足够用，代价是召回里会混进一些字面重叠的噪声。
 *
 * <p>语料在构造时建好索引，之后只读，线程安全。
 */
public final class KeywordRetrievePort implements RetrievePort {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Passage> passages;
    private final List<Map<String, Integer>> termFrequencies;
    /** 每个片段的词数，与 termFrequencies 一一对应；构造时就定了，不在每次查询时重新累加。 */
    private final int[] lengths;
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final double averageLength;

    public KeywordRetrievePort(List<Passage> passages) {
        this.passages = List.copyOf(passages);
        this.termFrequencies = new ArrayList<>();
        this.lengths = new int[this.passages.size()];
        long totalLength = 0;
        for (int i = 0; i < this.passages.size(); i++) {
            Passage passage = this.passages.get(i);
            Map<String, Integer> frequencies = new HashMap<>();
            List<String> tokens = tokenize(passage.source() + " " + passage.text());
            tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
            frequencies.keySet().forEach(token -> documentFrequency.merge(token, 1, Integer::sum));
            termFrequencies.add(frequencies);
            lengths[i] = tokens.size();
            totalLength += tokens.size();
        }
        this.averageLength = this.passages.isEmpty() ? 1 : Math.max(1, (double) totalLength / this.passages.size());
    }

    @Override
    public Mono<List<Passage>> retrieve(String query, int topK) {
        return Mono.fromSupplier(() -> search(query, topK));
    }

    private List<Passage> search(String query, int topK) {
        if (StrUtil.isBlank(query) || topK <= 0 || passages.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = new HashSet<>(tokenize(query));
        record Scored(Passage passage, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            double score = score(queryTerms, termFrequencies.get(i), lengths[i]);
            if (score > 0) {
                scored.add(new Scored(passages.get(i), score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::passage)
                .toList();
    }

    private double score(Set<String> queryTerms, Map<String, Integer> frequencies, int length) {
        double score = 0;
        for (String term : queryTerms) {
            Integer tf = frequencies.get(term);
            if (Objects.isNull(tf)) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (passages.size() - df + 0.5) / (df + 0.5));
            score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / averageLength));
        }
        return score;
    }

    /** 汉字按相邻两字切；单个孤立汉字也保留；字母数字按连续串切并转小写。 */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < lower.length()) {
            int codePoint = lower.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (isHan(codePoint)) {
                int end = i;
                while (end < lower.length() && isHan(lower.codePointAt(end))) {
                    end += Character.charCount(lower.codePointAt(end));
                }
                String run = lower.substring(i, end);
                int runLength = run.codePointCount(0, run.length());
                if (runLength == 1) {
                    tokens.add(run);
                } else {
                    int[] points = run.codePoints().toArray();
                    for (int j = 0; j + 1 < points.length; j++) {
                        tokens.add(new String(points, j, 2));
                    }
                }
                i = end;
            } else if (Character.isLetterOrDigit(codePoint)) {
                int end = i;
                while (end < lower.length() && Character.isLetterOrDigit(lower.codePointAt(end))
                        && !isHan(lower.codePointAt(end))) {
                    end += Character.charCount(lower.codePointAt(end));
                }
                tokens.add(lower.substring(i, end));
                i = end;
            } else {
                i += width;
            }
        }
        return tokens;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
