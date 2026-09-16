package com.rag.storage.es;

import java.io.IOException;
import java.util.function.Function;

import com.rag.config.RagProperties;
import com.rag.domain.exception.DomainException;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.ReindexRequest;
import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetMappingRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R5.1 mapping migration 定向单测：既有索引的 retrieval_content 兼容迁移。
 *
 * <ul>
 *   <li>字段不存在 → PUT mapping 补齐，analyzer = 当前 contentAnalyzer；</li>
 *   <li>字段已存在且 analyzer 一致 → 无写入；</li>
 *   <li>字段已存在且 analyzer 不一致 → fail-fast，<b>不删索引、不自动 reindex</b>
 *       （不允许任何 delete index / reindex 调用发生）。</li>
 * </ul>
 *
 * <p>ES 交互用 Mockito 桩（client.indices() 返回 mock indices client），
 * 不依赖真实 ES：迁移逻辑是纯判定分支，集成路径由 StorageIT 覆盖。</p>
 */
class EsChunkIndexMigrationTest {

    private ElasticsearchClient client;
    private ElasticsearchIndicesClient indices;

    private EsChunkIndex index;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ElasticsearchClient.class);
        indices = Mockito.mock(ElasticsearchIndicesClient.class);
        Mockito.when(client.indices()).thenReturn(indices);
        RagProperties props = new RagProperties();
        props.getModels().getEmbedding().setDimensions(8);
        props.getElasticsearch().setContentAnalyzer("ik_max_word");
        index = new EsChunkIndex(client, props);
    }

    /** ES client 的 lambda 重载消歧：固定 Builder/Request 类型的 any()。 */
    private static <B, R extends ObjectBuilder<?>> Function<B, R> fnStub() {
        return Mockito.any();
    }

    private void stubAnalyzerAvailable() throws IOException {
        when(indices.analyze(fnStub())).thenReturn(AnalyzeResponse.of(a -> a));
    }

    private void stubIndexExists(boolean exists) throws IOException {
        when(indices.exists(fnStub())).thenReturn(new BooleanResponse(exists));
    }

    private void stubMapping(Property retrievalContent) throws IOException {
        when(indices.getMapping(fnStub())).thenReturn(GetMappingResponse.of(g -> g
                .result("rag_chunks_v1", IndexMappingRecord.of(r -> r
                        .mappings(TypeMapping.of(m -> {
                            if (retrievalContent != null) {
                                m.properties("retrieval_content", retrievalContent);
                            }
                            return m;
                        }))))));
    }

    private static Property textProperty(String analyzer) {
        return Property.of(p -> p.text(TextProperty.of(t -> {
            if (analyzer != null) {
                t.analyzer(analyzer);
            }
            return t;
        })));
    }

    /** 断言：从未发生删索引 / reindex（fail-fast 的破坏面约束）。 */
    private void verifyNoDestructiveCalls() throws IOException {
        verify(indices, never()).delete(fnStub());
        Mockito.verify(client, never()).reindex(fnStub());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingFieldPutsMappingWithCurrentAnalyzer() throws IOException {
        stubIndexExists(true);
        stubAnalyzerAvailable();
        stubMapping(null); // 旧索引：无 retrieval_content 字段

        index.ensureIndex();

        // 捕获 PUT mapping 的 lambda 重载参数并在全新 Builder 上执行，校验字段与 analyzer
        org.mockito.ArgumentCaptor<Function<PutMappingRequest.Builder,
                ObjectBuilder<PutMappingRequest>>> captor =
                org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(indices).putMapping(captor.capture());
        PutMappingRequest req = captor.getValue()
                .apply(new PutMappingRequest.Builder())
                .build();
        Property p = req.properties().get("retrieval_content");
        org.assertj.core.api.Assertions.assertThat(p).isNotNull();
        org.assertj.core.api.Assertions.assertThat(p.text().analyzer()).isEqualTo("ik_max_word");
        verifyNoDestructiveCalls();
    }

    @Test
    void consistentAnalyzerIsNoOp() throws IOException {
        stubIndexExists(true);
        stubAnalyzerAvailable();
        stubMapping(textProperty("ik_max_word"));

        assertThatCode(index::ensureIndex).doesNotThrowAnyException();

        verify(indices, never()).putMapping(fnStub());
        verifyNoDestructiveCalls();
    }

    @Test
    void mismatchedAnalyzerFailsFastWithoutDeletionOrReindex() throws IOException {
        stubIndexExists(true);
        stubAnalyzerAvailable();
        // 动态 mapping 漂移场景：字段被建成 default（standard）analyzer
        stubMapping(textProperty(null));

        assertThatThrownBy(index::ensureIndex)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("retrieval_content analyzer")
                .hasMessageContaining("reindex");

        // fail-fast 语义：不 PUT 修正、不删索引、不自动 reindex
        verify(indices, never()).putMapping(fnStub());
        verifyNoDestructiveCalls();
    }

    @Test
    void nonTextFieldTypeAlsoFailsFast() throws IOException {
        stubIndexExists(true);
        stubAnalyzerAvailable();
        // 糟糕的动态 mapping：keyword 形态 → existing.text() == null
        stubMapping(Property.of(p -> p.keyword(k -> k)));

        assertThatThrownBy(index::ensureIndex)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("retrieval_content analyzer");

        verify(indices, never()).putMapping(fnStub());
        verifyNoDestructiveCalls();
    }
}
