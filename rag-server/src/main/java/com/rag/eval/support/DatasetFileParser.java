package com.rag.eval.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.domain.enums.EvalCategory;
import com.rag.domain.enums.EvalEvidenceMode;
import com.rag.domain.enums.EvalFailureMode;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 评测数据集文件解析器（contracts/openapi.yaml importEvalDataset）。
 *
 * <p>按文件扩展名路由：</p>
 * <ul>
 *   <li>{@code .json}：顶层对象数组，元素字段 question（必填）、referenceAnswer、
 *       answerable（boolean，缺省 true）、category（EvalCategory，必填合法值）、
 *       evidence（EvidenceRef[]，可缺省为 []）；R6-A 可选：failureMode / evidenceMode /
 *       temptingEvidence / missingRequirement（Hard Eval 标签，带一致性校验）；</li>
 *   <li>{@code .csv}：表头须含 question/answerable/category（同名字段），
 *       evidence/history/temptingEvidence 列为 JSON 字符串数组
 *       （如 {@code [{"docName":"...","titlePath":"..."}]}）。
 *       可选 history（R4.1）：[{role: user|assistant, content}] 时间正序，仅用于解析当前问题指代。</li>
 * </ul>
 *
 * <p>失败统一抛 {@link DomainException}(INVALID_DATASET_FILE, 422)，message
 * 带行号定位。解析结果为纯结构（{@link EvalItem}），持久化由 EvalService 负责。</p>
 */
@Component
public class DatasetFileParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 解析产物（纯结构，与 JPA 实体解耦）。history 为可选对话历史（R4.1 FOLLOW_UP 口径）。
     *  R6-A 新增：failureMode/evidenceMode（Hard Eval 最小标签集）+ temptingEvidence/missingRequirement（负例诊断信息）。 */
    public record EvalItem(String question, String referenceAnswer,
                           List<Map<String, Object>> evidence,
                           List<Map<String, Object>> history,
                           boolean answerable, EvalCategory category,
                           EvalFailureMode failureMode, EvalEvidenceMode evidenceMode,
                           List<Map<String, Object>> temptingEvidence,
                           String missingRequirement) {

        /** 旧调用兼容（R6-A 之前的行为：无 Hard Eval 标签）。 */
        public EvalItem(String question, String referenceAnswer,
                        List<Map<String, Object>> evidence,
                        List<Map<String, Object>> history,
                        boolean answerable, EvalCategory category) {
            this(question, referenceAnswer, evidence, history, answerable, category,
                    null, null, null, null);
        }
    }

    /**
     * @param fileName 原始文件名（决定路由 .json/.csv）
     * @param in       文件内容流
     * @return 按文件顺序的样本列表（空数组 = 空数据集，由调用方决定是否拒绝）
     */
    public List<EvalItem> parse(String fileName, InputStream in) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return parseJson(in);
        }
        if (lower.endsWith(".csv")) {
            return parseCsv(in);
        }
        throw invalid("不支持的评测数据集文件类型（仅支持 .json/.csv）：" + fileName);
    }

    /** 便捷重载：multipart 直传。 */
    public List<EvalItem> parse(MultipartFile file) {
        try {
            return parse(file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            throw invalid("读取上传文件失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    private List<EvalItem> parseJson(InputStream in) {
        List<Map<String, Object>> rows;
        try {
            rows = JSON.readValue(in,
                    JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (IOException e) {
            throw invalid("JSON 解析失败：应为对象数组。" + e.getMessage());
        }
        List<EvalItem> items = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            items.add(toItem(row, "JSON 第 " + (i + 1) + " 个元素"));
        }
        return items;
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    private List<EvalItem> parseCsv(InputStream in) {
        List<String[]> rows = readCsvRows(in);
        if (rows.isEmpty()) {
            throw invalid("CSV 文件为空：首行必须为表头。");
        }
        String[] header = rows.get(0);
        Map<String, Integer> col = new java.util.HashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim(), i);
        }
        for (String required : List.of("question", "answerable", "category")) {
            if (!col.containsKey(required)) {
                throw invalid("CSV 缺少必需列：" + required);
            }
        }
        List<EvalItem> items = new ArrayList<>(rows.size() - 1);
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            if (row.length == 1 && row[0].isBlank()) {
                continue; // 跳过全空行
            }
            int lineNo = r + 1;
            Map<String, Object> record = new java.util.LinkedHashMap<>();
            record.put("question", cell(row, col.get("question")));
            record.put("referenceAnswer", col.containsKey("referenceAnswer")
                    ? cell(row, col.get("referenceAnswer")) : null);
            // 原样透传，布尔合法性（true/false，大小写不敏感）由 toItem 统一校验
            record.put("answerable", cell(row, col.get("answerable")));
            record.put("category", cell(row, col.get("category")));
            // R6-A：Hard Eval 可选列（原样透传，合法性由 toItem 统一校验）
            record.put("failureMode", col.containsKey("failureMode")
                    ? cell(row, col.get("failureMode")) : null);
            record.put("evidenceMode", col.containsKey("evidenceMode")
                    ? cell(row, col.get("evidenceMode")) : null);
            record.put("missingRequirement", col.containsKey("missingRequirement")
                    ? cell(row, col.get("missingRequirement")) : null);
            String evidenceJson = col.containsKey("evidence") ? cell(row, col.get("evidence")) : "[]";
            List<Map<String, Object>> evidence;
            try {
                evidence = JSON.readValue(evidenceJson == null || evidenceJson.isBlank()
                        ? "[]" : evidenceJson,
                        JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
            } catch (IOException e) {
                throw invalid("CSV 第 " + lineNo + " 行 evidence 列不是合法的 JSON 数组："
                        + e.getMessage());
            }
            record.put("evidence", evidence);
            // R6-A：temptingEvidence 列同样为 JSON 字符串数组（仅负例诊断用）
            if (col.containsKey("temptingEvidence")) {
                String temptingJson = cell(row, col.get("temptingEvidence"));
                if (temptingJson != null && !temptingJson.isBlank()) {
                    try {
                        record.put("temptingEvidence", JSON.readValue(temptingJson,
                                JSON.getTypeFactory().constructCollectionType(List.class, Map.class)));
                    } catch (IOException e) {
                        throw invalid("CSV 第 " + lineNo + " 行 temptingEvidence 列不是合法的 JSON 数组："
                                + e.getMessage());
                    }
                }
            }
            items.add(toItem(record, "CSV 第 " + lineNo + " 行"));
        }
        return items;
    }

    /** 逐行读取（简化 RFC4180：字段内不含换行；引号包裹与逗号转义由 splitCsvLine 处理）。 */
    private static List<String[]> readCsvRows(InputStream in) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                if (line.isBlank()) {
                    continue;
                }
                rows.add(splitCsvLine(line));
            }
        } catch (IOException e) {
            throw invalid("CSV 读取失败：" + e.getMessage());
        }
        return rows;
    }

    /** 单行切分：支持双引号包裹字段（内含逗号/转义引号 ""）。 */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(String[]::new);
    }

    private static String cell(String[] row, Integer index) {
        if (index == null || index >= row.length) {
            return null;
        }
        return row[index].trim();
    }

    // ------------------------------------------------------------------
    // 共用字段校验
    // ------------------------------------------------------------------

    private EvalItem toItem(Map<String, Object> row, String location) {
        Object question = row.get("question");
        if (!(question instanceof String q) || q.isBlank()) {
            throw invalid(location + "：缺少必填字段 question");
        }
        EvalCategory category;
        Object rawCategory = row.get("category");
        if (rawCategory == null || String.valueOf(rawCategory).isBlank()) {
            throw invalid(location + "：缺少必填字段 category");
        }
        try {
            category = EvalCategory.valueOf(String.valueOf(rawCategory));
        } catch (IllegalArgumentException e) {
            throw invalid(location + "：非法 category '" + rawCategory
                    + "'（合法值：DIRECT/TERM_VARIATION/FOLLOW_UP/OUT_OF_KB/CONFUSABLE/PARTIAL_EVIDENCE）");
        }
        Boolean answerable = true;
        Object rawAnswerable = row.get("answerable");
        if (rawAnswerable instanceof Boolean b) {
            answerable = b;
        } else if (rawAnswerable != null) {
            String s = String.valueOf(rawAnswerable).trim();
            if ("true".equalsIgnoreCase(s)) {
                answerable = true;
            } else if ("false".equalsIgnoreCase(s)) {
                answerable = false;
            } else {
                throw invalid(location + "：answerable 不是合法布尔值：" + rawAnswerable);
            }
        }
        List<Map<String, Object>> evidence = toEvidence(row.get("evidence"), location);
        Object reference = row.get("referenceAnswer");
        String referenceAnswer = reference == null || String.valueOf(reference).isBlank()
                ? null : String.valueOf(reference);
        List<Map<String, Object>> history = toHistory(row.get("history"), location);

        // ---------------- R6-A：Hard Eval 最小标签集 ----------------
        EvalFailureMode failureMode = null;
        Object rawFailureMode = row.get("failureMode");
        if (rawFailureMode != null && !String.valueOf(rawFailureMode).isBlank()) {
            try {
                failureMode = EvalFailureMode.valueOf(String.valueOf(rawFailureMode));
            } catch (IllegalArgumentException e) {
                throw invalid(location + "：非法 failureMode '" + rawFailureMode
                        + "'（合法值：OUT_OF_KB/PARTIAL_EVIDENCE/MISSING_CONDITION/ENTITY_MISMATCH/"
                        + "SCOPE_MISMATCH/NUMERIC_MISMATCH/VERSION_CONFLICT/UNSUPPORTED_INFERENCE）");
            }
        }
        EvalEvidenceMode evidenceMode = null;
        Object rawEvidenceMode = row.get("evidenceMode");
        if (rawEvidenceMode != null && !String.valueOf(rawEvidenceMode).isBlank()) {
            try {
                evidenceMode = EvalEvidenceMode.valueOf(String.valueOf(rawEvidenceMode));
            } catch (IllegalArgumentException e) {
                throw invalid(location + "：非法 evidenceMode '" + rawEvidenceMode
                        + "'（合法值：SINGLE_CHUNK/MULTI_CHUNK/FOLLOW_UP）");
            }
        }
        List<Map<String, Object>> temptingEvidence = toEvidence(row.get("temptingEvidence"), location);
        Object rawMissing = row.get("missingRequirement");
        String missingRequirement = rawMissing == null || String.valueOf(rawMissing).isBlank()
                ? null : String.valueOf(rawMissing);

        // 一致性校验（R6-A §12 + R6-A.1 §9/§10 补齐）：错误样本直接拒绝导入，
        // 不静默修正标签。answerable 与 failureMode/evidenceMode 不允许矛盾状态。
        if (answerable && failureMode != null) {
            throw invalid(location + "：answerable=true 时不得设置 failureMode（当前："
                    + failureMode + "）");
        }
        if (!answerable && evidenceMode != null) {
            throw invalid(location + "：answerable=false 时不得设置 evidenceMode（当前："
                    + evidenceMode + "）");
        }
        if (!answerable && failureMode == null) {
            throw invalid(location + "：answerable=false 时 failureMode 必填"
                    + "（OUT_OF_KB/PARTIAL_EVIDENCE/MISSING_CONDITION/ENTITY_MISMATCH/SCOPE_MISMATCH/"
                    + "NUMERIC_MISMATCH/VERSION_CONFLICT/UNSUPPORTED_INFERENCE）");
        }
        // R6-A.1 §10：temptingEvidence 每一条都必须有分块级锚点（此前误用 anyMatch，
        // 一条合法会掩盖其余无锚点的条目）
        if (!answerable && temptingEvidence != null && !temptingEvidence.isEmpty()
                && !allEntriesAnchored(temptingEvidence)) {
            throw invalid(location + "：temptingEvidence 每条都必须含分块级锚点"
                    + "（contentHash/chunkId/titlePath/anchorPath 之一）");
        }
        if (answerable && (temptingEvidence != null && !temptingEvidence.isEmpty()
                || missingRequirement != null)) {
            throw invalid(location + "：temptingEvidence/missingRequirement 仅用于 answerable=false 的负例");
        }
        // R6-A.1 §9：正例必须有证据，且每条 required evidence 都要有分块级锚点——
        // 缺锚点的证据在 Hit/Coverage 判定中恒为未命中，只会污染指标而不报错
        if (answerable) {
            if (evidence.isEmpty()) {
                throw invalid(location + "：answerable=true 时 evidence 必填（至少 1 条）");
            }
            for (int i = 0; i < evidence.size(); i++) {
                Map<String, Object> ev = evidence.get(i);
                if (!hasChunkAnchor(ev)) {
                    throw invalid(location + "：evidence[" + i
                            + "] 缺少分块级锚点（contentHash/chunkId/titlePath/anchorPath 之一）");
                }
            }
            // evidenceMode 合理约束（允许多块等价证据，故 SINGLE_CHUNK 只要求非空）
            if (evidenceMode == EvalEvidenceMode.MULTI_CHUNK && evidence.size() < 2) {
                throw invalid(location + "：evidenceMode=MULTI_CHUNK 时 evidence 至少 2 条（当前 "
                        + evidence.size() + " 条）");
            }
            if (evidenceMode == EvalEvidenceMode.FOLLOW_UP && history.isEmpty()) {
                throw invalid(location + "：evidenceMode=FOLLOW_UP 时 history 必填（指代解析依赖会话历史）");
            }
        }

        return new EvalItem(q.trim(), referenceAnswer, evidence, history, answerable, category,
                failureMode, evidenceMode, temptingEvidence, missingRequirement);
    }

    /** 单条证据是否带分块级锚点（contentHash/chunkId/titlePath/anchorPath 之一）。 */
    private static boolean hasChunkAnchor(Map<String, Object> ev) {
        return ev.get("contentHash") != null || ev.get("chunkId") != null
                || ev.get("titlePath") != null || ev.get("anchorPath") != null;
    }

    /** 全部条目都带分块级锚点（R6-A.1 §10：allMatch 语义，与 EvidenceMatcher.hasChunkLevelAnchor 对齐）。 */
    private static boolean allEntriesAnchored(List<Map<String, Object>> evidenceList) {
        return evidenceList.stream().allMatch(DatasetFileParser::hasChunkAnchor);
    }

    /** evidence 解析（与旧实现一致，抽出便于 history 复用数组校验路径）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toEvidence(Object rawEvidence, String location) {
        if (rawEvidence == null) {
            return List.of();
        }
        if (!(rawEvidence instanceof List<?> list)) {
            throw invalid(location + "：evidence 必须是数组");
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw invalid(location + "：evidence 数组元素必须为对象（docName/titlePath 等）");
            }
            evidence.add((Map<String, Object>) o);
        }
        return evidence;
    }

    /**
     * 可选 history 解析（R4.1 FOLLOW_UP 口径修正）：数组元素必须为
     * {role: "user"|"assistant", content: 非空字符串}，时间正序（不校验内容语义）。
     * 缺省/null = 无历史。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toHistory(Object rawHistory, String location) {
        if (rawHistory == null) {
            return List.of();
        }
        if (!(rawHistory instanceof List<?> list)) {
            throw invalid(location + "：history 必须是数组");
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof Map)) {
                throw invalid(location + "：history[" + i + "] 必须为对象 {role, content}");
            }
            Map<String, Object> turn = (Map<String, Object>) o;
            Object role = turn.get("role");
            Object content = turn.get("content");
            boolean roleOk = role instanceof String r
                    && ("user".equalsIgnoreCase(r) || "assistant".equalsIgnoreCase(r));
            if (!roleOk) {
                throw invalid(location + "：history[" + i + "].role 必须为 user 或 assistant");
            }
            if (!(content instanceof String c) || c.isBlank()) {
                throw invalid(location + "：history[" + i + "].content 必须为非空字符串");
            }
            history.add(turn);
        }
        return history;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.INVALID_DATASET_FILE, message);
    }
}
