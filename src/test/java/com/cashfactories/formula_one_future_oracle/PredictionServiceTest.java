package com.cashfactories.formula_one_future_oracle;

import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import com.cashfactories.formula_one_future_oracle.service.PredictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PredictionServiceTest {

    @Mock private DriverRepository driverRepo;
    @Mock private GrandPrixRepository gpRepo;
    @Mock private NewsRepository newsRepo;
    @Mock private HistoricalResultRepository histRepo;
    @Mock private PracticeRepository practiceRepo;
    @Mock private QualifyingRepository qualiRepo;
    @Mock private PredictionRepository predictionRepo;

    @InjectMocks
    private PredictionService predictionService;

    private Driver testDriver;
    private GrandPrix testGp;

    @BeforeEach
    void setUp() {
        testDriver = Driver.builder().id(1L).name("Max Verstappen").team("Red Bull").driverNumber(1).build();
        testGp = GrandPrix.builder().id(1L).name("Monaco Grand Prix").country("Monaco")
                .raceDate(LocalDateTime.now().plusDays(5)).stage("UPCOMING").build();
    }

    @Test
    void generatePredictions_WhenUpcomingStage_ShouldCalculateBasedOnHistoryAndNews() {
        // Arrange
        when(gpRepo.findById(1L)).thenReturn(Optional.of(testGp));
        when(driverRepo.findAll()).thenReturn(List.of(testDriver));

        // НОВОЕ: Мокаем JPQL запросы к базе. Возвращаем среднее место = 1.0 (Score = 100)
        when(histRepo.findAveragePositionByDriverAndSeason(anyLong(), anyInt())).thenReturn(1.0);
        when(histRepo.findAverageTrackPosition(anyLong(), anyString(), anyInt())).thenReturn(1.0);

        // Новости: Нет новостей (Score = 50)
        when(newsRepo.findByGrandPrix_Id(anyLong())).thenReturn(Collections.emptyList());

        when(predictionRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<Prediction> predictions = predictionService.generatePredictions(1L);
        Prediction maxPred = predictions.get(0);

        // Assert
        assertEquals(1, maxPred.getPredictedPosition());

        // Проверяем математику: (100 * 0.5) + (100 * 0.2) + (50 * 0.3) = 85
        assertEquals(85.0, maxPred.getScore());

        // Проверяем новую математику уверенности:
        // base(0.4) + scoreComponent(|85-50|/50*0.15 = 0.105) + dataBonus(0.05+0.03 = 0.08) - disagreement(0) = 0.585
        assertEquals(0.585, maxPred.getConfidence());
    }

    @Test
    void generatePredictions_WhenNewsHasPenalty_ShouldReduceScore() {
        // Arrange
        when(gpRepo.findById(1L)).thenReturn(Optional.of(testGp));
        when(driverRepo.findAll()).thenReturn(List.of(testDriver));

        when(histRepo.findAveragePositionByDriverAndSeason(anyLong(), anyInt())).thenReturn(1.0);
        when(histRepo.findAverageTrackPosition(anyLong(), anyString(), anyInt())).thenReturn(1.0);

        // Мокаем плохую новость с риском
        News badNews = News.builder()
                .title("Verstappen gets grid penalty")
                .sentimentScore(-0.8)
                .riskKeywords(new String[]{"grid penalty"})
                .mentionedDrivers(new String[]{"Max Verstappen"})
                .build();
        when(newsRepo.findByGrandPrix_Id(anyLong())).thenReturn(List.of(badNews));

        when(predictionRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<Prediction> predictions = predictionService.generatePredictions(1L);
        Prediction maxPred = predictions.get(0);

        // Assert
        // News Score = 50 + (-0.8 * 50) = 10.
        // (100*0.5) + (100*0.2) + (10*0.3) = 50 + 20 + 3 = 73. Штраф: 73 - 15 = 58.
        assertEquals(58.0, maxPred.getScore());
        assertEquals("HIGH", maxPred.getRiskLevel());

        // Уверенность: base(0.4) + scoreComponent(|58-50|/50*0.15 = 0.024) + dataBonus(0.08) - disagreement(0) - hasPenalty(0.05) = 0.454
        assertEquals(0.454, maxPred.getConfidence());
    }

    @Test
    void generatePredictions_WhenQualiDone_ShouldCalculateBasedOnQualifyingAndPractice() {
        // Arrange
        testGp.setStage("QUALI_DONE");
        when(gpRepo.findById(1L)).thenReturn(Optional.of(testGp));
        when(driverRepo.findAll()).thenReturn(List.of(testDriver));

        when(histRepo.findAveragePositionByDriverAndSeason(anyLong(), anyInt())).thenReturn(1.0);
        when(histRepo.findAverageTrackPosition(anyLong(), anyString(), anyInt())).thenReturn(1.0);
        when(newsRepo.findByGrandPrix_Id(anyLong())).thenReturn(Collections.emptyList());

        // НОВОЕ: Практика с position=1 и gap=0 (Score = 100)
        PracticeResult pr = PracticeResult.builder().position(1).gapToP1Ms(0).build();
        when(practiceRepo.findTopByGrandPrix_IdAndDriver_IdOrderByLapTimeMsAsc(anyLong(), anyLong())).thenReturn(pr);

        // НОВОЕ: Квалификация с position=1 и startingGrid=1 (Score = 100)
        QualifyingResult qr = QualifyingResult.builder().position(1).startingGrid(1).build();
        when(qualiRepo.findByGrandPrix_IdAndDriver_Id(anyLong(), anyLong())).thenReturn(qr);

        when(predictionRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<Prediction> predictions = predictionService.generatePredictions(1L);
        Prediction maxPred = predictions.get(0);

        // Assert
        // (100*0.2) + (100*0.1) + (50*0.1) + (100*0.2) + (100*0.4) = 95
        assertEquals(95.0, maxPred.getScore());

        // Уверенность: base(0.7) + scoreComponent(|95-50|/50*0.15 = 0.135) + dataBonus(0.05+0.03+0.05+0.07 = 0.20) - disagreement(0) = 1.035
        // Ограничено сверху 0.99
        assertEquals(0.99, maxPred.getConfidence());
    }
}