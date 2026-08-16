package com.cashfactories.formula_one_future_oracle.controller;

import com.cashfactories.formula_one_future_oracle.model.GrandPrix;
import com.cashfactories.formula_one_future_oracle.service.GrandPrixFacadeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/grand-prix")
@RequiredArgsConstructor
public class GrandPrixController {

    private final GrandPrixFacadeService facadeService;

    @GetMapping
    public ResponseEntity<List<GrandPrix>> getAllGrandPrix() {
        return ResponseEntity.ok(facadeService.getAllGrandPrix());
    }

    @GetMapping("/{gpId}/data")
    public ResponseEntity<List<?>> getGrandPrixData(@PathVariable Long gpId) {
        return ResponseEntity.ok(facadeService.getGrandPrixData(gpId));
    }
}
