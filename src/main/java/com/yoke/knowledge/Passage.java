package com.yoke.knowledge;

/**
 * 知识库里的一个片段，也是检索的返回单位。
 *
 * @param id     片段的稳定标识（文档名加序号），多路检索融合时靠它判断是不是同一个片段
 * @param source 片段所属文档的标题，引用时展示给用户
 * @param text   片段正文
 */
public record Passage(String id, String source, String text) {
}
