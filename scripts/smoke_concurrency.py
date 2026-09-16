#!/usr/bin/env python3
"""R4.1.2 真实模型并发冒烟（走 /api/v1/qa/stream SSE，完整 Answerability 路径）。

与 R4.1.1 版的关键差异（实验隔离）：
  1. **每请求独立 session**——R4.1.1 版共用一个 session，FOLLOW_UP history 修复后
     请求间历史互相污染（串行档 history 越来越长，并发档 history 内容随机），
     会扭曲 Judge latency / ACCEPT-REFUSE 比例 / token 用量。本版每请求建独立
     session（history 恒为 []，session 创建不计入 latency），真正测同一批问题 +
     同一模型 + 相同 prompt 形态 × 不同 concurrency × Judge bulkhead。
  2. **8 个唯一问题**（R4.1.1 版第 8 题与第 2 题重复），全部 CONFUSABLE/
     PARTIAL_EVIDENCE 形态，R4.1 sweep 实测 top1 rerank 分 >= 0.75（正式阈值），
     不会提前被 LOW_SCORE_REFUSAL 挡掉。若某题因语料/模型变化跌破 0.75，请换题
     而不是降低阈值。

用法：SMOKE_KB_ID=<kbId> python3 scripts/smoke_concurrency.py

前置条件（preflight 会读取 /actuator/env 校验，失败则拒绝运行）：
  rag.retrieval.refusal.rerank-threshold = 0.75（正式校准值，见
  docs/round4/04-R4.1评测与校准.md §B）
  rag.retrieval.answerability.enabled = true
  rag.retrieval.answerability.max-concurrent-judges = 4
  rag.retrieval.answerability.bulkhead-wait-ms = 500
"""
import json, os, statistics, sys, time, urllib.error, uuid
from concurrent.futures import ThreadPoolExecutor
import urllib.request
import http.client

BASE = "http://localhost:8080"
BASE_HOST, BASE_PORT = "localhost", 8080
KB = os.environ.get("SMOKE_KB_ID", "1aab239e-71c5-4cfc-a774-9d863639d275")  # 冒烟目标知识库

# 8 道唯一题（answerability v2 数据集 CONFUSABLE/PARTIAL_EVIDENCE 形态），
# R4.1 sweep（docs/round4/04 §B）实测 top1 rerank 分：
QUESTIONS = [
    ("RDB 和 AOF 各自的优缺点是什么？无持久化模式相比两者又有什么优势？", 0.9907),   # seq55 PARTIAL
    ("Kafka 积压的四种常见原因是什么？每种原因分别怎么处理？", 1.0000),              # seq56 PARTIAL
    ("HikariCP 的 maximumPoolSize、minimumIdle、connectionTimeout 的默认值和最小值分别是多少？", 0.9233),  # seq57
    ("nginx 代理的三种超时（connect/read/send）各自超出后会发生什么？", 0.9275),     # seq58 PARTIAL
    ("Redis 三种 fsync 策略各自的性能表现和丢数据风险分别是什么？什么场景该选 no？", 1.0000),  # seq61 PARTIAL
    ("ES 磁盘超限处理的三个步骤分别解决什么问题？其中调整水位设置的风险是什么？", 0.9797),  # seq62 PARTIAL
    ("ES 未分配分片的排查工具有哪些？每种工具的输出字段分别是什么含义？", 0.9962),    # seq66 PARTIAL
    ("AOF 的 appendfsync always 模式下，Redis 每秒最多能扛多少写入 QPS？", 0.7790),  # seq33 CONFUSABLE
]
EXPECTED_THRESHOLD = 0.75
# preflight 行为探针题：语料外题（sweep seq41 实测 top1 rerank 0.1415），
# 用于验证正式阈值 0.75 与 answerability 开启真实生效——低于 0.75 必须被
# LOW_SCORE_REFUSAL 直接拒答（若阈值被误调低或 answerability 关闭，此探针失败）。
PROBE_QUESTION = "怎么用 Git rebase 整理提交历史？"

def preflight():
    """配置校验：优先 /actuator/env（若暴露）；否则退回行为探针（debug 检索接口）。"""
    expectations = {
        "rag.retrieval.refusal.rerank-threshold": EXPECTED_THRESHOLD,
        "rag.retrieval.answerability.enabled": True,
        "rag.retrieval.answerability.max-concurrent-judges": 4,
        "rag.retrieval.answerability.bulkhead-wait-ms": 500,
    }
    try:
        with urllib.request.urlopen(BASE + "/actuator/env", timeout=10) as r:
            env = json.loads(r.read())
    except urllib.error.HTTPError as e:
        if e.code != 404:
            print(json.dumps({"preflight": "SKIP", "reason": "/actuator/env 不可读（%s）；"
                  "请确保服务以 rerank-threshold=0.75 + answerability 开启 + bulkhead(4,500ms) 启动"
                  % e.code}, ensure_ascii=False))
            _behavior_probe()
            return
        # 404 = env 端点未暴露（本项目的安全默认：仅暴露 health,info,metrics）→ 行为探针
        _behavior_probe()
        return
    except Exception as e:
        print(json.dumps({"preflight": "SKIP", "reason": str(e)[:80]}, ensure_ascii=False))
        _behavior_probe()
        return
    found = {}
    for source in env.get("propertySources", []):
        props = source.get("properties", {}) or {}
        for key, expect in expectations.items():
            if key in props and key not in found:
                found[key] = props[key].get("value")
    for key, expect in expectations.items():
        actual = found.get(key)
        if actual is not None and str(actual) != str(expect):
            print(json.dumps({"preflight": "FAIL", "key": key, "expected": str(expect),
                              "actual": str(actual)}, ensure_ascii=False))
            sys.exit(2)
    print(json.dumps({"preflight": "OK(env)", "kbId": KB}, ensure_ascii=False))

def _behavior_probe():
    """sanity check：语料外题被低分门控直拒只能证明"低分门控在工作"——
    不能精确证明 rerank threshold 恰为 0.75 / maxConcurrentJudges=4 /
    bulkheadWaitMs=500。精确校验需 /actuator/env（未暴露时不新增接口），
    启动服务时请自行确保这些配置（见文件头前置条件）。"""
    body = json.dumps({"kbId": KB, "question": PROBE_QUESTION, "mode": "HYBRID_RERANK"}).encode()
    req = urllib.request.Request(BASE + "/api/v1/debug/retrieval", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.loads(r.read())
    except Exception as e:
        print(json.dumps({"preflight": "SKIP", "reason": "探针请求失败：%s" % str(e)[:80]}, ensure_ascii=False))
        return
    dec = (d.get("answerability") or {}).get("decisionType")
    if dec == "LOW_SCORE_REFUSAL":
        print(json.dumps({"preflight": "OK(sanity-check)", "probeDecision": dec,
                          "explanation": "语料外探针题被低分门控直拒——门控在工作；"
                                         "精确的 threshold/bulkhead 值请以服务启动配置为准"}, ensure_ascii=False))
    else:
        print(json.dumps({"preflight": "FAIL", "probeDecision": dec,
                          "explanation": "语料外探针题未被直拒——低分门控未生效"
                                         "（threshold 过低或 answerability 关闭）"}, ensure_ascii=False))
        sys.exit(2)

def make_session():
    body = json.dumps({"kbId": KB}).encode()
    req = urllib.request.Request(BASE + "/api/v1/sessions", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())["id"]

def one(question, session):
    body = json.dumps({"kbId": KB, "sessionId": session, "question": question,
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
    qs = [q for q, _ in QUESTIONS] * rounds
    # 每请求独立 session（history 恒空）；session 创建不计入 latency
    sessions = [make_session() for _ in qs]
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=conc) as pool:
        rs = list(pool.map(lambda pair: one(pair[0], pair[1]), zip(qs, sessions)))
    wall = (time.time() - t0) * 1000
    lats = sorted(r["lat"] for r in rs)
    def p(q): return lats[min(len(lats)-1, int(q*len(lats)+0.999)-1)] if lats else 0
    return {
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

if __name__ == "__main__":
    preflight()
    print(json.dumps({"kbId": KB, "expectedThreshold": EXPECTED_THRESHOLD,
                      "questionCount": len(QUESTIONS),
                      "questions": [{"q": q[:28], "sweepTop1": s} for q, s in QUESTIONS]},
                     ensure_ascii=False), flush=True)
    for conc in (1, 4, 8):
        print(json.dumps(run(conc), ensure_ascii=False), flush=True)
        time.sleep(2)
