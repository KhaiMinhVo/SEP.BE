package com.influencermatch.backend.brand.dto;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.controller.*;

import com.influencermatch.backend.brand.enums.Platform;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.URL;
import java.util.*;

public record BrandRequest(
        UUID userId,
        @NotBlank @Size(max = 200) String businessName,
        @Size(max = 120) String industry,
        @Size(max = 20) List<@NotBlank @Size(max = 100) String> productCategories,
        @Size(max = 500) @URL String website,
        @Size(max = 150) String location,
        @Size(max = 20) List<@NotBlank @Size(max = 100) String> targetMarkets,
        @Size(max = 20) List<@NotBlank @Size(max = 150) String> targetAudiences,
        @Size(max = 150) String brandTone,
        @Size(max = 3) List<Platform> preferredPlatforms,
        @Size(max = 1000) String description
) {}


