package us.bringardner.parley.mail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The PLAIN mechanism (RFC 4616) as the mail protocols carry it: Base64 of authzid NUL authcid NUL passwd. */
public final class Sasl {

	private Sasl() {
	}

	/** The Base64 initial response for PLAIN with no authorization identity. */
	public static String encodePlain(String user, String password) {
		return Base64.getEncoder().encodeToString(("\0" + user + "\0" + password).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decode a Base64 client response; "=" is an empty response (RFC 4954, RFC 4422).
	 *
	 * @throws IllegalArgumentException if it is not valid Base64
	 */
	public static byte[] decodeResponse(String base64) {
		return base64.equals("=") ? new byte[0] : Base64.getDecoder().decode(base64);
	}

	/**
	 * Read a PLAIN response.
	 *
	 * @return {user, password}, or null if it is malformed, has no user name, or asks to act as another user
	 */
	public static String[] parsePlain(byte[] response) {
		String[] parts = new String(response, StandardCharsets.UTF_8).split("\u0000", -1);
		if (parts.length != 3 || parts[1].isEmpty() || (!parts[0].isEmpty() && !parts[0].equals(parts[1]))) {
			return null;
		}
		return new String[] { parts[1], parts[2] };
	}
}
