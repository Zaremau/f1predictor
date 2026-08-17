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
        // Arrange (Подготовка данных)
        when(gpRepo.findById(1L)).thenReturn(Optional.of(testGp));
        when(driverRepo.findAll()).thenReturn(List.of(testDriver));

        // Мокаем историю: Ферстаппен всегда финишировал 1-м (Score должен быть 100)
        HistoricalResult histResult = HistoricalResult.builder().driver(testDriver).finalPosition(1).build();
        when(histRepo.findByDriver_Id(anyLong())).thenReturn(List.of(histResult));
        when(histRepo.findByDriver_IdAndGpName(anyLong(), anyString())).thenReturn(List.of(histResult));

        // Мокаем новости: Нет новостей (Score должен быть нейтральным - 50)
        when(newsRepo.findByGrandPrix_Id(anyLong())).thenReturn(Collections.emptyList());

        // Мокаем сохранение в БД, чтобы просто вернуть тот же прогноз
        when(predictionRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act (Действие)
        List<Prediction> predictions = predictionService.generatePredictions(1L);

        // Assert (Проверка)
        assertFalse(predictions.isEmpty());
        Prediction maxPred = predictions.get(0);

        // Проверяем, что он на 1-м месте
        assertEquals(1, maxPred.getPredictedPosition());

        // Проверяем математику: (100 * 0.5) + (100 * 0.2) + (50 * 0.3) = 50 + 20 + 15 = 85
        assertEquals(85.0, maxPred.getScore());

        // Проверяем уверенность для стадии UPCOMING
        assertEquals(0.4, maxPred.getConfidence());
    }

    @Test
    void generatePredictions_WhenNewsHasPenalty_ShouldReduceScore() {
        // Arrange
        when(gpRepo.findById(1L)).thenReturn(Optional.of(testGp));
        when(driverRepo.findAll()).thenReturn(List.of(testDriver));

        HistoricalResult histResult = HistoricalResult.builder().driver(testDriver).finalPosition(1).build();
        when(histRepo.findByDriver_Id(anyLong())).thenReturn(List.of(histResult));
        when(histRepo.findByDriver_IdAndGpName(anyLong(), anyString())).thenReturn(List.of(histResult));

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
        // С новыми весами: (100*0.5) + (100*0.2) + (10*0.3) = 50 + 20 + 3 = 73
        // Штраф за риск: 73 - 15 = 58
        assertEquals(58.0, maxPred.getScore());
        assertEquals("HIGH", maxPred.getRiskLevel()); // Риск должен быть высоким из-за штрафа
    }

    @Test
    void generatePredictions_WhenQualiDone_ShouldCalculateBasedOnQualifyingAndPractice() {
        // Arrange
        // Меняем стадию на QUALI_DONE
        testGp.setStage("QUALI_DONE");
        when(gpRepo.findById(1L)).thenReturn(Optional.of(testGp));
        when(driverRepo.findAll()).thenReturn(List.of(testDriver));

        // История: 1 место (Score = 100)
        HistoricalResult histResult = HistoricalResult.builder().driver(testDriver).finalPosition(1).build();
        when(histRepo.findByDriver_Id(anyLong())).thenReturn(List.of(histResult));
        when(histRepo.findByDriver_IdAndGpName(anyLong(), anyString())).thenReturn(List.of(histResult));

        // Новости: Нет (Score = 50)
        when(newsRepo.findByGrandPrix_Id(anyLong())).thenReturn(Collections.emptyList());

        // Практика: Отрыв от лидера 0 секунд (Score = 100)
        PracticeResult pr = PracticeResult.builder().gapToP1Ms(0).build();
        when(practiceRepo.findTopByGrandPrix_IdAndDriver_IdOrderByLapTimeMsAsc(anyLong(), anyLong())).thenReturn(pr);

        // Квалификация: Поул-позиция (1 место) (Score = 100)
        QualifyingResult qr = QualifyingResult.builder().position(1).build();
        when(qualiRepo.findByGrandPrix_IdAndDriver_Id(anyLong(), anyLong())).thenReturn(qr);

        when(predictionRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<Prediction> predictions = predictionService.generatePredictions(1L);
        Prediction maxPred = predictions.get(0);

        // Assert
        // Формула для QUALI_DONE: (история*0.2) + (история_трека*0.1) + (новости*0.1) + (практика*0.2) + (квалификация*0.4)
        // (100*0.2) + (100*0.1) + (50*0.1) + (100*0.2) + (100*0.4) = 20 + 10 + 5 + 20 + 40 = 95
        assertEquals(95.0, maxPred.getScore());

        // Уверенность для QUALI_DONE должна быть 0.9
        assertEquals(0.9, maxPred.getConfidence());
    }
}