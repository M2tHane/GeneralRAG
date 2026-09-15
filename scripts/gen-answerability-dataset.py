#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R4 Answerability 专项 TUNING 数据集生成器。
锚点全部来自 /tmp/r4-dataset/anchors.json（服务端真实分块：contentHash + chunkId + titlePath）。
产出：docs/eval/eval-answerability-v1-array.json

类别设计（对应用户批准的类别规模建议）：
  DIRECT            13  可回答，答案就在单个分块中
  TERM_VARIATION     9  可回答，问题措辞与语料用词不同
  CONFUSABLE        18  语义高度相关但证据不含答案：12 false / 6 true
  OUT_OF_KB         14  完全不可回答：8 无关域 / 6 同域无答案（false）
  PARTIAL_EVIDENCE  12  证据只覆盖问题要求的一部分（全部 false）
  FOLLOW_UP          6  依赖上文指代的追问（可回答）
合计 72 题；answerable=true 34 / false 38。
"""
import json

A = json.load(open('/tmp/r4-dataset/anchors.json'))

def anchor(title_path_sub):
    """按 titlePath 唯一前缀查锚点。"""
    hits = [v for v in A.values() if title_path_sub in v['titlePath']]
    assert len(hits) == 1, f"anchor ambiguity for {title_path_sub!r}: {len(hits)} hits"
    h = hits[0]
    return {'docName': h['docName'], 'titlePath': h['titlePath'],
            'chunkId': [k for k, v in A.items() if v is h][0],
            'contentHash': h['hash'], 'page': h['page']}

def q(question, answerable, category, evidence, reference=None):
    d = {'question': question, 'answerable': answerable, 'category': category}
    if reference:
        d['referenceAnswer'] = reference
    d['evidence'] = evidence  # false 题 evidence=[]
    return d

# ---------------- 可回答题锚点速查 ----------------
ES_ROOT   = 'elasticsearch-cluster-health.md'
ES_HEALTH = ES_ROOT + ' > 1.1. 集群健康状态'
ES_DISK   = ES_ROOT + ' > 1.2. 磁盘水位三阶段'
ES_FIX    = ES_ROOT + ' > 1.3. 磁盘超限的处理步骤'
ES_UNSIGN = ES_ROOT + ' > 1.4. 未分配分片的排查'
RD_PERSIST= 'redis-persistence.md > 1.1. 持久化方式概览'
RD_RDB    = 'redis-persistence.md > 1.2. RDB 的优缺点'
RD_AOF    = 'redis-persistence.md > 1.3. AOF 的优缺点与刷盘策略'
RD_FSYNC  = 'redis-persistence.md > 1.3.1. 三种 fsync 策略（appendfsync）'
RD_SNAP   = 'redis-persistence.md > 1.4. RDB 快照机制'
RD_CHOICE = 'redis-persistence.md > 1.5. 如何选择'
RD_INTER  = 'redis-persistence.md > 1.6. RDB 与 AOF 的相互作用'
KF_GROUP  = 'kafka-consumer-groups.md > 1.1. 消费者组的核心规则'
KF_REBAL  = 'kafka-consumer-groups.md > 1.2. 再平衡（Rebalance）'
KF_LAG    = 'kafka-consumer-groups.md > 1.3. 消费位移与 Lag'
KF_MON    = 'kafka-consumer-groups.md > 1.4. 积压的监控'
KF_BACKLOG= 'kafka-consumer-groups.md > 1.5. 积压的常见原因与处理'
NG_BUF    = 'nginx-proxy-buffering.md > 1.1. proxy_buffering 的行为'
NG_DIR    = 'nginx-proxy-buffering.md > 1.2. 相关指令'
NG_XACC   = 'nginx-proxy-buffering.md > 1.3. X-Accel-Buffering 响应头'
NG_EX     = 'nginx-proxy-buffering.md > 1.4. 示例'
NG_SSE    = 'nginx-proxy-buffering.md > 2. 流式接口（SSE）单独关闭缓冲'
NGD_BUF   = 'nginx-proxy-guide.docx > 1.1. 代理缓冲机制'
NGD_OVER  = 'nginx-proxy-guide.docx > 1.1.1. 缓冲区大小溢出行为'
NGD_TO    = 'nginx-proxy-guide.docx > 1.2. 关键超时参数'
NGD_TABLE = 'nginx-proxy-guide.docx > 1.3. 常用参数速查表'
NGD_HC    = 'nginx-proxy-guide.docx > 1.4. 健康检查与故障转移'
HK_PARAM  = 'hikari-cp-reference.xlsx > 参数说明 > 数据行 1-8'
HK_FAQ    = 'hikari-cp-reference.xlsx > 常见问题 > 数据行 1-4'
HK_CAP    = 'hikari-cp-reference.xlsx > 容量估算 > 数据行 1-3'
HK_SUM    = 'hikari-cp-reference.xlsx > 参数说明 > 概览'
KEC_ALL   = 'kafka-error-codes.csv > 数据行 1-5'
KEC_LAST  = 'kafka-error-codes.csv > 数据行 6-6'
KEC_OV    = 'kafka-error-codes.csv > 概览'

items = []
def add(*args, **kw):
    items.append(q(*args, **kw))

# ================= DIRECT（13，可回答） =================
add('Redis 中 AOF 的 fsync 默认策略是哪一种？', True, 'DIRECT', [anchor(RD_FSYNC)],
    'everysec（每秒 fsync），既是默认也是官方建议策略。')
add('Elasticsearch 集群健康状态为 red 意味着什么？', True, 'DIRECT', [anchor(ES_HEALTH)],
    '存在未分配的主分片，部分数据不可用。')
add('ES 节点磁盘使用率达到 flood_stage 水位（默认 95%）后会发生什么？', True, 'DIRECT', [anchor(ES_DISK)],
    '节点上的索引被加 index.blocks.read_only_allow_delete 块，拒绝写入（仅允许删除）。')
add('Kafka 消费者组的 Consumer Lag 是怎么定义的？', True, 'DIRECT', [anchor(KF_LAG)],
    '分区日志最新位移（LEO）− 消费者已提交/已消费位移，单位是消息数，分区级监控。')
add('nginx 的 proxy_buffering 指令默认是开启还是关闭？', True, 'DIRECT', [anchor(NG_BUF)],
    '默认 on（开启缓冲），可配置在 http/server/location 上下文。')
add('nginx 的 proxy_read_timeout 默认是多少秒？超时后会发生什么？', True, 'DIRECT', [anchor(NGD_TO)],
    '默认 60s；两次成功读操作之间的最大间隔，超时则关闭连接。')
add('HikariCP 的 maxLifetime 默认值是多少？', True, 'DIRECT', [anchor(HK_PARAM)],
    '1800000 ms（30 分钟）。')
add('HikariCP 出现 Communications link failure 的常见原因和处置方法？', True, 'DIRECT', [anchor(HK_FAQ)],
    '连接存活超过服务端 wait_timeout；将 maxLifetime 设为小于 MySQL wait_timeout 的值。')
add('Kafka 生产端报 MESSAGE_TOO_LARGE 该怎么处理？', True, 'DIRECT', [anchor(KEC_ALL)],
    '消息超过 max.message.bytes；调大 topic 的 max.message.bytes 或拆分消息。')
add('Kafka 报 COORDINATOR_NOT_AVAILABLE 是什么含义？', True, 'DIRECT', [anchor(KEC_LAST)],
    '组协调器暂不可用（__consumer_offsets 迁移、broker 负载高）；自动重试，持续出现则检查 broker 健康。')
add('响应体超过 nginx 的 proxy_buffers 总容量时会怎样？', True, 'DIRECT', [anchor(NGD_OVER)],
    '超出部分写入临时文件（proxy_max_temp_file_size 默认 1024m）；设为 0 禁用临时文件，超出部分同步转发并阻塞读取后端。')
add('HikariCP 高吞吐 OLTP 场景的池大小建议是多少？为什么？', True, 'DIRECT', [anchor(HK_CAP)],
    '40；超过 40 后加大池收益递减，优先优化慢 SQL。')
add('RDB 快照的 save 60 1000 配置是什么意思？', True, 'DIRECT', [anchor(RD_SNAP)],
    '60 秒内至少 1000 个键被修改则自动触发快照落盘。')

# ================= TERM_VARIATION（9，可回答） =================
add('Redis 那个把数据集写成单个紧凑文件的持久化方式，有什么好处和坏处？', True, 'TERM_VARIATION', [anchor(RD_RDB)],
    '即 RDB：优点是文件紧凑适合备份、父进程不阻塞、大库重启快；缺点是宕机会丢最近几分钟数据、fork 耗时。')
add('ES 里「黄着」但没「红着」的集群，数据有风险吗？', True, 'TERM_VARIATION', [anchor(ES_HEALTH)],
    'yellow=主分片齐全但副本未分配；节点故障时部分数据在修复前可能不可用（比 red 轻）。')
add('Kafka 里消费者「欠」了多少条消息没消费，这个指标叫什么、怎么算？', True, 'TERM_VARIATION', [anchor(KF_LAG)],
    '叫 Consumer Lag（滞后量）= LEO − 已消费位移。')
add('nginx 把后端响应先攒着再发给客户端的这个行为，由哪个配置控制？怎么关掉？', True, 'TERM_VARIATION', [anchor(NG_BUF)],
    'proxy_buffering，默认 on；设为 off 即关闭（同步透传）。')
add('数据库连接池里那个「连接最长能活多久」的参数，默认多少？', True, 'TERM_VARIATION', [anchor(HK_PARAM)],
    'maxLifetime，默认 1800000 ms（30 分钟）。')
add('Kafka 消费组里「一个成员掉线了会怎样」？', True, 'TERM_VARIATION', [anchor(KF_REBAL)],
    '触发再平衡（Rebalance），组内消费者停止消费直到分区重新分配完成。')
add('连接池报「connection is not available，请求超时」一般是什么问题？', True, 'TERM_VARIATION', [anchor(HK_FAQ)],
    '池耗尽（连接泄漏或并发超上限）；排查未关闭的连接，必要时调大 maximumPoolSize。')
add('Kafka 消费者请求的位移超出了分区已有的位移范围，报的是什么错、怎么办？', True, 'TERM_VARIATION', [anchor(KEC_ALL)],
    'OFFSET_OUT_OF_RANGE；按 auto.offset.reset 策略重置位移（earliest/latest）。')
add('Word 语料里写的 proxy_buffer_size 是干嘛用的、默认多大？', True, 'TERM_VARIATION', [anchor(NGD_TABLE)],
    '响应头缓冲区（与系统内存页一致），默认 4k|8k。')

# ================= CONFUSABLE（18：12 false + 6 true） =================
# --- CONFUSABLE answerable=false：语义相近、同产品/同领域，但证据不含答案 ---
add('Redis Cluster 的 slot 迁移失败应该怎么排查解决？', False, 'CONFUSABLE', [],
    '语料只讲 Redis 单机持久化（RDB/AOF），完全没有 Cluster/槽位迁移内容。')
add('Redis 的 Keyspace 通知（keyspace notifications）怎么开启？', False, 'CONFUSABLE', [],
    '语料是 Redis 持久化文档，不含 keyspace 通知配置。')
add('Tomcat JDBC 连接池的 maxAge 参数默认是多少？', False, 'CONFUSABLE', [],
    '语料只有 HikariCP 参数表（maxLifetime 等），没有 Tomcat JDBC Pool 的 maxAge。')
add('Vibur DBPC 连接池的 maxIdleTime 默认值是多少？', False, 'CONFUSABLE', [],
    '语料只有 HikariCP；Vibur 是另一个连接池产品，语料未涉及。')
add('Kafka MirrorMaker 2 的 offset 同步间隔怎么配置？', False, 'CONFUSABLE', [],
    '语料覆盖消费者组/位移/Lag/积压，没有 MirrorMaker 跨集群复制内容。')
add('Kafka 的 transactions（幂等生产者与事务）怎么使用？', False, 'CONFUSABLE', [],
    '语料没有事务/幂等生产者内容；消息错误码表也不含事务相关项。')
add('nginx 的 proxy_cache_path 应该怎么配置来开启响应缓存？', False, 'CONFUSABLE', [],
    '语料只讲代理缓冲（buffering）与超时，没有 proxy_cache 缓存模块内容。')
add('nginx 的 grpc_pass 怎么配置 gRPC 反向代理？', False, 'CONFUSABLE', [],
    '语料是 ngx_http_proxy_module 的缓冲与超时参数，没有 gRPC 代理内容。')
add('Elasticsearch 的 ILM 索引生命周期策略怎么配置 rollover？', False, 'CONFUSABLE', [],
    '语料讲集群健康与磁盘水位，没有索引生命周期管理内容。')
add('ES 的 shard allocation awareness（机架感知）怎么配置？', False, 'CONFUSABLE', [],
    '语料只有磁盘水位相关的分配设置，没有 allocation awareness 内容。')
add('AOF 的 appendfsync always 模式下，Redis 每秒最多能扛多少写入 QPS？', False, 'CONFUSABLE', [],
    '语料定性说明 always「非常慢」，但没有任何 QPS/吞吐数值，无法回答。')
add('HikariCP 的 connectionTimeout 在高并发下应该调成多少毫秒比较合适？', False, 'CONFUSABLE', [],
    '语料给出默认 30000 ms，但没有给出任何调优建议值或高并发场景的调整指引。')
# --- CONFUSABLE answerable=true：看似易混但证据确实可答 ---
add('HikariCP 的 idleTimeout 和 maxLifetime 有什么区别？', True, 'CONFUSABLE', [anchor(HK_PARAM)],
    'idleTimeout=600000 ms（空闲连接存活），maxLifetime=1800000 ms（连接最长存活），语义不同。')
add('ES 的低水位 85% 和高水位 90% 触发后的行为有什么不同？', True, 'CONFUSABLE', [anchor(ES_DISK)],
    '低水位=不再向该节点分配新分片；高水位=开始把分片迁移到磁盘使用率较低的节点。')
add('RDB 和 AOF 的「重写/快照」后台过程互相有什么约束？', True, 'CONFUSABLE', [anchor(RD_INTER)],
    'Redis 2.4 起避免两个后台重磁盘 IO 过程同时进行（快照中不触发 AOF 重写、反之亦然）。')
add('nginx 的 proxy_buffer_size 和 proxy_buffers 两个指令有什么区别？', True, 'CONFUSABLE', [anchor(NG_DIR)],
    'proxy_buffer_size=读取响应第一部分（响应头）的缓冲区；proxy_buffers=读取响应体的缓冲区数量和大小。')
add('Kafka 报 UNKNOWN_TOPIC_OR_PARTITION 和 NOT_LEADER_FOR_PARTITION 的处置有什么不同？', True, 'CONFUSABLE', [anchor(KEC_ALL)],
    '前者核对 topic 名/创建 topic 或开启自动创建；后者客户端自动刷新元数据后重试，无需人工干预。')
add('proxy_read_timeout 和 proxy_connect_timeout 分别管什么？', True, 'CONFUSABLE', [anchor(NGD_TO)],
    'connect=建立连接的超时；read=两次成功读操作之间的最大间隔（超时关闭连接）；默认都是 60s。')

# ================= OUT_OF_KB（14，全 false）：8 完全无关 + 6 同域无答案 =================
add('怎么用 Git rebase 整理提交历史？', False, 'OUT_OF_KB', [])
add('MySQL 的主从复制延迟怎么解决？', False, 'OUT_OF_KB', [])
add('Kubernetes 里 Pod 一直 CrashLoopBackOff 怎么排查？', False, 'OUT_OF_KB', [])
add('Python 的 GIL 对多线程程序有什么影响？', False, 'OUT_OF_KB', [])
add('TLS 证书过期前怎么自动轮换（cert-manager）？', False, 'OUT_OF_KB', [])
add('PostgreSQL 的 VACUUM FULL 和普通 VACUUM 有什么区别？', False, 'OUT_OF_KB', [])
add('React 组件的 useEffect 依赖数组应该怎么写？', False, 'OUT_OF_KB', [])
add('Linux 的 net.ipv4.tcp_tw_reuse 内核参数是什么含义？', False, 'OUT_OF_KB', [])
# --- 同域/同产品但语料没有该具体答案（高 rerank 风险样本） ---
add('Redis 主从复制（replication）的主从延迟怎么监控和解决？', False, 'OUT_OF_KB', [],
    '语料是 Redis 持久化主题，无主从复制内容——同产品不同主题。')
add('Kafka 消费者组的热点分区（skewed partition）该怎么均衡？', False, 'OUT_OF_KB', [],
    '语料讲 Lag 与积压处理，没有分区倾斜/均衡内容——同领域不同问题。')
add('ES 集群 rolling restart（滚动重启）的标准步骤是什么？', False, 'OUT_OF_KB', [],
    '语料有集群健康/水位/分片排查，但没有滚动重启流程——同产品不同运维任务。')
add('HikariCP 的 keepaliveTime 和 idleTimeout 的关系是什么？', False, 'OUT_OF_KB', [],
    '语料 FAQ 提到「开启 keepaliveTime 或调小 idleTimeout」这一处置，但 keepaliveTime 本身的默认值、'
    '工作机制与 idleTimeout 的交互在语料中没有展开，无法完整回答。')
add('nginx 的 worker_processes 和 worker_connections 应该怎么设置？', False, 'OUT_OF_KB', [],
    '语料只覆盖反向代理模块参数，没有 events/进程模型配置——同产品不同模块。')
add('ES 的 wait_for_status 参数有哪些取值？同步和异步调用有什么区别？', False, 'OUT_OF_KB', [],
    '语料提到 wait_for_status=green/yellow/red+timeout 的用法，但没有"同步/异步调用区别"的内容，'
    '问题的第二半无法回答。')

# ================= PARTIAL_EVIDENCE（12，全 false） =================
add('RDB 和 AOF 各自的优缺点是什么？无持久化模式相比两者又有什么优势？', False, 'PARTIAL_EVIDENCE', [],
    '语料有 RDB、AOF 优缺点，也有"无持久化：纯缓存场景使用"一句，但未展开无持久化的优势分析，'
    '问题要求的第三部分证据不完整。')
add('Kafka 积压的四种常见原因是什么？每种原因分别怎么处理？', False, 'PARTIAL_EVIDENCE', [],
    '语料列出 4 个常见原因，但处理手段按优先级给了 4 条整体措施，并未与原因一一对应，'
    '无法按"每种原因分别怎么处理"完整回答。')
add('HikariCP 的 maximumPoolSize、minimumIdle、connectionTimeout 的默认值和最小值分别是多少？', False, 'PARTIAL_EVIDENCE', [],
    '语料只有默认值（10/同 maximumPoolSize/30000 ms），没有任何最小值信息。')
add('nginx 代理的三种超时（connect/read/send）各自超出后会发生什么？', False, 'PARTIAL_EVIDENCE', [],
    '语料明确说明 read 超时「则关闭连接」，但 connect/send 超时后的具体行为没有说明，只能部分回答。')
add('Kafka 错误码表里消费端、集群、生产端三类错误各自的处置建议分别有哪些？', False, 'PARTIAL_EVIDENCE', [],
    '语料覆盖消费端与集群类错误码，但"生产端"只有 MESSAGE_TOO_LARGE 一条且 Row-group 分块'
    '（1-5 行 + 第 6 行分开），需要跨块聚合；且"三类各自的建议"要求完整枚举，证据不足。')
add('HikariCP 在低频后台任务、常规 Web、高吞吐 OLTP 三种场景下分别建议连接数是多少？其中低频场景的并发数依据是什么？', False, 'PARTIAL_EVIDENCE', [],
    '语料有三种场景的建议池大小，但低频场景"并发数 10"与"核心数×2"的说明并未解释依据细节，'
    '问题最后半部分无法回答。')
add('Redis 三种 fsync 策略各自的性能表现和丢数据风险分别是什么？什么场景该选 no？', False, 'PARTIAL_EVIDENCE', [],
    '语料给出三种策略的速度/安全性定性描述，但没有"该选 no 的场景"的指引内容。')
add('ES 磁盘超限处理的三个步骤分别解决什么问题？其中调整水位设置的风险是什么？', False, 'PARTIAL_EVIDENCE', [],
    '语料有完整三步操作，但"调整水位的风险"没有任何论述，只能部分回答。')
add('nginx 开启和关闭缓冲分别适合什么场景？proxy_busy_buffers_size 的默认值怎么计算？', False, 'PARTIAL_EVIDENCE', [],
    '语料说明两种模式的适用场景，busy_buffers 默认值说"默认为 proxy_buffers 中两块的大小"——'
    '可算出，但"怎么计算"的规则细节（向上取整/对齐）语料没有；严格看最后一问证据不足。')
add('RDB 的 fork 消耗和 AOF 的 everysec 刷盘，两者在性能上的量化对比数据是什么？', False, 'PARTIAL_EVIDENCE', [],
    '语料只有定性描述（fork 毫秒级~一秒、everysec 基本与快照同速），没有任何量化对比数据。')
add('Kafka 消费者组扩容后分区分配不均，应该怎么调整？assignment strategy 可以配置哪些？', False, 'PARTIAL_EVIDENCE', [],
    '语料说"消费者数与分区数最好成整除关系"，但分配策略（range/round robin/sticky 等）完全没有。')
add('ES 未分配分片的排查工具有哪些？每种工具的输出字段分别是什么含义？', False, 'PARTIAL_EVIDENCE', [],
    '语料列出 _cat/shards 与 _cluster/allocation/explain 及部分字段名，但"每种输出字段含义"'
    '没有逐项解释，问题要求超出证据范围。')

# ================= FOLLOW_UP（6，可回答） =================
# 场景式追问：语义依赖一个"隐含上文"，但答案可在语料中找到
add('磁盘清理完了之后，ES 索引上的只读块要怎么解除？', True, 'FOLLOW_UP', [anchor(ES_FIX)],
    '磁盘恢复后只读块不会自动解除，须手动 PUT /<索引名>/_settings {index.blocks.read_only_allow_delete: null}。')
add('那对因此分配失败的分片，还要做什么？', True, 'FOLLOW_UP', [anchor(ES_FIX)],
    'POST _cluster/reroute?retry_failed=true 重试分配。')
add('以后想避免再触发洪泛水位，应该调哪些配置？', True, 'FOLLOW_UP', [anchor(ES_DISK)],
    '调高 cluster.routing.allocation.disk.watermark.low/high/flood_stage 三档水位（或绝对值）。')
add('RDB 快照太频繁导致 fork 卡顿，怎么缓解？', True, 'FOLLOW_UP', [anchor(RD_SNAP), anchor(RD_RDB)],
    '调低快照频率（save N M 的 N/M），fork 耗时与数据集大小相关，大数据集 fork 会停服务数毫秒~一秒。')
add('线上出现积压告警，第一步应该看什么命令？', True, 'FOLLOW_UP', [anchor(KF_MON)],
    'kafka-consumer-groups.sh --describe --group <group> 查分区级 Lag。')
add('如果积压实在处理不过来，可以把消费者位移重置到最新跳过去吗？', True, 'FOLLOW_UP', [anchor(KF_BACKLOG)],
    '不要——重置位移会丢数据；应扩容消费者/加分区/优化消费逻辑，极端情况旁路暂存补偿。')

# ================= 校验与写出 =================
assert len(items) == 72, f"expected 72 items, got {len(items)}"
from collections import Counter
cat = Counter(i['category'] for i in items)
ans = Counter(i['answerable'] for i in items)
print('total:', len(items))
print('by category:', dict(sorted(cat.items())))
print('answerable:', dict(ans))
# 每类 answerable 明细
for c in sorted(cat):
    t = sum(1 for i in items if i['category'] == c and i['answerable'])
    f = cat[c] - t
    print(f'  {c:16s} true={t:2d} false={f:2d}')

json.dump(items, open('docs/eval/eval-answerability-v1-array.json', 'w'), ensure_ascii=False, indent=1)
print('written docs/eval/eval-answerability-v1-array.json')
