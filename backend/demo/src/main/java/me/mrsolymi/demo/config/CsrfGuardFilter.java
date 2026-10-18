package me.mrsolymi.demo.config;

import java.io.IOException;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cheap CSRF defence for a cookie-authenticated JSON API: state-changing requests must carry a
 * custom header. Browsers only send custom headers cross-origin after a CORS preflight, which the
 * CORS config grants to the SPA origin alone. Not a Spring bean, so it is only registered in the
 * security filter chain.
 */
public class CsrfGuardFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Requested-By";
	public static final String VALUE = "spa";

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SAFE_METHODS.contains(request.getMethod()) || VALUE.equals(request.getHeader(HEADER))) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("application/problem+json");
		response.getWriter().write("{\"status\":403,\"detail\":\"Missing " + HEADER + " header\"}");
	}

}
