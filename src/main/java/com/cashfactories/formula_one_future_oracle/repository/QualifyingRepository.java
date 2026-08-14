package com.cashfactories.formula_one_future_oracle.repository;

import com.cashfactories.formula_one_future_oracle.model.QualifyingResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualifyingRepository extends JpaRepository<QualifyingResult, Long> {

    QualifyingResult findByGrandPrix_IdAndDriver_Id(Long gpId, Long driverId);
}