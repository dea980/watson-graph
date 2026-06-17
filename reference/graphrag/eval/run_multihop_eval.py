#!/usr/bin/env python3
"""
증권 멀티홉 retrieval 평가 하니스 (reference-only).

기존 eval/run_eval.py 와 같은 방식(/api/rag/ask 호출 → sources 검사 → recall)이되,
멀티홉의 핵심을 보기 위해 recall을 **category(multihop vs single-hop)와 hops별로 분해**한다.

핵심 질문: "벡터/BM25로는 안 풀리고 그래프로만 풀리는 멀티홉 케이스가 실제로 있는가, 얼마나 되는가?"
→ single-hop recall은 높고 multihop recall은 낮으면, 그게 그래프가 메울 격차(baseline gap)다.

표준 라이브러리만 사용. 앱 코드가 아니라 외부 보조 스크립트다.

Usage:
    # 1) 코퍼스 적재 (앱 실행 중이어야 함)
    bash ingest_multihop.sh
    # 2) 평가
    python3 run_multihop_eval.py
    API=http://localhost:8080 python3 run_multihop_eval.py

그래프를 통합한 뒤(reference/graphrag의 코드를 src로 이동 + AskRequest에 graph 토글 추가)에는
CONFIGS에 {"rerank":"mmr","hybrid":True,"graph":True} 를 추가해 +그래프 열을 같이 본다.
(통합 전에는 graph 파라미터를 보내지 않는다 — 서버가 unknown 필드로 400을 낼 수 있음.)
"""
import json, os, sys, urllib.request

API = os.environ.get("API", "http://localhost:8080")
HERE = os.path.dirname(os.path.abspath(__file__))

# 통합 전: 벡터-only vs 하이브리드(벡터+BM25) 비교.
# 통합 후: graph=True 열을 추가하면 +그래프 효과가 같은 표에서 보인다.
CONFIGS = [
    {"rerank": "none", "hybrid": False},   # 벡터-only
    {"rerank": "none", "hybrid": True},    # +BM25 (하이브리드)
    {"rerank": "mmr",  "hybrid": True},    # +rerank
]


def ask(question, namespace, rerank=None, hybrid=None, graph=None):
    payload = {"question": question, "namespace": namespace}
    if rerank is not None:
        payload["rerank"] = rerank
    if hybrid is not None:
        payload["hybrid"] = hybrid
    if graph is not None:            # 그래프 통합 후에만 사용
        payload["graph"] = graph
    req = urllib.request.Request(
        f"{API}/api/rag/ask",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def hit(case, resp):
    """retrieval hit: 검색된 sources(title+summary)에 기대 키워드가 모두 있나."""
    sources = resp.get("sources", []) or []
    blob = " ".join((s.get("title", "") + " " + s.get("summary", "")).lower()
                    for s in sources)
    if "expectTitleContains" in case:
        if case["expectTitleContains"].lower() not in blob:
            return False
    if "expectKeywords" in case:
        for kw in case["expectKeywords"]:
            if kw.lower() not in blob:
                return False
    return True


def pct(h, n):
    return f"{h}/{n} ({(h/n if n else 0):.0%})"


def sweep(cases):
    graded = [c for c in cases if c.get("category") != "refusal"]
    mh = [c for c in graded if c.get("category") == "multihop"]
    sh = [c for c in graded if c.get("category") == "single-hop"]
    refus = [c for c in cases if c.get("category") == "refusal"]

    print(f"\n=== Retrieval recall (총 {len(graded)}케이스: multihop {len(mh)}, single-hop {len(sh)}) ===\n")
    print(f"{'config':30} {'overall':>12} {'single-hop':>12} {'multihop':>12}   multihop-miss")
    print("-" * 92)
    for cfg in CONFIGS:
        def run(group):
            h, miss = 0, []
            for c in group:
                try:
                    resp = ask(c["question"], c.get("namespace", "default"),
                               cfg.get("rerank"), cfg.get("hybrid"), cfg.get("graph"))
                    ok = hit(c, resp)
                except Exception:
                    ok = False
                if ok:
                    h += 1
                else:
                    miss.append(c["id"])
            return h, miss
        ho, _ = run(graded)
        hs, _ = run(sh)
        hm, mmiss = run(mh)
        label = f"rerank={cfg.get('rerank')},hyb={cfg.get('hybrid')}" + \
                (",graph=True" if cfg.get("graph") else "")
        print(f"{label:30} {pct(ho,len(graded)):>12} {pct(hs,len(sh)):>12} "
              f"{pct(hm,len(mh)):>12}   {', '.join(mmiss) if mmiss else '-'}")

    print("\n해석: single-hop은 높고 multihop은 낮으면 → 그 격차가 '그래프가 메울 몫'.")
    print("      그래프 통합 후 multihop 열이 오르고 single-hop이 안 떨어지면 채택.\n")

    if refus:
        print(f"(refusal {len(refus)}케이스는 recall로 평가 불가 — 정답 근거가 코퍼스에 없음. "
              f"--judge 또는 수동으로 '모른다'고 답하는지 확인: {', '.join(c['id'] for c in refus)})\n")


def main():
    with open(os.path.join(HERE, "golden_multihop.json"), encoding="utf-8") as f:
        cases = json.load(f)
    sweep(cases)


if __name__ == "__main__":
    main()
