package us.bringardner.parley.mail;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import us.bringardner.parley.io.IoUtils;

/** Stream helpers used to read and write message bodies without loading them. */
final class Streams {

	private Streams() {
	}

	static void copy(InputStream in, OutputStream out) throws IOException {
		IoUtils.copy(in, out);
	}

	/** Writes through, turning every bare LF into CRLF. */
	static final class CrlfOutputStream extends FilterOutputStream {
		private int last = -1;

		CrlfOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			if (b == '\n' && last != '\r') {
				out.write('\r');
			}
			out.write(b);
			last = b;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			int from = off;
			int end = off + len;
			for (int i = off; i < end; i++) {
				if (b[i] == '\n' && (i == off ? last : b[i - 1]) != '\r') {
					out.write(b, from, i - from);
					out.write('\r');
					from = i;
				}
			}
			out.write(b, from, end - from);
			if (len > 0) {
				last = b[end - 1];
			}
		}

		/** Doesn't close the underlying stream. */
		@Override
		public void close() throws IOException {
			flush();
		}
	}

	/** Like {@link CrlfOutputStream}, but a bare CR also becomes CRLF (canonical text). */
	static final class CanonicalTextOutputStream extends FilterOutputStream {
		private boolean pendingCr;

		CanonicalTextOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			if (pendingCr) {
				pendingCr = false;
				out.write('\r');
				out.write('\n');
				if (b == '\n') {
					return;
				}
			}
			if (b == '\r') {
				pendingCr = true;
			} else if (b == '\n') {
				out.write('\r');
				out.write('\n');
			} else {
				out.write(b);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			for (int i = off; i < off + len; i++) {
				write(b[i]);
			}
		}

		@Override
		public void close() throws IOException {
			if (pendingCr) {
				pendingCr = false;
				out.write('\r');
				out.write('\n');
			}
			out.close();
		}
	}

	/** Discards output, recording what 7bit/8bit transfer encoding would allow. */
	static final class StatsOutputStream extends OutputStream {
		private static final int MAX_LINE = 998;
		boolean nonAscii;
		boolean invalid8bit; // NUL, bare CR, bare LF or a line over 998 octets
		long count;
		private int col;
		private boolean pendingCr;

		@Override
		public void write(int b) {
			b &= 0xff;
			count++;
			if (pendingCr) {
				pendingCr = false;
				if (b == '\n') {
					col = 0;
					return;
				}
				invalid8bit = true; // bare CR
				col++;
			}
			if (b == '\r') {
				pendingCr = true;
				return;
			}
			if (b == '\n' || b == 0) {
				invalid8bit = true;
			}
			if (b >= 128) {
				nonAscii = true;
			}
			if (++col > MAX_LINE) {
				invalid8bit = true;
			}
		}

		@Override
		public void write(byte[] b, int off, int len) {
			for (int i = off; i < off + len; i++) {
				write(b[i]);
			}
		}

		@Override
		public void close() {
			if (pendingCr) {
				pendingCr = false;
				invalid8bit = true;
			}
		}

		boolean is7bit() {
			return !nonAscii && !invalid8bit;
		}

		boolean is8bit() {
			return !invalid8bit;
		}
	}

	/** Compares what is written with an expected stream; stops reading at the first difference. */
	static final class ComparingOutputStream extends OutputStream {
		private final InputStream expected;
		private final byte[] buf = new byte[64 * 1024];
		private boolean different;

		ComparingOutputStream(InputStream expected) {
			this.expected = expected;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] {(byte) b}, 0, 1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			if (different) {
				return;
			}
			while (len > 0) {
				int n = expected.read(buf, 0, Math.min(len, buf.length));
				if (n <= 0) {
					different = true;
					return;
				}
				for (int i = 0; i < n; i++) {
					if (buf[i] != b[off + i]) {
						different = true;
						return;
					}
				}
				off += n;
				len -= n;
			}
		}

		/** True if the written bytes equal the whole expected stream. */
		boolean matches() throws IOException {
			return !different && expected.read() < 0;
		}
	}

	/** An output stream whose close() doesn't close the stream it writes to. */
	static OutputStream noClose(OutputStream out) {
		return IoUtils.noClose(out);
	}
}
