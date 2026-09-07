package io.mszymanski.orknux.shell

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * The door. One static bearer token, configured at deploy, checked on every
 * request — the same shape as Orknux's own `orkx_...` tokens, which is what
 * the contract asks for.
 *
 * A filter rather than Spring Security because there is exactly one rule and
 * no second kind of caller: no sessions, no roles, no login page. Anything
 * without the token is answered 401 before it reaches the endpoint.
 */
@Component
class BearerAuth(private val properties: ShellProperties) : OncePerRequestFilter() {

    init {
        // Refusing to start beats starting open. There is no development
        // default on purpose: a default token is a published one.
        check(properties.token.isNotBlank()) {
            "orknux-shell refuses to start without a token. Set ORKNUX_SHELL_TOKEN " +
                "and give the same value to whatever calls this server."
        }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // The health probes answer without the token, because a Kubernetes
        // probe cannot fetch a secret to send and "up or not" gives nothing
        // away. Metrics and everything else stay behind it.
        if (request.requestURI.startsWith("/actuator/health")) {
            chain.doFilter(request, response)
            return
        }

        val presented = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()

        // Compared in constant time: a comparison that stops at the first wrong
        // byte tells a patient caller how much of a guess was right.
        if (presented.isNotEmpty() &&
            MessageDigest.isEqual(presented.toByteArray(), properties.token.toByteArray())
        ) {
            chain.doFilter(request, response)
            return
        }

        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.setHeader("WWW-Authenticate", "Bearer")
        response.contentType = "text/plain"
        response.writer.write("This server takes a bearer token, and this request carried none it recognises.")
    }
}
