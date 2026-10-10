package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The quoted-printable decoder reads its source in blocks and decodes many bytes per
 * pass. The result must not depend on how it is read (a byte, a block, odd sizes) or on
 * how the source hands out its data (all at once, or a few bytes at a time like a socket).
 */
public class TestQuotedPrintableDecoder {

	/** Gives the data out a few bytes at a time and says little about what is ready. */
	private static class Trickle extends InputStream {
		private final byte[] data;
		private final Random random;
		private int pos;

		Trickle(byte[] data, long seed) {
			this.data = data;
			this.random = new Random(seed);
		}

		@Override
		public int read() {
			return pos < data.length ? data[pos++] & 0xff : -1;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if( pos >= data.length ) {
				return -1;
			}
			int n = Math.min(Math.min(len, data.length - pos), 1 + random.nextInt(5));
			System.arraycopy(data, pos, b, off, n);
			pos += n;
			return n;
		}

		@Override
		public int available() {
			return Math.min(data.length - pos, random.nextInt(3));
		}
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] readAll(InputStream in, int mode, long seed) throws IOException {
		Random r = new Random(seed);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (InputStream i = in) {
			if( mode == 0 ) {
				int b;
				while( (b = i.read()) >= 0 ) {
					out.write(b);
				}
			} else if( mode == 1 ) {
				byte[] buf = new byte[65536];
				int n;
				while( (n = i.read(buf)) > 0 ) {
					out.write(buf, 0, n);
				}
			} else {
				byte[] buf = new byte[8];
				int n;
				while( (n = i.read(buf, 0, 1 + r.nextInt(buf.length))) > 0 ) {
					out.write(buf, 0, n);
				}
			}
		}
		return out.toByteArray();
	}

	/** Decodes 'encoded' every way there is and checks each gives 'expected'. */
	private static void check(String expected, String encoded) throws IOException {
		byte[] data = bytes(encoded);
		for(int mode = 0; mode < 3; mode++) {
			assertEquals(expected, new String(readAll(QuotedPrintable.decoder(new ByteArrayInputStream(data)), mode, 1),
					StandardCharsets.ISO_8859_1), "from an array, read mode " + mode);
			assertEquals(expected, new String(readAll(QuotedPrintable.decoder(new Trickle(data, 2)), mode, 3),
					StandardCharsets.ISO_8859_1), "from a trickle, read mode " + mode);
		}
	}

	@Test
	public void escapesAndSoftBreaks() throws IOException {
		check("a=b", "a=3Db");
		check("café", "caf=E9");
		check("one line", "one =\r\nline");
		check("one line", "one =\nline");
		check("x\r\ny", "x\r\ny");
		check("ends", "ends=");
	}

	@Test
	public void trailingWhitespaceGoesButInnerWhitespaceStays() throws IOException {
		check("a b\r\nc", "a b  \t \r\nc");
		check("a \tb", "a \tb");
		check("end", "end \t ");
		check("a  =b", "a  =3Db");
	}

	@Test
	public void leniencyIsKept() throws IOException {
		// not an escape: kept as it is
		check("=ZZ", "=ZZ");
		check("=4", "=4");
		check("= x", "= x");
		check("", "=");   // a soft break at the end of the data
		check("a\rb", "a\rb");
	}

	@Test
	public void longWhitespaceRunsAreContent() throws IOException {
		StringBuilder sb = new StringBuilder("a");
		for(int i = 0; i < 9000; i++) {
			sb.append(i % 7 == 0 ? '\t' : ' ');
		}
		sb.append("b\r\nc");
		check(sb.toString(), sb.toString());
	}

	@Test
	public void mixedContentIsTheSameWhateverTheReadPattern() throws IOException {
		Random r = new Random(11);
		String alphabet = "ab =\r\n\t=3D=4=FFZ=\r\n=\n \t\r\r\n.";
		for(int iter = 0; iter < 300; iter++) {
			StringBuilder sb = new StringBuilder();
			int len = r.nextInt(iter % 20 == 0 ? 12000 : 80);
			for(int i = 0; i < len; i++) {
				sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
			}
			byte[] data = bytes(sb.toString());
			byte[] want = readAll(QuotedPrintable.decoder(new ByteArrayInputStream(data)), 1, 0);
			for(int mode = 0; mode < 3; mode++) {
				assertArrayEquals(want, readAll(QuotedPrintable.decoder(new ByteArrayInputStream(data)), mode, iter),
						"iteration " + iter + ", read mode " + mode);
				assertArrayEquals(want, readAll(QuotedPrintable.decoder(new Trickle(data, iter)), mode, iter),
						"iteration " + iter + ", trickle, read mode " + mode);
			}
		}
	}

	@Test
	public void throughput() throws IOException {
		Random r = new Random(5);
		StringBuilder sb = new StringBuilder();
		while( sb.length() < 8 * 1024 * 1024 ) {
			for(int i = 0; i < 70; i++) {
				if( r.nextInt(12) == 0 ) {
					sb.append('=').append("0123456789ABCDEF".charAt(r.nextInt(16))).append("0123456789ABCDEF".charAt(r.nextInt(16)));
				} else {
					sb.append((char) ('a' + r.nextInt(26)));
				}
			}
			sb.append(r.nextBoolean() ? "=\r\n" : "\r\n");
		}
		byte[] data = bytes(sb.toString());
		long best = Long.MAX_VALUE;
		long total = 0;
		for(int round = 0; round < 3; round++) {
			long t0 = System.nanoTime();
			total = readAll(QuotedPrintable.decoder(new ByteArrayInputStream(data)), 1, 0).length;
			best = Math.min(best, System.nanoTime() - t0);
		}
		double mbPerSecond = (data.length / 1048576.0) / (best / 1e9);
		System.out.printf("[qp decode] %d MB -> %d MB at %.0f MB/s%n", data.length >> 20, total >> 20, mbPerSecond);
		// the decoder used to run at 6-9 MB/s here; leave a wide margin for a slow machine
		assertTrue(mbPerSecond > 30, "only " + mbPerSecond + " MB/s");
	}
}
