package com.miniwatson.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 증권 · 자본시장 (공시 · IR) 도메인 사전.
 *
 * 왜 한 도메인으로 좁혔나: 범용 사전은 "넓지만 얕다". 그래프가 빛나려면 한 도메인의 엔티티·관계가
 * 촘촘해야 한다. 자본시장/공시는 (1) DART 전자공시가 공개 API라 실데이터 인덱싱이 가능하고,
 * (2) 기업↔공시↔법령↔상품↔기관↔이벤트가 자연스러운 멀티홉이며, (3) 약어·규정 밀도가 높아
 * 도메인화 효과가 가장 크다.
 *
 * 왜 LLM 0회: 로컬(Ollama)에선 청크마다 LLM 추출이 인덱싱 비용을 폭발시킨다. 도메인 지식을
 * "정적 사전 + 규칙"으로 주입해 결정적이고 빠르게 엔티티를 뽑는다. AcronymExpander(문서 내 정의
 * 자동수집)와 상호보완 — 이쪽은 자본시장 선험지식 seed.
 *
 * 구성: ACRONYMS(약어->정식명) + INSTITUTIONS / LAWS / PRODUCTS / EVENTS (그래프 노드 시드).
 * 순수 static — Spring 없이 단위 테스트/standalone 실행 가능.
 */
public final class DomainGlossary {

    private DomainGlossary() {}

    public enum EntityType { INSTITUTION, LAW, PRODUCT, EVENT, INDEX }

    /** 약어 -> 정식명. 자본시장/공시/IR/회계/시장지수. */
    public static final Map<String, String> ACRONYMS = new LinkedHashMap<>();
    static {
        // --- 감독/시장 기관 ---
        ACRONYMS.put("FSC", "금융위원회");
        ACRONYMS.put("SFC", "증권선물위원회");
        ACRONYMS.put("FSS", "금융감독원");
        ACRONYMS.put("KRX", "한국거래소");
        ACRONYMS.put("KSD", "한국예탁결제원");
        ACRONYMS.put("KOFIA", "금융투자협회");
        ACRONYMS.put("KOSCOM", "코스콤");
        // --- 공시/회계 ---
        ACRONYMS.put("DART", "전자공시시스템");
        ACRONYMS.put("XBRL", "재무정보표준화언어");
        ACRONYMS.put("IFRS", "국제회계기준");
        ACRONYMS.put("K-IFRS", "한국채택국제회계기준");
        // --- 시장/지수 ---
        ACRONYMS.put("KOSPI", "코스피");
        ACRONYMS.put("KOSDAQ", "코스닥");
        ACRONYMS.put("KONEX", "코넥스");
        // --- 밸류에이션 지표 ---
        ACRONYMS.put("PER", "주가수익비율");
        ACRONYMS.put("PBR", "주가순자산비율");
        ACRONYMS.put("EPS", "주당순이익");
        ACRONYMS.put("BPS", "주당순자산");
        ACRONYMS.put("DPS", "주당배당금");
        ACRONYMS.put("ROE", "자기자본이익률");
        ACRONYMS.put("ROA", "총자산이익률");
        // --- 상품/증권 ---
        ACRONYMS.put("ETF", "상장지수펀드");
        ACRONYMS.put("ETN", "상장지수증권");
        ACRONYMS.put("ELS", "주가연계증권");
        ACRONYMS.put("ELW", "주식워런트증권");
        ACRONYMS.put("DLS", "파생결합증권");
        ACRONYMS.put("MMF", "머니마켓펀드");
        ACRONYMS.put("CMA", "종합자산관리계좌");
        ACRONYMS.put("ISA", "개인종합자산관리계좌");
        ACRONYMS.put("IRP", "개인형퇴직연금");
        ACRONYMS.put("CB", "전환사채");
        ACRONYMS.put("BW", "신주인수권부사채");
        ACRONYMS.put("RCPS", "상환전환우선주");
        // --- 발행/거래/IR 이벤트 ---
        ACRONYMS.put("IPO", "기업공개");
        ACRONYMS.put("SPAC", "기업인수목적회사");
        ACRONYMS.put("IR", "기업설명회");
        ACRONYMS.put("AGM", "주주총회");
        ACRONYMS.put("ESG", "환경사회지배구조");
    }

    /** 기관 엔티티 (그래프 노드 시드). */
    public static final Set<String> INSTITUTIONS = new LinkedHashSet<>();
    static {
        INSTITUTIONS.add("금융위원회");
        INSTITUTIONS.add("증권선물위원회");
        INSTITUTIONS.add("금융감독원");
        INSTITUTIONS.add("한국거래소");
        INSTITUTIONS.add("한국예탁결제원");
        INSTITUTIONS.add("금융투자협회");
        INSTITUTIONS.add("코스콤");
        INSTITUTIONS.add("한국회계기준원");
        INSTITUTIONS.add("한국상장회사협의회");
        INSTITUTIONS.add("코스닥협회");
    }

    /** 법령/규정 엔티티. */
    public static final Set<String> LAWS = new LinkedHashSet<>();
    static {
        LAWS.add("자본시장법");
        LAWS.add("외부감사법");
        LAWS.add("상법");
        LAWS.add("금융소비자보호법");
        LAWS.add("신용정보법");
        LAWS.add("공정공시규정");
        LAWS.add("유가증권시장공시규정");
        LAWS.add("코스닥시장공시규정");
    }

    /** 상품/증권 엔티티. */
    public static final Set<String> PRODUCTS = new LinkedHashSet<>();
    static {
        PRODUCTS.add("주식");
        PRODUCTS.add("채권");
        PRODUCTS.add("펀드");
        PRODUCTS.add("상장지수펀드");
        PRODUCTS.add("상장지수증권");
        PRODUCTS.add("주가연계증권");
        PRODUCTS.add("주식워런트증권");
        PRODUCTS.add("파생결합증권");
        PRODUCTS.add("부동산투자회사");
        PRODUCTS.add("머니마켓펀드");
        PRODUCTS.add("종합자산관리계좌");
        PRODUCTS.add("개인종합자산관리계좌");
        PRODUCTS.add("개인형퇴직연금");
        PRODUCTS.add("전환사채");
        PRODUCTS.add("신주인수권부사채");
        PRODUCTS.add("상환전환우선주");
        PRODUCTS.add("선물");
        PRODUCTS.add("옵션");
    }

    /** 시장 지수 엔티티 — 기초자산↔상품(ETF·ELS)을 잇는 멀티홉 노드. (한글 표기로 본문에 자주 등장) */
    public static final Set<String> INDICES = new LinkedHashSet<>();
    static {
        INDICES.add("코스피200");
        INDICES.add("코스피");
        INDICES.add("코스닥");
        INDICES.add("코넥스");
    }

    /** 시장 이벤트/행위 엔티티 — 기업↔공시↔법령을 잇는 멀티홉 노드. */
    public static final Set<String> EVENTS = new LinkedHashSet<>();
    static {
        EVENTS.add("기업공개");
        EVENTS.add("유상증자");
        EVENTS.add("무상증자");
        EVENTS.add("자사주매입");
        EVENTS.add("배당");
        EVENTS.add("인수합병");
        EVENTS.add("공매도");
        EVENTS.add("주주총회");
        EVENTS.add("기업설명회");
        EVENTS.add("사업보고서");
        EVENTS.add("분기보고서");
        EVENTS.add("감사보고서");
        EVENTS.add("정정공시");
        EVENTS.add("공정공시");
    }

    /** 엔티티명 -> 타입. 그래프 노드 라벨링/디버깅용. */
    public static EntityType typeOf(String entity) {
        if (INSTITUTIONS.contains(entity)) return EntityType.INSTITUTION;
        if (LAWS.contains(entity)) return EntityType.LAW;
        if (PRODUCTS.contains(entity)) return EntityType.PRODUCT;
        if (EVENTS.contains(entity)) return EntityType.EVENT;
        if (INDICES.contains(entity)) return EntityType.INDEX;
        return null;
    }

    /** 모든 사전 엔티티(기관+법령+상품+이벤트+지수) 합집합. 추출기에서 사전 매칭에 사용. */
    public static Set<String> allEntities() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(INSTITUTIONS);
        all.addAll(LAWS);
        all.addAll(PRODUCTS);
        all.addAll(EVENTS);
        all.addAll(INDICES);
        return all;
    }
}
