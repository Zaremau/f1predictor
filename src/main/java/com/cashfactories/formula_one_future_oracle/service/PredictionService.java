package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.Driver;
import com.cashfactories.formula_one_future_oracle.model.GrandPrix;
import com.cashfactories.formula_one_future_oracle.model.HistoricalResult;
import com.cashfactories.formula_one_future_oracle.model.News;
import com.cashfactories.formula_one_future_oracle.model.PracticeResult;
import com.cashfactories.formula_one_future_oracle.model.QualifyingResult;
import com.cashfactories.formula_one_future_oracle.repository.HistoricalResultRepository;
import com.cashfactories.formula_one_future_oracle.repository.NewsRepository;
import com.cashfactories.formula_one_future_oracle.repository.PracticeRepository;
import com.cashfactories.formula_one_future_oracle.repository.QualifyingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PredictionService {

    private final NewsRepository newsRepo;
    private final HistoricalResultRepository histRepo;
    private final PracticeRepository practiceRepo;
    private final QualifyingRepository qualiRepo;

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
        List<HistoricalResult> results = histRepo.findByDriverId(driverId);

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
        List<HistoricalResult> results = histRepo.findByDriverIdAndGpName(driverId, gpName);
        if (results.isEmpty()) return 50.0; // Если пилот никогда здесь не ездил

        double avgPos = results.stream()
                .mapToInt(HistoricalResult::getFinalPosition)
                .average().orElse(10.0);

        return Math.max(0, 100 - ((avgPos - 1) * 5));
    }

    // В методе формирования аргументов (formArguments) теперь можно вывести конкретные цифры:
    // "Средняя позиция в последних гонках: 2.3 (Score: 88). На этом треке в среднем финишировал: 4.5 (Score: 77)."
}