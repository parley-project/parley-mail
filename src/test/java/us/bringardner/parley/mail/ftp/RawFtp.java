package us.bringardner.parley.mail.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * A raw control connection for server tests: every line of each reply, and helpers for the data
 * connection. {@link #one(String)} checks a command got exactly one reply (NOOP follows it and must get
 * its own 200).
 */
class RawFtp implements AutoCloseable {

	private static final Pattern PASV = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
	final Socket plain = new Socket();
	BufferedReader in;
	Writer out;

	/** On a connection already open: reads the greeting */
	RawFtp(Socket s) throws IOException {
		attach(s);
		assertEquals(220, code(read()));
	}

	RawFtp(int port) throws IOException {
		plain.connect(new InetSocketAddress("127.0.0.1", port), 5000);
		attach(plain);
		assertEquals(220, code(read()));
	}

	static FtpServer startServer(int port, File root) throws Exception {
		FtpServer s = new FtpServer();
		s.setSecure(false);
		s.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		s.setPort(port);
		s.setLoginFailureDelay(0);
		s.getLogger().setLevel(Level.ERROR);
		s.start();
		long t = System.currentTimeMillis();
		while (!s.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		assertTrue(s.isRunning());
		return s;
	}

	void attach(Socket s) throws IOException {
		s.setSoTimeout(10000);
		in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
		out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
	}

	RawFtp login() throws IOException {
		assertEquals(331, code(send("USER anonymous")));
		assertEquals(230, code(send("PASS x")));
		return this;
	}

	void write(String command) throws IOException {
		out.write(command + "\r\n");
		out.flush();
	}

	List<String> send(String command) throws IOException {
		write(command);
		return read();
	}

	List<String> read() throws IOException {
		List<String> lines = new ArrayList<>();
		String line = in.readLine();
		assertTrue(line != null, "connection closed");
		assertTrue(line.matches("\\d{3}[ -].*") || line.matches("\\d{3}"), "not a reply line: '" + line + "'");
		lines.add(line);
		if (line.length() > 3 && line.charAt(3) == '-') {
			String end = line.substring(0, 3) + " ";
			do {
				line = in.readLine();
				assertTrue(line != null, "connection closed inside a multi-line reply");
				lines.add(line);
			} while (!line.startsWith(end));
		}
		return lines;
	}

	/** The command's reply (all of it), after checking NOOP then gets its own 200 */
	List<String> oneReply(String command) throws IOException {
		List<String> reply = send(command);
		assertEquals("200 NOOP", send("NOOP").get(0), "a second reply to '" + command + "' was waiting");
		return reply;
	}

	/** The command's reply code, after checking NOOP then gets its own 200 */
	int one(String command) throws IOException {
		return code(oneReply(command));
	}

	/** PASV and connect */
	Socket pasv() throws IOException {
		Matcher m = PASV.matcher(send("PASV").get(0));
		assertTrue(m.find());
		Socket data = new Socket();
		data.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6))), 5000);
		data.setSoTimeout(10000);
		return data;
	}

	/** PASV, then the command, which must open the data connection (1xx); returns all it sends, after its 226 */
	byte[] receive(String command) throws IOException {
		Socket data = pasv();
		List<String> r = send(command);
		assertEquals(1, code(r) / 100, command + " -> " + r);
		byte[] ret = drain(data);
		assertEquals(226, code(read()), "after " + command);
		return ret;
	}

	/** PASV, then the command, which must open the data connection (1xx); sends the data; returns the 1xx line */
	String sendData(String command, byte[] data) throws IOException {
		String prelim;
		try (Socket d = pasv()) {
			List<String> r = send(command);
			assertEquals(1, code(r) / 100, command + " -> " + r);
			prelim = r.get(0);
			d.getOutputStream().write(data);
			d.getOutputStream().flush();
		}
		assertEquals(226, code(read()), "after " + command);
		return prelim;
	}

	static byte[] drain(Socket data) throws IOException {
		try (Socket d = data; InputStream in = d.getInputStream()) {
			return in.readAllBytes();
		}
	}

	static int code(List<String> reply) {
		return Integer.parseInt(reply.get(0).substring(0, 3));
	}

	@Override
	public void close() throws IOException {
		plain.close();
	}
}
