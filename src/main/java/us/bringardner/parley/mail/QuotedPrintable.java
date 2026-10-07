package us.bringardner.parley.mail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;

/**
 * Quoted-printable content transfer encoding (RFC 2045 section 6.7), as streams
 * for content of any size and as byte-array helpers for small content.
 */
public final class QuotedPrintable {

	/** Encoded lines are at most 76 characters, including a trailing '=' soft break. */
	private static final int MAX_LINE = 76;

	private QuotedPrintable() {
	}

	/**
	 * Encode bytes.
	 *
	 * @param text true for text: CRLF (or a bare LF) is kept as a line break
	 *             and written as CRLF. False for binary data: every CR and LF is encoded.
	 */
	public static byte[] encode(byte[] data, boolean text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + data.length / 8 + 16);
		try (OutputStream enc = encoder(out, text)) {
			enc.write(data);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // can't happen in memory
		}
		return out.toByteArray();
	}

	/**
	 * Decode bytes. Lenient: an '=' not followed by two hex digits is kept as is,
	 * and trailing whitespace on encoded lines is removed as RFC 2045 requires.
	 */
	public static byte[] decode(byte[] data) {
		try (InputStream in = decoder(new ByteArrayInputStream(data))) {
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A stream that quoted-printable encodes what is written to it. Closing it closes {@code out}. */
	public static OutputStream encoder(OutputStream out, boolean text) {
		return new Encoder(out, text);
	}

	/** A stream that decodes quoted-printable data read from {@code in}. */
	public static InputStream decoder(InputStream in) {
		return new Decoder(in);
	}

	private static final class Encoder extends FilterOutputStream {
		private final boolean text;
		private int col;
		private int pendingWs = -1;     // a space or tab not yet known to be trailing
		private boolean pendingCr;      // text mode: a CR that may start CRLF

		Encoder(OutputStream out, boolean text) {
			super(out);
			this.text = text;
		}

		@Override
		public void write(int b) throws IOException {
			b &= 0xff;
			if (text) {
				if (pendingCr) {
					pendingCr = false;
					if (b == '\n') {
						flushWs(true);
						hardBreak();
						return;
					}
					flushWs(false);
					emit('\r'); // bare CR: encoded
				}
				if (b == '\r') {
					pendingCr = true;
					return;
				}
				if (b == '\n') {
					flushWs(true);
					hardBreak();
					return;
				}
			}
			flushWs(false);
			if (b == ' ' || b == '\t') {
				pendingWs = b;
				return;
			}
			emit(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			for (int i = off; i < off + len; i++) {
				write(b[i]);
			}
		}

		private void flushWs(boolean atLineEnd) throws IOException {
			if (pendingWs >= 0) {
				int ws = pendingWs;
				pendingWs = -1;
				if (atLineEnd) {
					emitEncoded(ws);
				} else {
					emitLiteral(ws);
				}
			}
		}

		private void emit(int b) throws IOException {
			if (b >= 33 && b <= 126 && b != '=') {
				emitLiteral(b);
			} else {
				emitEncoded(b);
			}
		}

		private void emitLiteral(int b) throws IOException {
			softBreakIfNeeded(1);
			out.write(b);
			col++;
		}

		private void emitEncoded(int b) throws IOException {
			softBreakIfNeeded(3);
			out.write('=');
			out.write(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
			out.write(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
			col += 3;
		}

		private void softBreakIfNeeded(int len) throws IOException {
			if (col + len > MAX_LINE - 1) {
				out.write('=');
				out.write('\r');
				out.write('\n');
				col = 0;
			}
		}

		private void hardBreak() throws IOException {
			out.write('\r');
			out.write('\n');
			col = 0;
		}

		/** Finishes the encoding: whitespace at the very end is encoded. */
		@Override
		public void close() throws IOException {
			if (pendingCr) {
				pendingCr = false;
				flushWs(false);
				emit('\r');
			}
			flushWs(true);
			super.close();
		}
	}

	private static final class Decoder extends InputStream {
		/** A whitespace run longer than this can't be trailing whitespace of a valid line. */
		private static final int MAX_WS = 4096;

		private final PushbackInputStream in;
		private final ByteArrayOutputStream ws = new ByteArrayOutputStream();
		private final byte[] queue = new byte[MAX_WS + 4];
		private int qPos;
		private int qLen;
		private boolean eof;

		Decoder(InputStream in) {
			this.in = new PushbackInputStream(in, 3);
		}

		@Override
		public int read() throws IOException {
			while (qPos >= qLen) {
				if (eof) {
					return -1;
				}
				fill();
			}
			return queue[qPos++] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			int n = 0;
			while (n < len) {
				if (qPos >= qLen) {
					if (eof || (n > 0 && in.available() <= 0)) {
						break;
					}
					fill();
					continue;
				}
				int c = Math.min(len - n, qLen - qPos);
				System.arraycopy(queue, qPos, b, off + n, c);
				qPos += c;
				n += c;
			}
			return n == 0 ? -1 : n;
		}

		private void put(int b) {
			queue[qLen++] = (byte) b;
		}

		/** Pending literal whitespace is real content (something follows it on the line). */
		private void flushWs() {
			byte[] w = ws.toByteArray();
			System.arraycopy(w, 0, queue, qLen, w.length);
			qLen += w.length;
			ws.reset();
		}

		/** Decode until at least one byte is queued or the input ends. */
		private void fill() throws IOException {
			qPos = 0;
			qLen = 0;
			while (qLen == 0) {
				int b = in.read();
				if (b < 0) {
					ws.reset(); // trailing whitespace at the end is removed
					eof = true;
					return;
				}
				if (b == '=') {
					decodeEquals();
				} else if (b == '\r') {
					int d = in.read();
					if (d == '\n') {
						ws.reset(); // trailing whitespace before a line break is removed
						put('\r');
						put('\n');
					} else {
						if (d >= 0) {
							in.unread(d);
						}
						flushWs();
						put('\r');
					}
				} else if (b == '\n') {
					ws.reset();
					put('\n');
				} else if (b == ' ' || b == '\t') {
					ws.write(b);
					if (ws.size() >= MAX_WS) {
						flushWs();
					}
				} else {
					flushWs();
					put(b);
				}
			}
		}

		private void decodeEquals() throws IOException {
			flushWs(); // whitespace before '=' is content
			int c = in.read();
			// soft line break: '=' then optional whitespace then line end (or end of data)
			int skipped = 0;
			byte[] skippedWs = new byte[76];
			while ((c == ' ' || c == '\t') && skipped < skippedWs.length) {
				skippedWs[skipped++] = (byte) c;
				c = in.read();
			}
			if (c < 0) {
				return; // soft break at the end of the data
			}
			if (c == '\n') {
				return;
			}
			if (c == '\r') {
				int d = in.read();
				if (d == '\n') {
					return;
				}
				if (d >= 0) {
					in.unread(d);
				}
			}
			if (skipped == 0) {
				int h1 = MimeHeaderValue.hex((char) c);
				if (h1 >= 0) {
					int d = in.read();
					int h2 = d < 0 ? -1 : MimeHeaderValue.hex((char) d);
					if (h2 >= 0) {
						put(h1 * 16 + h2);
						return;
					}
					if (d >= 0) {
						in.unread(d);
					}
				}
				in.unread(c);
				put('=');
				return;
			}
			// '=' followed by whitespace and then other text: keep it all literally
			in.unread(c);
			put('=');
			ws.write(skippedWs, 0, skipped);
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
