/**
 * 内置示例问题（第一版：与原型演示文案一致，来自 prototype/data.js sampleQuestions）。
 * T9 联调后如需按知识库动态推荐，再由后端接口替换。
 */
export const SAMPLE_QUESTIONS = [
  "支付回调超时一般怎么排查？",
  "签名验签的时间戳允许多大偏差？",
  "重复发起支付请求会怎样？",
  "网关的容量上限是多少？",
  "数据库连接池耗尽怎么应急？",
  "ES 集群 yellow 状态如何排查？",
  "Kafka 积压为什么不能直接重置 offset？",
] as const;
