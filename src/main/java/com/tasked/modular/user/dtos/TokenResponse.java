package com.tasked.modular.user.dtos;

/**
 * What login and rotation both return.
 *
 * <p>The refresh token here is the <em>raw</em> JWT. The server keeps only
 * {@code BCrypt(SHA256(token))}, so this is the one and only moment the plaintext exists
 * outside the client: a database dump cannot be replayed against the refresh endpoint.
 *
 * <p>Both tokens land in the JSON body, which means a browser client has to store them
 * somewhere JavaScript can reach - and therefore somewhere XSS can reach. The stronger
 * arrangement is to keep the access token in memory only and deliver the refresh token as an
 * {@code HttpOnly; Secure; SameSite=Strict} cookie; that is a client-contract change, noted
 * here so the trade-off is visible at the point where it is made.
 */
public record TokenResponse(String accessToken, String refreshToken) {
}
