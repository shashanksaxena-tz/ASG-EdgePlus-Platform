package com.asg.edgeplus.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class CallerContextArgumentResolverTest {

  @SuppressWarnings("unused")
  void handler(CallerContext caller, String other) {}

  private final CallerContextArgumentResolver resolver = new CallerContextArgumentResolver();

  private MethodParameter param(int index) throws NoSuchMethodException {
    Method m = getClass().getDeclaredMethod("handler", CallerContext.class, String.class);
    return new MethodParameter(m, index);
  }

  @Test
  void supportsOnlyCallerContextParameters() throws Exception {
    assertThat(resolver.supportsParameter(param(0))).isTrue();
    assertThat(resolver.supportsParameter(param(1))).isFalse();
  }

  @Test
  void resolvesTheCallerTheFilterPutOnTheRequest() throws Exception {
    CallerContext caller =
        new CallerContext(UUID.fromString("7d0c9b1e-1f55-4b53-9c1a-2a4a9f2f0001"), null, Set.of());
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setAttribute(GatewayIdentityFilter.ATTRIBUTE, caller);

    assertThat(resolver.resolveArgument(param(0), null, new ServletWebRequest(req), null))
        .isSameAs(caller);
  }

  @Test
  void refusesWhenThereIsNoCallerOnTheRequest() {
    assertThatExceptionOfType(MissingGatewayIdentityException.class)
        .isThrownBy(
            () ->
                resolver.resolveArgument(
                    param(0), null, new ServletWebRequest(new MockHttpServletRequest()), null));
  }
}
