/**
 * SSE 按行解析器（POST fetch 流专用，EventSource 不支持 POST）。
 *
 * 传输格式（契约 /api/v1/qa/stream）：
 *   event: <类型>\n
 *   data: <JSON 载荷>\n
 *   \n            （事件以空行结束）
 *   :ping         （注释行心跳，忽略）
 *
 * 跨 chunk 断行由内部缓冲处理：不完整的行保留到下一次 push。
 */
export interface SseEvent {
  event: string;
  data: string;
}

export interface SseParser {
  /** 喂入一段可能任意断行的文本 */
  push(chunk: string): void;
  /** 流结束时调用：冲刷缓冲中残留的最后一行/最后一个事件 */
  end(): void;
}

export function createSseParser(onEvent: (e: SseEvent) => void): SseParser {
  let buffer = "";
  let eventName = "";
  let dataLines: string[] = [];

  function dispatch() {
    if (dataLines.length === 0) {
      // 无 data 的事件（如仅有 event 行）按规范丢弃
      eventName = "";
      return;
    }
    const data = dataLines.join("\n");
    dataLines = [];
    const event = eventName || "message";
    eventName = "";
    onEvent({ event, data });
  }

  function processLine(rawLine: string) {
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    if (line === "") {
      dispatch();
      return;
    }
    if (line.startsWith(":")) {
      // 注释行（:ping 心跳），忽略
      return;
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") {
      eventName = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
    // 其余字段（id/retry 等）本接口未使用，忽略
  }

  return {
    push(chunk: string) {
      buffer += chunk;
      let idx: number;
      while ((idx = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        processLine(line);
      }
    },
    end() {
      if (buffer !== "") {
        processLine(buffer);
        buffer = "";
      }
      dispatch();
    },
  };
}
