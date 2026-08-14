package com.cashfactories.formula_one_future_oracle.repository;

import com.cashfactories.formula_one_future_oracle.model.PracticeResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeRepository extends JpaRepository<PracticeResult, Long> {

    PracticeResult findTopByGrandPrix_IdAndDriver_IdOrderByLapTimeMsAsc(Long gpId, Long driverId);
}
