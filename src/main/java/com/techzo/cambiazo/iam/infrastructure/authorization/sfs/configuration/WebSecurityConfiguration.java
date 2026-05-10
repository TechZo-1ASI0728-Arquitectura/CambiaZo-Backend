package com.techzo.cambiazo.iam.infrastructure.authorization.sfs.configuration;

import com.techzo.cambiazo.iam.infrastructure.authorization.sfs.pipeline.BearerAuthorizationRequestFilter;
import com.techzo.cambiazo.iam.infrastructure.hashing.bcrypt.BCryptHashingService;
import com.techzo.cambiazo.iam.infrastructure.tokens.jwt.BearerTokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

/**
 * Web Security Configuration.
 * <p>
 * This class is responsible for configuring the web security.
 * It enables the method security and configures the security filter chain.
 * It includes the authentication manager, the authentication provider, the password encoder and the authentication entry point.
 * </p>
 */
@Configuration
@EnableMethodSecurity
public class WebSecurityConfiguration {

    private final UserDetailsService userDetailsService;

    private final BearerTokenService tokenService;

    private final BCryptHashingService hashingService;

    private final AuthenticationEntryPoint unauthorizedRequestHandler;

    /**
     * This method creates the Bearer Authorization Request Filter.
     * @return The Bearer Authorization Request Filter
     */
    @Bean
    public BearerAuthorizationRequestFilter authorizationRequestFilter() {
        return new BearerAuthorizationRequestFilter(tokenService, userDetailsService);
    }

    /**
     * This method creates the authentication manager.
     * @param authenticationConfiguration The authentication configuration
     * @return The authentication manager
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * This method creates the authentication provider.
     * @return The authentication provider
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        var authenticationProvider = new DaoAuthenticationProvider();
        authenticationProvider.setUserDetailsService(userDetailsService);
        authenticationProvider.setPasswordEncoder(hashingService);
        return authenticationProvider;
    }

    /**
     * This method creates the password encoder.
     * @return The password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return hashingService;
    }

    /**
     * This method creates the security filter chain.
     * It also configures the http security.
     *
     * @param http The http security
     * @return The security filter chain
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(configurer -> configurer.configurationSource(request  -> {
            var cors = new CorsConfiguration();
            cors.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:4200", "https://cambia-zo.netlify.app", "file://"));
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            cors.setAllowedHeaders(List.of("*"));
            cors.setAllowCredentials(true);
            return cors;
        }));
        http.csrf(csrfConfigurer -> csrfConfigurer.disable())
                .exceptionHandling(exceptionHandling -> exceptionHandling.authenticationEntryPoint(unauthorizedRequestHandler))
                .sessionManagement(customizer -> customizer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorizeRequests -> authorizeRequests

                        .requestMatchers(
                                "/api/v2/authentication/sign-in",
                                "/api/v2/authentication/sign-up",
                                "/api/v2/authentication/**",
                                "/api/v2/donations/**",
                                "/api/v2/countries",
                                "/api/v2/countries/**",
                                "/api/v2/exchanges/ai/**",
                                "/api/v2/departments",
                                "/api/v2/products/**",
                                "/api/v2/products",
                                "/api/v2/product-categories",
                                "/api/v2/product-categories/**",
                                "/api/v2/plans/**",
                                "/api/v2/plans",
                                "/api/v2/departments/**",
                                "/api/v2/districts",
                                "/api/v2/users",
                                "/api/v2/users/**",
                                "/api/v2/districts/**",
                                "/api/v2/chat/**",
                                "/api/v2/files/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/ws/**",
                                "/ws"
                                ).permitAll()
                        .anyRequest().authenticated());
        http.authenticationProvider(authenticationProvider());
        http.addFilterBefore(authorizationRequestFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();

    }

    /**
     * This is the constructor of the class.
     * @param userDetailsService The user details service
     * @param tokenService The token service
     * @param hashingService The hashing service
     * @param authenticationEntryPoint The authentication entry point
     */
    public WebSecurityConfiguration(@Qualifier("defaultUserDetailsService") UserDetailsService userDetailsService, BearerTokenService tokenService, BCryptHashingService hashingService, AuthenticationEntryPoint authenticationEntryPoint) {
        this.userDetailsService = userDetailsService;
        this.tokenService = tokenService;
        this.hashingService = hashingService;
        this.unauthorizedRequestHandler = authenticationEntryPoint;
    }
}
