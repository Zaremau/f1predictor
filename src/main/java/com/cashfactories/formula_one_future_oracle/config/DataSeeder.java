package com.cashfactories.formula_one_future_oracle.config;

import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final DriverRepository driverRepo;
    private final GrandPrixRepository gpRepo;
    private final HistoricalResultRepository histRepo;
    private final ActualResultRepository actualResultRepo;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String OPENF1_BASE = "https://api.openf1.org/v1";

    @Override
    public void run(String... args) {
        if (gpRepo.count() > 0) {
            log.info("База данных уже содержит календарь, пропускаем инициализацию.");
            return;
        }

        log.info("=== Начало инициализации данных (Системная дата: 2026 год) ===");
        try {
            seedRealDriversAndHistoryFromOpenF1();
            log.info("=== Реальные данные из OpenF1 загружены ===");
        } catch (Exception e) {
            log.warn("!!! Не удалось получить полную историю из OpenF1: {}. Используем Fallback для истории.", e.getMessage());
            seedFallbackHistory();
        }

        // В любом случае загружаем календарь 2026 года
        seedCalendar2026();
        log.info("=== Инициализация завершена ===");
    }

    // 1. Скачиваем пилотов и последние гонки из OpenF1
    private void seedRealDriversAndHistoryFromOpenF1() throws Exception {
        if (driverRepo.count() == 0) {
            log.info("Запрос пилотов из OpenF1...");
            String json = restTemplate.getForObject(OPENF1_BASE + "/drivers?session_key=latest", String.class);
            JsonNode root = objectMapper.readTree(json);

            for (JsonNode node : root) {
                driverRepo.save(Driver.builder()
                        .name(node.get("full_name").asText())
                        .team(node.get("team_name").asText())
                        .driverNumber(node.get("driver_number").asInt())
                        .build());
            }
        }

        log.info("Запрос исторических результатов (сезон 2024) из OpenF1...");
        // Ограничиваем сезон 2024 года, чтобы не скачать гигантский JSON за всю историю Ф1
        String sessionsJson = restTemplate.getForObject(OPENF1_BASE + "/sessions?session_name=Race&year=2024", String.class);
        JsonNode sessions = objectMapper.readTree(sessionsJson);

        // Берем первые 3 гонки сезона 2024 для исторических данных
        int racesToFetch = Math.min(3, sessions.size());
        for (int i = 0; i < racesToFetch; i++) {
            JsonNode raceSession = sessions.get(i);
            int sessionKey = raceSession.get("session_key").asInt();
            String gpName = raceSession.get("meeting_name").asText();
            int season = raceSession.get("year").asInt();

            String posJson = restTemplate.getForObject(OPENF1_BASE + "/position?session_key=" + sessionKey, String.class);
            JsonNode positions = objectMapper.readTree(posJson);

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

            for (Map.Entry<Integer, Integer> entry : finalPositions.entrySet()) {
                int drvNum = entry.getKey();
                int finalPos = entry.getValue();

                driverRepo.findByDriverNumber(drvNum).ifPresent(driver -> {
                    histRepo.save(HistoricalResult.builder()
                            .driver(driver)
                            .gpName(gpName)
                            .season(season)
                            .finalPosition(finalPos)
                            .teamName(driver.getTeam())
                            .build());
                });
            }
        }
    }

    // 2. Календарь 2026 года (относительно текущей даты системы)
    private void seedCalendar2026() {
        log.info("Загрузка календаря 2026 года...");
        LocalDateTime now = LocalDateTime.now();

        gpRepo.save(GrandPrix.builder().name("Bahrain Grand Prix").country("Bahrain").raceDate(now.minusDays(140)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Saudi Arabian Grand Prix").country("Saudi Arabia").raceDate(now.minusDays(130)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Australian Grand Prix").country("Australia").raceDate(now.minusDays(120)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Japanese Grand Prix").country("Japan").raceDate(now.minusDays(110)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Chinese Grand Prix").country("China").raceDate(now.minusDays(100)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Miami Grand Prix").country("USA").raceDate(now.minusDays(90)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Emilia Romagna Grand Prix").country("Italy").raceDate(now.minusDays(80)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Monaco Grand Prix").country("Monaco").raceDate(now.minusDays(70)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Canadian Grand Prix").country("Canada").raceDate(now.minusDays(60)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Spanish Grand Prix").country("Spain").raceDate(now.minusDays(50)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Austrian Grand Prix").country("Austria").raceDate(now.minusDays(40)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("British Grand Prix").country("UK").raceDate(now.minusDays(30)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Hungarian Grand Prix").country("Hungary").raceDate(now.minusDays(20)).stage("RACE_DONE").build());
        gpRepo.save(GrandPrix.builder().name("Belgian Grand Prix").country("Belgium").raceDate(now.minusDays(10)).stage("RACE_DONE").build());

        gpRepo.save(GrandPrix.builder().name("Dutch Grand Prix").country("Netherlands").raceDate(now.plusDays(5)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Italian Grand Prix").country("Italy").raceDate(now.plusDays(15)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Azerbaijan Grand Prix").country("Azerbaijan").raceDate(now.plusDays(25)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Singapore Grand Prix").country("Singapore").raceDate(now.plusDays(35)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("United States Grand Prix").country("USA").raceDate(now.plusDays(45)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Mexico City Grand Prix").country("Mexico").raceDate(now.plusDays(55)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("São Paulo Grand Prix").country("Brazil").raceDate(now.plusDays(65)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Las Vegas Grand Prix").country("USA").raceDate(now.plusDays(75)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Qatar Grand Prix").country("Qatar").raceDate(now.plusDays(85)).stage("UPCOMING").build());
        gpRepo.save(GrandPrix.builder().name("Abu Dhabi Grand Prix").country("UAE").raceDate(now.plusDays(95)).stage("UPCOMING").build());
    }

    // 3. Безопасный Fallback (не падает с ошибкой Duplicate Key)
    private void seedFallbackHistory() {
        log.info("Загрузка резервной истории (если пилоты есть, они не дублируются)...");

        // Получаем Ферстаппена (если он уже скачался из API)
        Driver verstappen = driverRepo.findByDriverNumber(1).orElseGet(() ->
                driverRepo.save(Driver.builder().name("Max Verstappen").team("Red Bull Racing").driverNumber(1).build())
        );
        // Получаем Норриса
        Driver norris = driverRepo.findByDriverNumber(4).orElseGet(() ->
                driverRepo.save(Driver.builder().name("Lando Norris").team("McLaren").driverNumber(4).build())
        );
        // Получаем Хэмилтона
        Driver hamilton = driverRepo.findByDriverNumber(44).orElseGet(() ->
                driverRepo.save(Driver.builder().name("Lewis Hamilton").team("Ferrari").driverNumber(44).build())
        );

        if (histRepo.count() == 0) {
            histRepo.save(HistoricalResult.builder().driver(norris).gpName("Belgian Grand Prix").season(2025).finalPosition(1).teamName("McLaren").build());
            histRepo.save(HistoricalResult.builder().driver(verstappen).gpName("Belgian Grand Prix").season(2025).finalPosition(2).teamName("Red Bull Racing").build());
            histRepo.save(HistoricalResult.builder().driver(hamilton).gpName("Belgian Grand Prix").season(2025).finalPosition(3).teamName("Ferrari").build());
        }
    }
}