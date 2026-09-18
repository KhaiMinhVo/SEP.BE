package com.influencermatch.backend.common.dto;
import com.influencermatch.backend.common.model.*;
import com.influencermatch.backend.common.repository.*;
import com.influencermatch.backend.common.service.*;
import com.influencermatch.backend.common.enums.*;
import com.influencermatch.backend.common.dto.*;
import com.influencermatch.backend.common.controller.*;
import org.springframework.data.domain.Page;
import java.util.List;
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}


