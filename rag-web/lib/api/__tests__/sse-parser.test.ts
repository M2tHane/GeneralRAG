import { describe, it, expect } from "vitest";
import { createSseParser, type SseEvent } from "@/lib/api/sse";

function collect() {
  const events: SseEvent[] = [];
  const parser = createSseParser((e) => events.push(e));
  return { events, parser };
}

describe("SSE 解析器（POST /qa/stream 事件流）", () => {
  it("按契约顺序解析 stage → token* → citations → done 完整序列", () => {
    const { events, parser } = collect();
    parser.push(
      [
        "event: stage",
        'data: {"stage":"RETRIEVAL_STARTED"}',
        "",
        "event: stage",
        'data: {"stage":"RETRIEVAL_COMPLETED","detail":"命中 6 块 · 128ms"}',
        "",
        "event: stage",
        'data: {"stage":"GENERATION_STARTED"}',
        "",
        "",
      ].join("\n")
    );
    parser.push(
      [
        "event: token",
        'data: {"delta":"根据"}',
        "",
        "event: token",
        'data: {"delta":"文档"}',
        "",
        "event: citations",
        'data: {"citations":[{"chunkId":"d1-c0","docId":"d1","docName":"a.md","titlePath":"a","score":0.9}]}',
        "",
        "event: done",
        'data: {"messageId":"m1","elapsedMs":3200}',
        "",
      ].join("\n")
    );
    parser.end();

    expect(events.map((e) => e.event)).toEqual([
      "stage",
      "stage",
      "stage",
      "token",
      "token",
      "citations",
      "done",
    ]);
    expect(JSON.parse(events[1].data).detail).toBe("命中 6 块 · 128ms");
    expect(JSON.parse(events[3].data).delta).toBe("根据");
    expect(JSON.parse(events[5].data).citations).toHaveLength(1);
    expect(JSON.parse(events[6].data).messageId).toBe("m1");
  });

  it("忽略 :ping 心跳注释行", () => {
    const { events, parser } = collect();
    parser.push(
      [
        ":ping",
        "",
        "event: token",
        'data: {"delta":"hi"}',
        "",
        ":ping",
        "",
      ].join("\n")
    );
    parser.end();
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe("token");
  });

  it("跨 chunk 断行缓冲：事件被切成任意片段仍能正确解析", () => {
    const { events, parser } = collect();
    const full =
      'event: stage\ndata: {"stage":"RETRIEVAL_STARTED"}\n\nevent: token\ndata: {"delta":"你好"}\n\n';
    // 逐字符喂入，模拟最恶劣的分片
    for (const ch of full) parser.push(ch);
    parser.end();
    expect(events.map((e) => e.event)).toEqual(["stage", "token"]);
    expect(JSON.parse(events[1].data).delta).toBe("你好");
  });

  it("跨 chunk 断行：data 行中间被切断", () => {
    const { events, parser } = collect();
    parser.push('event: token\ndata: {"delta":"部');
    parser.push('分内容"}\n\n');
    parser.end();
    expect(events).toHaveLength(1);
    expect(JSON.parse(events[0].data).delta).toBe("部分内容");
  });

  it("处理 \\r\\n 行尾", () => {
    const { events, parser } = collect();
    parser.push('event: done\r\ndata: {"messageId":"m9"}\r\n\r\n');
    parser.end();
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe("done");
  });

  it("canceled 事件分支", () => {
    const { events, parser } = collect();
    parser.push('event: canceled\ndata: {"reason":"USER_CANCELED"}\n\n');
    parser.end();
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe("canceled");
    expect(JSON.parse(events[0].data).reason).toBe("USER_CANCELED");
  });

  it("error 事件分支（携带错误体）", () => {
    const { events, parser } = collect();
    parser.push(
      [
        "event: token",
        'data: {"delta":"部分回答"}',
        "",
        "event: error",
        'data: {"error":{"code":"MODEL_TIMEOUT","message":"模型服务超时","requestId":"r1"}}',
        "",
      ].join("\n")
    );
    parser.end();
    expect(events.map((e) => e.event)).toEqual(["token", "error"]);
    const err = JSON.parse(events[1].data).error;
    expect(err.code).toBe("MODEL_TIMEOUT");
    expect(err.message).toBe("模型服务超时");
  });

  it("流末尾无换行的最后一行也能被 end() 冲刷", () => {
    const { events, parser } = collect();
    parser.push('event: done\ndata: {"messageId":"m2"}');
    parser.end();
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe("done");
  });

  it("无 data 的纯 event 行被丢弃，不产生事件", () => {
    const { events, parser } = collect();
    parser.push("event: ping\n\n");
    parser.end();
    expect(events).toHaveLength(0);
  });
});
