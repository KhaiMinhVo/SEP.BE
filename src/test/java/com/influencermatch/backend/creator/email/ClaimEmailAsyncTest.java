package com.influencermatch.backend.creator.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ClaimEmailAsyncTest {
  @org.springframework.boot.test.context.TestConfiguration @EnableAsync @Import({ClaimEmailWorker.class,ClaimEmailConfig.class})
  static class Config {
    @Bean jakarta.persistence.EntityManagerFactory entityManagerFactory(){return mock(jakarta.persistence.EntityManagerFactory.class);}
    @Bean ClaimEmailOutboxService outbox(){return mock(ClaimEmailOutboxService.class);}
    @Bean VerificationEmailSender sender(){return mock(VerificationEmailSender.class);}
    @Bean ClaimEmailCipher cipher(){return new ClaimEmailCipher(ClaimEmailCipherTest.KEY,new ObjectMapper());}
  }
  @Test void workerRunsOnDedicatedBoundedExecutorAndDoesNotHoldTransaction() throws Exception {
    try(var context=new AnnotationConfigApplicationContext(Config.class)) {
      var store=context.getBean(ClaimEmailOutboxService.class);var sender=context.getBean(VerificationEmailSender.class);
      UUID id=UUID.randomUUID();var work=new ClaimEmailOutboxService.Work(id,UUID.randomUUID(),context.getBean(ClaimEmailCipher.class).encrypt("email@test.test","https://test.test/?token=x"));
      when(store.prepare(id)).thenReturn(work);
      var done=new CountDownLatch(1);var thread=new AtomicReference<String>();var activeTx=new AtomicReference<Boolean>();
      doAnswer(i->{thread.set(Thread.currentThread().getName());activeTx.set(TransactionSynchronizationManager.isActualTransactionActive());done.countDown();return null;})
          .when(store).finish(work,true);
      context.getBean(ClaimEmailWorker.class).sendAsync(id);
      assertThat(done.await(10,TimeUnit.SECONDS)).isTrue();assertThat(thread.get()).startsWith("claim-email-");assertThat(activeTx.get()).isFalse();
      verify(sender).sendCreatorClaimInvitation("email@test.test","https://test.test/?token=x");
      var executor=context.getBean("claimEmailExecutor",ThreadPoolTaskExecutor.class);
      assertThat(executor.getCorePoolSize()).isEqualTo(2);assertThat(executor.getMaxPoolSize()).isEqualTo(2);
      assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
    }
  }
  @Test void rejectedExecutorLeavesPersistedJobsForLaterSweep() {
    var worker=mock(ClaimEmailWorker.class);var outbox=mock(ClaimEmailOutboxService.class);var dispatch=new ClaimEmailDispatch(worker,outbox);
    UUID id=UUID.randomUUID();doThrow(new org.springframework.core.task.TaskRejectedException("Full")).when(worker).sendAsync(id);
    assertThatCode(()->dispatch.committed(new ClaimEmailOutboxService.Queued(id))).doesNotThrowAnyException();
    when(outbox.due()).thenReturn(java.util.List.of(id));doNothing().when(worker).sendAsync(id);dispatch.sweep();
    verify(worker,times(2)).sendAsync(id);verifyNoMoreInteractions(worker);
  }
  @Test void smtpFailureLogsOnlyJobIdAndRecordsRetryWithoutSensitiveDetails() {
    var store=mock(ClaimEmailOutboxService.class);var sender=mock(VerificationEmailSender.class);
    var cipher=new ClaimEmailCipher(ClaimEmailCipherTest.KEY,new ObjectMapper());
    var worker=new ClaimEmailWorker(store,cipher,sender);UUID id=UUID.randomUUID();
    var work=new ClaimEmailOutboxService.Work(id,UUID.randomUUID(),cipher.encrypt("private@example.test","https://test.test/?token=private-token"));
    when(store.prepare(id)).thenReturn(work);
    doThrow(new org.springframework.mail.MailSendException("private-provider-error")).when(sender).sendCreatorClaimInvitation(anyString(),anyString());
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ClaimEmailWorker.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try {
      worker.process(id);verify(store).finish(work,false);
      assertThat(appender.list).hasSize(1).allSatisfy(event->{
        assertThat(event.getFormattedMessage()).contains(id.toString()).doesNotContain("private@example.test","private-token","private-provider-error");
        assertThat(event.getThrowableProxy()).isNull();
      });
    } finally {logger.detachAppender(appender);appender.stop();}
  }
}
