package com.cashfactories.formula_one_future_oracle.model;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "practice_results")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PracticeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gp_id")
    private GrandPrix grandPrix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Column(name = "session_type", length = 10)
    private String sessionType; // FP1, FP2, FP3

    @Column(name = "lap_time_ms")
    private Integer lapTimeMs;

    @Column(name = "gap_to_p1_ms")
    private Integer gapToP1Ms;
}