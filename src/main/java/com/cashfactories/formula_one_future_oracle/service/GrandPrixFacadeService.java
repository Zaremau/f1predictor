package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GrandPrixFacadeService {

    private final GrandPrixRepository gpRepo;
    private final PredictionRepository predictionRepo;
    private final ActualResultRepository actualResultRepo;
    private final OpenF1Service openF1Service;
    private final PredictionService predictionService;

    /**
     * Главный метод для фронтенда.
     * Возвращает список объектов в зависимости от стадии Гран-при.
     */
    public List<?> getGrandPrixData(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();

        // 1. Если гонка уже прошла — возвращаем РЕАЛЬНЫЕ результаты
        if ("RACE_DONE".equals(gp.getStage())) {
            return actualResultRepo.findByGrandPrix_IdOrderByFinalPositionAsc(gpId);
        }

        // 2. Если гонка еще не прошла — работаем с прогнозами
        // Сначала синхронизируем статус (возможно, прошла квалификация)
        openF1Service.syncGrandPrixData(gpId);

        // Обновляем GP, так как стадия могла измениться
        GrandPrix updatedGp = gpRepo.findById(gpId).orElseThrow();
        if ("RACE_DONE".equals(updatedGp.getStage())) {
            return actualResultRepo.findByGrandPrix_IdOrderByFinalPositionAsc(gpId);
        }

        // Проверяем, есть ли уже сохраненный прогноз
        List<Prediction> existingPredictions = predictionRepo.findByGrandPrix_Id(gpId);

        // Если прогноза нет, или стадия изменилась (например, была UPCOMING, стала QUALI_DONE) - генерируем заново
        if (existingPredictions.isEmpty() || !existingPredictions.get(0).getStage().equals(updatedGp.getStage())) {
            // Удаляем старые прогнозы, если стадия поменялась
            if (!existingPredictions.isEmpty()) {
                predictionRepo.deleteAll(existingPredictions);
            }
            return predictionService.generatePredictions(gpId);
        }

        // Если прогноз актуален, возвращаем его
        return existingPredictions;
    }

    /**
     * Возвращает список всех Гран-при для отображения на главной странице
     */
    public List<GrandPrix> getAllGrandPrix() {
        return gpRepo.findAll();
    }
}
