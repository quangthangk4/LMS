package com.library.auth.infrastructure.audit;

import com.library.shared.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class SystemAuditFilter extends OncePerRequestFilter {

  private static final int MAX_QUERY_LENGTH = 500;
  private final AuditLogService auditLogService;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    return "GET".equalsIgnoreCase(method)
        || "HEAD".equalsIgnoreCase(method)
        || "OPTIONS".equalsIgnoreCase(method)
        || path.startsWith("/actuator")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/v3/api-docs")
        || path.equals("/api/v1/auth/login")
        || path.equals("/api/v1/auth/refresh-accesstoken")
        // Chat uses POST for a query body; it performs no business mutation.
        || path.equals("/api/v1/chatbot/messages")
        || path.equals("/api/v1/chatbot/my/messages")
        || path.startsWith("/api/v1/chatbot/conversations/")
        || path.startsWith("/api/v1/chatbot/my/conversations/")
        || path.equals("/api/v1/fines/payments/payos/webhook");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Instant startedAt = Instant.now();
    Throwable failure = null;
    try {
      filterChain.doFilter(request, response);
    } catch (Throwable ex) {
      failure = ex;
      throw ex;
    } finally {
      writeAuditLog(request, response, startedAt, failure);
    }
  }

  private void writeAuditLog(HttpServletRequest request, HttpServletResponse response,
      Instant startedAt, Throwable failure) {
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      Long actorId = resolveActorId(authentication);
      String actorRole = resolveActorRole(authentication);
      String method = request.getMethod().toUpperCase();
      String path = request.getRequestURI();
      int status = response.getStatus();
      String action = deriveAction(method, path);
      String entityType = deriveEntityType(path);
      String entityId = deriveEntityId(path);

      Map<String, Object> details = new LinkedHashMap<>();
      details.put("method", method);
      details.put("path", path);
      details.put("query", trimQuery(request.getQueryString()));
      details.put("status", status);
      details.put("success", failure == null && status < 400);
      details.put("durationMs", Duration.between(startedAt, Instant.now()).toMillis());
      if (failure != null) {
        details.put("error", failure.getClass().getSimpleName());
      }

      auditLogService.log(
          actorId,
          actorRole,
          action,
          entityType,
          entityId,
          buildSummary(actorRole, method, path, status),
          details
      );
    } catch (Exception e) {
      log.warn("Failed to write system audit log for {} {}", request.getMethod(), request.getRequestURI(), e);
    }
  }

  private Long resolveActorId(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    try {
      return Long.valueOf(authentication.getName());
    } catch (Exception e) {
      return null;
    }
  }

  private String resolveActorRole(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return "ANONYMOUS";
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority.startsWith("ROLE_"))
        .map(authority -> authority.substring("ROLE_".length()))
        .findFirst()
        .orElse("AUTHENTICATED");
  }

  private String deriveAction(String method, String path) {
    String suffix = path.replaceFirst("^/api/v1/", "")
        .replaceAll("[^A-Za-z0-9]+", "_")
        .replaceAll("^_+|_+$", "")
        .toUpperCase();
    return method + "_" + (suffix.isBlank() ? "API" : suffix);
  }

  private String deriveEntityType(String path) {
    String normalized = path.replaceFirst("^/api/v1/", "");
    String firstSegment = normalized.split("/")[0];
    return firstSegment.isBlank() ? "api" : firstSegment;
  }

  private String deriveEntityId(String path) {
    String[] segments = path.split("/");
    for (int i = segments.length - 1; i >= 0; i--) {
      if (segments[i].matches("\\d+")) {
        return segments[i];
      }
    }
    return null;
  }

  private String buildSummary(String actorRole, String method, String path, int status) {
    return "%s %s %s -> %d".formatted(actorRole, method, path, status);
  }

  private String trimQuery(String query) {
    if (query == null || query.length() <= MAX_QUERY_LENGTH) {
      return query;
    }
    return query.substring(0, MAX_QUERY_LENGTH) + "...";
  }
}
