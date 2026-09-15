package com.rag.ingestion.chunk;

import java.util.List;

import com.rag.domain.enums.ChunkStrategy;
import com.rag.domain.exception.DomainException;
import com.rag.ingestion.parse.ParsedDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分块器单元测试：计划 Task 3 规定的边界（空文本、overlap≥maxLength、
 * 重叠区一致、标题路径、超长截断、PDF 页码归属、chunkId 确定性）。
 */
class ChunkerTest {

    private static final String DOC_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DOC_NAME = "运维手册.md";

    private final LengthOverlapChunker lengthOverlap = new LengthOverlapChunker();
    private final StructureChunker structure = new StructureChunker();

    // ------------------------------------------------------------------
    // LengthOverlapChunker
    // ------------------------------------------------------------------

    @Test
    void emptyTextYieldsZeroChunks() {
        List<ChunkDraft> chunks = lengthOverlap.chunk(
                ParsedDocument.plain("", List.of()), cfg(100, 20), DOC_ID);
        assertThat(chunks).isEmpty();
    }

    @Test
    void overlapGreaterOrEqualMaxLengthRejected() {
        assertThatThrownBy(() -> cfg(100, 100))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cfg(100, 120))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void slidingWindowSplitsAndKeepsOverlap() {
        String text = "a".repeat(250);
        List<ChunkDraft> chunks = lengthOverlap.chunk(
                ParsedDocument.plain(text, List.of()), cfg(100, 20), DOC_ID);

        assertThat(chunks).hasSize(3); // 步长 80：0-99 / 80-179 / 160-249
        // 相邻块重叠区内容一致（后块开头 overlap 字符 == 前块末尾 overlap 字符）
        assertThat(chunks.get(1).text().substring(0, 20))
                .isEqualTo(chunks.get(0).text().substring(80));
        assertThat(chunks.get(2).text().substring(0, 20))
                .isEqualTo(chunks.get(1).text().substring(80));
        // 首块从原文开头开始、末块覆盖到原文结尾
        assertThat(chunks.get(0).text()).startsWith("a");
        assertThat(chunks.get(2).text()).hasSize(90);
    }

    @Test
    void titlePathFallsBackToDocumentName() {
        List<ChunkDraft> chunks = lengthOverlap.chunk(
                ParsedDocument.plain("x".repeat(50), List.of()).withDocumentName(DOC_NAME),
                cfg(100, 10), DOC_ID);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).titlePath()).isEqualTo(DOC_NAME);
        assertThat(chunks.get(0).page()).isNull();
    }

    // ------------------------------------------------------------------
    // StructureChunker
    // ------------------------------------------------------------------

    @Test
    void structureChunkerBuildsTitlePathsFromHeadings() {
        String md = "# 总览\n" + "p".repeat(30) + "\n"
                + "## 部署\n" + "d".repeat(30);
        List<ChunkDraft> chunks = structure.chunk(
                ParsedDocument.plain(md, List.of()).withDocumentName(DOC_NAME), cfg(1000, 0), DOC_ID);

        assertThat(chunks).hasSize(2);
        // 精确断言（原先只断言 contains，故编号重复缺陷未被发现）
        assertThat(chunks.get(0).titlePath()).isEqualTo(DOC_NAME + " > 1. 总览");
        assertThat(chunks.get(1).titlePath()).isEqualTo(DOC_NAME + " > 1.1. 部署");
    }

    /**
     * R2-T1：原文标题自带编号时，不得再叠加自动编号产生「1.6. 6. …」这类重复。
     * 语料为运维/中间件官方文档的中文 Markdown（标题普遍手写编号）。
     */
    @Test
    void structureChunkerDoesNotDuplicateNumberInHeading() {
        String md = "# Redis 持久化\n" + "a".repeat(30) + "\n"
                + "## 1. 持久化方式概览\n" + "b".repeat(30) + "\n"
                + "## 6. RDB 与 AOF 的相互作用\n" + "c".repeat(30);
        List<ChunkDraft> chunks = structure.chunk(
                ParsedDocument.plain(md, List.of()).withDocumentName("redis-persistence.md"),
                cfg(1000, 0), DOC_ID);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).titlePath()).isEqualTo("redis-persistence.md > 1. Redis 持久化");
        // 原「1.2. 1. 持久化方式概览」→ 修复后「1.2. 持久化方式概览」
        assertThat(chunks.get(1).titlePath()).isEqualTo("redis-persistence.md > 1.1. 持久化方式概览");
        assertThat(chunks.get(2).titlePath()).isEqualTo("redis-persistence.md > 1.2. RDB 与 AOF 的相互作用");
        // 全量不得出现「数字. 数字.」重复编号
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.titlePath()).doesNotMatch(".*\\d\\.\\s+\\d+\\.\\s.*"));
    }

    @Test
    void structureChunkerTruncatesOverlongSection() {
        String longSection = "# 长节\n" + "x".repeat(500);
        List<ChunkDraft> chunks = structure.chunk(
                ParsedDocument.plain(longSection, List.of()).withDocumentName(DOC_NAME), cfg(200, 0), DOC_ID);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(3); // 500 字符按 200 上限切成 ≥3 块
        assertThat(chunks).allSatisfy(c -> assertThat(c.charCount()).isLessThanOrEqualTo(200));
    }

    @Test
    void structureChunkerKeepsPdfPageNumbers() {
        // 3 页文本（\f 分隔）：第 1 页 120 字符（单行），标题在第 2 页
        ParsedDocument doc = ParsedDocument.paged(List.of(
                "a".repeat(120), "# 配置\n" + "b".repeat(100), "c".repeat(30)));
        List<ChunkDraft> chunks = structure.chunk(doc.withDocumentName(DOC_NAME), cfg(1000, 0), DOC_ID);

        // 含标题的块应落在第 2 页（第 1 页只有无标题正文，会形成独立块）
        assertThat(chunks).extracting(ChunkDraft::page).contains((Integer) 2);
    }

    @Test
    void emptyPersistedTextYieldsZeroChunks() {
        assertThat(structure.chunk(ParsedDocument.plain("", List.of()).withDocumentName(DOC_NAME), cfg(200, 0), DOC_ID))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // chunkId 确定性
    // ------------------------------------------------------------------

    @Test
    void chunkIdsAreDeterministicAcrossRuns() {
        String text = "# 标题\n" + "y".repeat(300);
        List<ChunkDraft> first = structure.chunk(
                ParsedDocument.plain(text, List.of()).withDocumentName(DOC_NAME), cfg(100, 10), DOC_ID);
        List<ChunkDraft> second = structure.chunk(
                ParsedDocument.plain(text, List.of()).withDocumentName(DOC_NAME), cfg(100, 10), DOC_ID);

        assertThat(first).extracting(ChunkDraft::chunkId)
                .isEqualTo(second.stream().map(ChunkDraft::chunkId).toList());
        assertThat(first).extracting(ChunkDraft::chunkId)
                .startsWith(DOC_ID + "-c0000");
    }

    // ------------------------------------------------------------------

    private static ChunkingConfig cfg(int maxLength, int overlap) {
        return new ChunkingConfig(ChunkStrategy.STRUCTURE, maxLength, overlap);
    }
}
