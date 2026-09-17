package com.rag.ingestion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import com.rag.support.FakeOpenAiServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6-D 手动模式回归（pdf-parser 缺省 = pdfbox）：真实流水线下
 *
 * <ul>
 *   <li>手动 PDFBOX 不做 AUTO probe（metadata 无 probe 字段、无远端调用）；</li>
 *   <li>requested=selected=PDFBOX、routingReason=null（§16 二选一方案的 null 侧）；</li>
 *   <li>既有行为不变：正常文本 PDF 完整入库；扫描件 PDF 首页无文本层 →
 *       SCANNED_PDF_NOT_SUPPORTED FAILED（原有语义，绝不转投 MinerU）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PdfManualParserIT {

    private static FakeOpenAiServer fakeModel;

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
        } catch (Exception e) {
            throw new IllegalStateException("FakeOpenAiServer 启动失败", e);
        }
        registry.add("minio.bucket", () -> "pdfmanual-it");
        registry.add("rag.models.embedding.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.chat.base-url", () -> fakeModel.baseUrl());
        registry.add("rag.models.startup-check", () -> "false");
        // 不设置 rag.ingestion.pdf-parser → 缺省 pdfbox（与生产默认一致）
    }

    @AfterAll
    static void tearDown() {
        if (fakeModel != null) fakeModel.stop();
    }

    private String newKb() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setName("pdfmanual-it-" + UUID.randomUUID());
        return kbRepository.save(kb).getId();
    }

    private String uploadPdf(String kbId, byte[] pdfBytes) {
        String docId = UUID.randomUUID().toString();
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setName(docId + ".pdf");
        doc.setFileType(FileType.PDF);
        doc.setSizeBytes(pdfBytes.length);
        doc.setContentSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        doc.setStatus(DocumentStatus.QUEUED);
        doc.setCurrentStage(PipelineStage.QUEUED);
        doc.setChunkConfig(Map.of("strategy", ChunkStrategy.LENGTH_OVERLAP.name(), "maxLength", 400, "overlap", 50));
        documentRepository.save(doc);

        objectStore.putSource(kbId, docId, docId + ".pdf",
                new ByteArrayInputStream(pdfBytes), pdfBytes.length);

        IngestionTaskEntity task = new IngestionTaskEntity();
        task.setDocumentId(docId);
        task.setStatus(TaskStatus.QUEUED);
        task.setStage(PipelineStage.QUEUED);
        task.setAttempt(1);
        taskRepository.save(task);
        return docId;
    }

    private static byte[] textPdf(int pages) {
        String line = "Manual mode regression text layer content for pdfbox parsing pipeline ";
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

    private static byte[] imageOnlyPdf() {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parserMetadata(DocumentEntity doc) {
        return (Map<String, Object>) ((Map<String, Object>) doc.getParseMetadata()).get("parser");
    }

    @Test
    void manualPdfBoxCompletesWithoutProbeAndRecordsIdentityMetadata() {
        String kbId = newKb();
        String docId = uploadPdf(kbId, textPdf(2));

        taskManager.processOne(docId);

        assertThat(taskRepository.findByDocumentId(docId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.COMPLETED);
        DocumentEntity doc = documentRepository.findById(docId).orElseThrow();
        // R6-D.1：手动 PDFBOX 也写 identity metadata（requested=selected=PDFBOX），
        // 不是 null——记录"文档实际由哪个 parser 解析"
        assertThat(doc.getParseMetadata()).isNotNull();
        Map<String, Object> parser = parserMetadata(doc);
        assertThat(parser.get("requested")).isEqualTo("PDFBOX");
        assertThat(parser.get("selected")).isEqualTo("PDFBOX");
        assertThat(parser.get("routingReason")).isNull();
        // 手动模式不做 probe（§33/§34：手动 parser 零 AUTO 开销）
        assertThat(parser.containsKey("probe")).isFalse();
    }

    @Test
    void manualPdfBoxStillRejectsScannedPdfWithOriginalErrorCode() {
        String kbId = newKb();
        String docId = uploadPdf(kbId, imageOnlyPdf());

        taskManager.processOne(docId);

        IngestionTaskEntity task = taskRepository.findByDocumentId(docId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getFailureStage()).isEqualTo(PipelineStage.PARSING);
        // 原有失败语义逐字保留（R6-D §2：不让 AUTO 逻辑影响显式 parser）
        assertThat(task.getFailureReason()).contains("SCANNED_PDF_NOT_SUPPORTED");
    }
}
