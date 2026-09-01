package com.asg.edgeplus.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Both outcomes of the two sign predicates.
 *
 * <p>Existing MoneyTest exercised the arithmetic thoroughly but only ever asked isPositive/isZero
 * about a positive amount, so the false branch of each was never taken — which is exactly the kind
 * of gap a branch-coverage gate exists to catch. A negative Money is not hypothetical here: a
 * credit note or a straight-line adjustment reversal produces one.
 */
class MoneySignTest {

  @Test
  void isPositive_is_true_only_above_zero() {
    assertThat(Money.of(new BigDecimal("0.01"), "USD").isPositive()).isTrue();
    assertThat(Money.zero("USD").isPositive()).isFalse();
    assertThat(Money.of(new BigDecimal("-0.01"), "USD").isPositive()).isFalse();
  }

  @Test
  void isZero_is_true_only_at_zero() {
    assertThat(Money.zero("USD").isZero()).isTrue();
    assertThat(Money.of(new BigDecimal("0.01"), "USD").isZero()).isFalse();
    assertThat(Money.of(new BigDecimal("-0.01"), "USD").isZero()).isFalse();
  }

  /** Scale must not change the answer: 0.00 and 0 are both zero, and neither is positive. */
  @Test
  void trailing_zeros_do_not_change_the_sign_answers() {
    Money scaled = Money.of(new BigDecimal("0.0000"), "USD");
    assertThat(scaled.isZero()).isTrue();
    assertThat(scaled.isPositive()).isFalse();
  }

  @Test
  void a_negative_amount_survives_multiplication_by_a_negative_factor() {
    Money credit = Money.of(new BigDecimal("-100.00"), "USD");
    assertThat(credit.isPositive()).isFalse();
    assertThat(credit.multiply(-1).isPositive()).isTrue();
  }
}
