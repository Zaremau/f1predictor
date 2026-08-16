package com.cashfactories.formula_one_future_oracle.repository;

import com.cashfactories.formula_one_future_oracle.model.HistoricalResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoricalResultRepository extends JpaRepository<HistoricalResult, Long> {

    List<HistoricalResult> findByDriver_Id(Long driverId);

    List<HistoricalResult> findByDriver_IdAndGpName(Long driverId, String gpName);

    List<HistoricalResult> findByTeamName(String teamName);

}
