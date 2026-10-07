package us.bringardner.parley.mail;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a {@link Body} line by line, reporting each line's position and keeping
 * only its first {@code maxPrefix} bytes, so lines of any length (or a body with
 * no line breaks at all) never need to fit in memory.
 */
final class LineScanner implements Closeable {

	private final InputStream in;
	private final byte[] buf = new byte[64 * 1024];
	private int bufPos;
	private int bufLen;
	private long pos; // position of the next unread byte, relative to the body

	/** Start of the current line. */
	long lineStart;
	/** End of the line's content (before CRLF or LF). */
	long contentEnd;
	/** Position after the line, including its line break. */
	long lineEnd;
	/** True if the line ended with LF (false only for the last line). */
	boolean newline;
	/** The first bytes of the line (up to contentEnd, or maxPrefix bytes). */
	final byte[] prefix;
	int prefixLen;
	/** True if the line was longer than the prefix. */
	boolean truncated;

	LineScanner(Body body, long from, int maxPrefix) throws IOException {
		this.in = body.open(from);
		this.pos = from;
		this.prefix = new byte[maxPrefix];
	}

	/** Advance to the next line; false at the end of the body. */
	boolean next() throws IOException {
		lineStart = pos;
		prefixLen = 0;
		truncated = false;
		int last = -1;
		while (true) {
			if (bufPos >= bufLen) {
				bufLen = in.read(buf, 0, buf.length);
				bufPos = 0;
				if (bufLen <= 0) {
					bufLen = 0;
					break;
				}
			}
			// scan the buffer for LF
			int i = bufPos;
			while (i < bufLen && buf[i] != '\n') {
				i++;
			}
			int n = i - bufPos;
			if (n > 0) {
				int room = prefix.length - prefixLen;
				int copy = Math.min(room, n);
				System.arraycopy(buf, bufPos, prefix, prefixLen, copy);
				prefixLen += copy;
				if (copy < n) {
					truncated = true;
				}
				last = buf[i - 1];
				pos += n;
			}
			if (i < bufLen) { // found LF
				bufPos = i + 1;
				pos++;
				newline = true;
				lineEnd = pos;
				contentEnd = pos - 1 - (last == '\r' ? 1 : 0);
				trimPrefix();
				return true;
			}
			bufPos = bufLen;
		}
		if (pos == lineStart) {
			return false;
		}
		newline = false;
		lineEnd = pos;
		contentEnd = pos - (last == '\r' ? 1 : 0);
		trimPrefix();
		return true;
	}

	/** Drop a trailing CR from the prefix so it holds just the line content. */
	private void trimPrefix() {
		long contentLen = contentEnd - lineStart;
		if (!truncated && prefixLen > contentLen) {
			prefixLen = (int) contentLen;
		}
	}

	boolean isBlank() {
		return contentEnd == lineStart;
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
