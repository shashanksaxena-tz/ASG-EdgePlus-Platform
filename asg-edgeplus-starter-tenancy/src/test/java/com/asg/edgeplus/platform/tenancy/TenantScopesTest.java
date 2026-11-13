package com.asg.edgeplus.platform.tenancy;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TenantScopesTest {

  private static final TenantScope FIRM_A = TenantScope.firm(TenantId.STANDARD);
  private static final TenantScope FIRM_B = TenantScope.firm(TenantId.of("00000000-0000-0000-0000-0000000000b2"));

  @Test
  void nothingIsBoundUntilSomeoneSaysWhichScopeTheWorkIsIn() {
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void bindsTheScopeForTheDurationOfTheWork() {
    TenantScope seen = TenantScopes.callIn(FIRM_A, () -> TenantScopes.current().orElseThrow());
    assertThat(seen).isEqualTo(FIRM_A);
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void runInBindsAndReleasesToo() {
    AtomicReference<TenantScope> seen = new AtomicReference<>();
    TenantScopes.runIn(TenantScope.platform(), () -> seen.set(TenantScopes.current().orElseThrow()));
    assertThat(seen.get()).isEqualTo(TenantScope.platform());
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void restoresTheOuterScopeRatherThanClearingIt() {
    TenantScopes.runIn(
        FIRM_A,
        () -> {
          TenantScopes.runIn(FIRM_B, () -> assertThat(TenantScopes.current()).contains(FIRM_B));
          assertThat(TenantScopes.current()).contains(FIRM_A);
        });
  }

  @Test
  void releasesTheScopeWhenTheWorkThrows() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                TenantScopes.runIn(
                    FIRM_A,
                    () -> {
                      throw new IllegalStateException("boom");
                    }));
    assertThat(TenantScopes.current()).isEmpty();
  }

  @Test
  void aScopeDoesNotLeakToAnotherThread() throws Exception {
    AtomicReference<Boolean> otherThreadSawScope = new AtomicReference<>();
    TenantScopes.runIn(
        FIRM_A,
        () -> {
          Thread t = new Thread(() -> otherThreadSawScope.set(TenantScopes.current().isPresent()));
          t.start();
          try {
            t.join();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(otherThreadSawScope.get()).isFalse();
  }

  @Test
  void aFirmScopeNeedsAFirm() {
    assertThatNullPointerException().isThrownBy(() -> TenantScope.firm(null));
  }

  @Test
  void aFirmScopeKnowsItsFirmAndPlatformKnowsNone() {
    assertThat(FIRM_A).isInstanceOf(TenantScope.Firm.class);
    assertThat(((TenantScope.Firm) FIRM_A).tenantId()).isEqualTo(TenantId.STANDARD);
    assertThat(TenantScope.platform()).isSameAs(TenantScope.platform());
    assertThat(TenantScope.platform()).isNotEqualTo(FIRM_A);
  }

  @Test
  void scopesDescribeThemselvesForLogs() {
    assertThat(TenantScope.platform().toString()).contains("platform");
    assertThat(FIRM_A.toString()).contains(TenantId.STANDARD.value().toString());
  }

  @Test
  void callInRejectsNullArguments() {
    assertThatNullPointerException().isThrownBy(() -> TenantScopes.callIn(null, () -> "x"));
    assertThatNullPointerException().isThrownBy(() -> TenantScopes.callIn(FIRM_A, null));
  }

  @Test
  void switchingToAnotherScopeWhileARoutedConnectionIsBoundIsRefused() {
    TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    TenantScopes.runIn(
        FIRM_A,
        () -> {
          TransactionSynchronizationManager.bindResource(routing, new Object());
          try {
            assertThatThrownBy(() -> TenantScopes.runIn(FIRM_B, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside an active transaction");
            assertThatThrownBy(() -> TenantScopes.runIn(TenantScope.platform(), () -> {}))
                .isInstanceOf(IllegalStateException.class);
            assertThat(TenantScopes.callIn(FIRM_A, () -> "same scope is fine")).isEqualTo("same scope is fine");
            assertThat(TenantScopes.current()).contains(FIRM_A);
          } finally {
            TransactionSynchronizationManager.unbindResource(routing);
          }
        });
  }

  @Test
  void aTransactionOnAnotherResourceDoesNotBlockEnteringAScope() {
    Object kafkaProducerFactory = new Object();
    TransactionSynchronizationManager.bindResource(kafkaProducerFactory, new Object());
    try {
      assertThat(TenantScopes.callIn(FIRM_B, () -> TenantScopes.current())).contains(FIRM_B);
    } finally {
      TransactionSynchronizationManager.unbindResource(kafkaProducerFactory);
    }
  }
}
