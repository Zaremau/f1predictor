package com.cashfactories.formula_one_future_oracle.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "actual_results")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ActualResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gp_id")
    private GrandPrix grandPrix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Column(name = "final_position")
    private Integer finalPosition;

    @Column(name = "error_margin")
    private Integer errorMargin; // abs(predicted - final)
}