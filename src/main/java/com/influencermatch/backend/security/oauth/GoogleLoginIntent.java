package com.influencermatch.backend.security.oauth;

import com.influencermatch.backend.user.enums.Role;
import jakarta.servlet.http.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Login intent is kept inside the state-bound server authorization request, never trusted from callback parameters. */
public final class GoogleLoginIntent {
  public static final String ATTRIBUTE = GoogleLoginIntent.class.getName() + ".role";
  private GoogleLoginIntent() {}

  public static class Resolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    public Resolver(ClientRegistrationRepository clients) { delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization"); }
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) { return withIntent(delegate.resolve(request), request); }
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) { return withIntent(delegate.resolve(request, registrationId), request); }
    private OAuth2AuthorizationRequest withIntent(OAuth2AuthorizationRequest original, HttpServletRequest request) {
      if (original == null) return null;
      String intent = request.getParameter("login_intent");
      if (intent != null && !intent.equals("BRAND") && !intent.equals("CREATOR"))
        throw new OAuth2AuthorizationException(new OAuth2Error("invalid_request", "Unsupported login intent", null));
      Role role = "CREATOR".equals(intent) ? Role.CREATOR : Role.BRAND;
      return OAuth2AuthorizationRequest.from(original).attributes(a -> a.put(ATTRIBUTE, role.name())).build();
    }
  }

  public static class Repository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) { return delegate.loadAuthorizationRequest(request); }
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request, HttpServletResponse response) { delegate.saveAuthorizationRequest(authorization, request, response); }
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
      OAuth2AuthorizationRequest authorization = delegate.removeAuthorizationRequest(request, response);
      if (authorization != null) {
        Object role = authorization.getAttribute(ATTRIBUTE);
        request.setAttribute(ATTRIBUTE, role == null ? Role.BRAND.name() : role);
      }
      return authorization;
    }
  }
}
