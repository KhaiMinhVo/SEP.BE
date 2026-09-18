package com.influencermatch.backend.creator.controller;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.controller.*;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.dto.CreatorIngestionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
public class CreatorIngestionController {

    private final CreatorIngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<ApiResponse<CreatorIngestionResponse>> ingestCreatorData(@RequestBody CreatorIngestionRequest request) {
        CreatorIngestionResponse response = ingestionService.ingestCreatorData(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
