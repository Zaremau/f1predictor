package com.cashfactories.formula_one_future_oracle.repository;

import com.cashfactories.formula_one_future_oracle.model.HistoricalResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoricalResultRepository extends JpaRepository<HistoricalResult, Long> {

    List<HistoricalResult> findByDriverId(Long driverId);

    List<HistoricalResult> findByDriverIdAndGpName(Long driverId, String gpName);

    // НОВЫЙ МЕТОД: поиск истории по команде
    List<HistoricalResult> findByTeamName(String teamName);

    // Можно усложнить: поиск истории команды на конкретном треке
    List<HistoricalResult> findByTeamNameAndGpName(String teamName, String gpName);
}
