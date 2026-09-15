package com.rag.ingestion.parse;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.rag.config.RagProperties;
import com.rag.domain.enums.FileType;
import com.rag.domain.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-P2 MinerU 解析器测试：响应映射、失败语义、multipart 构造、条件注册。
 *
 * <p>HTTP 层不做 mock 框架注入——映射与失败路径通过 static 方法与未启用配置覆盖；
 * 端到端 HTTP 语义由真实验证（mineru-api 实测）覆盖，见 docs/round3/02-实施记录-P2.md。</p>
 */
class MineruParserTest {

    @org.springframework.boot.context.properties.EnableConfigurationProperties(RagProperties.class)
    static class PropsConfig {
    }

    private static RagProperties properties(String pdfParser, String baseUrl) {
        RagProperties props = new RagProperties();
        props.getIngestion().setPdfParser(pdfParser);
        props.getIngestion().setMineruBaseUrl(baseUrl);
        return props;
    }

    // ------------------------------------------------------------- 条件注册

    @Test
    void registeredOnlyWhenPdfParserIsMineru() {
        new ApplicationContextRunner()
                .withBean(RagProperties.class, () -> properties("mineru", "http://localhost:8000"))
                .withUserConfiguration(MineruParser.class)
                .withPropertyValues("rag.ingestion.pdf-parser=mineru",
                        "rag.ingestion.mineru-base-url=http://localhost:8000")
                .run(context -> assertThat(context).hasSingleBean(MineruParser.class));

        new ApplicationContextRunner()
                .withBean(RagProperties.class, () -> properties("pdfbox", ""))
                .withUserConfiguration(MineruParser.class)
                .withPropertyValues("rag.ingestion.pdf-parser=pdfbox")
                .run(context -> assertThat(context).doesNotHaveBean(MineruParser.class));

        // 缺省（未配置）不注册：pdfbox 仍是默认解析器
        new ApplicationContextRunner()
                .withUserConfiguration(MineruParser.class)
                .run(context -> assertThat(context).doesNotHaveBean(MineruParser.class));
    }

    // ------------------------------------------------------------- 产物映射

    @Test
    void mapsMdContentToParsedDocument() {
        String response = """
                {"backend":"pipeline","version":"3.4.5","results":{
                  "source":{"md_content":"# 标题甲\\n正文一段。\\n## 子标题\\n正文二段。"}}}
                """;
        ParsedDocument parsed = MineruParser.toParsedDocument(response);
        assertThat(parsed.text()).contains("# 标题甲").contains("## 子标题");
        assertThat(parsed.headings()).extracting(ParsedDocument.Heading::text)
                .containsExactly("标题甲", "子标题");
        assertThat(parsed.isPaged()).isFalse();
    }

    @Test
    void emptyResultsFail() {
        assertThatThrownBy(() -> MineruParser.toParsedDocument(
                "{\"backend\":\"pipeline\",\"results\":{}}"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> MineruParser.toParsedDocument(
                "{\"results\":{\"source\":{\"md_content\":\"   \"}}}"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> MineruParser.toParsedDocument("not-json"))
                .isInstanceOf(DomainException.class);
    }

    // ------------------------------------------------------------- 未启用语义

    @Test
    void parseWithoutBaseUrlFailsExplicitly() {
        MineruParser parser = new MineruParser(properties("mineru", ""));
        assertThatThrownBy(() -> parser.parse(
                new java.io.ByteArrayInputStream(new byte[0]), FileType.PDF))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("mineru-base-url");
        assertThat(parser.supportedType()).isEqualTo(FileType.PDF);
    }

    // ------------------------------------------------------------- multipart

    @Test
    void multipartBodyContainsRequiredFormFields() {
        HttpRequest.BodyPublisher body = MineruParser.multipartBody(
                "PDFBYTES".getBytes(StandardCharsets.UTF_8), "b-123");
        String text = new String(joinPublisher(body), StandardCharsets.UTF_8);

        assertThat(text)
                .contains("name=\"files\"; filename=\"source.pdf\"")
                .contains("Content-Type: application/pdf")
                .contains("name=\"lang_list\"\r\n\r\nch\r\n")
                .contains("name=\"backend\"\r\n\r\npipeline\r\n")
                .contains("name=\"parse_method\"\r\n\r\nauto\r\n")
                .contains("name=\"return_md\"\r\n\r\ntrue\r\n")
                .contains("--b-123--");
    }

    private static byte[] joinPublisher(HttpRequest.BodyPublisher publisher) {
        // BodyPublishers.ofByteArrays 的内容长度已知；通过订阅聚合
        List<byte[]> chunks = new java.util.ArrayList<>();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] arr = new byte[item.remaining()];
                item.get(arr);
                chunks.add(arr);
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.join();
        int total = chunks.stream().mapToInt(c -> c.length).sum();
        byte[] all = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, all, pos, chunk.length);
            pos += chunk.length;
        }
        return all;
    }
}
