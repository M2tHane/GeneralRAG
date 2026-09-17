package com.rag.ingestion.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * PDFBox 轻量质量探针（R6-D）：AUTO 路由的数据来源。
 *
 * <p>逐页抽取文本并统计 {@link PdfQualityMetrics}，<b>不是</b>正式解析——
 * 产物（页文本）只用于统计后即弃，正式解析仍走对应 parser（R6-D §12：职责清晰，
 * 现有 parser 行为不漂移；probe 可以后续独立优化）。单次 PDDocument.load + 逐页
 * PDFTextStripper，远轻于 MinerU 远端 OCR。</p>
 *
 * <p>任何打开/读取失败（损坏、加密且无权限等）都以 {@link ProbeFailedException}
 * 上抛，由 Router 定论 PROBE_FAILED → MINERU——探针失败只是 AUTO 的路由输入，
 * 不等于解析失败（MinerU 或许能 OCR）。</p>
 */
@Component
public class PdfQualityProbe {

    /** PDFBox 无法打开/读取 PDF。msg 面向日志与失败元数据。 */
    public static class ProbeFailedException extends RuntimeException {
        public ProbeFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param emptyPageCharThreshold 单页有效字符低于该值视为空文本页（来自配置）
     */
    public PdfQualityMetrics probe(InputStream in, int emptyPageCharThreshold) {
        long start = System.currentTimeMillis();
        List<String> pageTexts = new ArrayList<>();
        try (PDDocument document = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pageTexts.add(stripper.getText(document));
            }
        } catch (Exception e) {
            throw new ProbeFailedException("PDF 质量探针失败：" + e.getMessage(), e);
        }
        if (pageTexts.isEmpty()) {
            // 0 页 PDF 在 PDFBox 中合法存在（结构损坏边缘形态）：按探针失败语义路由
            throw new ProbeFailedException("PDF 质量探针失败：0 页文档", null);
        }
        long latency = System.currentTimeMillis() - start;
        return metrics(pageTexts, emptyPageCharThreshold, latency);
    }

    /** 纯统计（包内可见，供单元测试直接喂文本，不依赖 PDFBox 版本行为）。 */
    static PdfQualityMetrics metrics(List<String> pageTexts, int emptyPageCharThreshold,
                                     long probeLatencyMs) {
        int pageCount = pageTexts.size();
        long charCount = 0;
        long nonWhitespace = 0;
        long printable = 0;
        long replacement = 0;
        int emptyPages = 0;
        for (String page : pageTexts) {
            long pageEffective = 0;
            for (int i = 0; i < page.length(); i++) {
                char c = page.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }
                nonWhitespace++;
                pageEffective++;
                if (c == '\uFFFD') {
                    replacement++;
                } else if (isPrintable(c)) {
                    printable++;
                }
            }
            charCount += pageEffective;
            if (pageEffective < emptyPageCharThreshold) {
                emptyPages++;
            }
        }
        double charsPerPage = pageCount == 0 ? 0 : (double) charCount / pageCount;
        double emptyPageRatio = (double) emptyPages / pageCount;
        double printableRatio = nonWhitespace == 0 ? 1 : (double) printable / nonWhitespace;
        double replacementRatio = nonWhitespace == 0 ? 0 : (double) replacement / nonWhitespace;
        return new PdfQualityMetrics(pageCount, charCount, charsPerPage, emptyPageRatio,
                printableRatio, replacementRatio, probeLatencyMs);
    }

    /**
     * 可打印判定：中英文/数字/常见全半角标点均算可打印（不以 ASCII 为界，否则中文全灭）。
     * 控制字符（Cc，除已按空白跳过的 \t\n\r\f 等）、未分配/私有区（Cn/Co）、
     * 代理区（Cs）与 U+FFFD 之外的格式字符（Cf）视为不可打印。
     */
    private static boolean isPrintable(char c) {
        if (c == '\uFFFD') {
            return false;
        }
        int type = Character.getType(c);
        return switch (type) {
            case Character.CONTROL, Character.PRIVATE_USE, Character.SURROGATE,
                    Character.UNASSIGNED, Character.FORMAT -> false;
            default -> true;
        };
    }
}
