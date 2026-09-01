package com.asg.edgeplus.platform.domain.primitives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** Result — explicit success/failure where an exception would be control flow. */
class ResultTest {

  @Test
  void successCarriesItsValue() {
    Result<String, String> r = Result.success("ok");
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.isFailure()).isFalse();
  }

  @Test
  void failureCarriesItsError() {
    Result<String, String> r = Result.failure("boom");
    assertThat(r.isFailure()).isTrue();
    assertThat(r.isSuccess()).isFalse();
  }

  @Test
  void successRejectsANullValue() {
    assertThatNullPointerException().isThrownBy(() -> Result.success(null));
  }

  @Test
  void failureRejectsANullError() {
    assertThatNullPointerException().isThrownBy(() -> Result.failure(null));
  }

  @Test
  void mapTransformsASuccess() {
    Result<Integer, String> r = Result.<String, String>success("abc").map(String::length);
    assertThat(r).isEqualTo(Result.<Integer, String>success(3));
  }

  @Test
  void mapLeavesAFailureUntouched() {
    Result<Integer, String> r = Result.<String, String>failure("boom").map(String::length);
    assertThat(r.isFailure()).isTrue();
    assertThat(r).isEqualTo(Result.<Integer, String>failure("boom"));
  }

  @Test
  void flatMapComposesTwoSuccesses() {
    Result<Integer, String> r =
        Result.<String, String>success("abc").flatMap(s -> Result.success(s.length()));
    assertThat(r).isEqualTo(Result.<Integer, String>success(3));
  }

  @Test
  void flatMapPropagatesTheInnerFailure() {
    Result<Integer, String> r =
        Result.<String, String>success("abc").flatMap(s -> Result.failure("inner"));
    assertThat(r).isEqualTo(Result.<Integer, String>failure("inner"));
  }

  @Test
  void flatMapShortCircuitsOnAnOuterFailure() {
    Result<Integer, String> r =
        Result.<String, String>failure("outer")
            .flatMap(
                s -> {
                  throw new AssertionError("mapper must not run on a failure");
                });
    assertThat(r).isEqualTo(Result.<Integer, String>failure("outer"));
  }

  @Test
  void orElseThrowReturnsTheSuccessValue() {
    assertThat(Result.<String, String>success("ok").orElseThrow(IllegalStateException::new))
        .isEqualTo("ok");
  }

  @Test
  void orElseThrowThrowsTheSuppliedExceptionOnFailure() {
    Result<String, String> r = Result.failure("boom");
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> r.orElseThrow(IllegalStateException::new));
  }
}
