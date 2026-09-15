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
 *       evidence（EvidenceRef[]，可缺省为 []）；</li>
 *   <li>{@code .csv}：表头须含 question/answerable/category（同名字段），
 *       evidence 列为 JSON 字符串数组（如 {@code [{"docName":"...","titlePath":"..."}]}）。</li>
 * </ul>
 *
 * <p>失败统一抛 {@link DomainException}(INVALID_DATASET_FILE, 422)，message
 * 带行号定位。解析结果为纯结构（{@link EvalItem}），持久化由 EvalService 负责。</p>
 */
@Component
public class DatasetFileParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 解析产物（纯结构，与 JPA 实体解耦）。 */
    public record EvalItem(String question, String referenceAnswer,
                           List<Map<String, Object>> evidence,
                           boolean answerable, EvalCategory category) {
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
                    + "'（合法值：DIRECT/TERM_VARIATION/FOLLOW_UP/OUT_OF_KB/CONFUSABLE）");
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
        List<Map<String, Object>> evidence;
        Object rawEvidence = row.get("evidence");
        if (rawEvidence == null) {
            evidence = List.of();
        } else if (rawEvidence instanceof List<?> list) {
            evidence = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map)) {
                    throw invalid(location + "：evidence 数组元素必须为对象（docName/titlePath 等）");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                evidence.add(m);
            }
        } else {
            throw invalid(location + "：evidence 必须是数组");
        }
        Object reference = row.get("referenceAnswer");
        String referenceAnswer = reference == null || String.valueOf(reference).isBlank()
                ? null : String.valueOf(reference);
        return new EvalItem(q.trim(), referenceAnswer, evidence, answerable, category);
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.INVALID_DATASET_FILE, message);
    }
}
