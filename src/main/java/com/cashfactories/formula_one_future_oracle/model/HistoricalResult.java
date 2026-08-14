package com.cashfactories.formula_one_future_oracle.model;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "historical_results")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class HistoricalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @Column(name = "gp_name", length = 100)
    private String gpName;

    @Column(name = "season")
    private Integer season;

    @Column(name = "final_position")
    private Integer finalPosition;

    @Column(name = "team_name", length = 100)
    private String teamName;
}
