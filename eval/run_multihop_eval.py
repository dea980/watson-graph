#!/usr/bin/env python3
"""
증권 멀티홉 retrieval 평가 하니스 (watson-graph).

/api/rag/ask 를 EVAL-ONLY 토글(rerank/hybrid/graph)로 스윕해, recall을
category(multihop vs single-hop)별로 분해한다. watson-graph는 graph 토글을
지원하므로 **+그래프 A/B를 바로** 볼 수 있다.

핵심: single-hop은 높은데 multihop이 낮으면 → 그 격차를 그래프가 메워야 한다.
      graph=True에서 multihop이 오르고 single-hop이 안 떨어지면 → 그래프 채택.

표준 라이브러리만 사용.

Usage:
    # 1) 앱 실행:  ./mvnw spring-boot:run
    # 2) 적재:     bash eval/ingest_multihop.sh
    # 3) 평가:     python3 eval/run_multihop_eval.py
    API=http://localhost:8080 python3 eval/run_multihop_eval.py
"""
import json, os, urllib.request

API = os.environ.get("API", "http://localhost:8080")
HERE = os.path.dirname(os.path.abspath(__file__))

CONFIGS = [
    {"label": "vector-only",      "rerank": "none", "hybrid": False, "graph": False},
    {"label": "+bm25 (hybrid)",   "rerank": "none", "hybrid": True,  "graph": False},
    {"label": "+graph",           "rerank": "none", "hybrid": True,  "graph": True},
    {"label": "+graph +rerank",   "rerank": "mmr",  "hybrid": True,  "graph": True},
]


def ask(question, namespace, cfg):
    payload = {"question": question, "namespace": namespace,
               "rerank": cfg["rerank"], "hybrid": cfg["hybrid"], "graph": cfg["graph"]}
    req = urllib.request.Request(
        f"{API}/api/rag/ask",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def hit(case, resp):
    sources = resp.get("sources", []) or []
    titles = [s.get("title", "") for s in sources]
    blob = " ".join((s.get("title", "") + " " + s.get("summary", "")).lower() for s in sources)
    # 1) 문서 단위 정밀 채점(주): gold 문서가 실제로 검색됐는가.
    #    키워드만 보면 같은 단어를 담은 오답 문서로도 거짓 통과한다(하드네거티브 무력화).
    #    gold stem("02_els_regulation")이 출처 제목("02_els_regulation.md #1")에 있는지로 본다.
    gold = case.get("gold")
    if gold:
        stem = gold.rsplit(".", 1)[0]
        if not any(stem in t for t in titles):
            return False
    # 2) 키워드 보조(부): gold 없는 경우 폴백 + 정답 내용 sanity.
    if "expectTitleContains" in case and case["expectTitleContains"].lower() not in blob:
        return False
    for kw in case.get("expectKeywords", []):
        if kw.lower() not in blob:
            return False
    return True


def pct(h, n):
    return f"{h}/{n} ({(h/n if n else 0):.0%})"


def main():
    with open(os.path.join(HERE, "golden_multihop.json"), encoding="utf-8") as f:
        cases = json.load(f)
    graded = [c for c in cases if c.get("category") != "refusal"]
    mh = [c for c in graded if c.get("category") == "multihop"]
    sh = [c for c in graded if c.get("category") == "single-hop"]
    refus = [c for c in cases if c.get("category") == "refusal"]

    print(f"\n=== Retrieval recall (multihop {len(mh)}, single-hop {len(sh)}) ===\n")
    print(f"{'config':20} {'overall':>12} {'single-hop':>12} {'multihop':>12}   multihop-miss")
    print("-" * 88)
    for cfg in CONFIGS:
        def run(group):
            h, miss = 0, []
            for c in group:
                try:
                    ok = hit(c, ask(c["question"], c.get("namespace", "default"), cfg))
                except Exception:
                    ok = False
                (miss.append(c["id"]) if not ok else None)
                h += 1 if ok else 0
            return h, miss
        ho, _ = run(graded); hs, _ = run(sh); hm, mmiss = run(mh)
        print(f"{cfg['label']:20} {pct(ho,len(graded)):>12} {pct(hs,len(sh)):>12} "
              f"{pct(hm,len(mh)):>12}   {', '.join(mmiss) if mmiss else '-'}")

    print("\n해석: vector-only/+bm25에서 multihop이 낮고, +graph에서 multihop이 오르면 그래프가 일한 것.")
    if refus:
        print(f"(refusal {len(refus)}: recall로 평가 불가 — 수동/judge로 '모른다' 확인: "
              f"{', '.join(c['id'] for c in refus)})")
    print()


if __name__ == "__main__":
    main()
