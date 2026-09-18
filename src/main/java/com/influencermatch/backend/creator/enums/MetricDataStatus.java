package com.influencermatch.backend.creator.enums;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.controller.*;

public enum MetricDataStatus {
    FRESH,
    STALE,
    REFRESH_FAILED
}
