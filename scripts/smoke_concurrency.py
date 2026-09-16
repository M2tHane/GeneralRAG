#!/usr/bin/env python3
"""R4.1.1 真实模型并发冒烟（走 /api/v1/qa/stream SSE，完整 Answerability 路径）。

8 道 CONFUSABLE/PARTIAL_EVIDENCE 题，top1 rerank 分均 >= 当前正式阈值 0.75
（R4.1 sweep 校准，见 docs/round4/04-R4.1评测与校准.md §B）→ 全部进入 Judge，
不会提前被 LOW_SCORE_REFUSAL 挡掉。

以 1/4/8 并发各发 16 请求（每档 2 轮 × 8 题）。解析 ANSWERABILITY_CHECKED
stage 的机器可读字段 decisionType / judgeFailureType（R4.1.1 契约），自动输出：

    requests / success / JUDGE_ACCEPT / JUDGE_REFUSE / LOW_SCORE_REFUSAL /
    TIMEOUT / OVERLOADED / MODEL_ERROR / INVALID_RESPONSE / P50 / P95 / wall

用法：SMOKE_KB_ID=<kbId> python3 scripts/smoke_concurrency.py
"""
import json, os, time, statistics, uuid
from concurrent.futures import ThreadPoolExecutor
import urllib.request
import http.client

BASE_HOST, BASE_PORT = "localhost", 8080
KB = os.environ.get("SMOKE_KB_ID", "1aab239e-71c5-4cfc-a774-9d863639d275")  # 冒烟目标知识库

def make_session():
    body = json.dumps({"kbId": KB}).encode()
    req = urllib.request.Request(f"http://{BASE_HOST}:{BASE_PORT}/api/v1/sessions", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())["id"]

# 8 道题均来自 answerability v2 数据集（seq55-58/61/56/62/66），R4.1 sweep 实测
# top1 rerank 分：0.9907 / 1.0000 / 0.9233 / 0.9275 / 1.0000 / 0.9797 / 0.9962 / 0.9233
# 全部 >= 正式阈值 0.75 → 必进 Judge。
QUESTIONS = [
    "RDB 和 AOF 各自的优缺点是什么？无持久化模式相比两者又有什么优势？",          # 0.9907
    "Kafka 积压的四种常见原因是什么？每种原因分别怎么处理？",                     # 1.0000
    "HikariCP 的 maximumPoolSize、minimumIdle、connectionTimeout 的默认值和最小值分别是多少？",  # 0.9233
    "nginx 代理的三种超时（connect/read/send）各自超出后会发生什么？",            # 0.9275
    "Redis 三种 fsync 策略各自的性能表现和丢数据风险分别是什么？什么场景该选 no？",  # 1.0000
    "ES 磁盘超限处理的三个步骤分别解决什么问题？其中调整水位设置的风险是什么？",    # 0.9797
    "ES 未分配分片的排查工具有哪些？每种工具的输出字段分别是什么含义？",           # 0.9962
    "Kafka 积压的四种常见原因是什么？每种原因分别怎么处理？",                     # 1.0000（第 2 轮复用）
]

DEGRADED_TYPES = ("TIMEOUT", "OVERLOADED", "MODEL_ERROR", "INVALID_RESPONSE")

def one(q, session):
    body = json.dumps({"kbId": KB, "sessionId": session, "question": q,
                       "clientRequestId": str(uuid.uuid4())}).encode()
    conn = http.client.HTTPConnection(BASE_HOST, BASE_PORT, timeout=120)
    t0 = time.time()
    try:
        conn.request("POST", "/api/v1/qa/stream", body=body,
                     headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
        resp = conn.getresponse()
        if resp.status != 200:
            return {"ok": False, "lat": (time.time()-t0)*1000, "err": f"HTTP {resp.status}"}
        body_text = resp.read().decode("utf-8", "replace")
        cur, decision, failure = None, None, None
        terminal = "none"
        for line in body_text.split("\n"):
            line = line.strip()
            if line.startswith("event:"):
                cur = line.split(":", 1)[1].strip()
                if cur in ("done", "error", "canceled"):
                    terminal = cur
            elif line.startswith("data:") and cur == "stage":
                try:
                    p = json.loads(line[5:].strip())
                    if p.get("stage") == "ANSWERABILITY_CHECKED":
                        decision = p.get("decisionType")
                        failure = p.get("judgeFailureType")
                except Exception:
                    pass
        return {"ok": terminal == "done", "lat": (time.time()-t0)*1000,
                "decision": decision, "failure": failure}
    except Exception as e:
        return {"ok": False, "lat": (time.time()-t0)*1000, "err": str(e)[:120]}
    finally:
        conn.close()

def run(conc, rounds=2):
    session = make_session()  # 每档独立会话，避免历史串扰
    qs = QUESTIONS * rounds
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=conc) as pool:
        rs = list(pool.map(lambda q: one(q, session), qs))
    wall = (time.time() - t0) * 1000
    lats = sorted(r["lat"] for r in rs)
    def p(q): return lats[min(len(lats)-1, int(q*len(lats)+0.999)-1)] if lats else 0
    out = {
        "requests": len(qs), "concurrency": conc,
        "success": sum(1 for r in rs if r["ok"]),
        "JUDGE_ACCEPT": sum(1 for r in rs if r.get("decision") == "JUDGE_ACCEPT"),
        "JUDGE_REFUSE": sum(1 for r in rs if r.get("decision") == "JUDGE_REFUSE"),
        "LOW_SCORE_REFUSAL": sum(1 for r in rs if r.get("decision") == "LOW_SCORE_REFUSAL"),
        "TIMEOUT": sum(1 for r in rs if r.get("failure") == "TIMEOUT"),
        "OVERLOADED": sum(1 for r in rs if r.get("failure") == "OVERLOADED"),
        "MODEL_ERROR": sum(1 for r in rs if r.get("failure") == "MODEL_ERROR"),
        "INVALID_RESPONSE": sum(1 for r in rs if r.get("failure") == "INVALID_RESPONSE"),
        "noDecision": sum(1 for r in rs if r["ok"] and not r.get("decision")),
        "avgMs": round(statistics.mean(lats)),
        "p50Ms": round(p(0.5)), "p95Ms": round(p(0.95)), "wallMs": round(wall),
        "errors": [r.get("err") for r in rs if not r["ok"]],
    }
    return out

print(json.dumps({"kbId": KB}, ensure_ascii=False), flush=True)
for conc in (1, 4, 8):
    print(json.dumps(run(conc), ensure_ascii=False), flush=True)
    time.sleep(2)
