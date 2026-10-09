package us.bringardner.parley.mail;

import us.bringardner.parley.io.IoUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * An Internet message (RFC 5322) with MIME structure (RFC 2045, 2046), whose
 * header parameters and encoded words are read and written according to
 * RFC 2231 and RFC 2047, with internationalized headers per RFC 6532.
 * <p>
 * A Message holds an ordered list of headers and either a body or, for a
 * multipart message, a list of parts. Each part is itself a Message (a MIME
 * "entity"), so parts can be nested.
 *
 * <h2>Storage</h2>
 * Messages of any size are supported. Only headers are held in memory: bodies,
 * parts, preambles and epilogues are windows (offset, length) onto the
 * {@link FileSource} the message was parsed from, and are only ever read through
 * streams.
 * <ul>
 * <li>{@link #parse(FileSource)} reads a message stored in a file, in place.
 *     Parsing streams through the file once per level of multipart nesting.</li>
 * <li>{@link #read(InputStream, FileSource)} stores a stream (e.g. an incoming SMTP
 *     DATA section) in a file and parses it; {@link #read(InputStream)} uses a temp
 *     file in the work directory.</li>
 * <li>{@link #writeTo(OutputStream)} and {@link #writeTo(FileSource)} stream the
 *     message out; writing back to the file it was parsed from is allowed.</li>
 * <li>{@link #openContent()}, {@link #writeContentTo(OutputStream)} and
 *     {@link #saveContent(FileSource)} decode a body as a stream;
 *     {@link #setContent(FileSource, String)} and
 *     {@link #addAttachment(String, String, FileSource)} encode large content as a
 *     stream into a temp file in the {@link #getWorkDirectory() work directory}.</li>
 * <li>{@link #close()} deletes the temp files the message created.</li>
 * </ul>
 * The byte-array methods ({@link #parse(byte[])}, {@link #toByteArray()},
 * {@link #getContent()}, {@link #getText()}, ...) remain for small messages and
 * small parts; they hold the data in memory.
 * <p>
 * The file a message was parsed from must not be changed by anything else while
 * the message is in use.
 *
 * <h2>Reading and writing</h2>
 * Headers are unfolded on reading, CRLF or bare LF line endings are accepted, and
 * multipart bodies are split into parts. Output always uses CRLF and folds headers
 * at 78 octets where possible. A message read with CRLF line endings and not
 * changed is written back byte for byte.
 *
 * <h2>Internationalized headers (RFC 6532)</h2>
 * Header values are kept as Unicode. Raw UTF-8 headers are read as such, and how
 * headers are written depends on {@link #setUtf8Headers(boolean)}:
 * <ul>
 * <li>false (the default for new messages): non-ASCII text is written in the
 *     ASCII-only forms every server accepts: RFC 2047 encoded words in Subject and
 *     other unstructured headers and in display names, RFC 2231 in MIME parameters.
 *     Only a non-ASCII mailbox (e.g. {@code josé@exämple.com}) stays UTF-8, because
 *     it has no ASCII form.</li>
 * <li>true: headers are written as raw UTF-8 (RFC 6532). Use this only when the
 *     receiving server supports SMTPUTF8. A message parsed from raw UTF-8 headers
 *     starts in this mode.</li>
 * </ul>
 * In both modes header values are normalized to NFC, lines are folded and limited
 * in octets (78 recommended, 998 maximum), and {@link #needsSmtpUtf8()} says whether
 * the result contains raw UTF-8 headers. Unchanged headers of a parsed message are
 * always written exactly as they were read.
 * <p>
 * Attached messages (message/rfc822 and message/global parts) are parsed on demand
 * by {@link #getAttachedMessage()}; {@link #attachMessage(Message)} adds one.
 */
public class Message implements Serializable, AutoCloseable {

	private static final long serialVersionUID = 2L;

	public static final String CRLF = "\r\n";
	private static final byte[] CRLF_BYTES = {'\r', '\n'};

	/** Fold header lines longer than this (RFC 5322 section 2.1.1). */
	private static final int FOLD_AT = 78;
	/** Lines in a 7bit or 8bit body must not be longer than this (without CRLF). */
	private static final int MAX_LINE = 998;
	/** A "header" line longer than this is taken as the start of the body. */
	private static final int MAX_HEADER_LINE = 128 * 1024;
	/** Header blocks larger than this end early; the rest is the body. */
	private static final long MAX_HEADER_BYTES = 16L * 1024 * 1024;
	/** Deeper nesting than this is kept as an unparsed body (protects against stack overflow). */
	private static final int MAX_DEPTH = 50;

	private static final Set<String> ADDRESS_HEADERS = Set.of("from", "to", "cc", "bcc", "reply-to", "sender",
			"resent-from", "resent-to", "resent-cc", "resent-bcc", "resent-sender");
	private static final Set<String> UNSTRUCTURED_HEADERS = Set.of("subject", "comments", "content-description",
			"thread-topic");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static volatile FileSource defaultWorkDirectory;

	protected final ArrayList<Header> headers = new ArrayList<>();
	/** The body as transferred (still transfer-encoded); null when the message has parts. */
	protected Body body = Body.EMPTY;
	protected final ArrayList<Message> parts = new ArrayList<>();
	/** Multipart text before the first boundary; null if there was none. */
	protected Body preamble;
	/** Multipart text after the closing boundary line; null if the closing line ended the data. */
	protected Body epilogue = Body.EMPTY;
	/** True for a body part (it doesn't get a MIME-Version header). */
	protected boolean part;
	/** Write headers as raw UTF-8 (RFC 6532) instead of RFC 2047/2231 encodings. */
	protected boolean utf8Headers;
	/** The parsed content of a message/rfc822 or message/global part, once requested. */
	protected Message attached;
	protected boolean attachedLoaded;
	/** Nesting depth (0 = top level). */
	protected int depth;
	/** For a parsed message or part: the stored bytes of its headers and body. */
	protected Body source;
	/** For a parsed message or part: the length of its header block in {@link #source}. */
	protected long headerLength = -1;
	/** Where temp files for this message go; null = the default. */
	protected FileSource workDirectory;
	/** Temp files this message created; deleted by close(). */
	private transient List<FileSource> ownedFiles = new ArrayList<>();

	public Message() {
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Parse a message stored in a file. The message reads its bodies from the
	 * file as needed, so the file must stay unchanged while the message is used.
	 */
	public static Message parse(FileSource file) throws IOException {
		Message m = new Message();
		m.load(Body.of(file), true, 0);
		return m;
	}

	/**
	 * Store a stream (to its end) in {@code store} and parse it from there.
	 * The stream is not closed.
	 */
	public static Message read(InputStream in, FileSource store) throws IOException {
		try (OutputStream out = output(store)) {
			Streams.copy(in, out);
		}
		return parse(store);
	}

	/**
	 * Store a stream (to its end) in a temp file in the default work directory and
	 * parse it from there. {@link #close()} deletes the temp file. The stream is not closed.
	 */
	public static Message read(InputStream in) throws IOException {
		Message m = new Message();
		FileSource f = m.createTempFile();
		try (OutputStream out = output(f)) {
			Streams.copy(in, out);
		}
		m.load(Body.of(f), true, 0);
		return m;
	}

	/** Parse a small message held in memory. */
	public static Message parse(byte[] data) {
		Message m = new Message();
		try {
			m.load(Body.of(data), true, 0);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // can't happen in memory
		}
		return m;
	}

	private void load(Body entity, boolean topLevel, int depth) throws IOException {
		this.depth = depth;
		headers.clear();
		parts.clear();
		attached = null;
		attachedLoaded = false;

		long length = entity.length();
		long bodyStart = length;
		StringBuilder current = null;
		StringBuilder raw = null;
		boolean firstLine = true;
		long headerBytes = 0;
		int prefix = (int) Math.min(MAX_HEADER_LINE, length + 1);
		try (LineScanner sc = new LineScanner(entity, 0, prefix)) {
			while (sc.next()) {
				if (sc.isBlank()) { // blank line: end of headers
					bodyStart = sc.lineEnd;
					break;
				}
				headerBytes += sc.lineEnd - sc.lineStart;
				if (sc.truncated || headerBytes > MAX_HEADER_BYTES) {
					bodyStart = sc.lineStart;
					break;
				}
				String line = decodeHeaderLine(sc.prefix, 0, sc.prefixLen);
				if (!utf8Headers && isUtf8(sc.prefix, 0, sc.prefixLen)) {
					utf8Headers = true; // keep the message's RFC 6532 style when it is written
				}
				if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && current != null) {
					current.append(line); // unfold: the line break is removed, the whitespace kept
					raw.append(CRLF).append(line);
				} else if (line.indexOf(':') > 0 && isFieldName(line.substring(0, line.indexOf(':')).trim())) {
					addParsedHeader(current, raw);
					current = new StringBuilder(line);
					raw = new StringBuilder(line);
				} else if (firstLine && topLevel && line.startsWith("From ")) {
					// mbox separator line: not part of the message
				} else {
					// not a header: the headers are missing their blank line, the body starts here
					bodyStart = sc.lineStart;
					break;
				}
				firstLine = false;
				bodyStart = length;
			}
		}
		addParsedHeader(current, raw);

		body = entity.slice(bodyStart, length - bodyStart);
		source = entity;
		headerLength = bodyStart;
		preamble = null;
		epilogue = Body.EMPTY;
		if (depth >= MAX_DEPTH) {
			return;
		}
		if (isMultipart()) {
			String boundary = getContentType().getParameter("boundary");
			if (boundary != null && !boundary.isEmpty()) {
				splitMultipart(boundary, depth);
			}
		}
	}

	private void addParsedHeader(StringBuilder line, StringBuilder raw) {
		if (line != null) {
			Header h = Header.parseHeader(line.toString());
			headers.add(new ParsedHeader(h.getName(), h.getValue(), raw.toString()));
		}
	}

	/**
	 * A header as it was read, with its original (folded) text. While its name and
	 * value are unchanged it is written back exactly as it was read.
	 */
	private static class ParsedHeader extends Header {
		private static final long serialVersionUID = 1L;
		private final String originalName;
		private final String originalValue;
		private final String rawText;

		ParsedHeader(String name, String value, String rawText) {
			super(name, value);
			this.originalName = name;
			this.originalValue = value;
			this.rawText = rawText;
		}

		String unchangedText() {
			return originalName.equals(name) && java.util.Objects.equals(originalValue, value) ? rawText : null;
		}
	}

	/** Header bytes are UTF-8 (RFC 6532); fall back to ISO-8859-1 for legacy 8-bit headers. */
	private static String decodeHeaderLine(byte[] data, int start, int end) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(data, start, end - start)).toString();
		} catch (CharacterCodingException e) {
			return new String(data, start, end - start, StandardCharsets.ISO_8859_1);
		}
	}

	/** True if the bytes contain non-ASCII and are valid UTF-8. */
	private static boolean isUtf8(byte[] data, int start, int end) {
		boolean nonAscii = false;
		for (int i = start; i < end && !nonAscii; i++) {
			nonAscii = data[i] < 0;
		}
		if (!nonAscii) {
			return false;
		}
		try {
			StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, start, end - start));
			return true;
		} catch (CharacterCodingException e) {
			return false;
		}
	}

	private static boolean isFieldName(String name) {
		if (name.isEmpty()) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 33 || c > 126) {
				return false;
			}
		}
		return true;
	}

	private void splitMultipart(String boundary, int depth) throws IOException {
		Body data = body;
		byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

		// {lineStart, lineEnd, isClose, hasNewline, length of the line break before it}
		List<long[]> found = new ArrayList<>();
		long prevBreak = 0;
		try (LineScanner sc = new LineScanner(data, 0, delim.length + 2 + 128)) {
			while (sc.next()) {
				if (!sc.truncated && startsWith(sc.prefix, sc.prefixLen, delim)) {
					int p = delim.length;
					boolean close = p + 1 < sc.prefixLen && sc.prefix[p] == '-' && sc.prefix[p + 1] == '-';
					if (close) {
						p += 2;
					}
					// the rest of the line may only be whitespace
					boolean delimiter = true;
					for (int q = p; q < sc.prefixLen && delimiter; q++) {
						byte c = sc.prefix[q];
						delimiter = c == ' ' || c == '\t' || c == '\r';
					}
					if (delimiter) {
						found.add(new long[] {sc.lineStart, sc.lineEnd, close ? 1 : 0, sc.newline ? 1 : 0, prevBreak});
						if (close) {
							break;
						}
					}
				}
				prevBreak = sc.lineEnd - sc.contentEnd;
			}
		}
		if (found.isEmpty()) {
			return; // not really multipart: keep the body as it is
		}

		long length = data.length();
		long[] first = found.get(0);
		preamble = first[0] == 0 ? null : data.slice(0, first[0] - first[4]);
		for (int i = 0; i < found.size(); i++) {
			long[] d = found.get(i);
			if (d[2] == 1) {
				break;
			}
			long start = d[1];
			// the line break before a delimiter belongs to the delimiter
			long end = i + 1 < found.size() ? found.get(i + 1)[0] - found.get(i + 1)[4] : length;
			Message p = new Message();
			p.part = true;
			p.workDirectory = workDirectory;
			p.load(data.slice(start, Math.max(0, end - start)), false, depth + 1);
			parts.add(p);
		}
		long[] last = found.get(found.size() - 1);
		if (last[2] == 1) {
			// null epilogue = the closing delimiter line had no line break
			epilogue = last[3] == 1 ? data.slice(last[1], length - last[1]) : null;
		} else {
			epilogue = null; // no closing delimiter; one is written on output
		}
		body = null;
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Write the message with CRLF line endings, in the header mode set by
	 * {@link #setUtf8Headers(boolean)}. The stream is not closed.
	 */
	public void writeTo(OutputStream out) throws IOException {
		write(out, utf8Headers);
	}

	/**
	 * Write the message to a file. Writing to the file the message was parsed from
	 * is allowed: the message is written to a temp file beside it, which then
	 * replaces it, and the message is re-read from it. Parts obtained before the
	 * call must be fetched again afterwards.
	 */
	public void writeTo(FileSource dest) throws IOException {
		if (!usesFile(dest)) {
			try (OutputStream out = output(dest)) {
				writeTo(out);
			}
			return;
		}
		FileSource dir = dest.getParentFile();
		FileSource tmp = dir.getFileSourceFactory().createTempFile("bjlmsg", ".tmp", dir);
		try (OutputStream out = output(tmp)) {
			writeTo(out);
		} catch (IOException | RuntimeException e) {
			tmp.delete();
			throw e;
		}
		List<FileSource> obsolete = new ArrayList<>();
		collectOwned(obsolete);
		if (!dest.delete()) {
			tmp.delete();
			throw new IOException("Could not replace " + dest.getAbsolutePath());
		}
		if (!tmp.renameTo(dest)) {
			throw new IOException("Could not rename " + tmp.getAbsolutePath() + " to " + dest.getAbsolutePath()
					+ "; the message is in " + tmp.getAbsolutePath());
		}
		boolean utf8 = utf8Headers;
		load(Body.of(dest), !part, depth);
		utf8Headers = utf8;
		// the old temp files are no longer used (except dest itself, if this message owns it)
		for (FileSource f : obsolete) {
			if (!sameFile(f, dest)) {
				f.delete();
				ownedFiles.remove(f);
			}
		}
	}

	/** The whole message in memory. Only for small messages; use writeTo otherwise. */
	public byte[] toByteArray() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			writeTo(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}

	private void write(OutputStream out, boolean utf8) throws IOException {
		String attachedEncoding = planAttached();
		writeHeaders(out, utf8);
		out.write(CRLF_BYTES);
		writeBody(out, utf8, attachedEncoding);
	}

	private void writeHeaders(OutputStream out, boolean utf8) throws IOException {
		for (Header h : headers) {
			String text = h instanceof ParsedHeader ? ((ParsedHeader) h).unchangedText() : null;
			if (text == null) {
				text = formatHeader(h.getName(), h.getValue(), utf8);
			}
			out.write(text.getBytes(StandardCharsets.UTF_8));
			out.write(CRLF_BYTES);
		}
	}

	private void writeBody(OutputStream out, boolean utf8, String attachedEncoding) throws IOException {
		if (parts.isEmpty()) {
			if (attachedEncoding != null) {
				writeAttached(out, attachedEncoding);
			} else if (body != null) {
				copyBody(body, out, "binary".equals(getTransferEncoding()));
			}
			return;
		}
		String boundary = ensureBoundary();
		byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
		if (preamble != null) {
			copyBody(preamble, out, false);
			out.write(CRLF_BYTES);
		}
		for (Message p : parts) {
			out.write(delim);
			out.write(CRLF_BYTES);
			p.write(out, utf8);
			out.write(CRLF_BYTES);
		}
		out.write(delim);
		out.write('-');
		out.write('-');
		if (epilogue != null) {
			out.write(CRLF_BYTES);
			copyBody(epilogue, out, false);
		}
	}

	/** Copy a body, turning bare LF into CRLF unless it is binary. */
	private static void copyBody(Body b, OutputStream out, boolean binary) throws IOException {
		try (InputStream in = b.open()) {
			if (binary) {
				Streams.copy(in, out);
			} else {
				Streams.CrlfOutputStream crlf = new Streams.CrlfOutputStream(out);
				Streams.copy(in, crlf);
				crlf.flush();
			}
		}
	}

	/** One header, NFC-normalized, encoded for the mode and folded. */
	static String formatHeader(String name, String rawValue, boolean utf8) {
		String value = rawValue == null ? "" : rawValue.replaceAll("[\\r\\n]+", " ");
		value = Normalizer.normalize(value, Normalizer.Form.NFC);
		if (!utf8) {
			value = toAscii(name, value);
		}
		String line = fold(name + ": " + value);
		if (maxLineOctets(line) > MAX_LINE && isUnstructured(name)) {
			// a run with no whitespace longer than 998 octets: encoded words can be folded
			line = fold(name + ": " + EncodedWord.encode(value, null, name.length() + 2, true));
		}
		return line;
	}

	/** The ASCII-only form of a header value: RFC 2047 for text, RFC 2231 for MIME parameters. */
	private static String toAscii(String name, String value) {
		if (isAscii(value)) {
			return value;
		}
		String n = name.toLowerCase(Locale.ROOT);
		if (ADDRESS_HEADERS.contains(n)) {
			List<Address> list = Address.parseAddressList(value);
			if (list.isEmpty()) {
				return value;
			}
			StringBuilder sb = new StringBuilder();
			for (Address a : list) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				sb.append(a.toString());
			}
			return sb.toString();
		}
		if (n.equals("content-type") || n.equals("content-disposition")) {
			return MimeHeaderValue.parse(value).toString();
		}
		if (isUnstructured(name)) {
			return EncodedWord.encode(value, null, name.length() + 2);
		}
		return value; // structured header with no ASCII form: left as UTF-8
	}

	private static boolean isUnstructured(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return UNSTRUCTURED_HEADERS.contains(n) || n.startsWith("x-");
	}

	private static boolean isAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 127) {
				return false;
			}
		}
		return true;
	}

	private static int maxLineOctets(String folded) {
		int max = 0;
		for (String l : folded.split(CRLF)) {
			max = Math.max(max, l.getBytes(StandardCharsets.UTF_8).length);
		}
		return max;
	}

	/**
	 * Fold a header line at whitespace so lines are at most 78 octets (UTF-8 bytes)
	 * where possible (RFC 5322 section 2.2.3, RFC 6532). A run of text without
	 * whitespace is never split.
	 */
	static String fold(String line) {
		if (line.getBytes(StandardCharsets.UTF_8).length <= FOLD_AT) {
			return line;
		}
		StringBuilder out = new StringBuilder();
		int noBreakBefore = line.indexOf(':') + 2; // keep "Name: x" together
		int lineStart = 0;
		int col = 0;            // octets since lineStart
		int lastWs = -1;        // last place a break is allowed
		int colAtLastWs = 0;
		boolean content = false; // non-whitespace seen on the current line
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (isWsp(c) && i > lineStart && i >= noBreakBefore && content) {
				lastWs = i;
				colAtLastWs = col;
			} else if (!isWsp(c)) {
				content = true;
			}
			col += octets(c);
			if (col > FOLD_AT && lastWs > lineStart) {
				out.append(line, lineStart, lastWs).append(CRLF);
				lineStart = lastWs;
				col -= colAtLastWs;
				content = !line.substring(lastWs, i + 1).trim().isEmpty();
				lastWs = -1;
			}
		}
		return out.append(line, lineStart, line.length()).toString();
	}

	private static int octets(char c) {
		if (c < 0x80) {
			return 1;
		}
		if (c < 0x800) {
			return 2;
		}
		if (Character.isHighSurrogate(c)) {
			return 4;
		}
		if (Character.isLowSurrogate(c)) {
			return 0;
		}
		return 3;
	}

	private static boolean isWsp(char c) {
		return c == ' ' || c == '\t';
	}

	/** The whole message as text. Only for small messages. */
	@Override
	public String toString() {
		return new String(toByteArray(), StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ headers

	/** All headers in order. The list is live: changes to it change the message. */
	public List<Header> getHeaders() {
		return headers;
	}

	/** The value of the first header with this name (case-insensitive), or null. */
	public String getHeader(String name) {
		for (Header h : headers) {
			if (h.getName().equalsIgnoreCase(name)) {
				return h.getValue();
			}
		}
		return null;
	}

	/** The values of every header with this name, in order. */
	public List<String> getHeaders(String name) {
		List<String> ret = new ArrayList<>();
		for (Header h : headers) {
			if (h.getName().equalsIgnoreCase(name)) {
				ret.add(h.getValue());
			}
		}
		return ret;
	}

	public Message addHeader(String name, String value) {
		checkName(name);
		headers.add(new Header(name, value));
		return this;
	}

	/**
	 * Replace the first header with this name, keeping its position, and remove any
	 * others; append it if there is none. A null value removes the header.
	 */
	public Message setHeader(String name, String value) {
		checkName(name);
		if (value == null) {
			return removeHeader(name);
		}
		boolean replaced = false;
		for (Iterator<Header> it = headers.iterator(); it.hasNext();) {
			Header h = it.next();
			if (h.getName().equalsIgnoreCase(name)) {
				if (replaced) {
					it.remove();
				} else {
					h.setName(name);
					h.setValue(value);
					replaced = true;
				}
			}
		}
		if (!replaced) {
			headers.add(new Header(name, value));
		}
		return this;
	}

	public Message removeHeader(String name) {
		headers.removeIf(h -> h.getName().equalsIgnoreCase(name));
		return this;
	}

	private static void checkName(String name) {
		if (name == null || !isFieldName(name) || name.indexOf(':') >= 0) {
			throw new IllegalArgumentException("Invalid header name: " + name);
		}
	}

	/** The decoded Subject, or null. */
	public String getSubject() {
		return EncodedWord.decode(getHeader("Subject"));
	}

	/**
	 * Set the Subject. Non-ASCII text is written as RFC 2047 encoded words, or as
	 * UTF-8 in UTF-8 header mode.
	 */
	public Message setSubject(String subject) {
		return setHeader("Subject", subject);
	}

	public List<Address> getFrom() {
		return Address.parseAddressList(getHeader("From"));
	}

	public Message setFrom(Address... from) {
		return setHeader("From", join(from));
	}

	public List<Address> getTo() {
		return Address.parseAddressList(getHeader("To"));
	}

	public Message setTo(Address... to) {
		return setHeader("To", join(to));
	}

	public List<Address> getCc() {
		return Address.parseAddressList(getHeader("Cc"));
	}

	public Message setCc(Address... cc) {
		return setHeader("Cc", join(cc));
	}

	private static String join(Address[] list) {
		if (list == null || list.length == 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (Address a : list) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(a.toUtf8String());
		}
		return sb.toString();
	}

	/**
	 * The Date header, or null if there is none.
	 *
	 * @throws ParseException if the header is present but not a valid date
	 */
	public Rfc2822Date getDate() throws ParseException {
		String d = getHeader("Date");
		return d == null ? null : Rfc2822Date.parseDate(d);
	}

	public Message setDate(Rfc2822Date date) {
		return setHeader("Date", date == null ? null : date.toString());
	}

	public String getMessageId() {
		return getHeader("Message-ID");
	}

	public Message setMessageId(String id) {
		return setHeader("Message-ID", id);
	}

	// ------------------------------------------------------------------ MIME

	/** The Content-Type; {@code text/plain; charset=us-ascii} when there is none (RFC 2045). */
	public MimeHeaderValue getContentType() {
		String ct = getHeader("Content-Type");
		MimeHeaderValue v = MimeHeaderValue.parse(ct);
		if (ct == null || v.getValue().isEmpty()) {
			v = new MimeHeaderValue("text/plain").setParameter("charset", "us-ascii");
		}
		return v;
	}

	public Message setContentType(MimeHeaderValue type) {
		return setHeader("Content-Type", type == null ? null : type.toUtf8String());
	}

	/** The media type in lower case, e.g. "text/plain" or "multipart/mixed". */
	public String getMimeType() {
		return getContentType().getValue().toLowerCase(Locale.ROOT);
	}

	public boolean isMultipart() {
		return getMimeType().startsWith("multipart/");
	}

	/** The Content-Disposition, or null if there is none. */
	public MimeHeaderValue getContentDisposition() {
		String cd = getHeader("Content-Disposition");
		return cd == null ? null : MimeHeaderValue.parse(cd);
	}

	public Message setContentDisposition(MimeHeaderValue disposition) {
		return setHeader("Content-Disposition", disposition == null ? null : disposition.toUtf8String());
	}

	/**
	 * The decoded file name: Content-Disposition filename, else Content-Type name
	 * (RFC 2231 and RFC 2047 encodings are decoded), or null.
	 */
	public String getFilename() {
		MimeHeaderValue cd = getContentDisposition();
		String name = cd == null ? null : cd.getParameter("filename");
		if (name == null) {
			name = getContentType().getParameter("name");
		}
		return name;
	}

	/**
	 * True if Content-Disposition is "attachment"; or the part is an attached
	 * message or has a file name, and isn't marked inline.
	 */
	public boolean isAttachment() {
		MimeHeaderValue cd = getContentDisposition();
		if (cd != null && cd.getValue().equalsIgnoreCase("attachment")) {
			return true;
		}
		boolean inline = cd != null && cd.getValue().equalsIgnoreCase("inline");
		return !inline && (isAttachedMessageType() || getFilename() != null);
	}

	/** The Content-Transfer-Encoding in lower case; "7bit" when there is none. */
	public String getTransferEncoding() {
		String cte = getHeader("Content-Transfer-Encoding");
		return cte == null || cte.trim().isEmpty() ? "7bit" : cte.trim().toLowerCase(Locale.ROOT);
	}

	/** The size of the body as transferred, in bytes; -1 for a message with parts. */
	public long getBodyLength() throws IOException {
		materializeAttached();
		return parts.isEmpty() && body != null ? body.length() : -1;
	}

	/**
	 * The body exactly as transferred (still transfer-encoded), as a stream; null
	 * for a message with parts. The caller closes it.
	 */
	public InputStream openBody() throws IOException {
		materializeAttached();
		return parts.isEmpty() && body != null ? body.open() : null;
	}

	/**
	 * The body exactly as transferred, in memory; null for a message with parts.
	 * Only for small bodies; use {@link #openBody()} otherwise.
	 */
	public byte[] getBody() {
		try {
			materializeAttached();
			return parts.isEmpty() && body != null ? body.toByteArray() : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Set the body as transferred; it must already match Content-Transfer-Encoding. Removes any parts. */
	public Message setBody(byte[] body) {
		return setBody(Body.of(body == null ? new byte[0] : body));
	}

	/**
	 * Use a file as the body, as transferred (it must already match
	 * Content-Transfer-Encoding). The file is read when the message is written, so
	 * it must stay unchanged until then. Removes any parts.
	 */
	public Message setBody(FileSource file) throws IOException {
		return setBody(Body.of(file));
	}

	private Message setBody(Body b) {
		this.body = b;
		attached = null;
		attachedLoaded = false;
		parts.clear();
		preamble = null;
		epilogue = Body.EMPTY;
		return this;
	}

	/**
	 * The content with its transfer encoding (base64 or quoted-printable) removed,
	 * as a stream; for a multipart message, the multipart body. The caller closes it.
	 */
	public InputStream openContent() throws IOException {
		if (!parts.isEmpty()) {
			if (isInMemory()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				writeBody(out, utf8Headers, null);
				return new ByteArrayInputStream(out.toByteArray());
			}
			FileSource f = createTempFile();
			try (OutputStream out = output(f)) {
				writeBody(out, utf8Headers, null);
			}
			return f.getInputStream();
		}
		InputStream raw = openBody();
		return raw == null ? InputStream.nullInputStream() : decode(raw, getTransferEncoding());
	}

	private static InputStream decode(InputStream raw, String cte) {
		switch (cte) {
		case "base64":
			return Base64.getMimeDecoder().wrap(raw);
		case "quoted-printable":
			return QuotedPrintable.decoder(raw);
		default:
			return raw;
		}
	}

	/** Write the decoded content to a stream, which is not closed. */
	public void writeContentTo(OutputStream out) throws IOException {
		try (InputStream in = openContent()) {
			Streams.copy(in, out);
		}
	}

	/** Write the decoded content (e.g. an attachment) to a file. */
	public void saveContent(FileSource dest) throws IOException {
		try (OutputStream out = output(dest)) {
			writeContentTo(out);
		}
	}

	/**
	 * The decoded content in memory; for a multipart message, the multipart body.
	 * Only for small content; use {@link #openContent()} otherwise.
	 *
	 * @throws UncheckedIOException if the content can't be read or decoded
	 */
	public byte[] getContent() {
		try {
			if (!parts.isEmpty()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				writeBody(out, utf8Headers, null);
				return out.toByteArray();
			}
			materializeAttached();
			if (body == null) {
				return new byte[0];
			}
			if (body.length() > Body.MAX_ARRAY) {
				throw new IOException("Too large to load into memory: " + body.length() + " bytes; use openContent()");
			}
			try (InputStream in = openContent()) {
				return in.readAllBytes();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The content as text, decoded with the Content-Type charset (UTF-8 when there
	 * is none; ISO-8859-1 when the charset is unknown, so no bytes are lost).
	 * Only for small content.
	 */
	public String getText() {
		String name = getContentType().getParameter("charset");
		Charset cs = StandardCharsets.UTF_8;
		if (name != null) {
			try {
				cs = Charset.forName(name.trim());
			} catch (RuntimeException e) {
				cs = StandardCharsets.ISO_8859_1;
			}
		}
		return new String(getContent(), cs);
	}

	/** Set plain text content. */
	public Message setText(String text) {
		return setText(text, "plain");
	}

	/**
	 * Set text content of type text/{subtype} (e.g. "plain" or "html"). Line breaks
	 * become CRLF. ASCII text is sent as 7bit; anything else as UTF-8 quoted-printable.
	 */
	public Message setText(String text, String subtype) {
		String normalized = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", CRLF);
		byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
		boolean ascii = is7bit(bytes);
		MimeHeaderValue ct = new MimeHeaderValue("text/" + subtype).setParameter("charset", ascii ? "us-ascii" : "UTF-8");
		setEncodedContent(bytes, ct, true);
		return this;
	}

	/**
	 * Set content of any type from memory. Text types get CRLF line breaks and are
	 * sent as 7bit when they are ASCII, quoted-printable otherwise; everything else
	 * as base64.
	 *
	 * @param contentType e.g. "application/pdf" or "text/csv; charset=UTF-8"
	 */
	public Message setContent(byte[] data, String contentType) {
		MimeHeaderValue ct = MimeHeaderValue.parse(contentType);
		byte[] bytes = data == null ? new byte[0] : data;
		boolean text = ct.getValue().toLowerCase(Locale.ROOT).startsWith("text/");
		if (text) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 16);
			try (OutputStream c = new Streams.CanonicalTextOutputStream(out)) {
				c.write(bytes); // canonical form for text (RFC 2049)
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			bytes = out.toByteArray();
		}
		setEncodedContent(bytes, ct, text);
		return this;
	}

	/**
	 * Set content of any size from a file, encoded the same way as
	 * {@link #setContent(byte[], String)}. The encoded content is streamed into a
	 * temp file in the work directory (deleted by {@link #close()}); the source
	 * file is only read during this call.
	 */
	public Message setContent(FileSource data, String contentType) throws IOException {
		MimeHeaderValue ct = MimeHeaderValue.parse(contentType);
		boolean text = ct.getValue().toLowerCase(Locale.ROOT).startsWith("text/");
		FileSource encoded = createTempFile();
		String cte;
		if (text) {
			Streams.StatsOutputStream stats = new Streams.StatsOutputStream();
			try (InputStream in = data.getInputStream(); OutputStream c = new Streams.CanonicalTextOutputStream(stats)) {
				Streams.copy(in, c);
			}
			cte = stats.is7bit() ? "7bit" : "quoted-printable";
			try (InputStream in = data.getInputStream(); OutputStream file = output(encoded);
					OutputStream c = new Streams.CanonicalTextOutputStream(
							stats.is7bit() ? Streams.noClose(file) : QuotedPrintable.encoder(Streams.noClose(file), true))) {
				Streams.copy(in, c);
			}
		} else {
			cte = "base64";
			try (InputStream in = data.getInputStream(); OutputStream file = output(encoded)) {
				OutputStream b64 = Base64.getMimeEncoder(76, CRLF_BYTES).wrap(Streams.noClose(file));
				long n;
				try {
					byte[] buf = new byte[57 * 1024];
					n = 0;
					int r;
					while ((r = in.read(buf)) > 0) {
						b64.write(buf, 0, r);
						n += r;
					}
				} finally {
					b64.close();
				}
				if (n > 0) {
					file.write(CRLF_BYTES);
				}
			}
		}
		setContentType(ct);
		setHeader("Content-Transfer-Encoding", cte);
		setBody(Body.of(encoded));
		ensureMimeVersion();
		return this;
	}

	private void setEncodedContent(byte[] data, MimeHeaderValue ct, boolean text) {
		String cte;
		byte[] encoded;
		if (text && is7bit(data)) {
			cte = "7bit";
			encoded = data;
		} else if (text) {
			cte = "quoted-printable";
			encoded = QuotedPrintable.encode(data, true);
		} else {
			cte = "base64";
			encoded = Base64.getMimeEncoder(76, CRLF_BYTES).encode(data);
			if (encoded.length > 0) {
				encoded = concat(encoded, CRLF_BYTES);
			}
		}
		setContentType(ct);
		setHeader("Content-Transfer-Encoding", cte);
		setBody(encoded);
		ensureMimeVersion();
	}

	/** ASCII, no NUL, no bare CR or LF, and no line longer than 998 characters. */
	private static boolean is7bit(byte[] data) {
		Streams.StatsOutputStream s = new Streams.StatsOutputStream();
		s.write(data, 0, data.length);
		s.close();
		return s.is7bit();
	}

	private void ensureMimeVersion() {
		if (!part && getHeader("MIME-Version") == null) {
			addHeader("MIME-Version", "1.0");
		}
	}

	// ------------------------------------------------------------------ parts

	/** The parts of a multipart message (empty otherwise). The list is read-only. */
	public List<Message> getParts() {
		return Collections.unmodifiableList(parts);
	}

	/**
	 * Add a part. A message that is not yet multipart becomes multipart/mixed, with
	 * its existing content (if any) as the first part.
	 */
	public Message addPart(Message p) {
		if (!isMultipart()) {
			makeMultipart("mixed");
		}
		p.part = true;
		p.removeHeader("MIME-Version");
		if (p.workDirectory == null) {
			p.workDirectory = workDirectory;
		}
		parts.add(p);
		body = null;
		return this;
	}

	public Message removePart(Message p) {
		parts.remove(p);
		return this;
	}

	/**
	 * Add an attachment from memory. The file name is written in both
	 * Content-Disposition filename and Content-Type name, RFC 2231-encoded when it
	 * isn't plain ASCII. If this message isn't multipart/mixed it becomes one, with
	 * the existing content as the first part.
	 *
	 * @return the new attachment part
	 */
	public Message addAttachment(String filename, String contentType, byte[] data) {
		Message att = newPart();
		att.setContent(data, contentType);
		return addAttachmentPart(att, filename);
	}

	/**
	 * Add an attachment of any size from a file; see {@link #setContent(FileSource, String)}.
	 *
	 * @return the new attachment part
	 */
	public Message addAttachment(String filename, String contentType, FileSource data) throws IOException {
		Message att = newPart();
		att.setContent(data, contentType);
		return addAttachmentPart(att, filename);
	}

	/** Add a file as an attachment under its own name. */
	public Message addAttachment(FileSource data, String contentType) throws IOException {
		return addAttachment(data.getName(), contentType, data);
	}

	private Message newPart() {
		Message att = new Message();
		att.part = true;
		att.workDirectory = workDirectory;
		return att;
	}

	private Message addAttachmentPart(Message att, String filename) {
		if (filename != null) {
			att.setContentType(att.getContentType().setParameter("name", filename));
		}
		MimeHeaderValue cd = new MimeHeaderValue("attachment");
		if (filename != null) {
			cd.setParameter("filename", filename);
		}
		att.setContentDisposition(cd);

		if (!getMimeType().equals("multipart/mixed")) {
			makeMultipart("mixed");
		}
		addPart(att);
		return att;
	}

	/** Every attachment in this message, searching nested multiparts. */
	public List<Message> getAttachments() {
		List<Message> ret = new ArrayList<>();
		collectAttachments(ret);
		return ret;
	}

	private void collectAttachments(List<Message> ret) {
		for (Message p : parts) {
			if (!p.parts.isEmpty()) {
				p.collectAttachments(ret);
			} else if (p.isAttachment()) {
				ret.add(p);
			}
		}
	}

	/**
	 * Turn this message into multipart/{subtype}. Its current content headers and
	 * body move into a first part, unless there is no content yet.
	 */
	private void makeMultipart(String subtype) {
		boolean hasContent;
		try {
			hasContent = (body != null && body.length() > 0) || !parts.isEmpty() || attached != null
					|| getHeader("Content-Type") != null;
		} catch (IOException e) {
			hasContent = true;
		}
		if (hasContent) {
			Message first = newPart();
			for (Iterator<Header> it = headers.iterator(); it.hasNext();) {
				Header h = it.next();
				if (h.getName().toLowerCase(Locale.ROOT).startsWith("content-")) {
					first.headers.add(h);
					it.remove();
				}
			}
			first.body = body;
			first.attached = attached;
			first.attachedLoaded = attachedLoaded;
			first.parts.addAll(parts);
			first.preamble = preamble;
			first.epilogue = epilogue;
			first.depth = depth + 1;
			parts.clear();
			parts.add(first);
		} else {
			removeHeader("Content-Transfer-Encoding");
		}
		body = null;
		attached = null;
		attachedLoaded = false;
		preamble = null;
		epilogue = Body.EMPTY;
		setContentType(new MimeHeaderValue("multipart/" + subtype).setParameter("boundary", newBoundary()));
		ensureMimeVersion();
	}

	private String ensureBoundary() {
		MimeHeaderValue ct = getContentType();
		String b = ct.getParameter("boundary");
		if (b == null || b.isEmpty()) {
			b = newBoundary();
			if (!ct.getValue().toLowerCase(Locale.ROOT).startsWith("multipart/")) {
				ct = new MimeHeaderValue("multipart/mixed");
			}
			setContentType(ct.setParameter("boundary", b));
		}
		return b;
	}

	/**
	 * "=_" can't appear in base64 or quoted-printable output, so with a random
	 * suffix the boundary can't collide with encoded content.
	 */
	private static String newBoundary() {
		byte[] r = new byte[12];
		RANDOM.nextBytes(r);
		StringBuilder sb = new StringBuilder("=_Part_");
		for (byte b : r) {
			sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
		}
		return sb.toString();
	}

	public byte[] getPreamble() {
		try {
			return preamble == null ? null : preamble.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void setPreamble(byte[] preamble) {
		this.preamble = preamble == null ? null : Body.of(preamble);
	}

	public byte[] getEpilogue() {
		try {
			return epilogue == null ? null : epilogue.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void setEpilogue(byte[] epilogue) {
		this.epilogue = epilogue == null ? null : Body.of(epilogue);
	}

	// ------------------------------------------------------------------ RFC 6532

	/** True if headers are written as raw UTF-8 (RFC 6532). */
	public boolean isUtf8Headers() {
		return utf8Headers;
	}

	/**
	 * Choose how non-ASCII header text is written: raw UTF-8 (RFC 6532; needs a
	 * server that supports SMTPUTF8) or, when false, RFC 2047/2231 encodings that
	 * every server accepts. Applies to this message and all its parts.
	 */
	public Message setUtf8Headers(boolean utf8) {
		this.utf8Headers = utf8;
		return this;
	}

	/**
	 * True if the message, as it would be written now, has raw UTF-8 in its headers
	 * or its parts' headers, so it can only be sent to a server that supports
	 * SMTPUTF8 (RFC 6531). Headers inside an attached message/global don't count:
	 * that content is part of the body.
	 */
	public boolean needsSmtpUtf8() {
		return needsSmtpUtf8(utf8Headers);
	}

	private boolean needsSmtpUtf8(boolean utf8) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			writeHeaders(out, utf8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		for (byte b : out.toByteArray()) {
			if (b < 0) {
				return true;
			}
		}
		for (Message p : parts) {
			if (p.needsSmtpUtf8(utf8)) {
				return true;
			}
		}
		return false;
	}

	private boolean isAttachedMessageType() {
		String t = getMimeType();
		return t.equals("message/rfc822") || t.equals("message/global");
	}

	private static boolean isIdentityEncoding(String cte) {
		return cte.equals("7bit") || cte.equals("8bit") || cte.equals("binary");
	}

	/**
	 * For a message/rfc822 or message/global part: the attached message, parsed on
	 * first use (a base64 or quoted-printable one is decoded into a temp file).
	 * Changes to it are written out with this part. Null for other parts or if it
	 * can't be read.
	 */
	public Message getAttachedMessage() {
		if (!attachedLoaded) {
			attachedLoaded = true;
			if (parts.isEmpty() && body != null && isAttachedMessageType() && depth < MAX_DEPTH) {
				try {
					String cte = getTransferEncoding();
					Body content;
					if (isIdentityEncoding(cte)) {
						content = body;
					} else if (body.getFile() == null) {
						try (InputStream in = decode(body.open(), cte)) {
							content = Body.of(in.readAllBytes());
						}
					} else {
						FileSource f = createTempFile();
						try (InputStream in = decode(body.open(), cte); OutputStream out = output(f)) {
							Streams.copy(in, out);
						}
						content = Body.of(f);
					}
					Message m = new Message();
					m.workDirectory = workDirectory;
					m.load(content, true, depth + 1);
					attached = m;
				} catch (IOException | RuntimeException e) {
					attached = null;
				}
			}
		}
		return attached;
	}

	/**
	 * Attach a whole message (e.g. to forward it). It becomes a message/global part
	 * if it needs SMTPUTF8 (RFC 6532 section 3.7), otherwise message/rfc822.
	 *
	 * @return the new part
	 */
	public Message attachMessage(Message message) {
		Message p = newPart();
		p.setHeader("Content-Type", message.needsSmtpUtf8() ? "message/global" : "message/rfc822");
		p.setHeader("Content-Transfer-Encoding", "7bit");
		p.setHeader("Content-Disposition", "attachment");
		p.attached = message;
		p.attachedLoaded = true;
		p.body = null;
		try {
			p.planAttached(); // sets Content-Transfer-Encoding
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!getMimeType().equals("multipart/mixed")) {
			makeMultipart("mixed");
		}
		addPart(p);
		return p;
	}

	/**
	 * If this part holds an attached message that differs from the stored body,
	 * choose its transfer encoding (setting the header) and return it; otherwise null.
	 */
	private String planAttached() throws IOException {
		if (attached == null || !parts.isEmpty()) {
			return null;
		}
		if (body != null && attachedUnchanged()) {
			return null;
		}
		String cte = getTransferEncoding();
		if (isIdentityEncoding(cte)) {
			// the lightest encoding the content allows
			Streams.StatsOutputStream stats = new Streams.StatsOutputStream();
			attached.writeTo(stats);
			stats.close();
			boolean global = getMimeType().equals("message/global");
			cte = stats.is7bit() ? "7bit" : stats.is8bit() ? "8bit" : global ? "base64" : "binary";
			setHeader("Content-Transfer-Encoding", cte);
		}
		return cte;
	}

	/** Streams the attached message against the decoded stored body. */
	private boolean attachedUnchanged() {
		try (InputStream expected = decode(body.open(), getTransferEncoding())) {
			Streams.ComparingOutputStream cmp = new Streams.ComparingOutputStream(expected);
			attached.writeTo(cmp);
			return cmp.matches();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private void writeAttached(OutputStream out, String cte) throws IOException {
		switch (cte) {
		case "base64": {
			OutputStream enc = Base64.getMimeEncoder(76, CRLF_BYTES).wrap(Streams.noClose(out));
			attached.writeTo(enc);
			enc.close();
			out.write(CRLF_BYTES);
			break;
		}
		case "quoted-printable": {
			OutputStream enc = QuotedPrintable.encoder(Streams.noClose(out), true);
			attached.writeTo(enc);
			enc.close();
			break;
		}
		default:
			attached.writeTo(out);
		}
	}

	/** Make the stored body match a changed attached message (into a temp file). */
	private void materializeAttached() throws IOException {
		String cte = planAttached();
		if (cte == null) {
			return;
		}
		if (isInMemory()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeAttached(out, cte);
			body = Body.of(out.toByteArray());
			return;
		}
		FileSource f = createTempFile();
		try (OutputStream out = output(f)) {
			writeAttached(out, cte);
		}
		body = Body.of(f);
	}

	/** True if nothing in this message is stored in a file (it was built or parsed in memory). */
	private boolean isInMemory() {
		List<FileSource> files = new ArrayList<>();
		collectFiles(files);
		return files.isEmpty();
	}

	private static OutputStream output(FileSource f) throws IOException {
		return IoUtils.buffered(f.getOutputStream());
	}

	// ------------------------------------------------------------------ as parsed

	/**
	 * For a message or part read by parse() or read(): its size as stored, headers
	 * and body; -1 for one that was built rather than parsed. This describes the
	 * stored bytes, so it doesn't reflect later changes to the message.
	 */
	public long getSourceLength() throws IOException {
		return source == null ? -1 : source.length();
	}

	/**
	 * For a parsed message or part: the length of its header block as stored,
	 * including the blank line that ends it; -1 if it wasn't parsed.
	 */
	public long getHeaderLength() {
		return source == null ? -1 : headerLength;
	}

	/**
	 * Read part of a parsed message or part exactly as stored (offsets are
	 * relative to the start of its headers). The caller closes the stream.
	 */
	public InputStream openSource(long offset, long length) throws IOException {
		if (source == null) {
			throw new IOException("Not a parsed message");
		}
		return source.slice(offset, length).open();
	}

	// ------------------------------------------------------------------ storage

	/**
	 * The directory temp files are created in: this message's work directory if set,
	 * else the default set by {@link #setDefaultWorkDirectory(FileSource)}, else the
	 * default FileSource factory's temp directory.
	 */
	public FileSource getWorkDirectory() throws IOException {
		if (workDirectory != null) {
			return workDirectory;
		}
		FileSource d = defaultWorkDirectory;
		return d != null ? d : FileSourceFactory.getDefaultFactory().getTempDirectory();
	}

	/** Set the directory temp files of this message (and parts added later) go in. */
	public Message setWorkDirectory(FileSource dir) {
		this.workDirectory = dir;
		return this;
	}

	public static FileSource getDefaultWorkDirectory() {
		return defaultWorkDirectory;
	}

	/** Set the directory temp files go in for messages without their own; null for the system default. */
	public static void setDefaultWorkDirectory(FileSource dir) {
		defaultWorkDirectory = dir;
	}

	private FileSource createTempFile() throws IOException {
		FileSource dir = getWorkDirectory();
		FileSource f = dir.getFileSourceFactory().createTempFile("bjlmsg", ".tmp", dir);
		if (ownedFiles == null) {
			ownedFiles = new ArrayList<>();
		}
		ownedFiles.add(f);
		return f;
	}

	/**
	 * Delete the temp files this message and its parts created (copies made by
	 * {@link #read(InputStream)}, encoded attachments, decoded attached messages).
	 * The file a message was parsed from with {@link #parse(FileSource)} is not
	 * deleted. The message must not be used afterwards.
	 */
	@Override
	public void close() throws IOException {
		List<FileSource> all = new ArrayList<>();
		collectOwned(all);
		IOException first = null;
		for (FileSource f : all) {
			try {
				f.delete();
			} catch (IOException e) {
				if (first == null) {
					first = e;
				}
			}
		}
		clearOwned();
		if (first != null) {
			throw first;
		}
	}

	private void collectOwned(List<FileSource> into) {
		if (ownedFiles != null) {
			into.addAll(ownedFiles);
		}
		for (Message p : parts) {
			p.collectOwned(into);
		}
		if (attached != null) {
			attached.collectOwned(into);
		}
	}

	private void clearOwned() {
		if (ownedFiles != null) {
			ownedFiles.clear();
		}
		for (Message p : parts) {
			p.clearOwned();
		}
		if (attached != null) {
			attached.clearOwned();
		}
	}

	/** True if any body of this message (or its parts) is read from {@code file}. */
	private boolean usesFile(FileSource file) throws IOException {
		List<FileSource> files = new ArrayList<>();
		collectFiles(files);
		for (FileSource f : files) {
			if (sameFile(f, file)) {
				return true;
			}
		}
		return false;
	}

	private void collectFiles(List<FileSource> into) {
		for (Body b : new Body[] {body, preamble, epilogue}) {
			if (b != null && b.getFile() != null) {
				into.add(b.getFile());
			}
		}
		for (Message p : parts) {
			p.collectFiles(into);
		}
		if (attached != null) {
			attached.collectFiles(into);
		}
	}

	private static boolean sameFile(FileSource a, FileSource b) throws IOException {
		if (a == b) {
			return true;
		}
		return a.getFileSourceFactory().getClass() == b.getFileSourceFactory().getClass()
				&& a.getCanonicalPath().equals(b.getCanonicalPath());
	}

	// ------------------------------------------------------------------ helpers

	private static boolean startsWith(byte[] data, int len, byte[] prefix) {
		if (prefix.length > len) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (data[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] ret = new byte[a.length + b.length];
		System.arraycopy(a, 0, ret, 0, a.length);
		System.arraycopy(b, 0, ret, a.length, b.length);
		return ret;
	}
}
