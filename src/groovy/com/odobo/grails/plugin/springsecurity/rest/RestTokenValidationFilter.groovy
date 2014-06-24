package com.odobo.grails.plugin.springsecurity.rest

import com.google.common.io.CharStreams
import com.odobo.grails.plugin.springsecurity.rest.token.storage.TokenNotFoundException
import grails.plugin.springsecurity.authentication.GrailsAnonymousAuthenticationToken
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.filter.GenericFilterBean

import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * This filter starts the token validation flow. It extracts the token from the configured header name, and pass it to
 * the {@link RestAuthenticationProvider}.
 *
 * This filter, when applied, is incompatible with traditional browser-based Spring Security Core redirects. Users have
 * to make sure it's applied only to REST endpoint URL's.
 *
 * If the authentication is successful, the result is stored in the security context and the response is generated by the
 * {@link AuthenticationSuccessHandler}. Otherwise, an {@link AuthenticationFailureHandler} is called.
 */
@Slf4j
class RestTokenValidationFilter extends AbstractRestFilter {

    String headerName

    RestAuthenticationProvider restAuthenticationProvider

    AuthenticationSuccessHandler authenticationSuccessHandler
    AuthenticationFailureHandler authenticationFailureHandler

    String validationEndpointUrl
    Boolean active
    Boolean useBearerToken

    Boolean enableAnonymousAccess

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest servletRequest = request as HttpServletRequest
        HttpServletResponse servletResponse = response as HttpServletResponse

        String tokenValue

        if (useBearerToken) {
            tokenValue = findBearerToken(servletRequest, servletResponse)
        } else {
            log.debug "Looking for a token value in the header '${headerName}'"
            tokenValue = servletRequest.getHeader(headerName)
        }

        try {
            if (tokenValue) {
                log.debug "Token found: ${tokenValue}"

                log.debug "Trying to authenticate the token"
                RestAuthenticationToken authenticationRequest = new RestAuthenticationToken(tokenValue)
                RestAuthenticationToken authenticationResult = restAuthenticationProvider.authenticate(authenticationRequest) as RestAuthenticationToken

                if (authenticationResult.authenticated) {
                    log.debug "Token authenticated. Storing the authentication result in the security context"
                    log.debug "Authentication result: ${authenticationResult}"
                    SecurityContextHolder.context.setAuthentication(authenticationResult)

                    processFilterChain(request, response, chain, tokenValue, authenticationResult)

                }

            } else {
                log.debug "Token not found"
                processFilterChain(request, response, chain, tokenValue, null)
            }
        } catch (AuthenticationException ae) {
            log.debug "Authentication failed: ${ae.message}"
            authenticationFailureHandler.onAuthenticationFailure(servletRequest, servletResponse, ae)
        }

    }

    private processFilterChain(ServletRequest request, ServletResponse response, FilterChain chain, String tokenValue, RestAuthenticationToken authenticationResult) {
        HttpServletRequest servletRequest = request as HttpServletRequest
        HttpServletResponse servletResponse = response as HttpServletResponse

        def actualUri = servletRequest.requestURI - servletRequest.contextPath

        if (active) {
            if (!tokenValue) {
                if (enableAnonymousAccess) {
                    log.debug "Anonymous access is enabled"
                    Authentication authentication = SecurityContextHolder.context.authentication
                    if (authentication && authentication instanceof GrailsAnonymousAuthenticationToken) {
                        log.debug "Request is already authenticated as anonymous request. Continuing the filter chain"
                        chain.doFilter(request, response)
                    } else {
                        log.debug "However, request is not authenticated as anonymous"
                        throw new AuthenticationCredentialsNotFoundException("Token is missing")
                    }
                } else {
                    throw new AuthenticationCredentialsNotFoundException("Token is missing")
                }
            } else {
                if (actualUri == validationEndpointUrl) {
                    log.debug "Validation endpoint called. Generating response."
                    authenticationSuccessHandler.onAuthenticationSuccess(servletRequest, servletResponse, authenticationResult)
                } else {
                    log.debug "Continuing the filter chain"
                    chain.doFilter(request, response)
                }
            }
        } else {
            log.debug "Token validation is disabled. Continuing the filter chain"
            chain.doFilter(request, response)
        }

    }

    /**
     * Returns the specified queryString as a map.
     * @param queryString
     * @return
     */
    private Map<String, String> getQueryAsMap(String queryString) {
        queryString?.split('&').inject([:]) { map, token ->
            token?.split('=').with { map[it[0]] = it[1] }
            map
        }
    }
}
