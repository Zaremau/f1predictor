package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.dto.ActualResultDto;
import com.cashfactories.formula_one_future_oracle.dto.PredictionDto;
import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GrandPrixFacadeService {

    private final GrandPrixRepository gpRepo;
    private final PredictionRepository predictionRepo;
    private final ActualResultRepository actualResultRepo;
    private final OpenF1Service openF1Service;
    private final PredictionService predictionService;

    /**
     * Возвращает список всех Гран-при для главной страницы фронтенда
     */
    public List<GrandPrix> getAllGrandPrix() {
        return gpRepo.findAll();
    }

    /**
     * Решает, что отдать на фронтенд (прогноз или результаты)
     */
    public List<?> getGrandPrixData(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();

        // --- 1. Если гонка уже прошла ---
        if ("RACE_DONE".equals(gp.getStage())) {
            return getRaceResults(gpId);
        }
        // Паузы из-за лимита OpenF1 API
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // --- 2. Если гонка еще не прошла ---
        // Сначала синхронизируем статус (вдруг прошла квалификация)
        openF1Service.syncGrandPrixData(gpId);
        GrandPrix updatedGp = gpRepo.findById(gpId).orElseThrow();

        // Проверяем, не изменился ли статус на RACE_DONE после синхронизации
        if ("RACE_DONE".equals(updatedGp.getStage())) {
            return getRaceResults(gpId);
        }

        // Проверяем, есть ли уже сохраненный прогноз
        List<Prediction> existingPredictions = predictionRepo.findByGrandPrix_Id(gpId);

        // Если прогноза нет, ИЛИ стадия изменилась (например, была UPCOMING, стала QUALI_DONE) - генерируем заново
        if (existingPredictions.isEmpty() || !existingPredictions.get(0).getStage().equals(updatedGp.getStage())) {
            if (!existingPredictions.isEmpty()) {
                predictionRepo.deleteAll(existingPredictions); // Удаляем устаревший прогноз
            }
            openF1Service.fetchHistoryForGrandPrix(updatedGp);
            existingPredictions = predictionService.generatePredictions(gpId); // Считаем заново
        }

        // Конвертируем сущности в DTO и отдаем
        return existingPredictions.stream()
                .map(this::convertToPredictionDto)
                .collect(Collectors.toList());
    }

    /**
     * Вспомогательный метод: собирает результаты прошедшей гонки
     */
    private List<ActualResultDto> getRaceResults(Long gpId) {
        // Пробуем достать результаты из БД
        List<ActualResult> results = actualResultRepo.findByGrandPrix_IdOrderByFinalPositionAsc(gpId);

        // Если их там еще нет (гонка только что закончилась), скачиваем из OpenF1
        if (results.isEmpty()) {
            openF1Service.fetchAndSaveRaceResults(gpId);
            results = actualResultRepo.findByGrandPrix_IdOrderByFinalPositionAsc(gpId);
        }

        // Формируем DTO для фронтенда
        return results.stream().map(res -> {
            // Ищем, был ли построен прогноз для этого пилота ДО гонки
            Prediction pred = predictionRepo.findByGrandPrix_IdAndDriver_Id(gpId, res.getDriver().getId());
            Integer predictedPos = (pred != null) ? pred.getPredictedPosition() : null;

            // Формируем текстовое объяснение ошибки (Explainable AI)
            String explanation;
            if (predictedPos == null) {
                explanation = "Прогноз на эту гонку не строился.";
            } else if (res.getErrorMargin() == 0) {
                explanation = "Прогноз идеален!";
            } else {
                explanation = "Система ошиблась на " + res.getErrorMargin() + " позиций.";
            }

            return ActualResultDto.builder()
                    .driverName(res.getDriver().getName())
                    .team(res.getDriver().getTeam())
                    .predictedPosition(predictedPos) // Будет null, если прогноза не было
                    .actualPosition(res.getFinalPosition())
                    .errorMargin(res.getErrorMargin())
                    .explanation(explanation)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * Вспомогательный метод: маппинг Prediction в PredictionDto
     */
    private PredictionDto convertToPredictionDto(Prediction pred) {
        return PredictionDto.builder()
                .driverName(pred.getDriver().getName())
                .team(pred.getDriver().getTeam())
                .predictedPosition(pred.getPredictedPosition())
                .confidence(pred.getConfidence())
                .riskLevel(pred.getRiskLevel())
                .arguments(pred.getArguments())
                .stage(pred.getStage())
                .build();
    }
}