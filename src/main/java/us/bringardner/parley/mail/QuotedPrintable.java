package us.bringardner.parley.mail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

		/** Decode about this much per fill() when the input has it ready. */
		private static final int FILL_TARGET = 4096;
		/** What one pass of the loop in fill() can add: a whitespace run, then a couple of bytes. */
		private static final int MAX_PASS = MAX_WS + 4;

		private final InputStream in;
		/** Our own input buffer: a call per byte on a wrapper stream was most of the time. */
		private final byte[] ibuf = new byte[8192];
		private int iPos;
		private int iLen;
		/** Bytes given back by back(); at most the 3 the decoder ever looks ahead. */
		private final int[] pushed = new int[4];
		private int pushedCount;
		/** Whitespace seen since the last other byte: it is dropped before a line end, kept otherwise. */
		private final byte[] ws = new byte[MAX_WS];
		private int wsLen;
		private final byte[] queue = new byte[FILL_TARGET + MAX_PASS];
		/** Whitespace skipped after a '=' (a soft break may be followed by it), reused. */
		private final byte[] skippedWs = new byte[76];
		/** Bytes taken from in so far; fill() compares it with what in had ready. */
		private long consumed;
		private int qPos;
		private int qLen;
		private boolean eof;

		Decoder(InputStream in) {
			this.in = in;
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
					if (eof || (n > 0 && ready() <= 0)) {
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
			if (wsLen == 0) {
				return;
			}
			System.arraycopy(ws, 0, queue, qLen, wsLen);
			qLen += wsLen;
			wsLen = 0;
		}

		private int next() throws IOException {
			consumed++;
			if( pushedCount > 0 ) {
				return pushed[--pushedCount];
			}
			if( iPos >= iLen ) {
				int n = in.read(ibuf, 0, ibuf.length);
				if( n <= 0 ) {
					consumed--;
					return -1;
				}
				iPos = 0;
				iLen = n;
			}
			return ibuf[iPos++] & 0xff;
		}

		private void back(int b) {
			consumed--;
			pushed[pushedCount++] = b;
		}

		/** Bytes that can be taken without waiting for the source (a guess for sources that can't say). */
		private int ready() throws IOException {
			return pushedCount + (iLen - iPos) + in.available();
		}

		/**
		 * Decode until at least one byte is queued or the input ends, and on while the input
		 * has more ready (it never waits for input once there is something to return). A call
		 * used to produce a byte or two, and the reader asked in.available() after each.
		 */
		private void fill() throws IOException {
			qPos = 0;
			qLen = 0;
			// what can be read without blocking, less what one pass may look ahead
			long limit = consumed + Math.max(0, ready() - 4);
			while (qLen == 0 || (qLen < FILL_TARGET && consumed < limit)) {
				int b = next();
				if (b < 0) {
					wsLen = 0; // trailing whitespace at the end is removed
					eof = true;
					return;
				}
				if (b == '=') {
					decodeEquals();
				} else if (b == '\r') {
					int d = next();
					if (d == '\n') {
						wsLen = 0; // trailing whitespace before a line break is removed
						put('\r');
						put('\n');
					} else {
						if (d >= 0) {
							back(d);
						}
						flushWs();
						put('\r');
					}
				} else if (b == '\n') {
					wsLen = 0;
					put('\n');
				} else if (b == ' ' || b == '\t') {
					ws[wsLen++] = (byte) b;
					if (wsLen >= MAX_WS) {
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
			int c = next();
			// soft line break: '=' then optional whitespace then line end (or end of data)
			int skipped = 0;
			while ((c == ' ' || c == '\t') && skipped < skippedWs.length) {
				skippedWs[skipped++] = (byte) c;
				c = next();
			}
			if (c < 0) {
				return; // soft break at the end of the data
			}
			if (c == '\n') {
				return;
			}
			if (c == '\r') {
				int d = next();
				if (d == '\n') {
					return;
				}
				if (d >= 0) {
					back(d);
				}
			}
			if (skipped == 0) {
				int h1 = MimeHeaderValue.hex((char) c);
				if (h1 >= 0) {
					int d = next();
					int h2 = d < 0 ? -1 : MimeHeaderValue.hex((char) d);
					if (h2 >= 0) {
						put(h1 * 16 + h2);
						return;
					}
					if (d >= 0) {
						back(d);
					}
				}
				back(c);
				put('=');
				return;
			}
			// '=' followed by whitespace and then other text: keep it all literally
			back(c);
			put('=');
			System.arraycopy(skippedWs, 0, ws, wsLen, skipped);
			wsLen += skipped;
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
