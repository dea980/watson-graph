package com.miniwatson.governance;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "query_log")
public class QueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                          // Primary Key (Long)

    @Column(columnDefinition = "TEXT")
    private String question;                  // @Id 제거

//    @Column(length = 4000)
//    private String augmentedPrompt;
    @Column(columnDefinition = "TEXT")
    private String answer;

    private String model;

    private Long latencyMs;

    private LocalDateTime createdAt;
    @Column(columnDefinition = "TEXT")
    private String sources;
    private String feedback; // "up"| "down" | null (아직 평가 안 함)
    private int piiCount;
    @Lob
    private String augmentedPrompt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}