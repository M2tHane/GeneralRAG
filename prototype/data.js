/* =========================================================
   通用知识问答 RAG — 原型模拟数据（无真实后端）
   所有内容均为演示用模拟技术文档，不对应任何真实客户或生产系统。
   ========================================================= */

(function () {
  'use strict';

  // ---------- 知识库 ----------
  const knowledgeBases = [
    {
      id: 'kb-pay', name: '团队技术文档库',
      description: '支付网关、订单服务与基础组件的架构与运维文档',
      docCount: 8, chunkCount: 342, createdAt: '2026-08-02 10:24', updatedAt: '2026-09-11 16:40',
    },
    {
      id: 'kb-fault', name: '故障手册库',
      description: '历史故障复盘与应急处理手册',
      docCount: 5, chunkCount: 187, createdAt: '2026-08-15 14:02', updatedAt: '2026-09-09 11:18',
    },
    {
      id: 'kb-new', name: '新人培训资料', description: '待上传，暂无文档',
      docCount: 0, chunkCount: 0, createdAt: '2026-09-12 09:30', updatedAt: '2026-09-12 09:30',
    },
  ];

  // ---------- 文档（按知识库分组） ----------
  // status: queued | parsing | cleaning | chunking | embedding | indexing | completed | failed
  // chunkStrategy: length-overlap | structure
  const documents = {
    'kb-pay': [
      {
        id: 'doc-101', name: '支付网关服务架构说明.md', format: 'md', sizeKB: 86,
        status: 'completed', stage: null, failureReason: null, chunkCount: 96, pages: null,
        hash: 'sha256:9f21c3…a8b2', chunkStrategy: 'structure', uploadedAt: '2026-08-02 10:31', retries: 0,
      },
      {
        id: 'doc-102', name: '订单服务部署手册.pdf', format: 'pdf', sizeKB: 2140,
        status: 'completed', stage: null, failureReason: null, chunkCount: 78, pages: 24,
        hash: 'sha256:3de577…01c9', chunkStrategy: 'length-overlap', uploadedAt: '2026-08-05 15:12', retries: 1,
      },
      {
        id: 'doc-103', name: 'API 网关限流策略说明.txt', format: 'txt', sizeKB: 22,
        status: 'completed', stage: null, failureReason: null, chunkCount: 31, pages: null,
        hash: 'sha256:cc04e1…77d0', chunkStrategy: 'length-overlap', uploadedAt: '2026-08-09 09:47', retries: 0,
      },
      {
        id: 'doc-104', name: '用户中心数据迁移方案.md', format: 'md', sizeKB: 64,
        status: 'chunking', stage: '分块', failureReason: null, chunkCount: 0, pages: null,
        hash: 'sha256:b1a902…f45e', chunkStrategy: 'structure', uploadedAt: '2026-09-13 21:52', retries: 0,
        progressDone: ['解析', '清洗'], progressCurrent: '分块', progressTodo: ['向量化', '入库'],
      },
      {
        id: 'doc-105', name: '消息队列 Kafka 运维手册.pdf', format: 'pdf', sizeKB: 3860,
        status: 'embedding', stage: '向量化', failureReason: null, chunkCount: 52, pages: 41,
        hash: 'sha256:7c8fd2…9e11', chunkStrategy: 'length-overlap', uploadedAt: '2026-09-13 21:56', retries: 0,
        progressDone: ['解析', '清洗', '分块'], progressCurrent: '向量化', progressTodo: ['入库'],
      },
      {
        id: 'doc-106', name: '统一鉴权服务接口文档.pdf', format: 'pdf', sizeKB: 1580,
        status: 'failed', stage: '向量化', failureReason: 'Embedding 服务连接超时（已重试 3 次）',
        chunkCount: 0, pages: 12, hash: 'sha256:5ab3d8…2c6f', chunkStrategy: 'structure',
        uploadedAt: '2026-09-12 18:20', retries: 3,
      },
      {
        id: 'doc-107', name: '入职培训手册-扫描版.pdf', format: 'pdf', sizeKB: 8930,
        status: 'failed', stage: '解析', failureReason: '扫描件 PDF 暂不支持：未检测到文本层，请使用文本型 PDF',
        chunkCount: 0, pages: 36, hash: 'sha256:0d19aa…83b7', chunkStrategy: 'structure',
        uploadedAt: '2026-09-13 20:05', retries: 1,
      },
      {
        id: 'doc-108', name: '订单超时关单逻辑说明.md', format: 'md', sizeKB: 18,
        status: 'queued', stage: '排队中', failureReason: null, chunkCount: 0, pages: null,
        hash: 'sha256:6e42c0…d9a3', chunkStrategy: 'structure', uploadedAt: '2026-09-13 21:58', retries: 0,
      },
    ],
    'kb-fault': [
      {
        id: 'doc-201', name: '2026-06 支付回调超时故障复盘.pdf', format: 'pdf', sizeKB: 980,
        status: 'completed', stage: null, failureReason: null, chunkCount: 44, pages: 11,
        hash: 'sha256:e77b31…40af', chunkStrategy: 'structure', uploadedAt: '2026-08-15 14:20', retries: 0,
      },
      {
        id: 'doc-202', name: '数据库连接池耗尽处理手册.md', format: 'md', sizeKB: 30,
        status: 'completed', stage: null, failureReason: null, chunkCount: 27, pages: null,
        hash: 'sha256:2f8c55…71de', chunkStrategy: 'structure', uploadedAt: '2026-08-20 10:05', retries: 0,
      },
      {
        id: 'doc-203', name: 'ES 集群黄色状态排查指南.md', format: 'md', sizeKB: 24,
        status: 'completed', stage: null, failureReason: null, chunkCount: 21, pages: null,
        hash: 'sha256:8801cf…b264', chunkStrategy: 'length-overlap', uploadedAt: '2026-08-28 16:44', retries: 0,
      },
      {
        id: 'doc-204', name: 'Kafka 消息积压应急手册.txt', format: 'txt', sizeKB: 15,
        status: 'completed', stage: null, failureReason: null, chunkCount: 18, pages: null,
        hash: 'sha256:41d9e6…08cf', chunkStrategy: 'length-overlap', uploadedAt: '2026-09-02 11:31', retries: 0,
      },
      {
        id: 'doc-205', name: 'Redis 主从切换异常复盘.md', format: 'md', sizeKB: 28,
        status: 'failed', stage: '清洗', failureReason: '文件编码异常：检测到混合 GBK/UTF-8 编码，清洗失败',
        chunkCount: 0, pages: null, hash: 'sha256:a3f009…e512', chunkStrategy: 'structure',
        uploadedAt: '2026-09-10 15:37', retries: 2,
      },
    ],
    'kb-new': [],
  };

  // ---------- 分块（供详情页与引用跳转使用） ----------
  // 完整分块列表仅给关键文档，其他文档详情页可按需展示为“生成中”。
  const chunks = {
    'doc-101': [
      { id: 'doc-101-c01', seq: 1, titlePath: '支付网关服务架构说明 > 1. 系统定位', page: null, chars: 412, text: '支付网关服务（pay-gateway）是面向内部业务方的统一支付接入层，负责渠道路由、签名验签、幂等控制与回调分发。系统定位为无状态服务，可水平扩展，所有请求状态落在数据库与 Redis 中。' },
      { id: 'doc-101-c02', seq: 2, titlePath: '支付网关服务架构说明 > 2. 整体链路', page: null, chars: 486, text: '业务方发起支付请求后，网关按以下链路处理：1）参数校验与签名验签；2）渠道选择（微信/支付宝/银联）；3）生成本地支付单并落库；4）调用渠道下单接口；5）接收异步回调并校验金额与状态；6）回调业务方 notify 地址。其中第 5 步回调要求渠道重试至少 5 次，间隔 15s/30s/1m/5m/10m。' },
      { id: 'doc-101-c03', seq: 3, titlePath: '支付网关服务架构说明 > 2. 整体链路 > 2.3 回调处理', page: null, chars: 398, text: '回调处理采用"先落库、后分发"模式。收到渠道回调后首先校验签名与金额一致性，写入 callback_record 表并标记 RECEIVED，再投递内部 MQ 通知业务方。若业务方回调确认超时（默认 5 秒），进入重试队列，最多重试 8 次，之后转人工。' },
      { id: 'doc-101-c04', seq: 4, titlePath: '支付网关服务架构说明 > 3. 对外接口 > 3.2 签名验签', page: null, chars: 356, text: '对外接口统一使用 HMAC-SHA256 签名。签名串按 appId、timestamp、nonce、body 顺序拼接，密钥通过配置中心下发并每 24 小时轮换。时间戳允许偏差 ±300 秒，超时请求直接拒绝并返回 GW-4003。' },
      { id: 'doc-101-c05', seq: 5, titlePath: '支付网关服务架构说明 > 4. 幂等控制', page: null, chars: 302, text: '幂等键 = appId + outTradeNo。网关侧以幂等键建立唯一索引，重复请求 10 秒内返回原结果，超过 10 秒返回 GW-4010（重复支付请求待确认），由业务方调用查询接口对账。' },
      { id: 'doc-101-c06', seq: 6, titlePath: '支付网关服务架构说明 > 5. 依赖组件', page: null, chars: 288, text: '网关依赖：MySQL（支付单主库，按 appId 分 16 库）、Redis（幂等与频控）、Kafka（回调分发，topic: pay-callback-dispatch）、Elasticsearch（交易流水检索，保留 180 天）。' },
      { id: 'doc-101-c07', seq: 7, titlePath: '支付网关服务架构说明 > 6. 监控与告警', page: null, chars: 334, text: '核心监控指标：回调成功率（1 分钟窗口 < 99% 告警）、渠道下单 P99 延迟（> 800ms 告警）、MQ 积压量（> 5000 条告警）。告警接入值班群，P0 级别自动电话呼叫。' },
      { id: 'doc-101-c08', seq: 8, titlePath: '支付网关服务架构说明 > 7. 容量与部署', page: null, chars: 261, text: '生产环境 4 实例，单实例 4C8G，可支撑峰值 1200 TPS 下单与 3000 TPS 回调查询。扩容时需同步调整 Kafka 消费组分区数与数据库连接池上限。' },
    ],
    'doc-102': [
      { id: 'doc-102-c01', seq: 1, titlePath: '订单服务部署手册 > 1. 环境要求', page: 2, chars: 305, text: '订单服务（order-service）生产环境要求 JDK 17、8C16G 节点、独立 MySQL 实例与 Redis 集群。部署前必须确认配置中心 profile=prod 且 ES 索引模板已创建。' },
      { id: 'doc-102-c02', seq: 2, titlePath: '订单服务部署手册 > 2. 发布流程 > 2.1 灰度发布', page: 5, chars: 368, text: '发布采用灰度策略：先发布 1 个实例观察 15 分钟，核心指标（下单成功率、关单延迟）无异常后全量。回滚使用上一版本镜像，回滚后必须手动清理本地缓存队列。' },
      { id: 'doc-102-c03', seq: 3, titlePath: '订单服务部署手册 > 2. 发布流程 > 2.2 数据库变更', page: 7, chars: 342, text: '数据库变更通过 Flyway 脚本执行，禁止手工改表。大表变更必须提供回滚脚本并在预发环境演练。订单主表 any DDL 需申请 DBA 工单，窗口期避开每日 20:00-22:00 支付高峰。' },
      { id: 'doc-102-c04', seq: 4, titlePath: '订单服务部署手册 > 3. 超时关单任务', page: 12, chars: 320, text: '超时关单由 XXL-Job 每 5 分钟调度，扫描创建超 15 分钟未支付的订单。执行时先查询渠道网关确认未支付，再执行关单。若渠道侧已支付但本地未收到回调，走补偿流程而不是直接关单。' },
      { id: 'doc-102-c05', seq: 5, titlePath: '订单服务部署手册 > 4. 常见部署问题', page: 18, chars: 288, text: '启动失败常见原因：1）ES 连接失败（检查索引模板与账号）；2）Redis 集群状态异常；3）配置中心密钥过期。日志关键字检索：ORDER-BOOT-FAIL。' },
    ],
    'doc-201': [
      { id: 'doc-201-c01', seq: 1, titlePath: '2026-06 支付回调超时故障复盘 > 1. 故障概述', page: 1, chars: 420, text: '2026-06-18 14:23 至 15:07，支付回调通知成功率由 99.6% 降至 91.2%，约 4200 笔支付回调延迟超过 1 分钟。影响范围：订单服务支付状态更新延迟，无资金损失。' },
      { id: 'doc-201-c02', seq: 2, titlePath: '2026-06 支付回调超时故障复盘 > 2. 时间线', page: 3, chars: 466, text: '14:23 网关回调线程池队列开始积压；14:31 监控告警回调成功率跌破 99%；14:40 值班定位为下游订单服务接口 P99 升高导致回调确认超时；14:52 订单服务扩容 2 实例；15:07 成功率恢复。根因：订单服务慢 SQL 导致回调确认接口超时。' },
      { id: 'doc-201-c03', seq: 3, titlePath: '2026-06 支付回调超时故障复盘 > 3. 根因分析', page: 5, chars: 438, text: '直接原因：订单服务 payment_status 更新语句未走索引（隐式类型转换），执行时间从 8ms 恶化到 1.2s。深层原因：回调确认接口未做超时降级，网关回调线程池默认队列无界，放大了故障影响。' },
      { id: 'doc-201-c04', seq: 4, titlePath: '2026-06 支付回调超时故障复盘 > 4. 改进措施', page: 8, chars: 372, text: '改进：1）订单服务慢 SQL 修复并上线索引守护脚本；2）网关回调线程池改为有界队列 + 快速失败，超限进重试队列；3）回调确认接口增加 800ms 超时降级，降级期间直接落库待重试；4）增加"回调积压量"专项监控。' },
      { id: 'doc-201-c05', seq: 5, titlePath: '2026-06 支付回调超时故障复盘 > 5. 遗留风险', page: 10, chars: 258, text: '遗留风险：回调重试队列仍为单实例消费，极端情况下消费能力不足。计划在下一迭代引入分区并行消费（对应 Kafka 运维手册 4.2 节）。' },
    ],
    'doc-202': [
      { id: 'doc-202-c01', seq: 1, titlePath: '数据库连接池耗尽处理手册 > 1. 现象识别', page: null, chars: 356, text: '连接池耗尽典型现象：应用日志出现 "Connection is not available, request timed out after 30000ms"，接口大量 503，MySQL 侧 show processlist 可见大量 Sleep 连接。' },
      { id: 'doc-202-c02', seq: 2, titlePath: '数据库连接池耗尽处理手册 > 2. 应急步骤', page: null, chars: 402, text: '应急步骤：1）定位占用连接的会话：SELECT * FROM information_schema.processlist WHERE command="Sleep" AND time>60；2）kill 长事务会话释放连接；3）如无法快速定位，滚动重启应用实例；4）恢复后检查慢查询日志确认根因。' },
      { id: 'doc-202-c03', seq: 3, titlePath: '数据库连接池耗尽处理手册 > 3. 常见根因', page: null, chars: 388, text: '常见根因：1）慢 SQL 占用连接（最常见，先查慢日志）；2）事务范围过大，事务内调用外部接口；3）连接池 maxPoolSize 配置低于并发需求；4）连接泄漏，流未关闭。HikariCP 建议 leakDetectionThreshold 设为 60000 辅助定位。' },
    ],
    'doc-203': [
      { id: 'doc-203-c01', seq: 1, titlePath: 'ES 集群黄色状态排查指南 > 1. 状态含义', page: null, chars: 322, text: '集群 yellow 表示主分片全部可用但有副本分片未分配，读写不受影响但冗余能力下降。red 表示存在未分配的主分片，相关索引不可写入。' },
      { id: 'doc-203-c02', seq: 2, titlePath: 'ES 集群黄色状态排查指南 > 2. 排查步骤', page: null, chars: 418, text: '排查步骤：1）GET _cluster/health 查看未分配分片数；2）GET _cat/shards?v&h=index,shard,prirep,state,unassigned.reason 定位原因；3）常见原因为磁盘水位超限（flood_stage 85%）或节点数少于副本数；4）磁盘问题清理或调大 watermarks 后用 _cluster/reroute 重试分配。' },
      { id: 'doc-203-c03', seq: 3, titlePath: 'ES 集群黄色状态排查指南 > 3. 预防', page: null, chars: 268, text: '预防措施：索引按月滚动并保留 180 天自动删除；设置 disk watermarks 为 75%/85%/90%；监控接入 _cluster/health 每分钟采集，yellow 持续 10 分钟告警。' },
    ],
    'doc-204': [
      { id: 'doc-204-c01', seq: 1, titlePath: 'Kafka 消息积压应急手册 > 1. 判断积压', page: null, chars: 296, text: '通过 kafka-consumer-groups.sh --describe 查看 LAG。单分区 LAG 持续超过 5000 或 LAG 随时间增长即为积压。pay-callback-dispatch topic 生产告警阈值为 5000。' },
      { id: 'doc-204-c02', seq: 2, titlePath: 'Kafka 消息积压应急手册 > 2. 处理手段', page: null, chars: 430, text: '处理手段按优先级：1）扩容消费者（不超过分区数）；2）临时增加分区（需要 topic 空闲，生产慎用）；3）确认消费逻辑无慢调用（外部接口超时是最常见原因）；4）极端情况启用旁路消费：跳过非关键消息并落库补偿，禁止直接重置 offset 跳过支付回调类消息。' },
    ],
  };

  // ---------- 会话与问答 ----------
  const sessions = [
    {
      id: 'sess-01', kbId: 'kb-pay', title: '支付回调超时排查', createdAt: '2026-09-13 20:14',
      messages: [
        { role: 'user', content: '支付回调超时一般怎么排查？' },
        {
          role: 'assistant', content: '根据文档记录，支付回调超时可按以下路径排查：\n\n1. 先看网关侧：回调线程池队列是否积压、回调成功率是否跌破 99%（1 分钟窗口即告警）。\n2. 再看下游：回调确认接口（订单服务）的 P99 延迟是否升高，慢 SQL 是常见诱因——2026-06 的故障就是 payment_status 更新语句未走索引导致确认接口超时。\n3. 最后看 MQ：回调分发 topic（pay-callback-dispatch）积压量是否超过 5000 条。\n\n若确认是下游变慢，应急上可对回调确认接口执行 800ms 超时降级，降级期间回调直接落库进入重试队列。',
          citations: [
            { chunkId: 'doc-101-c07', docId: 'doc-101', docName: '支付网关服务架构说明.md', titlePath: '… > 6. 监控与告警', page: null },
            { chunkId: 'doc-201-c03', docId: 'doc-201', docName: '2026-06 支付回调超时故障复盘.pdf', titlePath: '… > 3. 根因分析', page: 5 },
          ],
        },
        { role: 'user', content: '那回调重试策略是什么？' },
        {
          role: 'assistant', content: '回调重试分两层：\n\n1. 渠道 → 网关：要求渠道至少重试 5 次，间隔 15s / 30s / 1m / 5m / 10m。\n2. 网关 → 业务方：回调确认超时（默认 5 秒）进入重试队列，最多重试 8 次，之后转人工处理。\n\n收到回调后先落库（callback_record 标记 RECEIVED）再分发，因此重试期间不会丢失原始回调记录。',
          citations: [
            { chunkId: 'doc-101-c02', docId: 'doc-101', docName: '支付网关服务架构说明.md', titlePath: '… > 2. 整体链路', page: null },
            { chunkId: 'doc-101-c03', docId: 'doc-101', docName: '支付网关服务架构说明.md', titlePath: '… > 2.3 回调处理', page: null },
          ],
        },
      ],
    },
    {
      id: 'sess-02', kbId: 'kb-pay', title: '幂等与重复支付', createdAt: '2026-09-12 11:02',
      messages: [
        { role: 'user', content: '重复发起支付请求会怎样？' },
        {
          role: 'assistant', content: '网关按幂等键（appId + outTradeNo）控制重复支付：\n\n- 10 秒内的重复请求直接返回原结果；\n- 超过 10 秒的重复请求返回 GW-4010（重复支付请求待确认），由业务方调用查询接口对账。\n\n幂等键在网关侧建立唯一索引，防重不依赖业务方实现。',
          citations: [
            { chunkId: 'doc-101-c05', docId: 'doc-101', docName: '支付网关服务架构说明.md', titlePath: '… > 4. 幂等控制', page: null },
          ],
        },
      ],
    },
    {
      id: 'sess-03', kbId: 'kb-fault', title: 'Kafka 积压处理', createdAt: '2026-09-10 16:40',
      messages: [
        { role: 'user', content: 'Kafka 消息积压了怎么处理？' },
        {
          role: 'assistant', content: '先确认积压程度（单分区 LAG 持续 > 5000 或持续增长），然后按优先级处理：\n\n1. 扩容消费者（不超过分区数）；\n2. 临时增加分区（topic 空闲时，生产慎用）；\n3. 排查消费逻辑慢调用（外部接口超时最常见）；\n4. 极端情况启用旁路消费跳过非关键消息并落库补偿。\n\n注意：支付回调类消息禁止通过重置 offset 跳过。',
          citations: [
            { chunkId: 'doc-204-c02', docId: 'doc-204', docName: 'Kafka 消息积压应急手册.txt', titlePath: '… > 2. 处理手段', page: null },
          ],
        },
      ],
    },
  ];

  // ---------- 预置问答场景（供“重新提问/示例问题”） ----------
  const sampleQuestions = {
    'kb-pay': [
      '支付回调超时一般怎么排查？',
      '签名验签的时间戳允许多大偏差？',
      '重复发起支付请求会怎样？',
      '年假有几天？（资料外问题演示）',
      '网关的容量上限是多少？',
    ],
    'kb-fault': [
      '数据库连接池耗尽怎么应急？',
      'ES 集群 yellow 状态如何排查？',
      'Kafka 积压为什么不能直接重置 offset？',
      '公司报销流程是什么？（资料外问题演示）',
    ],
  };

  // ---------- 检索调试场景 ----------
  const debugScenarios = {
    'kb-pay': {
      config: { topK: 5, minScore: 0.30, vectorDim: 1024, metric: 'cosine', chunkStrategy: '按标题结构切分（最大 512 字符）', embeddingModel: 'bge-m3（外部服务，可配置）', generatorModel: 'GLM-4.7（外部服务，可配置）' },
      runs: {
        '支付回调超时一般怎么排查？': {
          retrieved: true, hits: [
            { rank: 1, score: 0.874, docId: 'doc-101', docName: '支付网关服务架构说明.md', chunkId: 'doc-101-c07', titlePath: '支付网关服务架构说明 > 6. 监控与告警', page: null, chars: 334, text: '核心监控指标：回调成功率（1 分钟窗口 < 99% 告警）、渠道下单 P99 延迟（> 800ms 告警）、MQ 积压量（> 5000 条告警）。告警接入值班群，P0 级别自动电话呼叫。' },
            { rank: 2, score: 0.812, docId: 'doc-201', docName: '2026-06 支付回调超时故障复盘.pdf', chunkId: 'doc-201-c03', titlePath: '… > 3. 根因分析', page: 5, chars: 438, text: '直接原因：订单服务 payment_status 更新语句未走索引（隐式类型转换），执行时间从 8ms 恶化到 1.2s。深层原因：回调确认接口未做超时降级……' },
            { rank: 3, score: 0.795, docId: 'doc-101', docName: '支付网关服务架构说明.md', chunkId: 'doc-101-c03', titlePath: '… > 2.3 回调处理', page: null, chars: 398, text: '回调处理采用"先落库、后分发"模式……若业务方回调确认超时（默认 5 秒），进入重试队列，最多重试 8 次，之后转人工。' },
            { rank: 4, score: 0.641, docId: 'doc-201', docName: '2026-06 支付回调超时故障复盘.pdf', chunkId: 'doc-201-c04', titlePath: '… > 4. 改进措施', page: 8, chars: 372, text: '改进：……回调确认接口增加 800ms 超时降级，降级期间直接落库待重试……' },
            { rank: 5, score: 0.522, docId: 'doc-101', docName: '支付网关服务架构说明.md', chunkId: 'doc-101-c02', titlePath: '… > 2. 整体链路', page: null, chars: 486, text: '……第 5 步回调要求渠道重试至少 5 次，间隔 15s/30s/1m/5m/10m。' },
          ],
          timings: { embedMs: 62, searchMs: 38, totalMs: 104, llmFirstTokenMs: 720, llmTotalMs: 3120 },
          contextSent: '【文档 1】支付网关服务架构说明.md > 6. 监控与告警\n核心监控指标：回调成功率（1 分钟窗口 < 99% 告警）……\n\n【文档 2】2026-06 支付回调超时故障复盘.pdf（第 5 页）> 3. 根因分析\n直接原因：订单服务 payment_status 更新语句未走索引……\n\n【文档 3】支付网关服务架构说明.md > 2.3 回调处理\n回调处理采用"先落库、后分发"模式……',
          issues: null,
        },
        '年假有几天？': {
          retrieved: true, hits: [
            { rank: 1, score: 0.221, docId: 'doc-101', docName: '支付网关服务架构说明.md', chunkId: 'doc-101-c08', titlePath: '… > 7. 容量与部署', page: null, chars: 261, text: '生产环境 4 实例，单实例 4C8G……', note: '最高分低于 minScore 0.30，全部命中被过滤' },
          ],
          timings: { embedMs: 48, searchMs: 31, totalMs: 82, llmFirstTokenMs: null, llmTotalMs: null },
          contextSent: '（无候选超过 minScore=0.30，未构造上下文）',
          issues: '未召回：最高相似度 0.221 低于阈值 0.30。该问题超出知识库范围，应触发"无法依据当前资料回答"。',
        },
      },
    },
    'kb-fault': {
      config: { topK: 5, minScore: 0.30, vectorDim: 1024, metric: 'cosine', chunkStrategy: '按长度切分 + 80 字符重叠', embeddingModel: 'bge-m3（外部服务，可配置）', generatorModel: 'GLM-4.7（外部服务，可配置）' },
      runs: {
        '数据库连接池耗尽怎么应急？': {
          retrieved: true, hits: [
            { rank: 1, score: 0.893, docId: 'doc-202', docName: '数据库连接池耗尽处理手册.md', chunkId: 'doc-202-c02', titlePath: '… > 2. 应急步骤', page: null, chars: 402, text: '应急步骤：1）定位占用连接的会话……4）恢复后检查慢查询日志确认根因。' },
            { rank: 2, score: 0.847, docId: 'doc-202', docName: '数据库连接池耗尽处理手册.md', chunkId: 'doc-202-c03', titlePath: '… > 3. 常见根因', page: null, chars: 388, text: '常见根因：1）慢 SQL 占用连接……HikariCP 建议 leakDetectionThreshold 设为 60000 辅助定位。' },
            { rank: 3, score: 0.783, docId: 'doc-202', docName: '数据库连接池耗尽处理手册.md', chunkId: 'doc-202-c01', titlePath: '… > 1. 现象识别', page: null, chars: 356, text: '连接池耗尽典型现象：应用日志出现 "Connection is not available, request timed out after 30000ms"……' },
            { rank: 4, score: 0.512, docId: 'doc-201', docName: '2026-06 支付回调超时故障复盘.pdf', chunkId: 'doc-201-c03', titlePath: '… > 3. 根因分析', page: 5, chars: 438, text: '直接原因：订单服务 payment_status 更新语句未走索引……' },
          ],
          timings: { embedMs: 55, searchMs: 36, totalMs: 95, llmFirstTokenMs: 690, llmTotalMs: 2870 },
          contextSent: '【文档 1】数据库连接池耗尽处理手册.md > 2. 应急步骤\n……\n\n【文档 2】数据库连接池耗尽处理手册.md > 3. 常见根因\n……',
          issues: null,
        },
      },
    },
  };

  /* =========================================================
     第二轮 · 效果评测
     命中判定为分块级（数据集 v2）；指标含 Hit@K / Recall@K / MRR /
     拒答正确率 / 耗时分位；运行快照记录语料指纹供可比性守卫使用。
     ========================================================= */

  // ---------- 7 种 BadCase 归因类型（顺序即展示顺序） ----------
  const attributionMeta = {
    RETRIEVAL_MISS:     { label: '未召回',       hint: '正确分块未进入候选：查分块策略 / embedding / 查询表述' },
    RANK_DROP:          { label: '排名下降',     hint: '正确分块在候选内但名次下降：查融合权重或重排' },
    FUSED_BUT_FILTERED: { label: '被阈值卡掉',   hint: '进了候选但低于 minScore：阈值校准' },
    CONTEXT_TRUNCATED:  { label: '上下文截断',   hint: '候选进了但未进上下文：max-context-chars 预算' },
    CHUNK_BOUNDARY:     { label: '分块边界切断', hint: '答案跨分块被切断：分块策略' },
    REFUSAL_ERROR:      { label: '拒答误判',     hint: '该拒没拒 / 不该拒却拒：拒答规则' },
    GENERATION_ERROR:   { label: '生成错误',     hint: '证据齐备但回答错误：生成侧，与检索无关' },
  };

  // ---------- 数据集与版本（v1 文档级锚点 / v2 分块级锚点） ----------
  // v1 不可变；v2 为第二轮按真实 titlePath 重写 evidence 后的新版本。
  const evalDatasets = [
    {
      id: 'ds-test', name: '独立测试集', datasetType: 'TEST',
      versions: [
        { versionNo: 1, itemCount: 12, anchorMode: '文档级（docName 匹配）', createdAt: '2026-09-14 18:29' },
        { versionNo: 2, itemCount: 12, anchorMode: '分块级（titlePath 精确锚点）', createdAt: '2026-09-14 20:40' },
      ],
    },
    {
      id: 'ds-tuning', name: '调优集', datasetType: 'TUNING',
      versions: [
        { versionNo: 1, itemCount: 6, anchorMode: '文档级（docName 匹配）', createdAt: '2026-09-14 18:29' },
      ],
    },
  ];

  // ---------- 指标元信息（运行详情/对比页的标签与 tooltip） ----------
  const runMetricMeta = {
    hitAt1: { label: 'Hit@1', desc: '正确答案分块排在第 1 位的题目占比' },
    hitAt3: { label: 'Hit@3', desc: '正确答案分块排在前 3 位的题目占比' },
    hitAt5: { label: 'Hit@5', desc: '正确答案分块排在前 5 位的题目占比' },
    recallAt5: { label: 'Recall@5', desc: '前 5 条候选中覆盖到的正确答案分块比例' },
    mrr: { label: 'MRR', desc: '正确答案分块排名倒数的平均值' },
    refusalAccuracy: { label: '拒答正确率', desc: '资料外题正确拒答、资料内题未误拒的比例' },
    p50Ms: { label: '耗时 p50', desc: '单题端到端耗时中位数' },
    p95Ms: { label: '耗时 p95', desc: '单题端到端耗时 95 分位' },
    maxMs: { label: '耗时 max', desc: '最慢单题耗时' },
    avgLatencyMs: { label: '平均耗时', desc: '单题端到端耗时平均值' },
  };

  // ---------- 检索模式展示名 ----------
  const retrievalModes = {
    VECTOR: { label: '向量检索', short: '向量' },
    HYBRID: { label: '混合检索（BM25 + 向量 · RRF 融合）', short: '混合' },
    HYBRID_RERANK: { label: '混合检索 + 重排', short: '混合+重排' },
  };

  // ---------- 资料外问题（OUT_OF_KB）演示：第二轮语义 = 拒答即清空引用 ----------
  // 检索确实返回了高于阈值的命中（第一轮问题根源），但模型判定证据不足后
  // 按规则清空 citations；这些低相关命中只在「检索调试」页可见。
  const refusalDemo = {
    question: 'Redis 的 RDB 和 AOF 能一起用吗？',
    kbId: 'kb-pay',
    hits: [
      { rank: 1, chunkId: '11875e3c-9d5b-4a7e-8c31-6f0d2b4a91e7-c0008', docName: 'redis-persistence.md', titlePath: 'redis-persistence.md > 1.6. 6. RDB 与 AOF 的相互作用', fixedTitlePath: 'redis-persistence.md > 1.6. RDB 与 AOF 的相互作用', page: null, score: 0.773, passedThreshold: true, note: '标题编号重复（第一轮遗留），内容相关但表述不完整' },
      { rank: 2, chunkId: '11875e3c-9d5b-4a7e-8c31-6f0d2b4a91e7-c0001', docName: 'redis-persistence.md', titlePath: 'redis-persistence.md > 1.1. 1. 持久化方式概览', fixedTitlePath: 'redis-persistence.md > 1. 持久化方式概览', page: null, score: 0.741, passedThreshold: true, note: '仅列出持久化方式，未给出组合使用的结论' },
      { rank: 3, chunkId: '11875e3c-9d5b-4a7e-8c31-6f0d2b4a91e7-c0006', docName: 'redis-persistence.md', titlePath: 'redis-persistence.md > 1.5. 5. 如何选择', fixedTitlePath: 'redis-persistence.md > 5. 如何选择', page: null, score: 0.716, passedThreshold: true, note: '提到同时使用 RDB 和 AOF，但属于选择建议，未回答“能否一起用”的机制问题' },
      { rank: 4, chunkId: '11875e3c-9d5b-4a7e-8c31-6f0d2b4a91e7-c0002', docName: 'redis-persistence.md', titlePath: 'redis-persistence.md > 1.2. 2. RDB 的优缺点', fixedTitlePath: 'redis-persistence.md > 2. RDB 的优缺点', page: null, score: 0.688, passedThreshold: true, note: '仅 RDB 优缺点，与问题相关性最低' },
    ],
    threshold: 0.30,
    answer: '当前资料不足以回答该问题。',
    citations: [],
    debugNote: '本次检索有 4 条命中高于 minScore=0.30，但模型判定证据不足以支撑回答，按规则清空引用；低相关命中仅在检索调试页可见。',
  };

  // ---------- 标题路径编号修复对照（R2-T1） ----------
  const titlePathFixDemo = {
    before: 'redis-persistence.md > 1.6. 6. RDB 与 AOF 的相互作用',
    after: 'redis-persistence.md > 1.6. RDB 与 AOF 的相互作用',
    note: '第二轮修复：不再把原文标题里已有的编号重复拼接。',
  };

  /* =========================================================
     评测题集与运行
     seq 1..12 在两次运行间 1:1 对齐；evidence 为分块级锚点（v2）。
     运行指标由 items 派生（见 buildRun），避免手写数字与明细不一致。
     ========================================================= */

  // ---------- 12 道评测题（seq 为跨运行对齐键） ----------
  // answerable=false 的 2 题不进 Hit@K 分母，只进拒答正确率。
  const evalQuestionBank = [
    {
      seq: 1, category: 'DIRECT', answerable: true,
      question: '支付回调超时一般怎么排查？',
      referenceAnswer: '按「网关 → 下游订单服务 → MQ」三段排查：先看回调线程池队列是否积压、回调成功率是否跌破 99%（1 分钟窗口告警）；再看回调确认接口 P99 是否升高（慢 SQL 是常见诱因）；最后看回调分发 topic 积压量。应急可对回调确认接口做 800ms 超时降级。',
      evidence: [
        { docName: '支付网关服务架构说明.md', titlePath: '支付网关服务架构说明 > 6. 监控与告警', page: null },
        { docName: '2026-06 支付回调超时故障复盘.pdf', titlePath: '2026-06 支付回调超时故障复盘 > 3. 根因分析', page: 5 },
      ],
      answer: '可以从三个位置依次排查：\n\n1. 网关侧：回调线程池队列是否积压，回调成功率是否跌破 99%（1 分钟窗口即告警）。\n2. 下游订单服务：回调确认接口 P99 延迟是否升高；2026-06 的故障即 payment_status 更新未走索引导致确认超时。\n3. MQ：回调分发 topic 的积压量是否超过 5000 条。\n\n确认是下游变慢时，应急可对回调确认接口执行 800ms 超时降级。',
    },
    {
      seq: 2, category: 'DIRECT', answerable: true,
      question: '回调重试策略是什么？渠道要重试几次？',
      referenceAnswer: '渠道 → 网关要求至少重试 5 次，间隔 15s/30s/1m/5m/10m；网关 → 业务方回调确认超时默认 5 秒，进入重试队列最多重试 8 次，之后转人工。',
      evidence: [{ docName: '支付网关服务架构说明.md', titlePath: '支付网关服务架构说明 > 2. 整体链路', page: null }],
      answer: '分两层：渠道到网关要求至少重试 5 次，间隔为 15s / 30s / 1m / 5m / 10m；网关到业务方的回调确认超时默认 5 秒，超时进入重试队列，最多重试 8 次后转人工。',
    },
    {
      seq: 3, category: 'DIRECT', answerable: true,
      question: '网关对外接口的签名时间戳允许多大偏差？',
      referenceAnswer: '时间戳允许偏差 ±300 秒，超时请求直接拒绝并返回 GW-4003。',
      evidence: [{ docName: '支付网关服务架构说明.md', titlePath: '支付网关服务架构说明 > 3. 对外接口 > 3.2 签名验签', page: null }],
      answer: '签名串按 appId、timestamp、nonce、body 顺序拼接，使用 HMAC-SHA256；时间戳允许偏差 ±300 秒，超出直接拒绝并返回 GW-4003。密钥每 24 小时轮换。',
    },
    {
      seq: 4, category: 'TERM_VARIATION', answerable: true,
      question: '同一个单子重复请求会不会重复下单？防重靠什么？',
      referenceAnswer: '不会。网关以 appId + outTradeNo 作为幂等键建立唯一索引；10 秒内重复请求返回原结果，超过 10 秒返回 GW-4010，由业务方查询接口对账。',
      evidence: [{ docName: '支付网关服务架构说明.md', titlePath: '支付网关服务架构说明 > 4. 幂等控制', page: null }],
      answer: '不会重复下单。网关侧以「幂等键 = appId + outTradeNo」建立唯一索引，防重不依赖业务方实现：10 秒内的重复请求直接返回原结果；超过 10 秒的返回 GW-4010（重复支付请求待确认），由业务方调用查询接口对账。',
    },
    {
      seq: 5, category: 'DIRECT', answerable: true,
      question: '超时关单任务多久执行一次？扫什么条件？',
      referenceAnswer: '由 XXL-Job 每 5 分钟调度，扫描创建超过 15 分钟未支付的订单；执行前先查渠道确认未支付，渠道已支付但本地未收到回调时走补偿流程而不是直接关单。',
      evidence: [{ docName: '订单服务部署手册.pdf', titlePath: '订单服务部署手册 > 3. 超时关单任务', page: 12 }],
      answer: '超时关单由 XXL-Job 每 5 分钟调度一次，扫描创建超过 15 分钟仍未支付的订单。执行时先查询渠道网关确认未支付再关单；若渠道侧已支付但本地未收到回调，走补偿流程，不直接关单。',
    },
    {
      seq: 6, category: 'DIRECT', answerable: true,
      question: '数据库连接池耗尽怎么应急？',
      referenceAnswer: '定位占用连接的会话（processlist 中 Sleep 且 time>60）→ kill 长事务释放连接 → 无法快速定位时滚动重启 → 恢复后查慢查询日志确认根因。',
      evidence: [
        { docName: '数据库连接池耗尽处理手册.md', titlePath: '数据库连接池耗尽处理手册 > 2. 应急步骤', page: null },
        { docName: '数据库连接池耗尽处理手册.md', titlePath: '数据库连接池耗尽处理手册 > 3. 常见根因', page: null },
      ],
      answer: '应急步骤：1）用 information_schema.processlist 定位长时间 Sleep 的连接；2）kill 长事务会话释放连接；3）无法快速定位时滚动重启应用实例；4）恢复后检查慢查询日志确认根因。HikariCP 建议将 leakDetectionThreshold 设为 60000 辅助定位。',
    },
    {
      seq: 7, category: 'DIRECT', answerable: true,
      question: 'ES 集群 yellow 状态如何排查？',
      referenceAnswer: '先看 _cluster/health 的未分配分片数，再用 _cat/shards 看 unassigned.reason；常见原因是磁盘水位超限（flood_stage 85%）或节点数少于副本数；处理后用 _cluster/reroute 重试分配。',
      evidence: [{ docName: 'ES 集群黄色状态排查指南.md', titlePath: 'ES 集群黄色状态排查指南 > 2. 排查步骤', page: null }],
      answer: 'yellow 表示主分片全部可用但存在未分配副本，读写不受影响、冗余能力下降。排查：1）GET _cluster/health 看未分配分片数；2）GET _cat/shards?v&h=index,shard,prirep,state,unassigned.reason 定位原因；3）常见为磁盘水位超限（flood_stage 85%）或节点数少于副本数；4）磁盘问题清理或调大 watermarks 后用 _cluster/reroute 重试分配。',
    },
    {
      seq: 8, category: 'CONFUSABLE', answerable: true,
      question: 'Kafka 积压直接重置 offset 跳到最新行不行？',
      referenceAnswer: '不行（至少不能无条件这么干）。重置 offset 会永久跳过未消费消息，支付回调类消息会造成丢单；应优先扩容消费者、排查消费逻辑慢调用，极端情况用旁路消费落库补偿。',
      evidence: [{ docName: 'Kafka 消息积压应急手册.txt', titlePath: 'Kafka 消息积压应急手册 > 2. 处理手段', page: null }],
      answer: '不建议，至少不能无条件重置。重置 offset 会永久跳过未消费的消息，对支付回调类消息等于丢单。按优先级应先扩容消费者（不超过分区数）、确认消费逻辑没有慢调用（外部接口超时最常见）、必要时临时增加分区；极端情况启用旁路消费跳过非关键消息并落库补偿，但禁止直接重置 offset 跳过支付回调类消息。',
    },
    {
      seq: 9, category: 'FOLLOW_UP', answerable: true,
      question: '那回调确认超时之后呢？',
      referenceAnswer: '回调确认超时（默认 5 秒）后进入重试队列，最多重试 8 次，之后转人工处理；因采用「先落库、后分发」，重试期间不会丢失原始回调记录。',
      evidence: [
        { docName: '支付网关服务架构说明.md', titlePath: '支付网关服务架构说明 > 2. 整体链路 > 2.3 回调处理', page: null },
        { docName: '支付网关服务架构说明.md', titlePath: '支付网关服务架构说明 > 2. 整体链路', page: null },
      ],
      answer: '回调确认超时（默认 5 秒）后进入重试队列，最多重试 8 次，之后转人工处理。由于回调处理是「先落库、后分发」，收到回调即写入 callback_record 并标记 RECEIVED，重试期间不会丢失原始记录。',
    },
    {
      seq: 10, category: 'DIRECT', answerable: true,
      question: '2026-06 那次回调超时故障的根因是什么？',
      referenceAnswer: '直接原因是订单服务 payment_status 更新语句未走索引（隐式类型转换），执行时间从 8ms 恶化到 1.2s；深层原因是回调确认接口未做超时降级、网关回调线程池队列无界，放大了影响。',
      evidence: [
        { docName: '2026-06 支付回调超时故障复盘.pdf', titlePath: '2026-06 支付回调超时故障复盘 > 3. 根因分析', page: 5 },
        { docName: '2026-06 支付回调超时故障复盘.pdf', titlePath: '2026-06 支付回调超时故障复盘 > 4. 改进措施', page: 8 },
      ],
      answer: '直接原因：订单服务 payment_status 更新语句未走索引（隐式类型转换），执行时间从 8ms 恶化到 1.2s。深层原因：回调确认接口未做超时降级，且网关回调线程池使用无界队列，放大了故障影响。改进措施包括慢 SQL 修复、线程池改有界队列 + 快速失败、回调确认接口增加 800ms 超时降级。',
    },
    {
      seq: 11, category: 'OUT_OF_KB', answerable: false,
      question: '怎么用 Git rebase 整理提交历史？',
      referenceAnswer: '（资料外问题）团队技术文档库与故障手册库均不包含 Git 使用说明，正确行为是拒答。',
      evidence: [],
      answer: '当前资料不足以回答该问题。知识库中的文档涵盖支付网关、订单服务与基础组件的架构与运维，未包含 Git 操作说明。',
    },
    {
      seq: 12, category: 'OUT_OF_KB', answerable: false,
      question: 'MySQL 主从复制延迟怎么解决？',
      referenceAnswer: '（资料外问题）库内没有 MySQL 主从复制相关文档，正确行为是拒答。',
      evidence: [],
      answer: '当前资料不足以回答该问题。库内仅涉及连接池耗尽的应急处理，未覆盖 MySQL 主从复制延迟的解决方案。',
    },
  ];

  // ---------- 5 次运行（+1 次失败运行）的配置与检索计划 ----------
  // ranks: seq → 首个正确分块在 topK 内的名次（null = 未召回 / 不适用）
  // extras: seq → 第二个正确分块的名次（null = 该分块未进 top-5，用于 Recall@5）
  const evalRunSeeds = [
    {
      id: 'run-20260914-01', status: 'COMPLETED',
      datasetId: 'ds-test', datasetName: '独立测试集', datasetType: 'TEST', datasetVersionNo: 1,
      kbId: 'kb-pay', kbName: '团队技术文档库', itemCount: 12,
      createdAt: '2026-09-14 18:31', finishedAt: '2026-09-14 18:36', failureReason: null,
      title: '基线-向量检索',
      seed: 1101,
      config: {
        retrievalMode: 'VECTOR', vectorTopK: 5, bm25TopK: null, rrfK: null,
        rerankEnabled: false, rerankModel: null, rerankDegraded: false,
        topK: 5, minScore: 0.30, embeddingModel: 'bge-m3', embeddingDimensions: 1024,
        chunkStrategy: 'STRUCTURE/800', chunkCount: 26,
      },
      ranks: { 1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: 2, 7: 1, 8: 3, 9: 4, 10: 1 },
      extras: { 1: 5, 6: null, 8: null, 9: null, 10: null },
      latency: [2140, 2260, 2380, 2290, 2410, 2520, 2360, 2480, 3600, 2600, 1980, 11200],
      retrieval: [96, 104, 88, 112, 92, 108, 118, 101, 148, 126, 74, 142],
      refusalErrors: { 9: '不该拒却拒：正确分块就在第 4 名，证据充分却回复资料不足', 12: '该拒没拒：资料外问题仍给出回答并附引用' },
      attributions: {
        8: { type: 'RANK_DROP', note: '正确分块在第 3 名；纯向量检索对“重置 offset”一类术语不敏感，名次被通用描述块挤出前 2。' },
        9: { type: 'REFUSAL_ERROR', note: 'FOLLOW_UP 题检索到证据但拒答；拒答规则未区分“检索弱”与“资料外”。' },
        12: { type: 'REFUSAL_ERROR', note: 'OUT_OF_KB 题未拒答，且附了 2 条不相关引用。' },
      },
      reviews: {
        9: { tag: 'MISSING_EVIDENCE', note: '证据在第 4 名，属检索弱导致的误拒。', processed: false },
        12: { tag: 'WRONG_SOURCE', note: '资料外问题不应返回引用。', processed: false },
      },
      note: '第一轮基线：命中判定退化为文档级（数据集 v1 锚点），分块级指标无区分度，已标注为退役基线。',
    },
    {
      id: 'run-20260914-02', status: 'COMPLETED',
      datasetId: 'ds-test', datasetName: '独立测试集', datasetType: 'TEST', datasetVersionNo: 2,
      kbId: 'kb-pay', kbName: '团队技术文档库', itemCount: 12,
      createdAt: '2026-09-14 20:44', finishedAt: '2026-09-14 20:49', failureReason: null,
      title: '混合检索（BM25 + RRF）',
      seed: 2202,
      config: {
        retrievalMode: 'HYBRID', vectorTopK: 30, bm25TopK: 30, rrfK: 60,
        rerankEnabled: false, rerankModel: null, rerankDegraded: false,
        topK: 5, minScore: 0.30, embeddingModel: 'bge-m3', embeddingDimensions: 1024,
        chunkStrategy: 'STRUCTURE/800', chunkCount: 26,
      },
      ranks: { 1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: 1, 7: 1, 8: 2, 9: 2, 10: 1 },
      extras: { 1: 4, 6: 5, 9: null, 10: null },
      latency: [2340, 2480, 2560, 2450, 2600, 2720, 2510, 2640, 3900, 2780, 2140, 11800],
      retrieval: [214, 226, 208, 238, 220, 232, 246, 228, 640, 258, 196, 288],
      attributions: {
        9: { type: 'RANK_DROP', note: 'FOLLOW_UP 题“那回调确认超时之后呢？”指代缺失，正确块由第 1 名降到第 2 名。' },
      },
      reviews: {
        9: { tag: 'MISSING_EVIDENCE', note: '指代未独立化，属已记录的已知局限。', processed: false },
      },
      note: '新增 BM25 通道并在应用侧 RRF 融合；术语类与精确串检索明显改善。',
    },
    {
      id: 'run-20260914-03', status: 'COMPLETED',
      datasetId: 'ds-test', datasetName: '独立测试集', datasetType: 'TEST', datasetVersionNo: 2,
      kbId: 'kb-pay', kbName: '团队技术文档库', itemCount: 12,
      createdAt: '2026-09-14 21:05', finishedAt: '2026-09-14 21:12', failureReason: null,
      title: '混合检索 + 重排',
      seed: 3303,
      config: {
        retrievalMode: 'HYBRID_RERANK', vectorTopK: 30, bm25TopK: 30, rrfK: 60,
        rerankEnabled: true, rerankModel: 'qwen3.7-text-rerank', rerankDegraded: false,
        topK: 5, minScore: 0.30, embeddingModel: 'bge-m3', embeddingDimensions: 1024,
        chunkStrategy: 'STRUCTURE/800', chunkCount: 26,
      },
      ranks: { 1: 1, 2: 2, 3: 1, 4: 1, 5: 1, 6: 1, 7: 1, 8: 1, 9: 1, 10: 1, 11: null, 12: null },
      extras: { 1: 5, 9: 4, 10: 4 },
      filtered: { 6: 5 },
      latency: [3520, 3680, 3820, 3610, 3960, 4050, 3740, 3890, 6100, 4120, 3260, 12800],
      retrieval: [520, 546, 498, 560, 532, 548, 574, 540, 1120, 590, 468, 640],
      attributions: {
        2: { type: 'RANK_DROP', note: '重排把“整体链路”块压到第 2 名，正确块从第 1 名下降。' },
        6: { type: 'FUSED_BUT_FILTERED', note: '“常见根因”块融合分低于 minScore=0.30 被卡掉，回答缺少根因部分。' },
        10: { type: 'CONTEXT_TRUNCATED', note: '“改进措施”块在第 4 名，未进 max-context-chars 预算，回答缺少改进项。' },
        1: { type: 'GENERATION_ERROR', note: '证据齐备且排在首位，但回答把“渠道重试 5 次”与“网关重试 8 次”写反，属生成侧错误。' },
      },
      reviews: {
        1: { tag: 'WRONG_ANSWER', note: '重试次数张冠李戴，需在生成侧修正。', processed: false },
        10: { tag: 'MISSING_EVIDENCE', note: '上下文预算不足。', processed: true },
      },
      note: '重排上线；检索指标最好，但检索侧耗时明显上升（含重排调用）。',
    },
    {
      id: 'run-20260914-04', status: 'COMPLETED',
      datasetId: 'ds-test', datasetName: '独立测试集', datasetType: 'TEST', datasetVersionNo: 2,
      kbId: 'kb-pay', kbName: '团队技术文档库', itemCount: 12,
      createdAt: '2026-09-14 21:20', finishedAt: '2026-09-14 21:26', failureReason: null,
      title: '混合检索 + 重排（重排降级）',
      seed: 4404,
      config: {
        retrievalMode: 'HYBRID_RERANK', vectorTopK: 30, bm25TopK: 30, rrfK: 60,
        rerankEnabled: true, rerankModel: 'qwen3.7-text-rerank', rerankDegraded: true,
        topK: 5, minScore: 0.30, embeddingModel: 'bge-m3', embeddingDimensions: 1024,
        chunkStrategy: 'STRUCTURE/800', chunkCount: 26,
      },
      ranks: { 1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: 1, 7: 1, 8: null, 9: 2, 10: 1 },
      extras: { 1: 5, 6: null, 10: 4 },
      latency: [2980, 3080, 3200, 3050, 3260, 3350, 3120, 3240, 4800, 3400, 2740, 12100],
      retrieval: [330, 348, 318, 356, 340, 352, 366, 344, 720, 378, 300, 410],
      attributions: {
        6: { type: 'CHUNK_BOUNDARY', note: '“常见根因”与“应急步骤”被切在两个分块，第二条未进候选，回答只覆盖应急步骤。' },
        8: { type: 'RETRIEVAL_MISS', note: '降级回退到融合顺序后，“处理手段”块掉出前 5；开重排时可被救回。' },
        9: { type: 'RANK_DROP', note: '回退融合顺序后 FOLLOW_UP 题正确块维持在第 2 名。' },
        10: { type: 'CONTEXT_TRUNCATED', note: '“改进措施”块在第 4 名，未进上下文预算。' },
      },
      reviews: {
        6: { tag: 'MISSING_EVIDENCE', note: '分块边界问题，非检索失败。', processed: false },
        8: { tag: 'MISSING_EVIDENCE', note: '降级运行导致的未召回，需重跑确认。', processed: false },
      },
      note: '重排服务超时 2 次后按规则降级为融合顺序：检索指标回落到混合检索水平，且丢掉 1 道易混题的召回（开重排时可救回）；已显式标注降级。',
    },
    {
      id: 'run-20260914-05', status: 'RUNNING',
      datasetId: 'ds-test', datasetName: '独立测试集', datasetType: 'TEST', datasetVersionNo: 2,
      kbId: 'kb-pay', kbName: '团队技术文档库', itemCount: 12,
      createdAt: '2026-09-14 21:31', finishedAt: null, failureReason: null,
      title: '混合检索 + 重排（运行中）',
      seed: 5505,
      config: {
        retrievalMode: 'HYBRID_RERANK', vectorTopK: 30, bm25TopK: 30, rrfK: 60,
        rerankEnabled: true, rerankModel: 'qwen3.7-text-rerank', rerankDegraded: false,
        topK: 5, minScore: 0.30, embeddingModel: 'bge-m3', embeddingDimensions: 1024,
        chunkStrategy: 'STRUCTURE/800', chunkCount: 26,
      },
      ranks: { 1: 1, 2: 1, 3: 2 },
      extras: {},
      latency: [3560, 3720, 3850],
      retrieval: [512, 538, 494],
      partialCount: 3,
      note: '运行中：仅前 3 题已有结果，指标待运行完成后计算。',
    },
    {
      id: 'run-20260914-06', status: 'FAILED',
      datasetId: 'ds-tuning', datasetName: '调优集', datasetType: 'TUNING', datasetVersionNo: 1,
      kbId: 'kb-pay', kbName: '团队技术文档库', itemCount: 6,
      createdAt: '2026-09-14 21:35', finishedAt: '2026-09-14 21:36',
      failureReason: '向量化服务不可用：EMBEDDING_MODEL_BASE_URL 连接超时',
      title: '调优集冒烟（失败）',
      seed: 6606,
      config: {
        retrievalMode: 'HYBRID_RERANK', vectorTopK: 30, bm25TopK: 30, rrfK: 60,
        rerankEnabled: true, rerankModel: 'qwen3.7-text-rerank', rerankDegraded: false,
        topK: 5, minScore: 0.30, embeddingModel: 'bge-m3', embeddingDimensions: 1024,
        chunkStrategy: 'STRUCTURE/800', chunkCount: 26,
      },
      ranks: {}, extras: {}, latency: [], retrieval: [],
      note: '第一题向量化阶段即失败，整次运行标记 FAILED，不计入对比。',
    },
  ];

  /* =========================================================
     运行构建：先造逐题明细，再由明细派生指标。
     「运行详情的指标」与「逐题下钻看到的排名」因此不可能互相矛盾。
     ========================================================= */

  // 候选池：复用本原型的语料分块，保证文档名与标题路径真实存在。
  const evalPool = (() => {
    const meta = {};
    Object.keys(documents).forEach((kbId) => documents[kbId].forEach((d) => { meta[d.id] = d; }));
    const list = [];
    const docIdByChunk = {};
    const byTitlePath = {};
    Object.keys(chunks).forEach((docId) => {
      const d = meta[docId];
      chunks[docId].forEach((c) => {
        const entry = {
          docId, docName: d ? d.name : docId, chunkId: c.id,
          titlePath: c.titlePath, page: c.page == null ? null : c.page,
        };
        list.push(entry);
        docIdByChunk[c.id] = docId;
        byTitlePath[c.titlePath] = entry;
      });
    });
    return { list, docIdByChunk, byTitlePath };
  })();

  // 把题目的 evidence（docName + titlePath）解析为真实分块；解析不到时按锚点降级处理。
  function evidenceChunks(item, def) {
    const blockLevel = def.datasetVersionNo >= 2;
    return item.evidence.map((e) => {
      const hit = evalPool.byTitlePath[e.titlePath];
      return {
        chunkId: hit ? hit.chunkId : null,
        docName: e.docName,
        // 真实分块路径：检索命中始终按真实分块记录（ES 返回完整 titlePath）
        titlePath: hit ? hit.titlePath : e.titlePath,
        // 判定锚点：v1 退化为 docName（第一轮饱和根因），v2 起为精确 titlePath
        anchorPath: blockLevel ? (hit ? hit.titlePath : e.titlePath) : e.docName,
        anchor: blockLevel ? 'titlePath' : 'docName',
        page: e.page == null ? null : e.page,
        resolved: !!hit,
      };
    });
  }

  /** 命中是否命中某条 evidence 的锚点（口径随数据集版本变化）。 */
  function hitMatchesEvidence(h, evs) {
    return evs.some((e) => (e.anchor === 'titlePath' ? h.titlePath === e.anchorPath : h.docName === e.anchorPath));
  }

  function evalRng(seed) {
    let s = (seed >>> 0) || 1;
    return function () { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
  }

  function evalPick(pool, n, rng) {
    const copy = pool.slice();
    const out = [];
    while (out.length < n && copy.length) out.push(copy.splice(Math.floor(rng() * copy.length), 1)[0]);
    return out;
  }

  function evalRound(n, digits) {
    const f = Math.pow(10, digits);
    return Math.round(n * f) / f;
  }

  /** 题内最终排名 r 对应的展示分（单调递减，且高于 minScore）。 */
  function evalScore(mode, rank, rng) {
    const hi = mode === 'VECTOR' ? 0.92 : 0.90;
    const span = (hi - 0.58) / 5;
    return evalRound(hi - span * (rank - 1) - rng() * 0.008, 3);
  }

  /** 构造单题本次运行的 hit 列表（含各阶段位次与分数）。 */
  function buildRetrieved(def, item, rng) {
    const cfg = def.config;
    const mode = cfg.retrievalMode;
    const degraded = cfg.rerankDegraded === true;
    const evs = evidenceChunks(item, def);
    const plan = [];
    const first = def.ranks[item.seq] != null ? def.ranks[item.seq] : null;
    const second = def.extras && def.extras[item.seq] != null ? def.extras[item.seq] : null;
    if (first != null && evs[0]) plan.push({ rank: first, src: evs[0], ev: true });
    if (second != null && evs[1]) plan.push({ rank: second, src: evs[1], ev: true });

    // 被阈值卡掉的正确分块：不进 retrieved，只在 filteredOut 中留痕（调试页可见）。
    const filteredRank = def.filtered && def.filtered[item.seq] != null ? def.filtered[item.seq] : null;
    const usedRanks = new Set(plan.map((p) => p.rank));
    // 排除全部 evidence 分块，避免随机填充“碰巧”命中而被误判为已召回。
    const usedChunks = new Set(
      plan.map((p) => p.src.chunkId).concat(evs.map((e) => e.chunkId)).filter(Boolean)
    );
    const fillers = evalPick(evalPool.list.filter((c) => !usedChunks.has(c.chunkId)), 10, rng);
    let fi = 0;
    for (let r = 1; r <= 5; r++) {
      if (usedRanks.has(r)) continue;
      plan.push({ rank: r, src: fillers[fi++], ev: false });
    }
    plan.sort((a, b) => a.rank - b.rank);

    const jitter = (r) => {
      if (rng() >= 0.45) return r;
      const d = rng() < 0.5 ? -1 : 1;
      return Math.min(6, Math.max(1, r + d));
    };

    const retrieved = plan.map((p) => {
      const r = p.rank;
      const score = evalScore(mode, r, rng);
      const h = {
        rank: r,
        chunkId: p.src.chunkId,
        docName: p.src.docName,
        titlePath: p.src.titlePath,
        page: p.src.page,
        score,
        passedThreshold: score >= cfg.minScore,
        isEvidence: p.ev,
        vectorRank: null, vectorScore: null,
        bm25Rank: null, bm25Score: null,
        fusedRank: null, fusedScore: null,
        rerankRank: null, rerankScore: null,
        rankChangedReason: null,
      };
      if (mode === 'VECTOR') {
        h.vectorRank = r;
        h.vectorScore = score;
        return h; // 基线仅含向量字段
      }
      if (mode === 'HYBRID' || degraded) {
        h.fusedRank = r;
        h.vectorRank = jitter(r);
        h.bm25Rank = jitter(r);
      } else {
        h.rerankRank = r;
        h.fusedRank = jitter(r);
        h.vectorRank = jitter(r);
        h.bm25Rank = jitter(r);
      }
      h.vectorScore = evalRound(0.48 + rng() * 0.42, 3);
      h.bm25Score = evalRound(3.42 + rng() * 15.2, 2);
      h.fusedScore = evalRound(1 / (cfg.rrfK + h.fusedRank) + 1 / (cfg.rrfK + h.vectorRank), 4);
      if (h.rerankRank != null) {
        h.rerankScore = evalRound(0.995 - (h.rerankRank - 1) * 0.19 - rng() * 0.03, 3);
        if (h.rerankRank !== h.fusedRank) h.rankChangedReason = '重排调整：融合 #' + h.fusedRank + ' → 最终 #' + h.rerankRank;
      }
      if (!h.rankChangedReason && (h.fusedRank !== h.vectorRank || h.fusedRank !== h.bm25Rank)) {
        h.rankChangedReason = 'RRF 融合调整：向量 #' + h.vectorRank + ' · BM25 #' + h.bm25Rank + ' → 融合 #' + h.fusedRank;
      }
      return h;
    });

    const passed = retrieved.filter((h) => h.passedThreshold);
    const filteredOut = [];
    if (filteredRank != null && evs[1]) {
      filteredOut.push({
        fusedRank: filteredRank,
        chunkId: evs[1].chunkId,
        docName: evs[1].docName,
        titlePath: evs[1].titlePath,
        score: evalRound(cfg.minScore - 0.06 - rng() * 0.05, 3),
        passedThreshold: false,
        reason: '融合分低于 minScore=' + cfg.minScore.toFixed(2) + '，未进入候选',
      });
    }
    return { retrieved, filteredOut, passed, evs };
  }

  /** 单题完整明细。 */
  function buildItem(def, q, rng) {
    const built = buildRetrieved(def, q, rng);
    const firstRank = def.ranks[q.seq] != null ? def.ranks[q.seq] : null;
    const hit = q.answerable ? firstRank != null : null;
    const refusalErrors = def.refusalErrors || {};
    const shouldRefuse = q.answerable === false;
    const refusalError = refusalErrors[q.seq] || null;
    // 默认行为 = 该拒则拒；被标注为误判的题目行为反转（该拒没拒 / 不该拒却拒）。
    const refusal = refusalError ? !shouldRefuse : shouldRefuse;
    const retrievalMs = def.retrieval ? def.retrieval[q.seq - 1] : null;
    const latencyMs = def.latency ? def.latency[q.seq - 1] : null;
    const attr = def.attributions && def.attributions[q.seq] ? def.attributions[q.seq] : null;
    const review = def.reviews && def.reviews[q.seq] ? def.reviews[q.seq] : null;
    // R2-A1：拒答即清空引用。
    const citations = refusal ? [] : built.passed.slice(0, 2).map((h) => ({
      chunkId: h.chunkId,
      docId: evalPool.docIdByChunk[h.chunkId] || null,
      docName: h.docName,
      titlePath: h.titlePath,
      page: h.page,
      score: h.score,
    }));
    return {
      seq: q.seq,
      question: q.question,
      category: q.category,
      answerable: q.answerable,
      referenceAnswer: q.referenceAnswer,
      evidence: built.evs,
      hit,
      firstEvidenceRank: firstRank,
      latencyMs,
      retrievalMs,
      generationMs: latencyMs != null && retrievalMs != null ? latencyMs - retrievalMs : null,
      answer: refusal ? '当前资料不足以回答该问题。' : q.answer,
      refusal,
      refusalError,
      citations,
      retrieved: built.retrieved,
      filteredOut: built.filteredOut,
      attribution: attr ? attr.type : null,
      attributionNote: attr ? attr.note : null,
      reviewTag: review ? review.tag : null,
      reviewNote: review ? review.note : null,
      processed: review ? review.processed === true : false,
    };
  }

  /** 由逐题明细派生运行指标。 */
  function buildMetrics(items) {
    const ans = items.filter((i) => i.answerable === true);
    const n = ans.length;
    if (n === 0) return null;
    const at = (k) => evalRound(ans.filter((i) => i.firstEvidenceRank != null && i.firstEvidenceRank <= k).length / n, 4);
    const totalEvidence = ans.reduce((s, i) => s + i.evidence.length, 0);
    const covered = ans.reduce((s, i) => {
      return s + i.retrieved.filter((h) => h.passedThreshold && hitMatchesEvidence(h, i.evidence)).length;
    }, 0);
    const mrr = evalRound(ans.reduce((s, i) => s + (i.firstEvidenceRank ? 1 / i.firstEvidenceRank : 0), 0) / n, 4);
    const correct = items.filter((i) => (i.answerable ? !i.refusal : i.refusal)).length;
    const lat = items.map((i) => i.latencyMs).filter((v) => v != null).sort((a, b) => a - b);
    const pct = (p) => (lat.length ? lat[Math.min(lat.length - 1, Math.round(p * (lat.length - 1)))] : null);
    const rankDist = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, notFound: 0 };
    ans.forEach((i) => {
      if (i.firstEvidenceRank == null || i.firstEvidenceRank > 5) rankDist.notFound++;
      else rankDist[i.firstEvidenceRank]++;
    });
    return {
      hitAt1: at(1), hitAt3: at(3), hitAt5: at(5),
      recallAt5: totalEvidence ? evalRound(Math.min(1, covered / totalEvidence), 4) : null,
      mrr,
      refusalAccuracy: items.length ? evalRound(correct / items.length, 4) : null,
      answerableCount: n,
      outOfKbCount: items.length - n,
      rankDist,
      p50Ms: pct(0.5), p95Ms: pct(0.95), maxMs: lat.length ? lat[lat.length - 1] : null,
      avgLatencyMs: lat.length ? Math.round(lat.reduce((s, v) => s + v, 0) / lat.length) : null,
    };
  }

  const evalRuns = [];
  const evalRunItems = {};
  evalRunSeeds.forEach((def) => {
    const rng = evalRng(def.seed);
    const isFailed = def.status === 'FAILED';
    const partial = def.partialCount || null;
    const items = [];
    if (!isFailed) {
      evalQuestionBank.forEach((q) => {
        if (partial != null && q.seq > partial) return;
        items.push(buildItem(def, q, rng));
      });
    }
    evalRunItems[def.id] = items;
    evalRuns.push({
      id: def.id,
      title: def.title,
      status: def.status,
      datasetId: def.datasetId,
      datasetName: def.datasetName,
      datasetType: def.datasetType,
      datasetVersionNo: def.datasetVersionNo,
      kbId: def.kbId,
      kbName: def.kbName,
      itemCount: def.itemCount,
      processedCount: items.length,
      createdAt: def.createdAt,
      finishedAt: def.finishedAt,
      failureReason: def.failureReason,
      config: Object.assign({}, def.config),
      metrics: isFailed || partial != null ? null : buildMetrics(items),
      runNote: def.note,
    });
  });

  // 语料指纹统一补进各运行快照（可比性守卫据此区分「同配置但语料已重入库」）。
  const evalCorpusFingerprint = {
    docCount: 6, chunkCount: 26, chunkStrategy: 'STRUCTURE/800',
    docs: [
      '支付网关服务架构说明.md', '订单服务部署手册.pdf', '数据库连接池耗尽处理手册.md',
      'ES 集群黄色状态排查指南.md', 'Kafka 消息积压应急手册.txt', '2026-06 支付回调超时故障复盘.pdf',
    ],
  };
  evalRuns.forEach((r) => { r.config.corpusFingerprint = evalCorpusFingerprint; });
  evalDatasets.forEach((ds) => ds.versions.forEach((v) => {
    v.anchorModeCompact = v.anchorMode.indexOf('分块级') === 0 ? '分块级' : '文档级';
  }));

  window.MockData = {
    knowledgeBases, documents, chunks, sessions, sampleQuestions, debugScenarios,
    attributionMeta, evalDatasets, evalRuns, evalRunItems, runMetricMeta, retrievalModes,
    refusalDemo, titlePathFixDemo, evalQuestionBank, evalCorpusFingerprint,
  };

  // ---------- 常用工具 ----------
  window.MockUtil = {
    kb(id) { return knowledgeBases.find((k) => k.id === id) || null; },
    docs(kbId) { return documents[kbId] || []; },
    doc(id) {
      for (const kbId of Object.keys(documents)) {
        const d = documents[kbId].find((x) => x.id === id);
        if (d) return d;
      }
      return null;
    },
    chunksOf(docId) { return chunks[docId] || []; },
    statusMeta(status) {
      const m = {
        queued: { label: '排队中', cls: 'badge-neutral' },
        parsing: { label: '解析中', cls: 'badge-info' },
        cleaning: { label: '清洗中', cls: 'badge-info' },
        chunking: { label: '分块中', cls: 'badge-info' },
        embedding: { label: '向量化中', cls: 'badge-info' },
        indexing: { label: '入库中', cls: 'badge-info' },
        completed: { label: '已完成', cls: 'badge-success' },
        failed: { label: '失败', cls: 'badge-danger' },
      };
      return m[status] || { label: status, cls: 'badge-neutral' };
    },
    stageSeq: ['解析', '清洗', '分块', '向量化', '入库'],
    fmtTime(iso) { return iso; },
    uid(prefix) { return prefix + '-' + Math.random().toString(36).slice(2, 9); },

    /* ---------- 第二轮：评测相关工具 ---------- */

    run(id) { return evalRuns.find((r) => r.id === id) || null; },
    runItems(id) { return evalRunItems[id] || []; },
    dataset(id) { return evalDatasets.find((d) => d.id === id) || null; },
    datasetVersion(datasetId, versionNo) {
      const ds = evalDatasets.find((d) => d.id === datasetId);
      return ds ? (ds.versions.find((v) => v.versionNo === versionNo) || null) : null;
    },
    attribution(type) { return attributionMeta[type] || null; },
    /** 归因徽标样式类：RANK_DROP → attr-rank */
    attrClass(type) {
      const m = {
        RETRIEVAL_MISS: 'attr-miss', RANK_DROP: 'attr-rank', FUSED_BUT_FILTERED: 'attr-filter',
        CONTEXT_TRUNCATED: 'attr-ctx', CHUNK_BOUNDARY: 'attr-chunk', REFUSAL_ERROR: 'attr-refusal',
        GENERATION_ERROR: 'attr-gen',
      };
      return m[type] || '';
    },
    runStatusMeta(status) {
      const m = {
        COMPLETED: { label: '已完成', cls: 'badge-success' },
        RUNNING: { label: '运行中', cls: 'badge-info', spin: true },
        FAILED: { label: '失败', cls: 'badge-danger' },
      };
      return m[status] || { label: status, cls: 'badge-neutral' };
    },
    modeLabel(mode) { return retrievalModes[mode] ? retrievalModes[mode].label : mode; },
    modeShort(mode) { return retrievalModes[mode] ? retrievalModes[mode].short : mode; },
    /** 指标展示：比率 → 百分比字符串；耗时 → 毫秒带单位。 */
    fmtMetric(key, v) {
      if (v == null) return '—';
      if (key === 'p50Ms' || key === 'p95Ms' || key === 'maxMs' || key === 'avgLatencyMs') return v + ' ms';
      if (key === 'answerableCount' || key === 'outOfKbCount') return String(v);
      return (v * 100).toFixed(1) + '%';
    },
    /** 指标差值（pp / ms），用于对比页 delta。 */
    metricDelta(key, a, b) {
      if (a == null || b == null) return null;
      const isMs = key === 'p50Ms' || key === 'p95Ms' || key === 'maxMs' || key === 'avgLatencyMs';
      const diff = b - a;
      const better = isMs ? diff < 0 : diff > 0;
      const flat = Math.abs(diff) < (isMs ? 1 : 0.00005);
      return {
        raw: diff, better, flat,
        text: (isMs ? (diff > 0 ? '+' : '') + Math.round(diff) + ' ms' : (diff > 0 ? '+' : '') + (diff * 100).toFixed(1) + 'pp'),
      };
    },
    /**
     * 可比性守卫（R2-C3）：返回 { comparable, differences[], note }。
     * 数据集版本 / KB / embedding 模型或维度 / 语料指纹 任一不同即判为不可比。
     */
    compareGuard(baseId, expId) {
      const a = evalRuns.find((r) => r.id === baseId);
      const b = evalRuns.find((r) => r.id === expId);
      if (!a || !b) return { comparable: false, differences: ['运行不存在'], note: '' };
      if (a.id === b.id) return { comparable: false, differences: ['请选择两次不同的运行'], note: '' };
      const diffs = [];
      const push = (field, va, vb) => diffs.push({ field, base: va, exp: vb });
      if (a.datasetId !== b.datasetId) push('数据集', a.datasetName, b.datasetName);
      if (a.datasetVersionNo !== b.datasetVersionNo) {
        const va = this.datasetVersion(a.datasetId, a.datasetVersionNo);
        const vb = this.datasetVersion(b.datasetId, b.datasetVersionNo);
        push('数据集版本', a.datasetName + ' v' + a.datasetVersionNo + (va ? '（' + va.anchorMode + '）' : ''),
          b.datasetName + ' v' + b.datasetVersionNo + (vb ? '（' + vb.anchorMode + '）' : ''));
      }
      if (a.kbId !== b.kbId) push('知识库', a.kbName, b.kbName);
      if (a.config.embeddingModel !== b.config.embeddingModel) push('embedding 模型', a.config.embeddingModel, b.config.embeddingModel);
      if (a.config.embeddingDimensions !== b.config.embeddingDimensions) push('向量维度', a.config.embeddingDimensions, b.config.embeddingDimensions);
      const fa = a.config.corpusFingerprint || {};
      const fb = b.config.corpusFingerprint || {};
      if (fa.chunkCount !== fb.chunkCount) push('语料分块数', fa.chunkCount, fb.chunkCount);
      if (fa.chunkStrategy !== fb.chunkStrategy) push('分块策略', fa.chunkStrategy, fb.chunkStrategy);
      const sameCfg = a.config.retrievalMode === b.config.retrievalMode
        && a.config.vectorTopK === b.config.vectorTopK && a.config.rrfK === b.config.rrfK
        && a.config.rerankEnabled === b.config.rerankEnabled && a.config.topK === b.config.topK
        && a.config.minScore === b.config.minScore;
      // 可比时的提示：配置完全相同要显式说明「无差异」（否则 delta 全 0 会被误读为“没提升”）。
      let note = '';
      if (!diffs.length) {
        note = sameCfg
          ? '配置无差异：两次运行的检索配置与语料指纹完全一致，指标变化应理解为随机波动。'
          : '可比：数据集版本、知识库、embedding 与语料指纹一致，仅检索配置不同，指标差异可归因到配置。';
      }
      return { comparable: diffs.length === 0, differences: diffs, note, sameConfig: sameCfg };
    },
    /** 逐题对比：按 seq 1:1 关联，返回变好/变差/不变计数与逐题明细。 */
    diffRuns(baseId, expId) {
      const a = evalRunItems[baseId] || [];
      const b = evalRunItems[expId] || [];
      const bySeq = {};
      b.forEach((i) => { bySeq[i.seq] = i; });
      const rows = [];
      a.forEach((i) => {
        const o = bySeq[i.seq];
        if (!o) return;
        const ra = i.firstEvidenceRank;
        const rb = o.firstEvidenceRank;
        const na = ra == null ? 99 : ra;
        const nb = rb == null ? 99 : rb;
        const dir = nb < na ? 'better' : (nb > na ? 'worse' : 'flat');
        rows.push({ seq: i.seq, question: i.question, category: i.category, base: i, exp: o, direction: dir });
      });
      return {
        rows,
        better: rows.filter((r) => r.direction === 'better').length,
        worse: rows.filter((r) => r.direction === 'worse').length,
        flat: rows.filter((r) => r.direction === 'flat').length,
      };
    },
    /** 归因汇总：按归因类型计数（可传运行 id 限定范围）。 */
    attributionSummary(runId) {
      const counts = {};
      Object.keys(attributionMeta).forEach((k) => { counts[k] = { count: 0, processed: 0 }; });
      const runs = runId ? [runId] : Object.keys(evalRunItems);
      runs.forEach((id) => (evalRunItems[id] || []).forEach((i) => {
        if (!i.attribution || !counts[i.attribution]) return;
        counts[i.attribution].count++;
        if (i.processed) counts[i.attribution].processed++;
      }));
      return counts;
    },
    /** 排名分布 → 展示项数组（仅统计 answerable 题）。 */
    rankDistItems(metrics) {
      if (!metrics || !metrics.rankDist) return [];
      const out = [];
      for (let r = 1; r <= 5; r++) if (metrics.rankDist[r]) out.push({ label: '第' + r + '名', count: metrics.rankDist[r] });
      if (metrics.rankDist.notFound) out.push({ label: '未召回', count: metrics.rankDist.notFound, muted: true });
      return out;
    },
    /** 分阶段位次 chips，如「向量 #3 · 0.792」。退化运行只显示有效阶段。 */
    stageRanks(hit, mode, degraded) {
      const out = [];
      const add = (label, rank, score) => { if (rank != null) out.push({ label, rank, score }); };
      if (mode === 'VECTOR') { add('向量', hit.vectorRank, hit.vectorScore); return out; }
      add('向量', hit.vectorRank, hit.vectorScore);
      add('BM25', hit.bm25Rank, hit.bm25Score);
      add('融合', hit.fusedRank, hit.fusedScore);
      if (!degraded) add('重排', hit.rerankRank, hit.rerankScore);
      return out;
    },
    /** 拒绝/拒答复核标记的展示名。 */
    reviewTagLabel(tag) {
      const m = { OK: '正常', WRONG_ANSWER: '回答错误', MISSING_EVIDENCE: '证据缺失', WRONG_SOURCE: '来源错误', OTHER: '其他' };
      return m[tag] || tag || '未标记';
    },
    /** 溯源：由 chunkId 找到分块（评测快照与文档详情共用）。 */
    chunkById(chunkId) {
      for (const docId of Object.keys(chunks)) {
        const c = chunks[docId].find((x) => x.id === chunkId);
        if (c) return c;
      }
      return null;
    },
  };
})();
