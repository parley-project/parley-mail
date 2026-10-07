package us.bringardner.parley.mail;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A structured MIME header value with parameters, such as the value of a
 * Content-Type or Content-Disposition header:
 * <pre>
 *   text/plain; charset=UTF-8
 *   attachment; filename*=UTF-8''na%C3%AFve.txt
 * </pre>
 * Parameters are read and written according to RFC 2231 (MIME Parameter
 * Value and Encoded Word Extensions):
 * <ul>
 * <li>Character set and language: {@code title*=us-ascii'en-us'This%20is%20%2A%2A%2Afun%2A%2A%2A}</li>
 * <li>Continuations: {@code URL*0="ftp://"; URL*1="cs.utk.edu/pub/moore/bulk-mailer/bulk-mailer.tar"}</li>
 * <li>Both together: {@code title*0*=us-ascii'en'This%20is%20even%20more%20; title*1*=%2A%2A%2Afun%2A%2A%2A%20; title*2="isn't it!"}</li>
 * </ul>
 * Parameter names are case-insensitive. Values are stored decoded; {@link #toString()}
 * encodes them again, using RFC 2231 encoding (UTF-8) when a value is not plain
 * printable ASCII or has a language, and continuations when a value is long.
 * <p>
 * For compatibility with common mail clients, a plain parameter whose value is an
 * RFC 2047 encoded word (e.g. {@code filename="=?UTF-8?B?...?="}) is also decoded.
 */
public class MimeHeaderValue implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Encoded parameter text longer than this is split into continuations. */
	private static final int MAX_SECTION = 60;

	private static final String TSPECIALS = "()<>@,;:\\\"/[]?=";

	private static final Pattern CONTINUATION = Pattern.compile("^(.+?)\\*(\\d+)(\\*?)$");

	/** One decoded parameter. */
	public static class Parameter implements Serializable {
		private static final long serialVersionUID = 1L;

		private final String name;
		private final String value;
		private final String charset;
		private final String language;

		public Parameter(String name, String value, String charset, String language) {
			this.name = name;
			this.value = value;
			this.charset = charset;
			this.language = language;
		}

		/** The parameter name without any RFC 2231 '*' suffix, as it was given. */
		public String getName() {
			return name;
		}

		/** The decoded value. */
		public String getValue() {
			return value;
		}

		/** The character set the value was encoded in when read, or null. */
		public String getCharset() {
			return charset;
		}

		/** The RFC 2231 language tag (e.g. "en-us"), or null. */
		public String getLanguage() {
			return language;
		}

		@Override
		public String toString() {
			return name + "=" + value;
		}
	}

	protected String value;
	// key is the lower-case name; insertion order is kept for output
	protected final LinkedHashMap<String, Parameter> parameters = new LinkedHashMap<>();

	public MimeHeaderValue(String value) {
		this.value = value == null ? "" : value.trim();
	}

	/**
	 * Parse a header value such as {@code text/plain; charset="us-ascii"}.
	 * Parsing is lenient: malformed parameters are skipped rather than rejected.
	 */
	public static MimeHeaderValue parse(String headerValue) {
		if (headerValue == null) {
			return new MimeHeaderValue("");
		}
		Scanner s = new Scanner(headerValue);
		MimeHeaderValue ret = new MimeHeaderValue(stripComments(s.readUntilSemicolon()));

		List<String[]> raw = new ArrayList<>();
		while (s.more()) {
			s.pos++; // the ';'
			s.skipSpaceAndComments();
			if (!s.more()) {
				break;
			}
			String name = s.readName();
			s.skipSpaceAndComments();
			String val = null;
			if (s.more() && s.peek() == '=') {
				s.pos++;
				s.skipSpaceAndComments();
				if (s.more() && s.peek() == '"') {
					val = s.readQuoted();
				} else {
					// drop a trailing comment ("us-ascii (Plain text)"), but keep "report(1).pdf"
					val = s.readUntilSemicolon().replaceAll("\\s+\\([^)]*\\)\\s*$", "").trim();
				}
			}
			s.skipToSemicolon();
			if (!name.isEmpty() && val != null) {
				raw.add(new String[] {name, val});
			}
		}
		ret.decodeParameters(raw);
		return ret;
	}

	private void decodeParameters(List<String[]> raw) {
		// key -> sections (index -> {value, extended?}); index -1 = plain, single-extended uses 0
		Map<String, TreeMap<Integer, String[]>> sections = new LinkedHashMap<>();
		Map<String, String> plain = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();

		for (String[] p : raw) {
			String name = p[0];
			String val = p[1];
			Matcher m = CONTINUATION.matcher(name);
			String base;
			if (m.matches()) {
				base = m.group(1);
				int idx;
				try {
					idx = Integer.parseInt(m.group(2));
				} catch (NumberFormatException e) {
					continue;
				}
				String key = base.toLowerCase(Locale.ROOT);
				sections.computeIfAbsent(key, k -> new TreeMap<>())
						.putIfAbsent(idx, new String[] {val, m.group(3).isEmpty() ? "" : "*"});
			} else if (name.endsWith("*") && name.length() > 1) {
				base = name.substring(0, name.length() - 1);
				String key = base.toLowerCase(Locale.ROOT);
				TreeMap<Integer, String[]> single = new TreeMap<>();
				single.put(0, new String[] {val, "*"});
				sections.put(key, single);
			} else {
				base = name;
				plain.putIfAbsent(base.toLowerCase(Locale.ROOT), val);
			}
			names.putIfAbsent(base.toLowerCase(Locale.ROOT), base);
		}

		for (Map.Entry<String, String> e : names.entrySet()) {
			String key = e.getKey();
			TreeMap<Integer, String[]> secs = sections.get(key);
			Parameter p;
			if (secs != null) {
				p = decodeSections(e.getValue(), secs);
			} else {
				String v = plain.get(key);
				p = new Parameter(e.getValue(), v.contains("=?") ? EncodedWord.decode(v) : v, null, null);
			}
			parameters.put(key, p);
		}
	}

	private static Parameter decodeSections(String name, TreeMap<Integer, String[]> secs) {
		String charsetName = null;
		String language = null;
		Map.Entry<Integer, String[]> first = secs.firstEntry();
		String firstValue = first.getValue()[0];
		boolean firstExtended = !first.getValue()[1].isEmpty();
		if (firstExtended) {
			int q1 = firstValue.indexOf('\'');
			int q2 = q1 < 0 ? -1 : firstValue.indexOf('\'', q1 + 1);
			if (q2 >= 0) {
				charsetName = firstValue.substring(0, q1).trim();
				language = firstValue.substring(q1 + 1, q2).trim();
				firstValue = firstValue.substring(q2 + 1);
				if (charsetName.isEmpty()) {
					charsetName = null;
				}
				if (language.isEmpty()) {
					language = null;
				}
			}
		}
		Charset cs = lookupCharset(charsetName);

		// Decode all sections into one byte stream first, so a multi-byte
		// character split across two sections is reassembled correctly.
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		boolean isFirst = true;
		for (String[] sec : secs.values()) {
			String v = isFirst ? firstValue : sec[0];
			isFirst = false;
			if (sec[1].isEmpty()) {
				byte[] b = v.getBytes(cs);
				bytes.write(b, 0, b.length);
			} else {
				percentDecode(v, bytes);
			}
		}
		return new Parameter(name, new String(bytes.toByteArray(), cs), charsetName, language);
	}

	private static Charset lookupCharset(String name) {
		if (name == null) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(name);
		} catch (RuntimeException e) {
			// unknown charset: keep the bytes rather than fail
			return StandardCharsets.ISO_8859_1;
		}
	}

	private static void percentDecode(String s, ByteArrayOutputStream out) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '%' && i + 2 < s.length() &&hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
				out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
				i += 2;
			} else {
				byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
				out.write(b, 0, b.length);
			}
		}
	}

	static int hex(char c) {
		if (c >= '0' && c <= '9') return c - '0';
		if (c >= 'A' && c <= 'F') return c - 'A' + 10;
		if (c >= 'a' && c <= 'f') return c - 'a' + 10;
		return -1;
	}

	private static String stripComments(String s) {
		StringBuilder out = new StringBuilder();
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quoted) {
				out.append(c);
				if (c == '\\' && i + 1 < s.length()) {
					out.append(s.charAt(++i));
				} else if (c == '"') {
					quoted = false;
				}
			} else if (c == '(') {
				depth++;
			} else if (c == ')' && depth > 0) {
				depth--;
			} else if (depth == 0) {
				if (c == '"') {
					quoted = true;
				}
				out.append(c);
			}
		}
		return out.toString().trim();
	}

	/** Minimal scanner over a header value. */
	private static class Scanner {
		final String s;
		int pos;

		Scanner(String s) {
			this.s = s;
		}

		boolean more() {
			return pos < s.length();
		}

		char peek() {
			return s.charAt(pos);
		}

		void skipSpaceAndComments() {
			while (more()) {
				char c = peek();
				if (Character.isWhitespace(c)) {
					pos++;
				} else if (c == '(') {
					int depth = 0;
					while (more()) {
						char d = s.charAt(pos++);
						if (d == '\\') {
							pos++;
						} else if (d == '(') {
							depth++;
						} else if (d == ')' && --depth == 0) {
							break;
						}
					}
				} else {
					break;
				}
			}
		}

		/** Read up to (not including) the next ';' that is not inside quotes. */
		String readUntilSemicolon() {
			int start = pos;
			boolean quoted = false;
			while (more()) {
				char c = peek();
				if (quoted) {
					if (c == '\\') {
						pos++;
					} else if (c == '"') {
						quoted = false;
					}
				} else if (c == '"') {
					quoted = true;
				} else if (c == ';') {
					break;
				}
				pos++;
			}
			return s.substring(start, Math.min(pos, s.length()));
		}

		String readName() {
			int start = pos;
			while (more() && peek() != '=' && peek() != ';' && !Character.isWhitespace(peek())) {
				pos++;
			}
			return s.substring(start, pos);
		}

		String readQuoted() {
			StringBuilder out = new StringBuilder();
			pos++; // opening quote
			while (more()) {
				char c = s.charAt(pos++);
				if (c == '\\' && more()) {
					out.append(s.charAt(pos++));
				} else if (c == '"') {
					break;
				} else {
					out.append(c);
				}
			}
			return out.toString();
		}

		void skipToSemicolon() {
			readUntilSemicolon();
		}
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value == null ? "" : value.trim();
	}

	/** The decoded value of a parameter, or null if it is not present. Names are case-insensitive. */
	public String getParameter(String name) {
		Parameter p = parameters.get(name.toLowerCase(Locale.ROOT));
		return p == null ? null : p.getValue();
	}

	/** The parameter including its charset and language, or null. */
	public Parameter getParameterInfo(String name) {
		return parameters.get(name.toLowerCase(Locale.ROOT));
	}

	public List<Parameter> getParameters() {
		return Collections.unmodifiableList(new ArrayList<>(parameters.values()));
	}

	public MimeHeaderValue setParameter(String name, String value) {
		return setParameter(name, value, null);
	}

	/**
	 * Set a parameter. A language (e.g. "en") forces RFC 2231 encoding so the
	 * language tag can be written.
	 */
	public MimeHeaderValue setParameter(String name, String value, String language) {
		if (name == null || name.isEmpty() || !isToken(name) || name.indexOf('*') >= 0 || name.indexOf('\'') >= 0 || name.indexOf('%') >= 0) {
			throw new IllegalArgumentException("Invalid parameter name: " + name);
		}
		if (value == null) {
			return removeParameter(name);
		}
		String key = name.toLowerCase(Locale.ROOT);
		Parameter existing = parameters.get(key);
		Parameter p = new Parameter(existing == null ? name : existing.getName(), value, null, language);
		parameters.put(key, p);
		return this;
	}

	public MimeHeaderValue removeParameter(String name) {
		parameters.remove(name.toLowerCase(Locale.ROOT));
		return this;
	}

	/** The header value in ASCII, with parameters encoded per RFC 2231 as needed. */
	@Override
	public String toString() {
		return format(false);
	}

	/**
	 * The header value in RFC 6532 form: non-ASCII parameter values are written as
	 * UTF-8 quoted strings. RFC 2231 encoding is still used for a value with a
	 * language or control characters.
	 */
	public String toUtf8String() {
		return format(true);
	}

	private String format(boolean utf8) {
		StringBuilder sb = new StringBuilder(value);
		for (Parameter p : parameters.values()) {
			for (String section : encodeParameter(p.getName(), p.getValue(), p.getLanguage(), utf8)) {
				sb.append("; ").append(section);
			}
		}
		return sb.toString();
	}

	static List<String> encodeParameter(String name, String value, String language) {
		return encodeParameter(name, value, language, false);
	}

	static List<String> encodeParameter(String name, String value, String language, boolean utf8) {
		List<String> out = new ArrayList<>();
		boolean extended = language != null || (utf8 ? hasControlChars(value) : !isPrintableAscii(value));

		if (!extended) {
			if (isToken(value) && name.length() + 1 + value.length() <= MAX_SECTION + 10) {
				out.add(name + "=" + value);
				return out;
			}
			if (value.length() <= MAX_SECTION) {
				out.add(name + "=" + quote(value));
				return out;
			}
			int n = 0;
			int i = 0;
			while (i < value.length()) {
				int end = Math.min(value.length(), i + MAX_SECTION);
				if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
					end--; // don't split a surrogate pair
				}
				out.add(name + "*" + (n++) + "=" + quote(value.substring(i, end)));
				i = end;
			}
			return out;
		}

		String prefix = "UTF-8'" + (language == null ? "" : language) + "'";
		String encoded = percentEncode(value);
		if (prefix.length() + encoded.length() <= MAX_SECTION) {
			out.add(name + "*=" + prefix + encoded);
			return out;
		}
		int n = 0;
		int i = 0;
		boolean first = true;
		while (i < encoded.length()) {
			int room = first ? Math.max(12, MAX_SECTION - prefix.length()) : MAX_SECTION;
			int end = Math.min(encoded.length(), i + room);
			// never split a %XX escape
			if (end < encoded.length()) {
				if (end - 1 >= i && encoded.charAt(end - 1) == '%') {
					end -= 1;
				} else if (end - 2 >= i && encoded.charAt(end - 2) == '%') {
					end -= 2;
				}
			}
			out.add(name + "*" + (n++) + "*=" + (first ? prefix : "") + encoded.substring(i, end));
			first = false;
			i = end;
		}
		return out;
	}

	private static boolean hasControlChars(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 32 || c == 127) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPrintableAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 32 || c > 126) {
				return false;
			}
		}
		return true;
	}

	static boolean isToken(String s) {
		if (s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c <= 32 || c > 126 || TSPECIALS.indexOf(c) >= 0) {
				return false;
			}
		}
		return true;
	}

	private static String quote(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.append('"').toString();
	}

	/** RFC 2231 attribute-char: token characters except '*', '\'' and '%'. */
	private static boolean isAttributeChar(int b) {
		return b > 32 && b < 127 && TSPECIALS.indexOf(b) < 0 && b != '*' && b != '\'' && b != '%';
	}

	private static String percentEncode(String s) {
		StringBuilder sb = new StringBuilder();
		for (byte bb : s.getBytes(StandardCharsets.UTF_8)) {
			int b = bb & 0xff;
			if (isAttributeChar(b)) {
				sb.append((char) b);
			} else {
				sb.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
			}
		}
		return sb.toString();
	}
}
