package com.asg.edgeplus.platform.identity;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare {@code CallerContext caller} as a parameter instead of reaching for a
 * holder or re-reading headers. That keeps the dependency visible in the method signature and makes
 * the controller testable by simply passing one in.
 */
public class CallerContextArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return CallerContext.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {

    Object ctx =
        webRequest.getAttribute(GatewayIdentityFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (ctx instanceof CallerContext caller) {
      return caller;
    }
    // The filter binds on every authenticated path, so absence here means the endpoint is
    // permit-all (or the filter is disabled) and the handler should not have asked for identity.
    throw MissingGatewayIdentityException.missingHeader(GatewayIdentityHeaders.USER_ID);
  }
}
