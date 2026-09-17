package com.rag.config;

import com.rag.retrieval.model.RetrievalMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * rag.* 配置项（docs/03-技术路线.md §5）。启动期静态校验：配置缺失/非法时应用拒绝启动，
 * 而不是运行中途才失败。默认值与路线 §5 一致；密钥与地址一律经环境变量注入，仓库文件不落真实值。
 */
@ConfigurationProperties(prefix = "rag")
@Validated
public class RagProperties {

    @Valid
    @NotNull
    private Models models = new Models();

    @Valid
    @NotNull
    private Retrieval retrieval = new Retrieval();

    @Valid
    @NotNull
    private Ingestion ingestion = new Ingestion();

    @Valid
    @NotNull
    private Elasticsearch elasticsearch = new Elasticsearch();

    public Models getModels() {
        return models;
    }

    public void setModels(Models models) {
        this.models = models;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public void setIngestion(Ingestion ingestion) {
        this.ingestion = ingestion;
    }

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(Elasticsearch elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    /** 模型服务（OpenAI 兼容端点）。 */
    public static class Models {

        @Valid
        @NotNull
        private Chat chat = new Chat();

        @Valid
        @NotNull
        private Embedding embedding = new Embedding();

        /** 启动时向 embedding 服务发一次探活请求并校验维度（关闭仅用于模型服务晚于应用启动的本地场景）。 */
        private boolean startupCheck = true;

        public Chat getChat() {
            return chat;
        }

        public void setChat(Chat chat) {
            this.chat = chat;
        }

        public Embedding getEmbedding() {
            return embedding;
        }

        public void setEmbedding(Embedding embedding) {
            this.embedding = embedding;
        }

        public boolean isStartupCheck() {
            return startupCheck;
        }

        public void setStartupCheck(boolean startupCheck) {
            this.startupCheck = startupCheck;
        }
    }

    /** 对话模型配置。 */
    public static class Chat {

        /** OpenAI 兼容端点，如 http://localhost:8000/v1。必填。 */
        @NotBlank(message = "rag.models.chat.base-url 不能为空（环境变量 CHAT_MODEL_BASE_URL）")
        private String baseUrl = "";

        /** 可空：本地推理服务常不需要密钥。 */
        private String apiKey = "";

        private String modelName = "qwen2.5-7b-instruct";

        private double temperature = 0.2;

        /** 首 token 前与 token 间 idle 超时共用。 */
        @Positive
        private int timeoutSeconds = 120;

        /** QA-3：会话历史注入上限（仅连贯性，非证据）。 */
        @Min(0)
        private int maxHistoryMessages = 8;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }
    }

    /** Embedding 模型配置。 */
    public static class Embedding {

        @NotBlank(message = "rag.models.embedding.base-url 不能为空（环境变量 EMBEDDING_BASE_URL）")
        private String baseUrl = "";

        private String apiKey = "";

        private String modelName = "bge-m3";

        /** 必须与模型实际输出一致；startup-check 时校验（不一致是最隐蔽的线上事故源）。 */
        @Positive
        private int dimensions = 1024;

        @Positive
        private int timeoutSeconds = 30;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** 检索参数（调试页可按请求覆盖，此处为全局默认）。 */
    public static class Retrieval {

        @Min(value = 1, message = "rag.retrieval.top-k 必须 ≥ 1")
        private int topK = 6;

        @DecimalMin(value = "0.0", inclusive = true, message = "rag.retrieval.min-score 必须在 [0,1] 内")
        @DecimalMax(value = "1.0", inclusive = true, message = "rag.retrieval.min-score 必须在 [0,1] 内")
        private double minScore = 0.30;

        @Positive
        private int maxContextChars = 6000;

        /** 默认检索模式（R2-H）；调试/评测可按请求覆盖。 */
        @NotNull
        private RetrievalMode mode = RetrievalMode.HYBRID_RERANK;

        @Valid
        @NotNull
        private Rrf rrf = new Rrf();

        @Valid
        @NotNull
        private Rerank rerank = new Rerank();

        @Valid
        @NotNull
        private Refusal refusal = new Refusal();

        @Valid
        @NotNull
        private Answerability answerability = new Answerability();

        @Valid
        @NotNull
        private QueryRewrite queryRewrite = new QueryRewrite();

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }

        public RetrievalMode getMode() {
            return mode;
        }

        public void setMode(RetrievalMode mode) {
            this.mode = mode;
        }

        public Rrf getRrf() {
            return rrf;
        }

        public void setRrf(Rrf rrf) {
            this.rrf = rrf;
        }

        public Rerank getRerank() {
            return rerank;
        }

        public void setRerank(Rerank rerank) {
            this.rerank = rerank;
        }

        public Refusal getRefusal() {
            return refusal;
        }

        public void setRefusal(Refusal refusal) {
            this.refusal = refusal;
        }

    public Answerability getAnswerability() {
        return answerability;
    }

    public void setAnswerability(Answerability answerability) {
        this.answerability = answerability;
    }

    public QueryRewrite getQueryRewrite() {
        return queryRewrite;
    }

    public void setQueryRewrite(QueryRewrite queryRewrite) {
        this.queryRewrite = queryRewrite;
    }
    }

    /** RRF 融合参数（R2-H2/H3）。 */
    public static class Rrf {

        /** RRF 常数 k；融合分为 Σ 1/(k+rank)，k 越大各通道权重越平缓。 */
        @Min(value = 1, message = "rag.retrieval.rrf.k 必须 ≥ 1")
        private int k = 60;

        /** 各通道候选数上限。重排成本随候选数增长，故不进上下文但需收敛。 */
        @Min(value = 1, message = "rag.retrieval.rrf.candidate-limit 必须 ≥ 1")
        @Max(value = 500, message = "rag.retrieval.rrf.candidate-limit 过大（≤500），重排成本会失控")
        private int candidateLimit = 30;

        public int getK() {
            return k;
        }

        public void setK(int k) {
            this.k = k;
        }

        public int getCandidateLimit() {
            return candidateLimit;
        }

        public void setCandidateLimit(int candidateLimit) {
            this.candidateLimit = candidateLimit;
        }
    }

    /** 重排参数（R2-R）。 */
    public static class Rerank {

        @NotNull
        private String baseUrl = "";

        private String apiKey = "";

        private String modelName = "qwen3.7-text-rerank";

        @Positive
        private int timeoutSeconds = 10;

        /**
         * 失败降级开关：true=重排失败回退融合顺序（问答可用性优先）；
         * false=重排失败即请求失败。默认降级（R2-R2）。
         */
        private boolean degradeOnFailure = true;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isDegradeOnFailure() {
            return degradeOnFailure;
        }

        public void setDegradeOnFailure(boolean degradeOnFailure) {
            this.degradeOnFailure = degradeOnFailure;
        }
    }

    /**
     * 拒答与引用语义（R2-A）。
     *
     * <p>第一轮缺陷：模型正确拒答，但引用区仍挂着本次检索命中，"资料不足"与
     * "有来源"自相矛盾。第二轮规则：引用只来自"通过阈值的命中"，且当证据不足时
     * 判定拒答并清空引用，低相关命中只在检索调试页可见。</p>
     *
     * <p><b>为什么需要两个阈值</b>：区分"资料外/资料内"的能力取决于分数来源。
     * 实测（16 题语料）重排相关度可分——可答题最高分 0.787~1.000，资料外题
     * 0.142~0.630；而余弦相似度<b>不可分</b>——资料外题 0.65~0.77，与可答题
     * 区间重叠，任何单一余弦阈值都无法同时避免"该拒没拒"和"不该拒却拒"。
     * 故分两套阈值，各按自己的量纲校准，并在报告中如实说明局限。</p>
     */
    public static class Refusal {

        /** 是否启用"证据不足即清空引用"（关闭则退回第一轮行为，仅用于对照）。 */
        private boolean enabled = true;

        /**
         * 重排相关度口径阈值（默认模式 HYBRID_RERANK 生效）。
         * 校准依据：可答题 ≥0.787、资料外题 ≤0.630，取其间 0.55~0.70 区间；
         * 默认 0.65 偏向"宁可少拒"（避免把可答题误判为资料外）。
         */
        @DecimalMin(value = "0.0", inclusive = true, message = "rag.retrieval.refusal.rerank-threshold 必须在 [0,1] 内")
        @DecimalMax(value = "1.0", inclusive = true, message = "rag.retrieval.refusal.rerank-threshold 必须在 [0,1] 内")
        private double rerankThreshold = 0.65;

        /**
         * 余弦相似度口径阈值（VECTOR / HYBRID，或重排降级时生效）。
         * <b>区分度弱</b>：实测资料外题余弦分与可答题区间重叠，该阈值只能过滤
         * "明显不相关"，无法可靠识别资料外问题；默认 0.30 与既有 minScore 一致。
         */
        @DecimalMin(value = "0.0", inclusive = true, message = "rag.retrieval.refusal.cosine-threshold 必须在 [0,1] 内")
        @DecimalMax(value = "1.0", inclusive = true, message = "rag.retrieval.refusal.cosine-threshold 必须在 [0,1] 内")
        private double cosineThreshold = 0.30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getRerankThreshold() {
            return rerankThreshold;
        }

        public void setRerankThreshold(double rerankThreshold) {
            this.rerankThreshold = rerankThreshold;
        }

        public double getCosineThreshold() {
            return cosineThreshold;
        }

        public void setCosineThreshold(double cosineThreshold) {
            this.cosineThreshold = cosineThreshold;
        }
    }

    /**
     * Answerability 判定参数（R4）。
     *
     * <p><b>门控形态来自 Baseline 数据（docs/round4/01-Baseline分析.md），非经验值</b>：
     * 72 题专项集上 PARTIAL_EVIDENCE 与可答题的 rerank 分布完全重叠（两类都有 1.000），
     * 不存在安全的"高分直答"阈值——故只有 lowThreshold（沿用旧双阈值校准），
     * 低于它直接拒答，其余全部交 Judge。</p>
     */
    public static class Answerability {

        /** 是否启用 Answerability 判定（关闭 = 旧行为，仅对照）。 */
        private boolean enabled = true;

        /**
         * Judge 失败（超时/不可用/解析失败）时的降级策略：true=按旧阈值行为保守拒答，
         * false=放行生成（degrade to previous policy：按 rerank/cosine 阈值判定并显式标注）。
         * Baseline 数据（§7）：灰区全拒会把 34 道可答题全拒（FRR 100%），全放行回到
         * FAR 35% 的旧行为——默认 false（退回旧策略判定），误判上界=旧系统，可观测。
         */
        private boolean failClosed = false;

        /**
         * Judge 判定超时（秒）。R4.1 起 <b>语义 = Judge HTTP 请求超时</b>：
         * 该值同时作为 judgeChatModel 的 connect/read timeout——同步调用在模型侧
         * 硬超时（抛 TimeoutException），不存在"上层已放弃、底层仍在跑"的两层
         * timeout 失配；也不再使用 executor 排队，超时值纯粹代表模型执行时间。
         * Judge 只需输出短 JSON，实测 avg ≈ 2.6s。
         */
        @Min(value = 1, message = "rag.answerability.judge-timeout-seconds 必须 ≥ 1")
        private int judgeTimeoutSeconds = 15;

        /**
         * Judge 并发闸门（bulkhead，R4.1）：同时进入模型调用的判定数上限。
         * 默认 4——SSE 并发上限 32、评测串行执行，判定平均 2.6s；4 路并发下
         * 32 并发流的排队等待期望 ≈ 8 × 2.6s / 4 ≈ 5.2s，仍在 timeout 量级内，
         * 同时避免本地单实例模型被突发并发打满（吞吐随并发恶化）。容量耗尽时
         * 等待 {@link #bulkheadWaitMs} 后按 JUDGE_DEGRADED(OVERLOADED) 降级，
         * 不无限排队。
         */
        @Min(value = 1, message = "rag.answerability.max-concurrent-judges 必须 ≥ 1")
        private int maxConcurrentJudges = 4;

        /**
         * Judge 并发闸门获取许可的最长等待（毫秒）。等待耗尽仍未获得名额
         * → JUDGE_DEGRADED（原因 OVERLOADED），快速失败优于长时间排队拖垮首 token。
         */
        @Min(value = 0, message = "rag.answerability.bulkhead-wait-ms 必须 ≥ 0")
        private long bulkheadWaitMs = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFailClosed() {
            return failClosed;
        }

        public void setFailClosed(boolean failClosed) {
            this.failClosed = failClosed;
        }

        public int getJudgeTimeoutSeconds() {
            return judgeTimeoutSeconds;
        }

        public void setJudgeTimeoutSeconds(int judgeTimeoutSeconds) {
            this.judgeTimeoutSeconds = judgeTimeoutSeconds;
        }

        public int getMaxConcurrentJudges() {
            return maxConcurrentJudges;
        }

        public void setMaxConcurrentJudges(int maxConcurrentJudges) {
            this.maxConcurrentJudges = maxConcurrentJudges;
        }

        public long getBulkheadWaitMs() {
            return bulkheadWaitMs;
        }

        public void setBulkheadWaitMs(long bulkheadWaitMs) {
            this.bulkheadWaitMs = bulkheadWaitMs;
        }
    }

    /**
     * History-aware Query Rewrite 参数（R6-C）。
     *
     * <p><b>职责单一</b>：只把「依赖对话历史的当前问题」改写为可脱离历史独立理解的
     * 检索查询；Rewriter 失败（超时/模型异常/响应非法）一律回退原始问题——检索增强
     * 绝不能成为新的单点故障。它与 Answerability Judge 是两个独立的层：rewrite 失败
     * 不产生任何 Answerability 降级语义。</p>
     */
    public static class QueryRewrite {

        /** 是否启用（关闭 = 旧行为：检索永远用原始当前问题）。 */
        private boolean enabled = true;

        /**
         * rewrite 模型 HTTP connect/read timeout（秒，单一超时语义，同 Judge R4.1 设计）。
         * rewrite 只需输出一句短查询，独立于生成模型的 120s 与 Judge 的 15s。
         */
        @Min(value = 1, message = "rag.retrieval.query-rewrite.timeout-seconds 必须 ≥ 1")
        private int timeoutSeconds = 5;

        /**
         * 改写查询最大长度（字符）。超长输出视为非法（INVALID_RESPONSE）——
         * 检索查询不应是长文本，超长通常是模型跑题输出了解释段落。
         */
        @Min(value = 10, message = "rag.retrieval.query-rewrite.max-query-length 必须 ≥ 10")
        private int maxQueryLength = 200;

        /** rewrite 提示词里携带的最大历史轮数（0=只用当前问题，等于关闭 rewrite 的收益）。 */
        @Min(value = 0, message = "rag.retrieval.query-rewrite.max-history-turns 必须 ≥ 0")
        private int maxHistoryTurns = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxQueryLength() {
            return maxQueryLength;
        }

        public void setMaxQueryLength(int maxQueryLength) {
            this.maxQueryLength = maxQueryLength;
        }

        public int getMaxHistoryTurns() {
            return maxHistoryTurns;
        }

        public void setMaxHistoryTurns(int maxHistoryTurns) {
            this.maxHistoryTurns = maxHistoryTurns;
        }
    }

    /** 入库流水线参数。 */
    public static class Ingestion {

        /** 固定大小 worker 线程池大小（本机 JDK 17，不启用虚拟线程，见实现计划环境适配）。 */
        @Min(value = 1, message = "rag.ingestion.worker-threads 必须 ≥ 1")
        private int workerThreads = 2;

        @Positive
        private int maxUploadSizeMb = 50;

        /**
         * PDF 解析器选择（R3-P2；R6-D 扩展 auto）：pdfbox（默认，纯文本层提取）| mineru
         * （远端 mineru-api 服务，支持复杂版面/扫描件 OCR）| auto（PDFBox 质量探针 +
         * 确定性规则路由；探针失败/低文本/空页过多/低质量/乱码 → mineru，其余 → pdfbox）。
         */
        @jakarta.validation.constraints.Pattern(regexp = "pdfbox|mineru|auto",
                message = "rag.ingestion.pdf-parser 只支持 pdfbox|mineru|auto")
        private String pdfParser = "pdfbox";

        /** mineru-api 服务基址（pdf-parser=mineru 或 auto 路由到 mineru 时为调用目标），如 http://localhost:8000。 */
        private String mineruBaseUrl = "";

        /** mineru-api 调用超时（秒）：扫描件 OCR 较慢，长于常规 HTTP 调用。 */
        @Min(value = 1, message = "rag.ingestion.mineru-timeout-seconds 必须 ≥ 1")
        private int mineruTimeoutSeconds = 600;

        /** PDF AUTO 路由参数（R6-D；仅 pdf-parser=auto 时生效）。 */
        private PdfAuto pdfAuto = new PdfAuto();

        /** R6-D PDF AUTO 路由阈值：全部确定性规则，无 LLM 参与。 */
        public static class PdfAuto {

            /** 全文有效字符总数下限（trim 后去除空白），低于该值 → LOW_TEXT_DENSITY → mineru。 */
            @Min(value = 0, message = "rag.ingestion.pdf-auto.min-chars 必须 ≥ 0")
            private int minChars = 100;

            /** 单页平均有效字符数下限，低于该值 → LOW_TEXT_DENSITY → mineru。 */
            @Min(value = 0, message = "rag.ingestion.pdf-auto.min-chars-per-page 必须 ≥ 0")
            private int minCharsPerPage = 30;

            /** 空文本页占比上限（有效字符 < empty-page-char-threshold 的页 / 总页数），超过 → mineru。取值 [0,1]。 */
            @DecimalMin(value = "0.0", inclusive = true, message = "rag.ingestion.pdf-auto.max-empty-page-ratio 必须在 [0,1] 内")
            @DecimalMax(value = "1.0", inclusive = true, message = "rag.ingestion.pdf-auto.max-empty-page-ratio 必须在 [0,1] 内")
            private double maxEmptyPageRatio = 0.8;

            /** 判定空文本页的单页有效字符阈值。 */
            @Min(value = 0, message = "rag.ingestion.pdf-auto.empty-page-char-threshold 必须 ≥ 0")
            private int emptyPageCharThreshold = 20;

            /** 可打印字符占比下限（按 Unicode 类别，中文/英文/数字/常见标点均算可打印），低于 → LOW_TEXT_QUALITY → mineru。取值 [0,1]。 */
            @DecimalMin(value = "0.0", inclusive = true, message = "rag.ingestion.pdf-auto.min-printable-ratio 必须在 [0,1] 内")
            @DecimalMax(value = "1.0", inclusive = true, message = "rag.ingestion.pdf-auto.min-printable-ratio 必须在 [0,1] 内")
            private double minPrintableRatio = 0.90;

            /** U+FFFD replacement 字符占比上限（按非空白字符），超过 → GARBLED_TEXT → mineru。取值 [0,1]。 */
            @DecimalMin(value = "0.0", inclusive = true, message = "rag.ingestion.pdf-auto.max-replacement-char-ratio 必须在 [0,1] 内")
            @DecimalMax(value = "1.0", inclusive = true, message = "rag.ingestion.pdf-auto.max-replacement-char-ratio 必须在 [0,1] 内")
            private double maxReplacementCharRatio = 0.05;

            public int getMinChars() {
                return minChars;
            }

            public void setMinChars(int minChars) {
                this.minChars = minChars;
            }

            public int getMinCharsPerPage() {
                return minCharsPerPage;
            }

            public void setMinCharsPerPage(int minCharsPerPage) {
                this.minCharsPerPage = minCharsPerPage;
            }

            public double getMaxEmptyPageRatio() {
                return maxEmptyPageRatio;
            }

            public void setMaxEmptyPageRatio(double maxEmptyPageRatio) {
                this.maxEmptyPageRatio = maxEmptyPageRatio;
            }

            public int getEmptyPageCharThreshold() {
                return emptyPageCharThreshold;
            }

            public void setEmptyPageCharThreshold(int emptyPageCharThreshold) {
                this.emptyPageCharThreshold = emptyPageCharThreshold;
            }

            public double getMinPrintableRatio() {
                return minPrintableRatio;
            }

            public void setMinPrintableRatio(double minPrintableRatio) {
                this.minPrintableRatio = minPrintableRatio;
            }

            public double getMaxReplacementCharRatio() {
                return maxReplacementCharRatio;
            }

            public void setMaxReplacementCharRatio(double maxReplacementCharRatio) {
                this.maxReplacementCharRatio = maxReplacementCharRatio;
            }
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getMaxUploadSizeMb() {
            return maxUploadSizeMb;
        }

        public void setMaxUploadSizeMb(int maxUploadSizeMb) {
            this.maxUploadSizeMb = maxUploadSizeMb;
        }

        public String getPdfParser() {
            return pdfParser;
        }

        public void setPdfParser(String pdfParser) {
            this.pdfParser = pdfParser;
        }

        public String getMineruBaseUrl() {
            return mineruBaseUrl;
        }

        public void setMineruBaseUrl(String mineruBaseUrl) {
            this.mineruBaseUrl = mineruBaseUrl;
        }

        public int getMineruTimeoutSeconds() {
            return mineruTimeoutSeconds;
        }

        public void setMineruTimeoutSeconds(int mineruTimeoutSeconds) {
            this.mineruTimeoutSeconds = mineruTimeoutSeconds;
        }

        public PdfAuto getPdfAuto() {
            return pdfAuto;
        }

        public void setPdfAuto(PdfAuto pdfAuto) {
            this.pdfAuto = pdfAuto;
        }
    }

    /** Elasticsearch 行为参数。 */
    public static class Elasticsearch {

        /** 分块正文分析器；ES 镜像不含 IK 插件时退回 standard（实现计划总验证策略）。 */
        @NotBlank
        private String contentAnalyzer = "ik_max_word";

        public String getContentAnalyzer() {
            return contentAnalyzer;
        }

        public void setContentAnalyzer(String contentAnalyzer) {
            this.contentAnalyzer = contentAnalyzer;
        }
    }
}
