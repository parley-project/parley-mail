package us.bringardner.parley.mail;

import java.text.Normalizer;

/**
 * SASLprep (RFC 4013), the stringprep profile for user names and passwords,
 * which RFC 6856 (POP3) and RFC 9051 (IMAP, via SASL PLAIN) apply to UTF-8 logins.
 * <p>
 * Steps: map non-ASCII spaces to SPACE and remove characters "commonly mapped to
 * nothing" (RFC 3454 B.1), normalize with NFKC, reject prohibited characters
 * (RFC 3454 C.1.2-C.9) and check the bidirectional rules (RFC 3454 section 6).
 * A stored string also may not contain unassigned code points. Unassigned code
 * points are judged by this Java's Unicode version, not Unicode 3.2.
 */
public final class SaslPrep {

	private SaslPrep() {
	}

	/**
	 * Prepare a string.
	 *
	 * @param stored true for a stored string (e.g. a password from the user
	 *               database), false for a query string (what a client sent)
	 * @return the prepared string, or null if the string is prohibited
	 */
	public static String prepare(String s, boolean stored) {
		if (s == null) {
			return null;
		}
		StringBuilder mapped = new StringBuilder(s.length());
		for (int i = 0; i < s.length();) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (isNonAsciiSpace(cp)) {
				mapped.append(' ');
			} else if (!isMappedToNothing(cp)) {
				mapped.appendCodePoint(cp);
			}
		}
		String n = Normalizer.normalize(mapped, Normalizer.Form.NFKC);

		boolean randAL = false;
		boolean l = false;
		for (int i = 0; i < n.length();) {
			int cp = n.codePointAt(i);
			i += Character.charCount(cp);
			if (isProhibited(cp) || (stored && !Character.isDefined(cp))) {
				return null;
			}
			byte dir = Character.getDirectionality(cp);
			if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
				randAL = true;
			} else if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
				l = true;
			}
		}
		if (randAL) {
			// RFC 3454 section 6: no LCat characters, and RandALCat first and last
			if (l || !isRandAL(n.codePointAt(0)) || !isRandAL(n.codePointBefore(n.length()))) {
				return null;
			}
		}
		return n;
	}

	private static boolean isRandAL(int cp) {
		byte dir = Character.getDirectionality(cp);
		return dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
	}

	/** RFC 3454 C.1.2 */
	private static boolean isNonAsciiSpace(int cp) {
		return cp == 0x00A0 || cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200B) || cp == 0x202F || cp == 0x205F
				|| cp == 0x3000;
	}

	/** RFC 3454 B.1 */
	private static boolean isMappedToNothing(int cp) {
		return cp == 0x00AD || cp == 0x034F || cp == 0x1806 || (cp >= 0x180B && cp <= 0x180D)
				|| (cp >= 0x200B && cp <= 0x200D) || cp == 0x2060 || (cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0xFEFF;
	}

	/** RFC 3454 C.1.2, C.2.1, C.2.2, C.3, C.4, C.5, C.6, C.7, C.8, C.9 */
	private static boolean isProhibited(int cp) {
		return isNonAsciiSpace(cp)
				// C.2.1 ASCII control characters
				|| cp <= 0x1F || cp == 0x7F
				// C.2.2 non-ASCII control characters
				|| (cp >= 0x80 && cp <= 0x9F) || cp == 0x06DD || cp == 0x070F || cp == 0x180E || cp == 0x200C
				|| cp == 0x200D || cp == 0x2028 || cp == 0x2029 || (cp >= 0x2060 && cp <= 0x2063)
				|| (cp >= 0x206A && cp <= 0x206F) || cp == 0xFEFF || (cp >= 0xFFF9 && cp <= 0xFFFC)
				|| (cp >= 0x1D173 && cp <= 0x1D17A)
				// C.3 private use
				|| (cp >= 0xE000 && cp <= 0xF8FF) || (cp >= 0xF0000 && cp <= 0xFFFFD) || (cp >= 0x100000 && cp <= 0x10FFFD)
				// C.4 non-character code points
				|| (cp >= 0xFDD0 && cp <= 0xFDEF) || (cp & 0xFFFE) == 0xFFFE
				// C.5 surrogate codes
				|| (cp >= 0xD800 && cp <= 0xDFFF)
				// C.6 inappropriate for plain text (includes U+FFFD, i.e. invalid UTF-8)
				|| (cp >= 0xFFF9 && cp <= 0xFFFD)
				// C.7 inappropriate for canonical representation
				|| (cp >= 0x2FF0 && cp <= 0x2FFB)
				// C.8 change display properties or deprecated
				|| cp == 0x0340 || cp == 0x0341 || cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E)
				// C.9 tagging characters
				|| cp == 0xE0001 || (cp >= 0xE0020 && cp <= 0xE007F);
	}
}
