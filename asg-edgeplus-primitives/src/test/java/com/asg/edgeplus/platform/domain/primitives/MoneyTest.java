package com.asg.edgeplus.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Money — Constitution §4.4: no double/float in financial code, ever. */
class MoneyTest {

  @Test
  void rejectsNullAmount() {
    assertThatNullPointerException().isThrownBy(() -> new Money(null, Currency.getInstance("USD")));
  }

  @Test
  void rejectsNullCurrency() {
    assertThatNullPointerException().isThrownBy(() -> new Money(BigDecimal.ONE, null));
  }

  @Test
  void normalisesScaleToTheCurrencysFractionDigits() {
    assertThat(Money.of(new BigDecimal("10.5"), "USD").amount()).isEqualTo(new BigDecimal("10.50"));
  }

  @Test
  void honoursCurrenciesWithThreeFractionDigits() {
    assertThat(Money.of(new BigDecimal("10.5"), "BHD").amount()).isEqualTo(new BigDecimal("10.500"));
  }

  @Test
  void honoursCurrenciesWithZeroFractionDigits() {
    assertThat(Money.of(new BigDecimal("1050.4"), "JPY").amount()).isEqualTo(new BigDecimal("1050"));
  }

  @ParameterizedTest
  @CsvSource({
    "2.345, 2.34", // HALF_EVEN rounds to the even neighbour
    "2.355, 2.36",
    "2.344, 2.34",
    "2.346, 2.35"
  })
  void roundsHalfEven(String input, String expected) {
    assertThat(Money.of(new BigDecimal(input), "USD").amount())
        .isEqualTo(new BigDecimal(expected));
  }

  @Test
  void ofMinorConvertsFromMinorUnits() {
    assertThat(Money.ofMinor(1050, "USD").amount()).isEqualTo(new BigDecimal("10.50"));
  }

  @Test
  void ofMinorRespectsZeroFractionDigitCurrencies() {
    assertThat(Money.ofMinor(1050, "JPY").amount()).isEqualTo(new BigDecimal("1050"));
  }

  @Test
  void zeroIsZeroAndNotPositive() {
    Money zero = Money.zero("USD");
    assertThat(zero.isZero()).isTrue();
    assertThat(zero.isPositive()).isFalse();
  }

  @Test
  void addsSameCurrency() {
    assertThat(Money.of(new BigDecimal("10.50"), "USD").add(Money.of(new BigDecimal("0.50"), "USD")))
        .isEqualTo(Money.of(new BigDecimal("11.00"), "USD"));
  }

  @Test
  void subtractsSameCurrency() {
    assertThat(
            Money.of(new BigDecimal("10.50"), "USD")
                .subtract(Money.of(new BigDecimal("0.50"), "USD")))
        .isEqualTo(Money.of(new BigDecimal("10.00"), "USD"));
  }

  @Test
  void addingDifferentCurrenciesIsRejectedWithBothCodesNamed() {
    Money usd = Money.of(BigDecimal.ONE, "USD");
    Money eur = Money.of(BigDecimal.ONE, "EUR");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> usd.add(eur))
        .withMessageContaining("USD")
        .withMessageContaining("EUR");
  }

  @Test
  void subtractingDifferentCurrenciesIsRejected() {
    Money usd = Money.of(BigDecimal.ONE, "USD");
    Money gbp = Money.of(BigDecimal.ONE, "GBP");
    assertThatIllegalArgumentException().isThrownBy(() -> usd.subtract(gbp));
  }

  @Test
  void multipliesByAScalar() {
    assertThat(Money.of(new BigDecimal("2.50"), "USD").multiply(4))
        .isEqualTo(Money.of(new BigDecimal("10.00"), "USD"));
  }

  @Test
  void multiplyingByZeroYieldsZero() {
    assertThat(Money.of(new BigDecimal("2.50"), "USD").multiply(0).isZero()).isTrue();
  }

  @Test
  void negativeAmountsAreNotPositive() {
    assertThat(Money.of(new BigDecimal("-1.00"), "USD").isPositive()).isFalse();
  }

  @Test
  void subtractionCanGoNegative() {
    Money result = Money.zero("USD").subtract(Money.of(BigDecimal.ONE, "USD"));
    assertThat(result.amount()).isEqualTo(new BigDecimal("-1.00"));
    assertThat(result.isPositive()).isFalse();
  }

  @Test
  void toStringIsPlainAmountPlusIsoCode() {
    assertThat(Money.of(new BigDecimal("10.50"), "USD")).hasToString("10.50 USD");
  }

  @Test
  void equalityIgnoresIncomingScaleBecauseTheConstructorNormalises() {
    assertThat(Money.of(new BigDecimal("10.5"), "USD"))
        .isEqualTo(Money.of(new BigDecimal("10.50"), "USD"));
  }

  @Test
  void rejectsAnUnknownCurrencyCode() {
    assertThatIllegalArgumentException().isThrownBy(() -> Money.of(BigDecimal.ONE, "ZZZ"));
  }
}
