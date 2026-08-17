package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenF1Service {

    private final RestTemplate restTemplate = new RestTemplate(getClientHttpRequestFactory());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final GrandPrixRepository gpRepo;
    private final DriverRepository driverRepo;
    private final QualifyingRepository qualiRepo;
    private final PracticeRepository practiceRepo;
    private final ActualResultRepository actualResultRepo;
    private final PredictionRepository predictionRepo;
    private final HistoricalResultRepository histRepo;

    // Настройка таймаутов
    private SimpleClientHttpRequestFactory getClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        return factory;
    }

    private static final String BASE_URL = "https://api.openf1.org/v1";

    /**
     * Синхронизирует статус Гран-при и подтягивает данные из OpenF1 перед прогнозом
     */
    @Transactional
    public GrandPrix syncGrandPrixData(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();

        // Если гонка уже прошла, синхронизировать не нужно
        if ("RACE_DONE".equals(gp.getStage())) {
            return gp;
        }

        try {
            int year = gp.getRaceDate().getYear();
            String country = gp.getCountry();
            LocalDateTime now = LocalDateTime.now();

            // 1. Проверяем Квалификацию
            String qualiUrl = BASE_URL + "/sessions?year=" + year + "&country_name=" + country + "&session_name=Qualifying";
            JsonNode qualiSessions = restTemplate.getForObject(qualiUrl, JsonNode.class);

            if (qualiSessions != null && qualiSessions.size() > 0) {
                JsonNode qualiSession = qualiSessions.get(0);
                LocalDateTime qualiDate = OffsetDateTime.parse(qualiSession.get("date_end").asText()).toLocalDateTime();

                // Если квалификация уже закончилась
                if (qualiDate.isBefore(now)) {
                    int sessionKey = qualiSession.get("session_key").asInt();
                    fetchAndSaveQualifying(gp, sessionKey);
                    gp.setStage("QUALI_DONE");
                    gpRepo.save(gp);
                    log.info("Статус GP {} обновлен до QUALI_DONE", gpId);
                    return gp;
                }
            }

            // 2. Если квалификации еще нет, проверяем Practice 3
            String practiceUrl = BASE_URL + "/sessions?year=" + year + "&country_name=" + country + "&session_name=Practice 3";
            JsonNode practiceSessions = restTemplate.getForObject(practiceUrl, JsonNode.class);

            if (practiceSessions != null && !practiceSessions.isEmpty()) {
                JsonNode practiceSession = practiceSessions.get(0);
                LocalDateTime practiceDate = OffsetDateTime.parse(practiceSession.get("date_end").asText()).toLocalDateTime();

                if (practiceDate.isBefore(now)) {
                    int sessionKey = practiceSession.get("session_key").asInt();
                    fetchAndSavePractice(gp, sessionKey);
                    gp.setStage("FP_DONE");
                    gpRepo.save(gp);
                    log.info("Статус GP {} обновлен до FP_DONE", gpId);
                    return gp;
                }
            }
        } catch (Exception e) {
            log.error("Ошибка при синхронизации с OpenF1: {}", e.getMessage());
        }

        return gp; // Возвращаем GP с текущим (UPCOMING) статусом
    }

    /**
     * Сохраняет результаты квалификации
     */
    private void fetchAndSaveQualifying(GrandPrix gp, int sessionKey) throws Exception {
        // Если уже сохраняли, не дублируем
        if (qualiRepo.count() > 0 && !qualiRepo.findByGrandPrix_Id(gp.getId()).isEmpty()) return;

        String posJson = restTemplate.getForObject(BASE_URL + "/position?session_key=" + sessionKey, String.class);
        JsonNode positions = objectMapper.readTree(posJson);

        Map<Integer, Integer> finalPositions = getFinalPositions(positions);

        for (Map.Entry<Integer, Integer> entry : finalPositions.entrySet()) {
            driverRepo.findByDriverNumber(entry.getKey()).ifPresent(driver -> {
                qualiRepo.save(QualifyingResult.builder()
                        .grandPrix(gp)
                        .driver(driver)
                        .position(entry.getValue())
                        .build());
            });
        }
    }

    /**
     * Сохраняет лучшее время круга из практики
     */
    private void fetchAndSavePractice(GrandPrix gp, int sessionKey) throws Exception {
        if (practiceRepo.count() > 0 && !practiceRepo.findByGrandPrix_Id(gp.getId()).isEmpty()) return;

        String lapsJson = restTemplate.getForObject(BASE_URL + "/lap?session_key=" + sessionKey, String.class);
        JsonNode laps = objectMapper.readTree(lapsJson);

        // Собираем лучшие круги каждого пилота
        Map<Integer, Double> bestLaps = new HashMap<>();
        for (JsonNode lap : laps) {
            int drvNum = lap.get("driver_number").asInt();
            double lapDuration = lap.get("lap_duration").asDouble(); // в секундах
            if (!bestLaps.containsKey(drvNum) || lapDuration < bestLaps.get(drvNum)) {
                bestLaps.put(drvNum, lapDuration);
            }
        }

        // Находим время лидера (P1)
        double p1Time = bestLaps.values().stream().min(Double::compare).orElse(0.0);

        // Сохраняем в БД
        for (Map.Entry<Integer, Double> entry : bestLaps.entrySet()) {
            driverRepo.findByDriverNumber(entry.getKey()).ifPresent(driver -> {
                int lapTimeMs = (int) (entry.getValue() * 1000);
                int gapToP1Ms = (int) ((entry.getValue() - p1Time) * 1000);

                practiceRepo.save(PracticeResult.builder()
                        .grandPrix(gp)
                        .driver(driver)
                        .sessionType("FP3")
                        .lapTimeMs(lapTimeMs)
                        .gapToP1Ms(gapToP1Ms)
                        .build());
            });
        }
    }

    /**
     * Получает финальные результаты гонки и считает ошибку прогноза
     */
    @Transactional
    public void fetchAndSaveRaceResults(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();
        if ("RACE_DONE".equals(gp.getStage())) return;

        try {
            int year = gp.getRaceDate().getYear();
            String country = gp.getCountry();

            String sessionUrl = BASE_URL + "/sessions?year=" + year + "&country_name=" + country + "&session_name=Race";
            JsonNode sessions = restTemplate.getForObject(sessionUrl, JsonNode.class);
            if (sessions == null || sessions.isEmpty()) return;

            int sessionKey = sessions.get(0).get("session_key").asInt();
            String posJson = restTemplate.getForObject(BASE_URL + "/position?session_key=" + sessionKey, String.class);
            JsonNode positions = objectMapper.readTree(posJson);

            Map<Integer, Integer> finalPositions = getFinalPositions(positions);

            for (Map.Entry<Integer, Integer> entry : finalPositions.entrySet()) {
                int drvNum = entry.getKey();
                int finalPos = entry.getValue();

                driverRepo.findByDriverNumber(drvNum).ifPresent(driver -> {
                    // Ищем наш прогноз для этого пилота
                    Prediction pred = predictionRepo.findByGrandPrix_IdAndDriver_Id(gpId, driver.getId());
                    int errorMargin = 0;
                    if (pred != null && pred.getPredictedPosition() != null) {
                        errorMargin = Math.abs(pred.getPredictedPosition() - finalPos);
                    }

                    actualResultRepo.save(ActualResult.builder()
                            .grandPrix(gp)
                            .driver(driver)
                            .finalPosition(finalPos)
                            .errorMargin(errorMargin)
                            .build());
                });
            }

            gp.setStage("RACE_DONE");
            gpRepo.save(gp);
            log.info("Гонка GP {} завершена. Результаты сохранены, ошибка прогноза вычислена.", gpId);

        } catch (Exception e) {
            log.error("Ошибка при получении результатов гонки: {}", e.getMessage());
        }
    }

    // --- ВСПОМОГАТЕЛЬНАЯ ЛОГИКА ---

    /**
     * Парсит массив позиций из API и возвращает Map<Номер пилота, Финальная позиция>
     */
    private Map<Integer, Integer> getFinalPositions(JsonNode positions) {
        Map<Integer, Integer> finalPositions = new HashMap<>();
        Map<Integer, String> latestDates = new HashMap<>();

        for (JsonNode posNode : positions) {
            int drvNum = posNode.get("driver_number").asInt();
            String date = posNode.get("date").asText();
            int pos = posNode.get("position").asInt();

            if (!latestDates.containsKey(drvNum) || date.compareTo(latestDates.get(drvNum)) > 0) {
                latestDates.put(drvNum, date);
                finalPositions.put(drvNum, pos);
            }
        }
        return finalPositions;
    }

    /**
     * Ленивая загрузка: пытается скачать реальные данные, но если они слишком большие (таймаут 3 сек),
     * переключается на генерацию реалистичной заглушки.
     */
    @Transactional
    public void fetchHistoryForGrandPrix(GrandPrix gp) {
        if (histRepo.existsByGpName(gp.getName())) {
            log.info("История для {} уже загружена.", gp.getName());
            return;
        }

        log.info("Попытка скачать историю из OpenF1 для {}...", gp.getName());
        int currentYear = LocalDateTime.now().getYear();
        int downloadedCount = 0;

        for (int year = currentYear - 1; year >= currentYear - 3; year--) {
            try {
                // 1. Скачиваем список гонок (это быстро, весит пару КБ)
                String sessionsJson = restTemplate.getForObject(BASE_URL + "/sessions?session_name=Race&year=" + year, String.class);
                JsonNode sessions = objectMapper.readTree(sessionsJson);

                if (sessions == null || sessions.size() == 0) continue;

                for (JsonNode session : sessions) {
                    String countryName = session.path("country_name").asText();

                    if (countryName.equalsIgnoreCase(gp.getCountry())) {
                        int sessionKey = session.path("session_key").asInt();
                        log.info("Найдена гонка: {} ({}). Запрос позиций...", countryName, year);

                        // 2. Пытаемся скачать позиции (здесь может случиться таймаут из-за размера файла)
                        String posJson = restTemplate.getForObject(BASE_URL + "/position?session_key=" + sessionKey, String.class);
                        JsonNode positions = objectMapper.readTree(posJson);

                        Map<Integer, Integer> finalPositions = getFinalPositions(positions);
                        downloadedCount += finalPositions.size();

                        for (Map.Entry<Integer, Integer> entry : finalPositions.entrySet()) {
                            int finalYear = year;
                            driverRepo.findByDriverNumber(entry.getKey()).ifPresent(driver -> {
                                histRepo.save(HistoricalResult.builder()
                                        .driver(driver)
                                        .gpName(gp.getName())
                                        .season(finalYear)
                                        .finalPosition(entry.getValue())
                                        .teamName(driver.getTeam())
                                        .build());
                            });
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // Если файл слишком большой (Read timed out) или обрыв связи (Unexpected end-of-input)
                log.warn("Не удалось скачать/распарсить историю за {} год: {}. Файл слишком большой.", year, e.getClass().getSimpleName());
            }
        }

        // 3. Если реальные данные не скачались, используем заглушку
        if (downloadedCount == 0) {
            log.warn("Данные из API недоступны. Генерируем реалистичную заглушку для {}.", gp.getName());
            generateMockHistoryForGp(gp);
        } else {
            log.info("Успешно сохранено {} реальных результатов для {}.", downloadedCount, gp.getName());
        }
    }

    private void generateMockHistoryForGp(GrandPrix gp) {
        List<Driver> drivers = driverRepo.findAll();
        int currentYear = LocalDateTime.now().getYear();

        for (Driver driver : drivers) {
            int pos = getMockPosition2026(driver);
            histRepo.save(HistoricalResult.builder()
                    .driver(driver)
                    .gpName(gp.getName())
                    .season(currentYear - 1)
                    .finalPosition(pos)
                    .teamName(driver.getTeam())
                    .build());
        }
    }

    private int getMockPosition2026(Driver driver) {
        String team = driver.getTeam() == null ? "" : driver.getTeam();

        if (team.contains("Mercedes")) {
            return (int)(Math.random() * 2) + 1; // 1-2 место
        } else if (team.contains("Ferrari")) {
            return (int)(Math.random() * 2) + 3; // 3-4 место
        } else if (team.contains("Red Bull") || team.contains("McLaren")) {
            return (int)(Math.random() * 4) + 5; // 5-8 место
        } else if (team.contains("Aston Martin")) {
            return (int)(Math.random() * 2) + 19; // 19-20 место
        } else {
            return (int)(Math.random() * 10) + 9; // 9-18 место
        }
    }
}