package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.Driver;
import com.cashfactories.formula_one_future_oracle.model.News;
import com.cashfactories.formula_one_future_oracle.repository.DriverRepository;
import com.cashfactories.formula_one_future_oracle.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RssParserService {

    private final DriverRepository driverRepo;
    private final NewsRepository newsRepo;

    public void processNews() {
        List<News> unprocessed = newsRepo.findByIsProcessedFalse();
        List<Driver> allDrivers = driverRepo.findAll(); // Получаем всех пилотов из БД

        for (News news : unprocessed) {
            // 1. Python Sentiment Analysis
            double sentiment = callPythonSentiment(news.getTitle());
            news.setSentimentScore(sentiment);

            // 2. Java Regex for Risk Keywords
            String[] keywords = checkRiskKeywords(news.getTitle());
            news.setRiskKeywords(keywords);

            // 3. НОВОЕ: Ищем имена пилотов в тексте
            String[] mentionedDrivers = findMentionedDrivers(news.getTitle(), allDrivers);
            news.setMentionedDrivers(mentionedDrivers);

            news.setIsProcessed(true);
            newsRepo.save(news);
        }
    }

    private String[] findMentionedDrivers(String text, List<Driver> drivers) {
        String lowerText = text.toLowerCase();
        return drivers.stream()
                .filter(d -> lowerText.contains(d.getName().toLowerCase()))
                .map(Driver::getName)
                .toArray(String[]::new);
    }
}
