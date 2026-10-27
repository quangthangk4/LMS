package com.library.auth.infrastructure.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final CustomAccessDeniedHandler accessDeniedHandler;
  private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
  private final CustomAuthenticationConverter customAuthenticationConverter;
  private final CustomJwtDecoder customJwtDecoder;

  public static final String[] PUBLIC_ENDPOINTS = {
      "/api/v1/chatbot/faqs",
      "/api/v1/help/content",
      "/api/v1/publications/newest",
      "/api/v1/publications/search",
      "/api/v1/publications/{id}",
      "/api/v1/publications/{id}/items",
      "/api/v1/publications/{id}/similar",
      "/api/v1/publications/most-borrowed",
      "/api/v1/publications/public-stats",
      "/api/v1/publications/testimonials",
      "/api/v1/system-reviews",
      "/api/v1/system-reviews/top",
      "/api/v1/system-reviews/summary",
      "/api/v1/categories",
      "/api/v1/publications/{id}/ratings",
      "/api/v1/publications/{publicationId}/ratings/summary",
      "/api/v1/circulation-policies",
  };

  public static final String[] AUTHENTICATED_CATALOG_GET_ENDPOINTS = {
      "/api/v1/publications/librarian",
      "/api/v1/publications/book-lookup",
      "/api/v1/publications/{id}/document-upload-url",
      "/api/v1/categories/search",
      "/api/v1/authors",
      "/api/v1/publishers",
      "/api/v1/tags",
  };

  public static final String[] SWAGGER_ENDPOINTS = {
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/webjars/**",
  };

  public static final String[] ACTUATOR_ENDPOINTS = {
      "/actuator/health",
      "/actuator/info",
      "/actuator/prometheus",
  };

  public static final String[] WEBSOCKET_ENDPOINTS = {
      "/ws/**",
  };

  public static final String[] AUTH_ENDPOINTS = {
      "/api/v1/auth/**",
  };

  public static final String[] AI_PUBLIC_ENDPOINTS = {
      "/api/v1/chatbot/messages",
      "/api/v1/ai/semantic-search",
      "/api/ai/callback",
      "/api/v1/fines/payments/payos/webhook",
      "/api/v1/contact-messages",
  };

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
    http.authorizeHttpRequests(auth -> auth
        .requestMatchers(ACTUATOR_ENDPOINTS).permitAll()
        .requestMatchers(SWAGGER_ENDPOINTS).permitAll()
        .requestMatchers(AUTH_ENDPOINTS).permitAll()
        .requestMatchers(WEBSOCKET_ENDPOINTS).permitAll()
        .requestMatchers(HttpMethod.POST, AI_PUBLIC_ENDPOINTS).permitAll()
        .requestMatchers(HttpMethod.DELETE, "/api/v1/chatbot/conversations/*").permitAll()
        .requestMatchers(HttpMethod.GET, AUTHENTICATED_CATALOG_GET_ENDPOINTS).authenticated()
        .requestMatchers(HttpMethod.GET, PUBLIC_ENDPOINTS).permitAll()
        .anyRequest().authenticated()
    );
    http.oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt
            .decoder(customJwtDecoder)
            .jwtAuthenticationConverter(customAuthenticationConverter)
        )
        .authenticationEntryPoint(customAuthenticationEntryPoint)
        .accessDeniedHandler(accessDeniedHandler)
    );

    return http.build();
  }

  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(List.of("*"));
    configuration.setAllowedMethods(List.of("POST", "GET", "PUT", "DELETE", "PATCH", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public WebClient webClient(WebClient.Builder builder) {
    return builder.build();
  }
}
