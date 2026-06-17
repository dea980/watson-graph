# 멀티홉 eval 픽스처 설계 (변별·정직)

이 eval은 "graph가 vector를 이긴다"를 보여주려는 게 아니라 **graph가 언제 이기고 언제 불필요한지를 정직하게 가른다.** 결과를 미리 정해놓고 데이터를 맞추면 데모의 신뢰가 무너진다.

## 왜 이전 픽스처는 변별력이 없었나

- 코퍼스가 본문 6개뿐이고 distractor가 날씨/점심이라, 멀티홉 질문도 vector-only가 3/3을 찍었다.
- 채점이 "출처 어디든 키워드가 있으면 통과"라, 정답이 아닌 문서가 같은 단어를 담고 있으면 거짓 통과했다.

→ graph가 일할 헤드룸이 없어 "+graph가 올랐다"를 보여줄 수 없었다.

## 변별을 만드는 세 장치

1. **하드네거티브** — 질문 앵커와 표면이 비슷하지만 오답인 문서를 깐다.
   - 보고서 질문에는 분기보고서(10), 감사보고서(11), 공시(03)를 깔아 정답(ELS 규제 02)을 벡터 상위에서 밀어낸다.
   - 지수 질문에는 코스피(07), 코스닥(08), 한국거래소 개요(09)를 깐다.
   - 감독기구 질문에는 상품·규제 문서(02, 13)를 깔아 벡터가 그럴듯한 오답을 올리게 한다.
2. **vector-hard 멀티홉** — 질문과 정답 문서가 표면 어휘를 공유하지 않게 한다. 정답은 관계(다리 엔티티)로만 도달된다.
3. **문서 단위 정밀 채점** — `run_multihop_eval.py`가 키워드가 아니라 **gold 문서가 실제 검색됐는지**로 본다. 오답 문서가 같은 단어를 담아도 통과하지 않는다.

## 정직성(falsifiable) 장치

- **단일홉 대조군 6개** — 질문이 정답 문서를 직접 가리키는 케이스. graph를 켜도 recall이 떨어지면 안 된다(그래프 노이즈 체크). `sh-quarterly-law`는 하드네거티브가 깔린 상태에서도 단일홉은 벡터가 정답을 직접 짚어야 함을 본다.
- graph가 **질 수 있는** 케이스(단일홉)를 일부러 포함했다. 그럼에도 멀티홉에서만 이기면 그게 진짜 이유다.
- `mh-product-supervisor`의 두 번째 홉은 시드 관계가 아니라 UNTYPED(공출현)다. 부분 타입 경로도 작동함을, 그리고 모든 관계가 타입드일 필요는 없음을 정직하게 드러낸다.

## 기대 결과(가설, 실행으로 검증)

| 구간 | vector-only | +graph |
|---|---|---|
| 단일홉(6) | 높음 | 같음(안 떨어져야) |
| 멀티홉(4) | 낮음(하드네거티브로 정답 탈락) | 높아져야 |

멀티홉에서 +graph가 vector-only를 못 이기면 → **"이 코퍼스/도메인에선 graph가 recall로 정당화되지 않는다"**가 정직한 결론이다. 그 경우 graph는 기본 off로 두고, 답변 근거(타입드 경로) 설명용으로만 포지셔닝한다. 데모 서사는 숫자 자랑이 아니라 "각 레이어가 언제 값을 하는지 안다"가 핵심이다.

## 코퍼스 (kr-securities, 15개 = 본문 13 + 노이즈 2)

원본 6: 01_kospi200, 02_els_regulation, 03_disclosure, 04_short_selling, 05_ipo, 06_etf_basics
추가 7: 07_kospi_index, 08_kosdaq, 09_krx_overview, 10_quarterly_report, 11_audit_report, 12_fss_supervision, 13_etn
노이즈 2: 90_noise_weather, 91_noise_lunch

## 실행

```bash
./mvnw spring-boot:run
bash eval/ingest_multihop.sh            # 15개 적재 (glob이 새 문서 자동 포함)
curl 'localhost:8080/api/data/graph/stats?namespace=kr-securities'   # 노드/엣지/타입 확인
python3 eval/run_multihop_eval.py       # vector-only → +bm25 → +graph A/B
```
