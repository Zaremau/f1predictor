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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenF1Service {

    private static final String BASE_URL = "https://api.openf1.org/v1";

    private final GrandPrixRepository gpRepo;
    private final DriverRepository driverRepo;
    private final QualifyingRepository qualiRepo;
    private final PracticeRepository practiceRepo;
    private final ActualResultRepository actualResultRepo;
    private final PredictionRepository predictionRepo;
    private final HistoricalResultRepository histRepo;

    // Настраиваем таймауты (5 сек на подключение, 10 сек на чтение)
    private final RestTemplate restTemplate = new RestTemplate(getClientHttpRequestFactory());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleClientHttpRequestFactory getClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return factory;
    }

    // =========================================================
    // GRAND PRIX STATUS
    // =========================================================

    @Transactional
    public GrandPrix syncGrandPrixData(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();

        if ("RACE_DONE".equals(gp.getStage())) {
            return gp;
        }

        try {
            int year = gp.getRaceDate().getYear();
            String country = gp.getCountry();
            LocalDateTime now = LocalDateTime.now();

            // -------------------------
            // QUALIFYING
            // -------------------------
            JsonNode qualifyingSessions = null;
            try {
                Thread.sleep(500); // Пауза, чтобы не получить бан 429
                qualifyingSessions = getSessions(year, country, "Qualifying");
            } catch (HttpClientErrorException.NotFound e) {
                log.info("Квалификация за {} год в {} пока не найдена в API.", year, country);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (qualifyingSessions != null && !qualifyingSessions.isEmpty()) {
                JsonNode session = qualifyingSessions.get(0);
                LocalDateTime dateEnd = OffsetDateTime.parse(session.get("date_end").asText()).toLocalDateTime();

                if (dateEnd.isBefore(now)) {
                    int sessionKey = session.get("session_key").asInt();
                    fetchAndSaveQualifying(gp, sessionKey);
                    gp.setStage("QUALI_DONE");
                    gpRepo.save(gp);
                    log.info("GP {} updated to QUALI_DONE", gpId);
                    return gp;
                }
            }

            // -------------------------
            // FP3
            // -------------------------
            JsonNode practiceSessions = null;
            try {
                Thread.sleep(500);
                practiceSessions = getSessions(year, country, "Practice 3");
            } catch (HttpClientErrorException.NotFound e) {
                log.info("Practice 3 за {} год в {} пока не найдена в API.", year, country);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (practiceSessions != null && !practiceSessions.isEmpty()) {
                JsonNode session = practiceSessions.get(0);
                LocalDateTime dateEnd = OffsetDateTime.parse(session.get("date_end").asText()).toLocalDateTime();

                if (dateEnd.isBefore(now)) {
                    int sessionKey = session.get("session_key").asInt();
                    fetchAndSavePractice(gp, sessionKey);
                    gp.setStage("FP_DONE");
                    gpRepo.save(gp);
                    log.info("GP {} updated to FP_DONE", gpId);
                    return gp;
                }
            }

        } catch (Exception e) {
            log.error("OpenF1 sync failed for GP {}", gpId, e);
        }

        return gp;
    }

    private JsonNode getSessions(int year, String country, String sessionName) {
        String url = BASE_URL + "/sessions?year=" + year +
                "&country_name=" + UriUtils.encodeQueryParam(country, StandardCharsets.UTF_8) +
                "&session_name=" + UriUtils.encodeQueryParam(sessionName, StandardCharsets.UTF_8);

        return restTemplate.getForObject(url, JsonNode.class);
    }

    // =========================================================
    // PRACTICE
    // =========================================================

    private void fetchAndSavePractice(GrandPrix gp, int sessionKey) {
        List<PracticeResult> existing = practiceRepo.findByGrandPrix_Id(gp.getId());
        if (!existing.isEmpty()) return;

        try {
            Thread.sleep(500);
            String url = BASE_URL + "/session_result?session_key=" + sessionKey;
            JsonNode results = restTemplate.getForObject(url, JsonNode.class);

            if (results == null || results.isEmpty()) return;

            for (JsonNode result : results) {
                int driverNumber = result.path("driver_number").asInt();
                int position = result.path("position").asInt();
                double duration = result.path("duration").asDouble();
                double gap = parseGap(result.path("gap_to_leader"));

                driverRepo.findByDriverNumber(driverNumber).ifPresent(driver -> {
                    PracticeResult practice = PracticeResult.builder()
                            .grandPrix(gp)
                            .driver(driver)
                            .sessionType("FP3")
                            .position(position)
                            .lapTimeMs((int) (duration * 1000))
                            .gapToP1Ms((int) (gap * 1000))
                            .build();
                    practiceRepo.save(practice);
                });
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Нет результатов (404) для practice session_key: {}", sessionKey);
        } catch (Exception e) {
            log.warn("Ошибка при загрузке практики: {}", e.getClass().getSimpleName());
        }
    }

    // =========================================================
    // QUALIFYING
    // =========================================================

    private void fetchAndSaveQualifying(GrandPrix gp, int sessionKey) {
        if (!qualiRepo.findByGrandPrix_Id(gp.getId()).isEmpty()) return;

        try {
            Thread.sleep(500);
            String resultUrl = BASE_URL + "/session_result?session_key=" + sessionKey;
            JsonNode qualifyingResults = restTemplate.getForObject(resultUrl, JsonNode.class);

            Thread.sleep(500);
            String gridUrl = BASE_URL + "/starting_grid?session_key=" + getRaceSessionKey(gp);
            JsonNode startingGrid = restTemplate.getForObject(gridUrl, JsonNode.class);

            Map<Integer, Integer> gridPositions = new HashMap<>();
            if (startingGrid != null) {
                for (JsonNode node : startingGrid) {
                    gridPositions.put(node.path("driver_number").asInt(), node.path("position").asInt());
                }
            }

            if (qualifyingResults == null) return;

            for (JsonNode result : qualifyingResults) {
                int driverNumber = result.path("driver_number").asInt();
                int qualifyingPosition = result.path("position").asInt();
                Integer staGrid = gridPositions.get(driverNumber);
                Integer q3TimeMs = extractQ3TimeMs(result);

                driverRepo.findByDriverNumber(driverNumber).ifPresent(driver -> {
                    QualifyingResult qualifying = QualifyingResult.builder()
                            .grandPrix(gp)
                            .driver(driver)
                            .position(qualifyingPosition)
                            .startingGrid(staGrid)
                            .q3TimeMs(q3TimeMs)
                            .build();
                    qualiRepo.save(qualifying);
                });
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Нет результатов (404) для qualifying session_key: {}", sessionKey);
        } catch (Exception e) {
            log.warn("Ошибка при загрузке квалификации: {}", e.getClass().getSimpleName());
        }
    }

    private Integer extractQ3TimeMs(JsonNode result) {
        JsonNode duration = result.path("duration");
        if (!duration.isArray() || duration.size() < 3) return null;
        JsonNode q3 = duration.get(2);
        if (!q3.isNumber()) return null;
        return (int) (q3.asDouble() * 1000);
    }

    // =========================================================
    // CURRENT SEASON + TRACK HISTORY
    // =========================================================

    @Transactional
    public void syncHistoricalData(GrandPrix targetGp) {
        int currentYear = targetGp.getRaceDate().getYear();

        syncCurrentSeasonHistory(currentYear);

        for (int year = currentYear - 1; year >= currentYear - 3; year--) {
            syncTrackHistory(targetGp, year);
        }
    }

    private void syncCurrentSeasonHistory(int season) {
        try {
            Thread.sleep(500);
            String url = BASE_URL + "/sessions?year=" + season + "&session_name=Race";
            JsonNode sessions = restTemplate.getForObject(url, JsonNode.class);

            if (sessions == null) return;

            LocalDateTime now = LocalDateTime.now();

            for (JsonNode session : sessions) {
                String dateEndText = session.path("date_end").asText();
                if (dateEndText.isBlank()) continue;

                LocalDateTime dateEnd = OffsetDateTime.parse(dateEndText).toLocalDateTime();
                if (dateEnd.isAfter(now)) continue;

                int sessionKey = session.path("session_key").asInt();
                String gpName = session.path("location").asText();

                saveRaceHistoryIfNeeded(gpName, season, sessionKey);
            }
        } catch (Exception e) {
            log.warn("Ошибка при загрузке истории сезона {}: {}", season, e.getClass().getSimpleName());
        }
    }

    private void syncTrackHistory(GrandPrix targetGp, int season) {
        try {
            Thread.sleep(500);
            String url = BASE_URL + "/sessions?year=" + season +
                    "&country_name=" + UriUtils.encodeQueryParam(targetGp.getCountry(), StandardCharsets.UTF_8) +
                    "&session_name=Race";

            JsonNode sessions = restTemplate.getForObject(url, JsonNode.class);
            if (sessions == null) return;

            for (JsonNode session : sessions) {
                int sessionKey = session.path("session_key").asInt();
                if (!histRepo.existsByGpNameAndSeason(targetGp.getName(), season)) {
                    saveRaceHistory(targetGp.getName(), season, sessionKey);
                }
            }
        } catch (Exception e) {
            log.warn("Ошибка при загрузке истории трассы за {}: {}", season, e.getClass().getSimpleName());
        }
    }

    private void saveRaceHistoryIfNeeded(String gpName, int season, int sessionKey) {
        if (histRepo.existsByGpNameAndSeason(gpName, season)) return;
        saveRaceHistory(gpName, season, sessionKey);
    }

    private void saveRaceHistory(String gpName, int season, int sessionKey) {
        try {
            Thread.sleep(500);
            String url = BASE_URL + "/session_result?session_key=" + sessionKey;
            JsonNode results = restTemplate.getForObject(url, JsonNode.class);

            if (results == null || results.isEmpty()) return;

            for (JsonNode result : results) {
                boolean dnf = result.path("dnf").asBoolean(false);
                boolean dns = result.path("dns").asBoolean(false);
                boolean dsq = result.path("dsq").asBoolean(false);

                if (dnf || dns || dsq) continue;

                int driverNumber = result.path("driver_number").asInt();
                int finalPosition = result.path("position").asInt();

                driverRepo.findByDriverNumber(driverNumber).ifPresent(driver -> {
                    if (!histRepo.existsByDriver_IdAndGpNameAndSeason(driver.getId(), gpName, season)) {
                        histRepo.save(HistoricalResult.builder()
                                .driver(driver)
                                .gpName(gpName)
                                .season(season)
                                .finalPosition(finalPosition)
                                .teamName(driver.getTeam())
                                .sessionKey(sessionKey)
                                .build());
                    }
                });
            }
            log.info("Historical results saved: {} {}", gpName, season);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Нет результатов (404) для session_key: {}", sessionKey);
        } catch (Exception e) {
            log.warn("Ошибка сохранения истории (Key: {}): {}", sessionKey, e.getClass().getSimpleName());
        }
    }

    // =========================================================
    // RACE RESULTS
    // =========================================================

    @Transactional
    public void fetchAndSaveRaceResults(Long gpId) {
        GrandPrix gp = gpRepo.findById(gpId).orElseThrow();
        if ("RACE_DONE".equals(gp.getStage())) return;

        try {
            Thread.sleep(500);
            JsonNode sessions = getSessions(gp.getRaceDate().getYear(), gp.getCountry(), "Race");
            if (sessions == null || sessions.isEmpty()) return;

            int sessionKey = sessions.get(0).path("session_key").asInt();
            Thread.sleep(500);

            String url = BASE_URL + "/session_result?session_key=" + sessionKey;
            JsonNode results = restTemplate.getForObject(url, JsonNode.class);

            if (results == null) return;

            for (JsonNode result : results) {
                int driverNumber = result.path("driver_number").asInt();
                int finalPosition = result.path("position").asInt();

                driverRepo.findByDriverNumber(driverNumber).ifPresent(driver ->
                        saveActualResult(gp, driver, finalPosition)
                );
            }

            gp.setStage("RACE_DONE");
            gpRepo.save(gp);
            log.info("Race results saved for GP {}", gpId);

        } catch (Exception e) {
            log.error("Failed to fetch race results for GP {}", gpId, e);
        }
    }

    private void saveActualResult(GrandPrix gp, Driver driver, int finalPosition) {
        Prediction prediction = predictionRepo.findByGrandPrix_IdAndDriver_Id(gp.getId(), driver.getId());

        int errorMargin = 0;
        String errorType = "NO_PREDICTION";
        String explanation = "Прогноз для этого пилота отсутствовал.";

        if (prediction != null && prediction.getPredictedPosition() != null) {
            int predicted = prediction.getPredictedPosition();
            errorMargin = Math.abs(predicted - finalPosition);

            if (errorMargin == 0) {
                errorType = "EXACT";
                explanation = "Прогноз полностью совпал с фактическим результатом.";
            } else if (finalPosition > predicted) {
                errorType = "OVERESTIMATED";
                explanation = "Система завысила результат пилота на " + errorMargin + " позиций.";
            } else {
                errorType = "UNDERESTIMATED";
                explanation = "Система занизила результат пилота на " + errorMargin + " позиций.";
            }
        }

        actualResultRepo.save(ActualResult.builder()
                .grandPrix(gp)
                .driver(driver)
                .finalPosition(finalPosition)
                .errorMargin(errorMargin)
                .errorType(errorType)
                .errorExplanation(explanation)
                .build());
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private double parseGap(JsonNode gapNode) {
        if (gapNode == null || gapNode.isNull()) return 0.0;
        if (gapNode.isNumber()) return gapNode.asDouble();

        String value = gapNode.asText();
        if (value.startsWith("+")) value = value.substring(1);

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int getRaceSessionKey(GrandPrix gp) {
        JsonNode sessions = getSessions(gp.getRaceDate().getYear(), gp.getCountry(), "Race");
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalStateException("Race session not found");
        }
        return sessions.get(0).path("session_key").asInt();
    }
}