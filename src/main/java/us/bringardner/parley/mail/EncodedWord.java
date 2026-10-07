package us.bringardner.parley.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 2047 encoded words ({@code =?charset?B|Q?text?=}) in header text such as
 * Subject or a display name, including the RFC 2231 section 5 language
 * extension ({@code =?charset*language?B|Q?text?=}).
 */
public final class EncodedWord {

	private static final Pattern WORD =
			Pattern.compile("=\\?([^?*\\s]+)(?:\\*([^?\\s]*))?\\?([BbQq])\\?([^?\\s]*)\\?=");

	/** RFC 2047: an encoded word may be at most 75 characters long. */
	private static final int MAX_WORD = 75;

	private EncodedWord() {
	}

	/**
	 * Decode every encoded word in the text. Whitespace between two adjacent
	 * encoded words is dropped, as RFC 2047 requires. Words with an unknown
	 * charset or invalid data are left as they are.
	 */
	public static String decode(String text) {
		if (text == null || text.indexOf("=?") < 0) {
			return text;
		}
		StringBuilder out = new StringBuilder();
		Matcher m = WORD.matcher(text);
		int last = 0;
		boolean prevWasWord = false;
		while (m.find()) {
			String between = text.substring(last, m.start());
			String decoded = decodeWord(m.group(1), m.group(3), m.group(4));
			if (decoded == null) {
				out.append(between).append(m.group());
				prevWasWord = false;
			} else {
				if (!(prevWasWord && between.trim().isEmpty())) {
					out.append(between);
				}
				out.append(decoded);
				prevWasWord = true;
			}
			last = m.end();
		}
		out.append(text.substring(last));
		return out.toString();
	}

	/** The language of the first encoded word that has one (RFC 2231 section 5), or null. */
	public static String getLanguage(String text) {
		if (text == null) {
			return null;
		}
		Matcher m = WORD.matcher(text);
		while (m.find()) {
			if (m.group(2) != null && !m.group(2).isEmpty()) {
				return m.group(2);
			}
		}
		return null;
	}

	private static String decodeWord(String charsetName, String encoding, String text) {
		Charset cs;
		try {
			cs = Charset.forName(charsetName);
		} catch (RuntimeException e) {
			return null;
		}
		byte[] bytes;
		if (encoding.equalsIgnoreCase("B")) {
			try {
				bytes = Base64.getMimeDecoder().decode(text);
			} catch (IllegalArgumentException e) {
				return null;
			}
		} else {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '_') {
					out.write(' ');
				} else if (c == '=' && i + 2 < text.length()
						&& MimeHeaderValue.hex(text.charAt(i + 1)) >= 0 && MimeHeaderValue.hex(text.charAt(i + 2)) >= 0) {
					out.write(MimeHeaderValue.hex(text.charAt(i + 1)) * 16 + MimeHeaderValue.hex(text.charAt(i + 2)));
					i += 2;
				} else {
					out.write(c);
				}
			}
			bytes = out.toByteArray();
		}
		return new String(bytes, cs);
	}

	/** True if the text has to be encoded to be sent in a header. */
	public static boolean needsEncoding(String text) {
		if (text == null) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if ((c < 32 && c != '\t') || c > 126) {
				return true;
			}
		}
		return text.contains("=?");
	}

	public static String encode(String text) {
		return encode(text, null);
	}

	/**
	 * Encode text as one or more UTF-8 "B" encoded words if it needs encoding,
	 * otherwise return it unchanged. A language (e.g. "en") is written using the
	 * RFC 2231 section 5 extension. Words are separated by a space, which is where
	 * a long header is folded.
	 */
	public static String encode(String text, String language) {
		return encode(text, language, 0);
	}

	/**
	 * As {@link #encode(String, String)}, with the first word made short enough
	 * that it still fits on a 78-character header line after {@code firstLineUsed}
	 * characters (e.g. the length of "Subject: ").
	 */
	public static String encode(String text, String language, int firstLineUsed) {
		return encode(text, language, firstLineUsed, false);
	}

	/**
	 * As {@link #encode(String, String, int)}; with {@code force} the text is encoded
	 * even if it doesn't need to be (used to break up an over-long header line).
	 */
	public static String encode(String text, String language, int firstLineUsed, boolean force) {
		if (text == null || (!force && language == null && !needsEncoding(text))) {
			return text;
		}
		String prefix = "=?UTF-8" + (language == null || language.isEmpty() ? "" : "*" + language) + "?B?";
		int fullBytes = ((MAX_WORD - prefix.length() - 2) / 4) * 3;
		int firstBytes = Math.max(3, Math.min(fullBytes, ((78 - firstLineUsed - prefix.length() - 2) / 4) * 3));
		int maxBytes = firstBytes;

		List<String> words = new ArrayList<>();
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();
		int i = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
			if (chunk.size() > 0 && chunk.size() + b.length > maxBytes) {
				words.add(prefix + Base64.getEncoder().encodeToString(chunk.toByteArray()) + "?=");
				chunk.reset();
				maxBytes = fullBytes;
			}
			chunk.write(b, 0, b.length);
			i += Character.charCount(cp);
		}
		if (chunk.size() > 0 || words.isEmpty()) {
			words.add(prefix + Base64.getEncoder().encodeToString(chunk.toByteArray()) + "?=");
		}
		return String.join(" ", words);
	}
}
