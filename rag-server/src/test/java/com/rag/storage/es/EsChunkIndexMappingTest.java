package com.rag.storage.es;

import java.util.List;
import java.util.Map;

import com.rag.config.RagProperties;

import co.elastic.clients.elasticsearch.core.search.Hit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EsChunkIndex 纯映射单元测试（无 Testcontainers）：
 * ChunkDoc+vector → _source Map、Hit<Map> → EsHit、维度/数量校验。
 * ES 真实读写路径由 {@code StorageIT} 覆盖。
 */
class EsChunkIndexMappingTest {

    private static RagProperties props(int dims) {
        RagProperties properties = new RagProperties();
        properties.getModels().getEmbedding().setDimensions(dims);
        return properties;
    }

    private final EsChunkIndex index = new EsChunkIndex(null, props(8));

    @Test
    void toSourceMapMatchesRouteMappingFieldNames() {
        ChunkDoc chunk = new ChunkDoc("doc1-c0001", "研发规范>架构", 3, 1, 10, "微服务架构设计原则");
        Map<String, Object> source = EsChunkIndex.toSourceMap(
                "doc1", "kb1", chunk, new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});

        assertThat(source)
                .containsEntry("id", "doc1-c0001")
                .containsEntry("doc_id", "doc1")
                .containsEntry("knowledge_base_id", "kb1")
                .containsEntry("title_path", "研发规范>架构")
                .containsEntry("page", 3)
                .containsEntry("seq", 1)
                .containsEntry("char_count", 10)
                .containsEntry("content", "微服务架构设计原则");
        assertThat((List<?>) source.get("vector")).hasSize(8);
    }

    @Test
    void toSourceMapOmitsNullPage() {
        ChunkDoc chunk = new ChunkDoc("doc1-c0000", "文档名", null, 0, 4, "文本");
        Map<String, Object> source = EsChunkIndex.toSourceMap(
                "doc1", "kb1", chunk, new float[8]);
        assertThat(source).doesNotContainKey("page");
    }

    @Test
    void toEsHitMapsFieldsWithDefaults() {
        Hit<Map> hit = Hit.of(h -> h.index("rag_chunks_v1")
                .id("doc1-c0001")
                .score(0.87)
                .source(Map.of("doc_id", "doc1", "title_path", "研发规范>架构",
                        "page", 3, "seq", 1, "char_count", 10, "content", "微服务架构设计原则")));
        EsHit esHit = EsChunkIndex.toEsHit(hit);

        assertThat(esHit.chunkId()).isEqualTo("doc1-c0001");
        assertThat(esHit.docId()).isEqualTo("doc1");
        assertThat(esHit.titlePath()).isEqualTo("研发规范>架构");
        assertThat(esHit.page()).isEqualTo(3);
        assertThat(esHit.seq()).isEqualTo(1);
        assertThat(esHit.charCount()).isEqualTo(10);
        assertThat(esHit.content()).isEqualTo("微服务架构设计原则");
        assertThat(esHit.score()).isEqualTo(0.87);
    }

    @Test
    void rebuildRejectsChunkVectorCountMismatch() {
        assertThatThrownBy(() -> index.rebuildChunks("doc1", "kb1",
                List.of(new ChunkDoc("doc1-c0000", "t", null, 0, 2, "ab")),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分块数与向量数不一致");
    }

    @Test
    void rebuildRejectsWrongVectorDimsBeforeAnyEsCall() {
        // 维度校验发生在任何 ES 调用之前（client 为 null 也不会 NPE）
        ChunkDoc chunk = new ChunkDoc("doc1-c0000", "t", null, 0, 2, "ab");
        assertThatThrownBy(() -> index.rebuildChunks("doc1", "kb1",
                List.of(chunk), List.of(new float[4])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("向量维度与配置不一致");
    }
}
