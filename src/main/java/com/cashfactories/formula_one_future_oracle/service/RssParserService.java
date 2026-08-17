package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.Driver;
import com.cashfactories.formula_one_future_oracle.model.GrandPrix;
import com.cashfactories.formula_one_future_oracle.model.News;
import com.cashfactories.formula_one_future_oracle.repository.DriverRepository;
import com.cashfactories.formula_one_future_oracle.repository.GrandPrixRepository;
import com.cashfactories.formula_one_future_oracle.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RssParserService {

    private final NewsRepository newsRepo;
    private final GrandPrixRepository gpRepo;
    private final DriverRepository driverRepo;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String[] RSS_FEEDS = {
            "https://www.motorsport.com/rss/f1/news/",
            "https://www.formel1.de/rss.xml"
    };

    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "grid penalty", "engine penalty", "gearbox", "crash", "rain", "weather", "damage", "dnf"
    );

    private static final String PYTHON_SCRIPT_PATH = "/app/python-sentiment/sentiment.py";

    /**
     * Скачивает новости и запускает их обработку
     */
    public void fetchAndProcessNews(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow
                (()->new RuntimeException("No such grand-prix"));

        for (String url : RSS_FEEDS) {
            try {
                String xmlData = restTemplate.getForObject(url, String.class);
                parseAndSaveXml(xmlData, gp);
            } catch (Exception e) {
                log.error("Ошибка при получении RSS ленты {}: {}", url, e.getMessage());
            }
        }

        processNews();
    }

    /**
     * Парсит XML и сохраняет сырые новости в БД
     */
    private void parseAndSaveXml(String xmlData, GrandPrix gp) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlData.getBytes()));

            NodeList items = doc.getElementsByTagName("item"); // Стандартный тег RSS

            for (int i = 0; i < items.getLength(); i++) {
                Node node = items.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;

                    String title = element.getElementsByTagName("title").item(0).getTextContent();
                    String link = element.getElementsByTagName("link").item(0).getTextContent();

                    if (!isNewsRelevant(title, gp)) {
                        continue; // Если новость не про этот Гран-при, пропускаем её
                    }

                    if (newsRepo.existsByGrandPrix_IdAndUrl(gp.getId(), link)) {
                        continue;
                    }

                    News news = new News();
                    news.setGrandPrix(gp);
                    news.setTitle(title);
                    news.setUrl(link);
                    news.setSource(extractSourceFromUrl(link));
                    news.setPublishedAt(LocalDateTime.now());
                    news.setIsProcessed(false);
                    news.setRawXml(element.toString()); // Сохраняем сырой XML элемент

                    newsRepo.save(news);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка парсинга XML: {}", e.getMessage());
        }
    }

    /**
     * Метод проверяет, связана ли новость с конкретным Гран-при
     */
    private boolean isNewsRelevant(String title, GrandPrix gp) {
        if (title == null || title.isEmpty()) return false;

        String lowerTitle = title.toLowerCase();
        String gpName = gp.getName().toLowerCase();
        String country = gp.getCountry().toLowerCase();
        String mainKeyword = gpName.replace("grand prix", "").trim();

        return lowerTitle.contains(mainKeyword) || lowerTitle.contains(country);
    }

    /**
     * Анализ сырых новостей (вызов Python и поиск ключевых слов)
     */
    public void processNews() {
        List<News> unprocessed = newsRepo.findByIsProcessedFalse();
        List<Driver> allDrivers = driverRepo.findAll(); // Получаем всех пилотов из БД

        for (News news : unprocessed) {
            double sentiment = callPythonSentiment(news.getTitle());
            news.setSentimentScore(sentiment);

            String[] keywords = checkRiskKeywords(news.getTitle());
            news.setRiskKeywords(keywords);

            String[] mentionedDrivers = findMentionedDrivers(news.getTitle(), allDrivers);
            news.setMentionedDrivers(mentionedDrivers);

            news.setIsProcessed(true);
            newsRepo.save(news);
        }
    }


    private String extractSourceFromUrl(String url) {
        if (url.contains("motorsport.com")) return "motorsport.com";
        if (url.contains("formel1.de")) return "formel1.de";
        return "unknown";
    }

    private String[] findMentionedDrivers(String text, List<Driver> drivers) {
        String lowerText = text.toLowerCase();
        return drivers.stream()
                .filter(d -> lowerText.contains(d.getName().toLowerCase()))
                .map(Driver::getName)
                .toArray(String[]::new);
    }

    /**
     * Определяет тональность новости
     * @param text - текст новости
     * @return оценка тональности от -1 до 1
     */
    private double callPythonSentiment(String text) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", PYTHON_SCRIPT_PATH, text);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String result = reader.readLine();

                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return 0.0;
                }

                if (result != null && !result.isEmpty()) {
                    return Double.parseDouble(result.trim());
                }
            }
        } catch (Exception e) {
            log.error("Ошибка вызова Python скрипта: {}", e.getMessage());
        }
        return 0.0;
    }

    private String[] checkRiskKeywords(String text) {
        if (text == null || text.isEmpty()) return new String[0];

        String lowerText = text.toLowerCase();

        return RISK_KEYWORDS.stream()
                .filter(lowerText::contains)
                .toArray(String[]::new);
    }
}