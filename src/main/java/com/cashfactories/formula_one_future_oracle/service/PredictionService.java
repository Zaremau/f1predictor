package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.Driver;
import com.cashfactories.formula_one_future_oracle.model.GrandPrix;
import com.cashfactories.formula_one_future_oracle.model.HistoricalResult;
import com.cashfactories.formula_one_future_oracle.model.News;
import com.cashfactories.formula_one_future_oracle.model.PracticeResult;
import com.cashfactories.formula_one_future_oracle.model.Prediction;
import com.cashfactories.formula_one_future_oracle.model.QualifyingResult;
import com.cashfactories.formula_one_future_oracle.repository.DriverRepository;
import com.cashfactories.formula_one_future_oracle.repository.GrandPrixRepository;
import com.cashfactories.formula_one_future_oracle.repository.HistoricalResultRepository;
import com.cashfactories.formula_one_future_oracle.repository.NewsRepository;
import com.cashfactories.formula_one_future_oracle.repository.PracticeRepository;
import com.cashfactories.formula_one_future_oracle.repository.PredictionRepository;
import com.cashfactories.formula_one_future_oracle.repository.QualifyingRepository;
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
            double score = calculateScore(driver, gp);

            Prediction pred = new Prediction();
            pred.setGrandPrix(gp);
            pred.setDriver(driver);
            pred.setStage(gp.getStage());
            pred.setScore(score); // Временно кладем score в @Transient поле

            // Считаем уверенность и риск (методы ниже)
            pred.setConfidence(calculateConfidence(gp.getStage(), score));
            pred.setRiskLevel(calculateRisk(driver, gp));

            // Формируем JSON с аргументами (объяснимый ИИ)
            pred.setArguments(formArguments(driver, gp));

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

    private Double calculateConfidence(String stage, Double score) {
        // TODO: Разработать логичный способ определения уверенности без magic numbers
        double baseConfidence = 0.4;
        if ("FP_DONE".equals(stage)) baseConfidence = 0.6;
        if ("QUALI_DONE".equals(stage)) baseConfidence = 0.85;
        if (score > 90) baseConfidence = 0.9;
        return baseConfidence;
    }

    private String calculateRisk(Driver driver, GrandPrix gp) {
        // TODO:  Логика: если есть штрафы из новостей или гонка в Монако (сложно обгонять)
        //  возвращаем "HIGH", иначе "MEDIUM" или "LOW"

        return "MEDIUM";
    }

    private String formArguments(Driver driver, GrandPrix gp) {
        // TODO: Формируем аргументы на основе истории выступления пилота и новостей
        // "Средняя позиция в последних гонках: 2.3 (Score: 88). На этом треке в среднем финишировал: 4.5 (Score: 77)."
        return "{\"history\": \"Ср. место: 3.2\", \"news\": \"+0.5 (Позитив)\"}";
    }

    private double calculateScore(Driver driver, GrandPrix gp) {
        double score = 0;
        String stage = gp.getStage();

        // --- 1. Исторические данные ---
        double overallHistScore = calculateOverallHistoryScore(driver.getId(), driver.getTeam());
        double trackHistScore = calculateTrackHistoryScore(driver.getId(), gp.getName());

        // --- 2. Новости ---
        // Берем ВСЕ новости Гран-при
        List<News> gpNews = newsRepo.findByGrandPrix_Id(gp.getId());

        // Фильтруем: оставляем только те, где упоминается наш пилот
        List<News> driverNews = gpNews.stream()
                .filter(n -> n.getMentionedDrivers() != null &&
                        Arrays.asList(n.getMentionedDrivers()).contains(driver.getName()))
                .toList();

        // Считаем среднюю тональность ТОЛЬКО по новостям пилота
        double avgSentiment = driverNews.stream()
                .mapToDouble(News::getSentimentScore)
                .average().orElse(0.0);

        // Проверяем штрафы (риск) в новостях пилота
        boolean hasPenalty = driverNews.stream()
                .anyMatch(n -> n.getRiskKeywords() != null && n.getRiskKeywords().length > 0);



        // --- 3. Практики и Квалификация ---
        double paceScore = 0.0;
        double gridScore = 0.0;

        if (stage.equals("FP_DONE") || stage.equals("QUALI_DONE")) {
            PracticeResult pr = practiceRepo.findTopByGrandPrix_IdAndDriver_IdOrderByLapTimeMsAsc(gp.getId(), driver.getId());
            if (pr != null) {
                paceScore = Math.max(0, 100 - (pr.getGapToP1Ms() / 1000.0) * 10);
            }
        }

        if (stage.equals("QUALI_DONE")) {
            QualifyingResult qr = qualiRepo.findByGrandPrix_IdAndDriver_Id(gp.getId(), driver.getId());
            if (qr != null) {
                gridScore = Math.max(0, 100 - ((qr.getPosition() - 1) * 5));
            }
        }

        // --- ВЗВЕШИВАНИЕ (SCORING) ---
        // Теперь логика прозрачная: цифры сходятся по стадиям.

        if (stage.equals("UPCOMING")) {
            // До уикенда: 50% общая история + 20% история трека + 30% новости
            score = (overallHistScore * 0.5) + (trackHistScore * 0.2) + (avgSentiment * 20 * 0.3);
        } else if (stage.equals("FP_DONE")) {
            // После практик: 30% общая история + 10% история трека + 20% новости + 40% темп в практике
            score = (overallHistScore * 0.3) + (trackHistScore * 0.1) + (avgSentiment * 20 * 0.2) + (paceScore * 0.4);
        } else if (stage.equals("QUALI_DONE")) {
            // После квалификации: 20% общая история + 10% история трека + 10% новости + 20% темп + 40% стартовая решетка
            score = (overallHistScore * 0.2) + (trackHistScore * 0.1) + (avgSentiment * 20 * 0.1) + (paceScore * 0.2) + (gridScore * 0.4);
        }

        if (hasPenalty) score -= 15; // Штраф за риск из новостей
        return Math.max(0, Math.min(100, score));
    }

    // Расчет среднего места за всю историю пилота (нормализованный)
    private double calculateOverallHistoryScore(Long driverId, String teamName) {
        List<HistoricalResult> results = histRepo.findByDriver_Id(driverId);

        if (results.isEmpty()) {
            // Если пилот новичок, берем средний результат команды в этом сезоне
            results = histRepo.findByTeamName(teamName);
        }

        if (results.isEmpty()) return 50.0; // Абсолютный дефолт, если данных по команде нет

        double avgPos = results.stream()
                .mapToInt(HistoricalResult::getFinalPosition)
                .average().orElse(10.0);

        return Math.max(0, 100 - ((avgPos - 1) * 5));
    }

    // Расчет среднего места на конкретном треке (например, Ферстаппен в Монако)
    private double calculateTrackHistoryScore(Long driverId, String gpName) {
        List<HistoricalResult> results = histRepo.findByDriver_IdAndGpName(driverId, gpName);
        if (results.isEmpty()) return 50.0; // Если пилот никогда здесь не ездил

        double avgPos = results.stream()
                .mapToInt(HistoricalResult::getFinalPosition)
                .average().orElse(10.0);

        return Math.max(0, 100 - ((avgPos - 1) * 5));
    }

}