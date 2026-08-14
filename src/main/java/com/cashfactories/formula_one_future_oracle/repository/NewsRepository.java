package com.cashfactories.formula_one_future_oracle.repository;

import com.cashfactories.formula_one_future_oracle.model.News;

import java.util.List;

public interface NewsRepository {
    List<News> findByGpIdAndDriver(Long id, Long id1);

    List<News> findByIsProcessedFalse();

    void save(News news);

    List<News> findByGrandPrix_Id(Long id);
}
