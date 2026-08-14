package com.cashfactories.formula_one_future_oracle.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "qualifying_results")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class QualifyingResult {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gp_id")
    private GrandPrix grandPrix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Column(name = "position")
    private Integer position;

    @Column(name = "q3_time_ms")
    private Integer q3TimeMs;
}
