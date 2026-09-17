-- R6-D：PDF 解析路由元数据（纯新增列，历史行为 NULL，无需回填）。
-- PDF 文档解析成功后写入 document.parse_metadata（非 PDF 不写）：
--   AUTO（pdf-parser=auto）：
--   {"docId": "...", "parser": {"requested": "AUTO", "selected": "MINERU",
--               "routingReason": "LOW_TEXT_DENSITY",
--               "probe": {"pageCount": 12, "charCount": 83, "charsPerPage": 6.92,
--                         "emptyPageRatio": 0.75, "printableRatio": 0.98,
--                         "replacementCharRatio": 0.0, "probeLatencyMs": 18}}}
--   手动（pdfbox/mineru）：requested=selected=<模式>，routingReason=null，无 probe。
ALTER TABLE document ADD COLUMN parse_metadata JSON NULL AFTER parsed_object_key;
