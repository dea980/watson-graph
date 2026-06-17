package com.miniwatson.governance;


import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
@Entity
@Table(name ="document_catalog")
@Data
public class DocumentCatalog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String namespace;
    private String sourceType;
    private int chunks;
    private String embedModel;
    private LocalDateTime ingestedAt;

}
