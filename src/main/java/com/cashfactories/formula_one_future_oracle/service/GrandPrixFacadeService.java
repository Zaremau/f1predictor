package com.cashfactories.formula_one_future_oracle.service;

import com.cashfactories.formula_one_future_oracle.dto.ActualResultDto;
import com.cashfactories.formula_one_future_oracle.dto.PredictionDto;
import com.cashfactories.formula_one_future_oracle.model.*;
import com.cashfactories.formula_one_future_oracle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GrandPrixFacadeService {

    private final GrandPrixRepository gpRepo;
    private final PredictionRepository predictionRepo;
    private final ActualResultRepository actualResultRepo;
    private final OpenF1Service openF1Service;
    private final PredictionService predictionService;

    public List<GrandPrix> getAllGrandPrix() {
        return gpRepo.findAll();
    }

    public List<?> getGrandPrixData(Long gpId) {

        GrandPrix gp =
                gpRepo.findById(gpId)
                        .orElseThrow();

        // ==========================================
        // RACE ALREADY FINISHED
        // ==========================================

        if ("RACE_DONE".equals(gp.getStage())) {
            return getRaceResults(gpId);
        }

        // ==========================================
        // UPDATE CURRENT GP
        // ==========================================

        openF1Service.syncGrandPrixData(gpId);

        GrandPrix updatedGp =
                gpRepo.findById(gpId)
                        .orElseThrow();

        if ("RACE_DONE".equals(updatedGp.getStage())) {
            return getRaceResults(gpId);
        }

        // ==========================================
        // EXISTING PREDICTION
        // ==========================================

        List<Prediction> predictions =
                predictionRepo.findByGrandPrix_Id(gpId);

        boolean needNewPrediction =
                predictions.isEmpty()
                        || !predictions.get(0)
                        .getStage()
                        .equals(updatedGp.getStage());

        if (needNewPrediction) {

            if (!predictions.isEmpty()) {
                predictionRepo.deleteAll(predictions);
            }

            /*
             * Получаем:
             *
             * - результаты гонок 2026;
             * - последние результаты этой трассы.
             */
            openF1Service.syncHistoricalData(
                    updatedGp
            );

            predictions =
                    predictionService
                            .generatePredictions(gpId);
        }

        return predictions.stream()
                .map(this::convertToPredictionDto)
                .toList();
    }

    private List<ActualResultDto> getRaceResults(
            Long gpId
    ) {

        List<ActualResult> results =
                actualResultRepo
                        .findByGrandPrix_IdOrderByFinalPositionAsc(
                                gpId
                        );

        if (results.isEmpty()) {

            openF1Service.fetchAndSaveRaceResults(
                    gpId
            );

            results =
                    actualResultRepo
                            .findByGrandPrix_IdOrderByFinalPositionAsc(
                                    gpId
                            );
        }

        return results.stream()
                .map(res -> {

                    Prediction prediction =
                            predictionRepo
                                    .findByGrandPrix_IdAndDriver_Id(
                                            gpId,
                                            res.getDriver().getId()
                                    );

                    Integer predictedPosition =
                            prediction == null
                                    ? null
                                    : prediction.getPredictedPosition();

                    String explanation;

                    if (prediction == null) {

                        explanation =
                                "Прогноз на эту гонку " +
                                        "не строился.";

                    } else {

                        explanation =
                                res.getErrorExplanation();
                    }

                    return ActualResultDto.builder()
                            .driverName(
                                    res.getDriver().getName()
                            )
                            .team(
                                    res.getDriver().getTeam()
                            )
                            .predictedPosition(
                                    predictedPosition
                            )
                            .actualPosition(
                                    res.getFinalPosition()
                            )
                            .errorMargin(
                                    res.getErrorMargin()
                            )
                            .explanation(
                                    explanation
                            )
                            .build();

                })
                .toList();
    }

    private PredictionDto convertToPredictionDto(
            Prediction pred
    ) {

        return PredictionDto.builder()
                .driverName(
                        pred.getDriver().getName()
                )
                .team(
                        pred.getDriver().getTeam()
                )
                .predictedPosition(
                        pred.getPredictedPosition()
                )
                .confidence(
                        pred.getConfidence()
                )
                .riskLevel(
                        pred.getRiskLevel()
                )
                .arguments(
                        pred.getArguments()
                )
                .stage(
                        pred.getStage()
                )
                .build();
    }
}
