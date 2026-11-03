package us.bringardner.parley.mail.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.LaunchSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;

/**
 * FTP in the mail suite: the launcher runs it from parley-mail.properties with the mail users, the
 * local domains in the queue's settings and the mail store.
 */
public class TestMailSuiteFtp {

	private static final String[] CLEARED = { "parley.mail.ftp.port", "parley.mail.ftps.port", "parley.mail.users", "parley.mail.root",
			"parley.mail.ftp.requireTls", "parley.mail.ftp.authenticationProvider" };

	@TempDir
	File dir;

	private final Map<String, String> saved = new HashMap<>();
	private ServerLauncher launcher;

	@BeforeEach
	public void clear() {
		for (String n : CLEARED) {
			saved.put(n, System.getProperty(n));
			System.clearProperty(n);
		}
	}

	@AfterEach
	public void restore() {
		if (launcher != null) {
			launcher.close();
		}
		for (String n : CLEARED) {
			System.clearProperty(n);
			if (saved.get(n) != null) {
				System.setProperty(n, saved.get(n));
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void write(File f, String... lines) throws Exception {
		f.getParentFile().mkdirs();
		// (a .properties file reads \ as an escape: the paths in it have / on Windows)
		Files.write(f.toPath(), (String.join("\n", lines).replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
	}

	/** A suite with the domain example.com and the users tony@example.com and bob@other.org */
	private int suite(String... ftpSettings) throws Exception {
		int port = freePort();
		File mail = new File(dir, "mail");
		MailLocks.writeStoreSettings(FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()), Sharing.OFF);
		write(new File(mail, ".smtp-queue/queue.properties"), "hostname=mx.example.com", "domains=example.com");
		write(new File(dir, "users.txt"), "tony@example.com , secret , READ|WRITE", "bob@other.org , pw , READ|WRITE",
				"ro@example.com , pw , READ");
		write(new File(dir, "parley-mail.properties"), "parley.mail.root=" + mail.getPath(),
				"parley.mail.users=" + new File(dir, "users.txt").getPath());
		String[] own = new String[ftpSettings.length + 1];
		own[0] = "parley.mail.ftp.port=" + port;
		System.arraycopy(ftpSettings, 0, own, 1, ftpSettings.length);
		write(new File(dir, "parley-mail-ftp.properties"), own);
		launcher = new ServerLauncher("FtpTest", new LaunchSettings(new Properties()), Arrays.asList("ftp", "ftps"), dir, new ServerContext());
		launcher.init();
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("ftp"));
		return port;
	}

	@Test
	public void providersFound() {
		assertTrue(ServerLauncher.providers().keySet().containsAll(Arrays.asList("ftp", "ftps")), ServerLauncher.providers().keySet().toString());
	}

	@Test
	public void ftpsIsOffWithoutAPort() throws Exception {
		suite("parley.mail.ftp.requireTls=false");
		assertEquals(ServerLauncher.State.OFF, launcher.getState("ftps"));
	}

	@Test
	public void aMailUserLogsInAndGetsAFolderInTheStore() throws Exception {
		int port = suite("parley.mail.ftp.requireTls=false");
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(331, RawFtp.code(ftp.send("USER tony@example.com")));
			assertEquals(230, RawFtp.code(ftp.send("PASS secret")));
			assertTrue(ftp.send("PWD").get(0).contains("\"/\""), "the user's folder is the root");
			byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
			ftp.sendData("STOR a.txt", data);
			assertArrayEquals(data, ftp.receive("RETR a.txt"));
		}
		File stored = new File(dir, "mail/.ftp/example.com/tony/a.txt");
		assertTrue(stored.isFile(), "in the mail store, under the domain and the user: " + stored);
	}

	@Test
	public void theUsersPermissionsApply() throws Exception {
		int port = suite("parley.mail.ftp.requireTls=false");
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(331, RawFtp.code(ftp.send("USER ro@example.com")));
			assertEquals(230, RawFtp.code(ftp.send("PASS pw")));
			List<String> r = ftp.send("MKD sub");
			assertTrue(RawFtp.code(r) >= 500, "READ only can't make folders: " + r);
		}
		assertFalse(new File(dir, "mail/.ftp/example.com/ro/sub").exists());
	}

	@Test
	public void aDomainThatIsNotLocalIsRefused() throws Exception {
		int port = suite("parley.mail.ftp.requireTls=false");
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(331, RawFtp.code(ftp.send("USER bob@other.org")));
			assertEquals(530, RawFtp.code(ftp.send("PASS pw")), "bob is a user, but other.org is not a local domain");
		}
	}

	@Test
	public void aNameWithoutADomainIsRefused() throws Exception {
		int port = suite("parley.mail.ftp.requireTls=false");
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(331, RawFtp.code(ftp.send("USER tony")));
			assertEquals(530, RawFtp.code(ftp.send("PASS secret")));
		}
	}

	@Test
	public void aWrongPasswordIsRefused() throws Exception {
		int port = suite("parley.mail.ftp.requireTls=false");
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(331, RawFtp.code(ftp.send("USER tony@example.com")));
			assertEquals(530, RawFtp.code(ftp.send("PASS wrong")));
		}
	}

	@Test
	public void noLoginWithoutTlsByDefault() throws Exception {
		int port = suite();
		try (RawFtp ftp = new RawFtp(port)) {
			List<String> r = ftp.send("USER tony@example.com");
			assertEquals(530, RawFtp.code(r));
			assertTrue(r.get(0).contains("TLS"), r.get(0));
		}
	}

	@Test
	public void theDomainsAreReadAgain() throws Exception {
		int port = suite("parley.mail.ftp.requireTls=false");
		write(new File(dir, "mail/.smtp-queue/queue.properties"), "hostname=mx.example.com", "domains=example.com,other.org");
		Thread.sleep(5200); // the list is checked every 5 seconds
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(331, RawFtp.code(ftp.send("USER bob@other.org")));
			assertEquals(230, RawFtp.code(ftp.send("PASS pw")), "other.org was added to the domains");
		}
	}
}
