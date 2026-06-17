package com.miniwatson.dto;
import lombok.Data;


@Data // 어노테이션 하나로 getter/setter/toString 자동 생성. 본인이 안 적어도 됨.
public class AskRequest {
    private String question;
    // 선택: 검색 대상 tenant/collection (없으면 "default")
    private String namespace;
    // 선택: 사용할 chat model (없으면 서버 기본 모델)
    private String model;
    // Eval-Only 검색 전략 요청별 오버라이드 (평가용)
    private String rerank;
    private Boolean hybrid;
    private Boolean graph;   // EVAL-ONLY: 지식그래프 후보 합류 on/off

}
