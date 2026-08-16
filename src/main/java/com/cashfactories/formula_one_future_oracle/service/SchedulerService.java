package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.GrandPrix;
import com.cashfactories.formula_one_future_oracle.repository.GrandPrixRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final GrandPrixRepository gpRepo;
    private final RssParserService rssParserService;
    private final PredictionService predictionService;

    // Запускается каждый час (в 0 минут 0 секунд)
    @Scheduled(cron = "0 0 * * * *")
    public void hourlyNewsUpdate() {
        Optional<GrandPrix> upcomingGp = gpRepo.findFirstByStageNotOrderByRaceDateAsc("RACE_DONE");

        if (upcomingGp.isPresent()) {
            Long gpId = upcomingGp.get().getId();
            System.out.println("Планировщик: Обновление новостей для GP ID " + gpId);

            // 1. Скачиваем и обрабатываем новости
            rssParserService.fetchAndProcessNews(gpId);

            // 2. Пересчитываем прогнозы на основе новых новостей
            predictionService.generatePredictions(gpId);
        }
    }
}
