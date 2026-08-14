package com.cashfactories.formula_one_future_oracle.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "predictions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gp_id")
    private GrandPrix grandPrix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Column(name = "predicted_position")
    private Integer predictedPosition;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "risk_level", length = 20)
    private String riskLevel; // LOW, MEDIUM, HIGH

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", columnDefinition = "jsonb")
    private String arguments; // Здесь будет лежать JSON строка

    @Column(name = "stage", length = 20)
    private String stage;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // Транзитное поле для логики (не сохраняется в БД)
    @Transient
    private Double score;
}
