package com.influencermatch.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Stateless JWT utility component responsible for token creation, parsing,
 * and validation throughout the InfluencerMatch security pipeline.
 *
 * <p>Tokens are signed using HMAC-SHA256 (HS256) with a configurable Base64-encoded
 * secret key. Token expiry and the signing key are externalised to
 * {@code application.yml} (or environment variables in production).
 *
 * <p>This class is intentionally free of any Spring Security filter or HTTP
 * concerns — it is a pure utility that can be tested in isolation.
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${application.security.jwt.secret-key}")
    private String secretKey;

    @Value("${application.security.jwt.expiration}")
    private long jwtExpiration;

    @Value("${application.security.jwt.refresh-token.expiration}")
    private long refreshExpiration;

    // ─────────────────────────────────────────────────────────────────────────
    //  Token Generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates an access token for the supplied {@link UserDetails} with no
     * additional extra claims.
     *
     * @param userDetails The authenticated principal.
     * @return A signed, compact JWT string.
     */
    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    /**
     * Generates an access token embedding the provided extra claims.
     *
     * @param extraClaims Additional claims to embed in the token payload.
     * @param userDetails The authenticated principal.
     * @return A signed, compact JWT string.
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails, jwtExpiration);
    }

    /**
     * Generates a long-lived refresh token for the supplied principal.
     *
     * @param userDetails The authenticated principal.
     * @return A signed, compact refresh JWT string.
     */
    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails, refreshExpiration);
    }

    private String buildToken(
            Map<String, Object> extraClaims,
            UserDetails userDetails,
            long expiration) {

        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Token Validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the token has not expired and its subject matches
     * the username in {@code userDetails}.
     *
     * @param token       The compact JWT string to validate.
     * @param userDetails The principal to validate against.
     * @return {@code true} if the token is valid for the given principal.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    /**
     * Validates the structural and cryptographic integrity of a JWT string,
     * logging a descriptive warning for each known failure mode.
     *
     * @param token The compact JWT string.
     * @return {@code true} if the token is structurally valid and the signature verifies.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SignatureException ex) {
            log.warn("Invalid JWT signature: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
        } catch (ExpiredJwtException ex) {
            log.warn("JWT token is expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("JWT token is unsupported: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims string is empty: {}", ex.getMessage());
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Claims Extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the {@code sub} (subject / username) claim from a token.
     *
     * @param token The compact JWT string.
     * @return The username embedded as the subject claim.
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts the {@code exp} (expiration) claim from a token.
     *
     * @param token The compact JWT string.
     * @return The expiration {@link Date}.
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Generic claim extractor using a {@link Function} resolver.
     *
     * @param token          The compact JWT string.
     * @param claimsResolver A function that maps the {@link Claims} to the desired value.
     * @param <T>            The claim value type.
     * @return The extracted claim value.
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
