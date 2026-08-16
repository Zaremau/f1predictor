package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PredictionService {

    private final DriverRepository driverRepo;
    private final GrandPrixRepository gpRepo;
    private final NewsRepository newsRepo;
    private final HistoricalResultRepository histRepo;
    private final PracticeRepository practiceRepo;
    private final QualifyingRepository qualiRepo;
    private final PredictionRepository predictionRepo;

    @Transactional
    public List<Prediction> generatePredictions(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();
        List<Driver> drivers = driverRepo.findAll();
        List<Prediction> predictions = new ArrayList<>();

        // 1. Считаем score для каждого пилота
        for (Driver driver : drivers) {

            // --- РАСЧЕТ МЕТРИК ---
            double overallHistScore = calculateOverallHistoryScore(driver.getId(), driver.getTeam());
            double trackHistScore = calculateTrackHistoryScore(driver.getId(), gp.getName());

            // Новости
            List<News> gpNews = newsRepo.findByGrandPrix_Id(gp.getId());
            List<News> driverNews = gpNews.stream()
                    .filter(n -> n.getMentionedDrivers() != null &&
                            Arrays.asList(n.getMentionedDrivers()).contains(driver.getName()))
                    .toList();

            double newsScore = 50.0; // Нейтральный базовый уровень
            if (!driverNews.isEmpty()) {
                double avgSentiment = driverNews.stream()
                        .mapToDouble(News::getSentimentScore)
                        .average().orElse(0.0);
                newsScore = 50 + (avgSentiment * 50); // Перевод из [-1.0...1.0] в [0...100]
            }
            boolean hasPenalty = driverNews.stream()
                    .anyMatch(n -> n.getRiskKeywords() != null && n.getRiskKeywords().length > 0);

            // Практики и Квалификация
            double paceScore = 0.0;
            double gridScore = 0.0;
            String stage = gp.getStage();

            if ("FP_DONE".equals(stage) || "QUALI_DONE".equals(stage)) {
                PracticeResult pr = practiceRepo.findTopByGrandPrix_IdAndDriver_IdOrderByLapTimeMsAsc(gp.getId(), driver.getId());
                if (pr != null) {
                    paceScore = Math.max(0, 100 - (pr.getGapToP1Ms() / 1000.0) * 10);
                }
            }

            if ("QUALI_DONE".equals(stage)) {
                QualifyingResult qr = qualiRepo.findByGrandPrix_IdAndDriver_Id(gp.getId(), driver.getId());
                if (qr != null) {
                    gridScore = Math.max(0, 100 - ((qr.getPosition() - 1) * 5));
                }
            }

            // --- ИТОГОВЫЙ СКОР ---
            double score = calculateScore(stage, overallHistScore, trackHistScore, newsScore, paceScore, gridScore, hasPenalty);

            // --- ФОРМИРОВАНИЕ ПРОГНОЗА ---
            Prediction pred = new Prediction();
            pred.setGrandPrix(gp);
            pred.setDriver(driver);
            pred.setStage(stage);
            pred.setScore(score); // Временно кладем score в @Transient поле

            pred.setConfidence(calculateConfidence(stage, score));
            pred.setRiskLevel(calculateRisk(hasPenalty, gp, gridScore));
            pred.setArguments(formArguments(overallHistScore, trackHistScore, newsScore, hasPenalty));

            predictions.add(pred);
        }

        // 2. Сортируем пилотов по score (от большего к меньшему)
        predictions.sort(Comparator.comparing(Prediction::getScore).reversed());

        // 3. Назначаем итоговые позиции (1-й, 2-й, 3-й...)
        for (int i = 0; i < predictions.size(); i++) {
            predictions.get(i).setPredictedPosition(i + 1);
        }

        // 4. Сохраняем в БД и возвращаем на фронтенд
        return predictionRepo.saveAll(predictions);
    }

    // --- МЕТОДЫ РАСЧЕТА ---

    private double calculateScore(String stage, double overallHistScore, double trackHistScore, double newsScore, double paceScore, double gridScore, boolean hasPenalty) {
        double score = 0.0;

        if ("UPCOMING".equals(stage)) {
            score = (overallHistScore * 0.5) + (trackHistScore * 0.2) + (newsScore * 0.3);
        } else if ("FP_DONE".equals(stage)) {
            score = (overallHistScore * 0.3) + (trackHistScore * 0.1) + (newsScore * 0.2) + (paceScore * 0.4);
        } else if ("QUALI_DONE".equals(stage)) {
            score = (overallHistScore * 0.2) + (trackHistScore * 0.1) + (newsScore * 0.1) + (paceScore * 0.2) + (gridScore * 0.4);
        }

        if (hasPenalty) score -= 15; // Штраф за риск из новостей
        return Math.max(0, Math.min(100, score));
    }

    private Double calculateConfidence(String stage, Double score) {
        double baseConfidence = 0.4;
        if ("FP_DONE".equals(stage)) baseConfidence = 0.6;
        if ("QUALI_DONE".equals(stage)) baseConfidence = 0.85;

        // Бонус к уверенности, если пилот имеет явный перевес в скоринге
        if (score > 90) baseConfidence = Math.min(1.0, baseConfidence + 0.05);

        return baseConfidence;
    }

    private String calculateRisk(boolean hasPenalty, GrandPrix gp, double gridScore) {
        // Если есть штрафы или авария в новостях — риск высокий
        if (hasPenalty) return "HIGH";

        // Если трасса узкая (как Монако) и пилот стартует далеко (gridScore < 50, т.е. хуже 10-го места)
        if (gp.getName().toLowerCase().contains("monaco") && gridScore > 0 && gridScore < 50) {
            return "HIGH";
        }

        // Если стадия ранняя, данных мало — риск средний
        if ("UPCOMING".equals(gp.getStage())) return "MEDIUM";

        return "LOW";
    }

    private String formArguments(double overallHistScore, double trackHistScore, double newsScore, boolean hasPenalty) {
        // Формируем понятный JSON для фронтенда
        String newsExplanation;
        if (newsScore == 50.0 && !hasPenalty) {
            newsExplanation = "Релевантных новостей не найдено. Использован нейтральный коэффициент (50/100).";
        } else {
            newsExplanation = String.format("Влияние новостей: %.1f/100. Наличие рисков (штрафы/аварии): %b", newsScore, hasPenalty);
        }

        return String.format(
                "{\"overallHistory\": \"Общая история: %.1f/100\", " +
                        "\"trackHistory\": \"История на треке: %.1f/100\", " +
                        "\"news\": \"%s\"}",
                overallHistScore, trackHistScore, newsExplanation
        );
    }

    private double calculateOverallHistoryScore(Long driverId, String teamName) {
        List<HistoricalResult> results = histRepo.findByDriver_Id(driverId);

        if (results.isEmpty()) {
            results = histRepo.findByTeamName(teamName);
        }

        if (results.isEmpty()) return 50.0;

        double avgPos = results.stream()
                .mapToInt(HistoricalResult::getFinalPosition)
                .average().orElse(10.0);

        return Math.max(0, 100 - ((avgPos - 1) * 5));
    }

    private double calculateTrackHistoryScore(Long driverId, String gpName) {
        List<HistoricalResult> results = histRepo.findByDriver_IdAndGpName(driverId, gpName);
        if (results.isEmpty()) return 50.0;

        double avgPos = results.stream()
                .mapToInt(HistoricalResult::getFinalPosition)
                .average().orElse(10.0);

        return Math.max(0, 100 - ((avgPos - 1) * 5));
    }
}
