package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.dto.MockPaymentDtos.*;
import com.influencermatch.backend.billing.model.Payment;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.*;
import jakarta.validation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
@Profile("dev & !prod")
@ConditionalOnProperty(name="application.payment.mock.enabled",havingValue="true")
public class MockPaymentService {
  private final PaymentRepository payments;
  private final PaymentSettlementService settlement;
  private final AuditService audit;
  private final Validator validator;

  @Transactional @PreAuthorize("hasAuthority('MANAGE_OWN_SUBSCRIPTION')")
  public Response process(Request request) {
    Permissions.require(Permission.MANAGE_OWN_SUBSCRIPTION);
    var violations=validator.validate(request);
    if(!violations.isEmpty()) throw new ConstraintViolationException(violations);
    Payment existing=payments.findByTransactionCode(request.transactionCode())
        .filter(p->p.getBrandProfile().getUser().getId().equals(Permissions.actor().getId()))
        .orElseThrow(()->new NotFoundException("Payment not found"));
    Payment payment=settlement.lock(existing);
    Response before=response(payment);
    if(!settlement.apply(payment,request.outcome()==Outcome.SUCCESS,"MOCK_FAILED"))
      throw new ConflictException(ErrorCode.SUBSCRIPTION_CONFLICT,"Payment outcome conflicts with current payment or subscription state");
    Response after=response(payment);
    if(!before.equals(after)) audit.record("MOCK_PAYMENT_WEBHOOK","Payment",payment.getId(),before,after,"Development payment simulation");
    return after;
  }

  private Response response(Payment payment) {
    var sub=payment.getSubscription();
    return new Response(payment.getTransactionCode(),payment.getStatus(),sub.getId(),sub.getStatus(),sub.getStartDate(),sub.getExpirationDate());
  }
}
