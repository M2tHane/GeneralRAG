package com.rag.domain.enums;

/**
 * 助手消息终态（contracts/openapi.yaml Message.status；V1__init.sql chat_message.status）。
 * 与 SSE 终态事件 done / error / canceled 一一对应、互斥；用户消息不使用本状态。
 */
public enum MessageStatus {
    COMPLETED,
    ERROR,
    CANCELED
}
