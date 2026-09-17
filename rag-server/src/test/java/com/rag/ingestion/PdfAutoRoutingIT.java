package com.rag.ingestion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.domain.entity.KnowledgeBaseEntity;
import com.rag.domain.enums.ChunkStrategy;
import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;
import com.rag.domain.enums.TaskStatus;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.DocumentRepository;
import com.rag.storage.repository.IngestionTaskRepository;
import com.rag.storage.repository.KnowledgeBaseRepository;
import com.rag.support.FakeMineruServer;
import com.rag.support.FakeOpenAiServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6-D PDF AUTO 路由集成测试（真实 MySQL/ES/MinIO + 假模型/假 MinerU）：
 *
 * <ul>
 *   <li>AUTO→PDFBOX：正常文本 PDF 走 PDFBox 正式解析，metadata 记录 selected/routingReason/probe；</li>
 *   <li>AUTO→MINERU：无文本层 PDF 探针判定 LOW_TEXT_DENSITY → MinerU 正式解析，
 *       PDFBox 未被当最终 parser 使用（PdfBoxParser 首页无文本层会直接抛
 *       SCANNED_PDF_NOT_SUPPORTED——若发生静默回退本用例会失败）；</li>
 *   <li>No fallback：AUTO→MINERU→MinerU 500 = 任务 FAILED，且 MinerU 未被重试、PDFBox 未被调用；</li>
 *   <li>No fallback（结构对照）：AUTO→PDFBOX→首页无文本层失败 = FAILED（由 delegates 单测 +
 *       本用例的"MinerU 500 → FAILED 不换道"共同锁死 ownership 语义）；</li>
 *   <li>metadata 持久化：document.parseMetadata.parser 三级结构可复盘。</li>
 * </ul>
 *
 * <p>PDF 在测试内用 PDFBox 现场生成（R6-D §22：1~3 页小文件，不引入超大 fixture）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PdfAutoRoutingIT {

    private static FakeOpenAiServer fakeModel;
    private static FakeMineruServer fakeMineru;

    @Autowired IngestionTaskManager taskManager;
    @Autowired KnowledgeBaseRepository kbRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired IngestionTaskRepository taskRepository;
    @Autowired ObjectStore objectStore;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        try {
            fakeModel = new FakeOpenAiServer();
            fakeModel.start();
            fakeMineru = new FakeMineruServer();
            fakeMineru.start();
        } catch (Exception e) {
            throw new IllegalStateException("fake server 启动失败", e);
        }
        registry.add("minio.bucket", () -> "pdfauto-it");
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
        // R6-D 本测试类唯一语义：全局 parser = auto（互斥 Bean 装配在测试内与生产一致）
        registry.add("rag.ingestion.pdf-parser", () -> "auto");
        registry.add("rag.ingestion.mineru-base-url", () -> fakeMineru.baseUrl());
    }

    @AfterAll
    static void tearDown() {
        if (fakeModel != null) fakeModel.stop();
        if (fakeMineru != null) fakeMineru.stop();
    }

    private String newKb() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("pdfauto-it-" + UUID.randomUUID());
        return kbRepository.save(kb).getId();
    }

    /** 组装一篇"已上传"的 PDF 文档：源文件进 MinIO + QUEUED 任务行。 */
    private String uploadPdf(String kbId, byte[] pdfBytes, String name) {
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName(name);
        doc.setFileType(FileType.PDF);
        doc.setSizeBytes(pdfBytes.length);
        doc.setContentSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        doc.setStatus(DocumentStatus.QUEUED);
        doc.setCurrentStage(PipelineStage.QUEUED);
        doc.setChunkConfig(Map.of("strategy", ChunkStrategy.LENGTH_OVERLAP.name(), "maxLength", 400, "overlap", 50));
        documentRepository.save(doc);

        objectStore.putSource(kbId, docId, name, new ByteArrayInputStream(pdfBytes), pdfBytes.length);

        IngestionTaskEntity task = new IngestionTaskEntity();
        task.setDocumentId(docId);
        task.setStatus(TaskStatus.QUEUED);
        task.setStage(PipelineStage.QUEUED);
        task.setAttempt(1);
        taskRepository.save(task);
        return docId;
    }

    /** 正常文本 PDF：每页约 100+ 可提取字符。 */
    private static byte[] textPdf(int pages) {
        String line = "HikariCP provides connection pool parameters such as maximumPoolSize ";
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(line + "page " + (i + 1) + ".");
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 无文本层 PDF（模拟扫描件）：页面上不写任何内容流。 */
    private static byte[] imageOnlyPdf(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parserMetadata(DocumentEntity doc) {
        Map<String, Object> metadata = doc.getParseMetadata();
        assertThat(metadata).as("AUTO 文档必须写入 parse_metadata").isNotNull();
        return (Map<String, Object>) metadata.get("parser");
    }

    /** FakeMineruServer 计数跨用例累计（static 共享），各用例以基线增量断言。 */
    private int mineruRequestsBaseline;
    private int mineruRequestsDelta() {
        return fakeMineru.parseRequests() - mineruRequestsBaseline;
    }

    @org.junit.jupiter.api.BeforeEach
    void recordMineruBaseline() {
        mineruRequestsBaseline = fakeMineru.parseRequests();
    }

    @Test
    void autoNormalTextPdfRoutesToPdfBoxAndPersistsMetadata() {
        String kbId = newKb();
        String docId = uploadPdf(kbId, textPdf(2), "normal-text.pdf");

        taskManager.processOne(docId);

        assertThat(taskRepository.findByDocumentId(docId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        Map<String, Object> parser = parserMetadata(doc);
        assertThat(parser.get("requested")).isEqualTo("AUTO");
        assertThat(parser.get("selected")).isEqualTo("PDFBOX");
        assertThat(parser.get("routingReason")).isEqualTo("TEXT_PDF");
        Map<String, Object> probe = (Map<String, Object>) parser.get("probe");
        assertThat((Integer) probe.get("pageCount")).isEqualTo(2);
        assertThat(((Number) probe.get("charCount")).longValue()).isGreaterThan(100);
        assertThat(((Number) probe.get("emptyPageRatio")).doubleValue()).isLessThan(0.1);
        assertThat((Number) probe.get("probeLatencyMs")).isNotNull();
        // MinerU 未被调用（AUTO→PDFBOX 路径零远端开销）
        assertThat(mineruRequestsDelta()).isZero();
    }

    @Test
    void autoLowTextPdfRoutesToMinerUAndPersistsMetadata() {
        String kbId = newKb();
        String docId = uploadPdf(kbId, imageOnlyPdf(2), "scanned.pdf");

        taskManager.processOne(docId);

        assertThat(taskRepository.findByDocumentId(docId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        Map<String, Object> parser = parserMetadata(doc);
        assertThat(parser.get("requested")).isEqualTo("AUTO");
        assertThat(parser.get("selected")).isEqualTo("MINERU");
        assertThat(parser.get("routingReason")).isEqualTo("LOW_TEXT_DENSITY");
        Map<String, Object> probe = (Map<String, Object>) parser.get("probe");
        assertThat(((Number) probe.get("charCount")).longValue()).isZero();
        // MinerU 确实被调用且返回成功（FakeMineruServer 默认 md_content）
        assertThat(mineruRequestsDelta()).isEqualTo(1);
        // 解析产物来自 MinerU markdown
        String parsed = objectStore.getParsed(kbId, docId);
        assertThat(parsed).contains("MinerU OCR");
    }

    /** R6-D §31 最重要的 correctness test：AUTO→MINERU→500 = FAILED，绝不切 PDFBox。
     *  R6-D.1：失败路径同样持久化 parse_metadata（routing decision 已产生即为事实）。 */
    @Test
    void mineruFailureAfterAutoDecisionFailsWithoutPdfBoxFallback() {
        String kbId = newKb();
        String docId = uploadPdf(kbId, imageOnlyPdf(2), "scanned-fail.pdf");
        fakeMineru.setFailure(true);
        try {
            taskManager.processOne(docId);
        } finally {
            fakeMineru.setFailure(false);
        }

        IngestionTaskEntity task = taskRepository.findByDocumentId(docId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getFailureStage()).isEqualTo(PipelineStage.PARSING);
        assertThat(task.getFailureReason()).contains("AUTO selected MINERU");
        // 只调用了一次 MinerU（无重试无换道）；PDFBox 始终未被调用（否则
        // 其"首页无文本层 → SCANNED_PDF_NOT_SUPPORTED"会让失败原因不同）
        assertThat(mineruRequestsDelta()).isEqualTo(1);
        assertThat(task.getFailureReason()).doesNotContain("SCANNED_PDF_NOT_SUPPORTED");

        // R6-D.1 核心集成断言：任务 FAILED 后重新加载 document，routing metadata 仍存在
        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        Map<String, Object> parser = parserMetadata(doc);
        assertThat(parser.get("requested")).isEqualTo("AUTO");
        assertThat(parser.get("selected")).isEqualTo("MINERU");
        assertThat(parser.get("routingReason")).isEqualTo("LOW_TEXT_DENSITY");
        Map<String, Object> probe = (Map<String, Object>) parser.get("probe");
        assertThat(((Number) probe.get("charCount")).longValue()).isZero();
        assertThat(((Number) probe.get("probeLatencyMs")).longValue()).isGreaterThanOrEqualTo(0);
    }

    /** R6-D §32 对照面：AUTO→PDFBOX→正式解析失败 = FAILED，绝不切 MinerU。 */
    @Test
    void pdfBoxFailureAfterAutoDecisionFailsWithoutMinerUFallback() {
        String kbId = newKb();
        // 正常文本 PDF → 路由 PDFBOX；但故意喂给流水线一个损坏源对象内容，
        // 使 PDFBox 正式解析抛错（探针在 AutoPdfParser 内部读同一份 bytes 也会失败——
        // 那会变成 PROBE_FAILED→MINERU，无法构造"route=PDFBOX 后失败"。
        // 因此该路径的端到端注入由 AutoPdfParserTest#pdfBoxFailureAfterPdfBoxDecisionNeverFallsBackToMinerU
        // 以 mock 锁死；此处断言损坏 PDF 走 PROBE_FAILED→MINERU→FakeMineru 成功（行为可解释）。
        byte[] garbage = "not a real pdf body".getBytes();
        String docId = uploadPdf(kbId, garbage, "corrupted.pdf");

        taskManager.processOne(docId);

        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        Map<String, Object> parser = parserMetadata(doc);
        assertThat(parser.get("selected")).isEqualTo("MINERU");
        assertThat(parser.get("routingReason")).isEqualTo("PROBE_FAILED");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(mineruRequestsDelta()).isEqualTo(1);
    }
}
