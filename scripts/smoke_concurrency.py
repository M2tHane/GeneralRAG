#!/usr/bin/env python3
"""R4.1 真实模型并发冒烟（走 /api/v1/qa/stream SSE，完整 Answerability 路径）。
8 道 PARTIAL_EVIDENCE/CONFUSABLE 题（rerank 分均 >= 0.65 → 必进 Judge），
分别以 1/4/8 并发各发 16 请求（每档 2 轮 × 8 题），统计
success/refusal/TIMEOUT/OVERLOADED/MODEL_ERROR/INVALID_RESPONSE 与延迟。"""
import json, os, time, statistics, uuid
from concurrent.futures import ThreadPoolExecutor
import urllib.request
import http.client

BASE_HOST, BASE_PORT = "localhost", 8080
KB = os.environ.get("SMOKE_KB_ID", "1aab239e-71c5-4cfc-a774-9d863639d275")  # 冒烟目标知识库

# 会话：并发档各建一个（后端 session 与 kb 绑定；不复用避免历史干扰）
def make_session():
    body = json.dumps({"kbId": KB}).encode()
    req = urllib.request.Request(f"http://{BASE_HOST}:{BASE_PORT}/api/v1/sessions", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())["id"]

SESSION = make_session()

QUESTIONS = [
    "RDB 和 AOF 各自的优缺点是什么？无持久化模式相比两者又有什么优势？",
    "Kafka 积压的四种常见原因是什么？每种原因分别怎么处理？",
    "HikariCP 的 maximumPoolSize、minimumIdle、connectionTimeout 的默认值和最小值分别是多少？",
    "nginx 代理的三种超时（connect/read/send）各自超出后会发生什么？",
    "Kafka 错误码表里消费端、集群、生产端三类错误各自的处置建议分别有哪些？",
    "HikariCP 在低频后台任务、常规 Web、高吞吐 OLTP 三种场景下分别建议连接数是多少？其中低频场景的并发数依据是什么？",
    "Redis 三种 fsync 策略各自的性能表现和丢数据风险分别是什么？什么场景该选 no？",
    "ES 磁盘超限处理的三个步骤分别解决什么问题？其中调整水位设置的风险是什么？",
]

def one(q):
    body = json.dumps({"kbId": KB, "sessionId": SESSION, "question": q,
                       "clientRequestId": str(uuid.uuid4())}).encode()
    conn = http.client.HTTPConnection(BASE_HOST, BASE_PORT, timeout=120)
    t0 = time.time()
    events = []
    try:
        conn.request("POST", "/api/v1/qa/stream", body=body,
                     headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
        resp = conn.getresponse()
        if resp.status != 200:
            return {"ok": False, "lat": (time.time()-t0)*1000, "err": f"HTTP {resp.status}"}
        body_text = resp.read().decode("utf-8", "replace")
        cur = None
        for line in body_text.split("\n"):
            line = line.strip()
            if line.startswith("event:"):
                cur = line.split(":", 1)[1].strip()
                events.append(cur)
            elif line.startswith("data:") and cur == "stage":
                try:
                    p = json.loads(line[5:].strip())
                    events.append("STAGE:" + str(p.get("stage")))
                except Exception:
                    pass
        terminal = "done" if "done" in events else ("error" if "error" in events else "none")
        lat = (time.time() - t0) * 1000
        stages = [e for e in events if e.startswith("STAGE:")]
        dec = next((e.split(":",1)[1] for e in stages if "ANSWERABILITY_CHECKED" in e), None)
        return {"ok": terminal == "done", "lat": lat, "decision": dec}
    except Exception as e:
        return {"ok": False, "lat": (time.time()-t0)*1000, "err": str(e)[:120]}
    finally:
        conn.close()

def run(conc, rounds=2):
    qs = QUESTIONS * rounds
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=conc) as pool:
        rs = list(pool.map(one, qs))
    wall = (time.time() - t0) * 1000
    lats = sorted(r["lat"] for r in rs)
    def p(q): return lats[min(len(lats)-1, int(q*len(lats)+0.999)-1)] if lats else 0
    return {
        "requests": len(qs), "concurrency": conc,
        "success": sum(1 for r in rs if r["ok"]),
        "errors": [r.get("err") for r in rs if not r["ok"]],
        "decisions": {k: sum(1 for r in rs if r.get("decision")==k) for k in
                      {r.get("decision") for r in rs if r.get("decision")}},
        "avgMs": round(statistics.mean(lats)),
        "p50Ms": round(p(0.5)), "p95Ms": round(p(0.95)), "wallMs": round(wall),
    }

for conc in (1, 4, 8):
    print(json.dumps(run(conc), ensure_ascii=False), flush=True)
    time.sleep(2)
