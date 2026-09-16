package com.rag.eval;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.rag.domain.enums.EvalCategory;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import com.rag.eval.support.DatasetFileParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据集文件解析器单测（Task 6）：JSON/CSV 正常路径 + 各非法输入 → 422。
 */
class DatasetFileParserTest {

    private final DatasetFileParser parser = new DatasetFileParser();

    private InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parseJsonHappyPath() {
        String json = """
                [
                  {"question":"支付回调超时是多少？","referenceAnswer":"5 秒",
                   "answerable":true,"category":"DIRECT",
                   "evidence":[{"docName":"支付接口文档.md","titlePath":"支付 > 回调","page":3,"snippet":"..."}]},
                  {"question":"Q2","category":"OUT_OF_KB","answerable":false,
                   "failureMode":"OUT_OF_KB","evidence":[]}
                ]
                """;
        List<DatasetFileParser.EvalItem> items = parser.parse("ds.json", bytes(json));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).question()).isEqualTo("支付回调超时是多少？");
        assertThat(items.get(0).referenceAnswer()).isEqualTo("5 秒");
        assertThat(items.get(0).answerable()).isTrue();
        assertThat(items.get(0).category()).isEqualTo(EvalCategory.DIRECT);
        assertThat(items.get(0).evidence()).hasSize(1);
        assertThat(items.get(0).evidence().get(0)).containsEntry("docName", "支付接口文档.md");
        // 缺省 answerable 默认 true；referenceAnswer 缺省 null
        assertThat(items.get(1).answerable()).isFalse();
        assertThat(items.get(1).category()).isEqualTo(EvalCategory.OUT_OF_KB);
        assertThat(items.get(1).evidence()).isEmpty();
        assertThat(items.get(1).referenceAnswer()).isNull();
    }

    @Test
    void parseCsvHappyPath() {
        String csv = """
                question,referenceAnswer,answerable,category,failureMode,evidence
                "CSV 问题一","CSV 参考答案",true,DIRECT,,"[{""docName"":""a.md"",""titlePath"":""A > B""}]"
                "CSV 问题二",,false,OUT_OF_KB,OUT_OF_KB,"[]"
                """;
        List<DatasetFileParser.EvalItem> items = parser.parse("ds.csv", bytes(csv));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).question()).isEqualTo("CSV 问题一");
        assertThat(items.get(0).referenceAnswer()).isEqualTo("CSV 参考答案");
        assertThat(items.get(0).category()).isEqualTo(EvalCategory.DIRECT);
        assertThat(items.get(0).evidence()).hasSize(1);
        assertThat(items.get(0).evidence().get(0)).containsEntry("titlePath", "A > B");
        assertThat(items.get(1).answerable()).isFalse();
        assertThat(items.get(1).category()).isEqualTo(EvalCategory.OUT_OF_KB);
        assertThat(items.get(1).evidence()).isEmpty();
        assertThat(items.get(1).referenceAnswer()).isNull();
    }

    @Test
    void brokenJsonRejected() {
        assertThatThrownBy(() -> parser.parse("ds.json", bytes("{not an array")))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE));
    }

    @Test
    void jsonMissingQuestionRejected() {
        String json = """
                [{"referenceAnswer":"x","category":"DIRECT"}]
                """;
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("question");
                });
    }

    @Test
    void jsonBlankQuestionRejected() {
        String json = """
                [{"question":"  ","category":"DIRECT"}]
                """;
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE));
    }

    @Test
    void jsonIllegalCategoryRejected() {
        String json = """
                [{"question":"q","category":"NOT_A_CATEGORY"}]
                """;
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("category");
                });
    }

    @Test
    void csvMissingQuestionRejected() {
        String csv = """
                question,answerable,category
                ,true,DIRECT
                """;
        assertThatThrownBy(() -> parser.parse("ds.csv", bytes(csv)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("question");
                });
    }

    @Test
    void csvIllegalCategoryRejected() {
        String csv = """
                question,answerable,category
                q1,true,OOPS
                """;
        assertThatThrownBy(() -> parser.parse("ds.csv", bytes(csv)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("category");
                });
    }

    @Test
    void csvNonBooleanAnswerableRejected() {
        String csv = """
                question,answerable,category
                q1,YES,DIRECT
                """;
        assertThatThrownBy(() -> parser.parse("ds.csv", bytes(csv)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("answerable");
                });
    }

    @Test
    void csvBrokenEvidenceJsonRejected() {
        String csv = """
                question,answerable,category,evidence
                q1,true,DIRECT,"[{docName:"
                """;
        assertThatThrownBy(() -> parser.parse("ds.csv", bytes(csv)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("evidence");
                });
    }

    @Test
    void csvMissingRequiredColumnRejected() {
        String csv = """
                question,category
                q1,DIRECT
                """;
        assertThatThrownBy(() -> parser.parse("ds.csv", bytes(csv)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("answerable");
                });
    }

    @Test
    void unknownExtensionRejected() {
        assertThatThrownBy(() -> parser.parse("ds.xlsx", bytes("x")))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE));
    }

    // ---------- R4.1：history（FOLLOW_UP 口径修正） ----------

    @Test
    void parsesHistoryAndKeepsOrder() {
        String json = """
                [
                  {"question":"那对因此分配失败的分片，还要做什么？","category":"FOLLOW_UP",
                   "answerable":true,
                   "history":[
                     {"role":"user","content":"ES 磁盘洪泛水位是多少？"},
                     {"role":"assistant","content":"97% 触发只读块。"}
                   ]}
                ]""";
        List<DatasetFileParser.EvalItem> items = parser.parse("ds.json", bytes(json));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).history()).hasSize(2);
        assertThat(items.get(0).history().get(0)).containsEntry("role", "user");
        assertThat(items.get(0).history().get(1)).containsEntry("role", "assistant");
    }

    @Test
    void historyMissingDefaultsToEmpty() {
        String json = """
                [{"question":"q","category":"DIRECT"}]""";
        List<DatasetFileParser.EvalItem> items = parser.parse("ds.json", bytes(json));
        assertThat(items.get(0).history()).isEmpty();
    }

    @Test
    void historyIllegalRoleRejected() {
        String json = """
                [{"question":"q","category":"FOLLOW_UP","history":[{"role":"system","content":"x"}]}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("history[0].role");
                });
    }

    @Test
    void historyBlankContentRejected() {
        String json = """
                [{"question":"q","category":"FOLLOW_UP","history":[{"role":"user","content":"  "}]}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("history[0].content");
                });
    }

    @Test
    void historyNonArrayRejected() {
        String json = """
                [{"question":"q","category":"FOLLOW_UP","history":"昨天聊的"}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("history 必须是数组");
                });
    }

    // ---------- R6-A：Hard Eval 最小标签集 ----------

    @Test
    void parsesHardEvalLabels() {
        String json = """
                [
                  {"question":"AOF 每秒刷盘最多丢多少数据？","category":"DIRECT","answerable":true,
                   "evidenceMode":"SINGLE_CHUNK",
                   "evidence":[{"docName":"redis.md","contentHash":"b17e5488dd0f38e4"}]},
                  {"question":"Nginx 默认最大并发连接数是多少？","category":"PARTIAL_EVIDENCE","answerable":false,
                   "failureMode":"PARTIAL_EVIDENCE",
                   "missingRequirement":"语料只有 worker_connections，缺 worker_processes，不能推出总并发",
                   "temptingEvidence":[{"docName":"nginx.md","titlePath":"nginx.md > 1.2. 相关指令"}]}
                ]""";
        List<DatasetFileParser.EvalItem> items = parser.parse("hard.json", bytes(json));
        assertThat(items).hasSize(2);
        // 正例：evidenceMode 保留
        assertThat(items.get(0).evidenceMode()).isEqualTo(com.rag.domain.enums.EvalEvidenceMode.SINGLE_CHUNK);
        assertThat(items.get(0).failureMode()).isNull();
        assertThat(items.get(0).temptingEvidence()).isEmpty();
        assertThat(items.get(0).missingRequirement()).isNull();
        // 负例：failureMode + 诊断信息保留
        assertThat(items.get(1).failureMode()).isEqualTo(com.rag.domain.enums.EvalFailureMode.PARTIAL_EVIDENCE);
        assertThat(items.get(1).evidenceMode()).isNull();
        assertThat(items.get(1).missingRequirement()).contains("worker_processes");
        assertThat(items.get(1).temptingEvidence()).hasSize(1);
    }

    @Test
    void csvParsesHardEvalColumns() {
        String csv = """
                question,answerable,category,evidenceMode,failureMode,temptingEvidence,missingRequirement
                "默认端口是多少？",true,DIRECT,SINGLE_CHUNK,,"[]",""
                "最大并发是多少？",false,CONFUSABLE,,PARTIAL_EVIDENCE,"[{""contentHash"":""abc123""}]","缺 worker_processes"
                """;
        List<DatasetFileParser.EvalItem> items = parser.parse("hard.csv", bytes(csv));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).evidenceMode()).isEqualTo(com.rag.domain.enums.EvalEvidenceMode.SINGLE_CHUNK);
        assertThat(items.get(0).failureMode()).isNull();
        assertThat(items.get(1).failureMode()).isEqualTo(com.rag.domain.enums.EvalFailureMode.PARTIAL_EVIDENCE);
        assertThat(items.get(1).temptingEvidence()).hasSize(1);
        assertThat(items.get(1).missingRequirement()).isEqualTo("缺 worker_processes");
    }

    @Test
    void positiveItemWithFailureModeRejected() {
        String json = """
                [{"question":"q","category":"DIRECT","answerable":true,"failureMode":"OUT_OF_KB"}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("failureMode");
                });
    }

    @Test
    void negativeItemWithoutFailureModeRejected() {
        String json = """
                [{"question":"q","category":"OUT_OF_KB","answerable":false}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("failureMode 必填");
                });
    }

    @Test
    void negativeItemWithEvidenceModeRejected() {
        String json = """
                [{"question":"q","category":"OUT_OF_KB","answerable":false,
                  "failureMode":"OUT_OF_KB","evidenceMode":"SINGLE_CHUNK"}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("evidenceMode");
                });
    }

    @Test
    void positiveItemWithDiagnosticFieldsRejected() {
        String json = """
                [{"question":"q","category":"DIRECT","answerable":true,
                  "missingRequirement":"不应该出现"}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("仅用于 answerable=false");
                });
    }

    @Test
    void temptingEvidenceWithoutAnchorRejected() {
        String json = """
                [{"question":"q","category":"CONFUSABLE","answerable":false,
                  "failureMode":"ENTITY_MISMATCH",
                  "temptingEvidence":[{"docName":"x.md"}]}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("temptingEvidence");
                });
    }

    @Test
    void illegalFailureModeRejected() {
        String json = """
                [{"question":"q","category":"OUT_OF_KB","answerable":false,"failureMode":"SOMETHING_ELSE"}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("failureMode");
                });
    }

    @Test
    void illegalEvidenceModeRejected() {
        String json = """
                [{"question":"q","category":"DIRECT","answerable":true,"evidenceMode":"TEN_CHUNKS"}]""";
        assertThatThrownBy(() -> parser.parse("ds.json", bytes(json)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_DATASET_FILE);
                    assertThat(e.getMessage()).contains("evidenceMode");
                });
    }
}
