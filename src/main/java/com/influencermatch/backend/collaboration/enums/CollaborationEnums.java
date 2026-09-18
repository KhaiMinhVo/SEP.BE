package com.influencermatch.backend.collaboration.enums;
import com.influencermatch.backend.collaboration.model.*;
import com.influencermatch.backend.collaboration.repository.*;
import com.influencermatch.backend.collaboration.service.*;
import com.influencermatch.backend.collaboration.enums.*;
import com.influencermatch.backend.collaboration.dto.*;
import com.influencermatch.backend.collaboration.controller.*;
enum CollaborationStatus { PLANNED, IN_PROGRESS, COMPLETED, CANCELLED }
enum CollaborationPaymentStatus { PENDING, PARTIALLY_PAID, PAID, FAILED, REFUNDED }


