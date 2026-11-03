package us.bringardner.parley.mail.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.LaunchSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;

/**
 * FtpAcl: who may log in to the suite's FTP (loginPermission) and where their root is
 * (primaryRoot for the primary domain's users, domainRoot for the others').
 */
public class TestFtpAcl {

	@TempDir
	File dir;

	private ServerLauncher launcher;

	@AfterEach
	public void stop() {
		if (launcher != null) {
			launcher.close();
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void write(File f, String... lines) throws Exception {
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), (String.join("\n", lines).replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
	}

	private File home() {
		return new File(dir, "data/home");
	}

	/** The domains example.com (primary) and other.org, an admin and a user in each */
	private int suite(String... ftpSettings) throws Exception {
		int port = freePort();
		File mail = new File(dir, "mail");
		MailLocks.writeStoreSettings(FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()), Sharing.OFF);
		write(new File(mail, ".smtp-queue/queue.properties"), "hostname=mx.example.com", "domains=example.com,other.org");
		write(new File(dir, "users.txt"), "boss@example.com , pw , READ|WRITE|ADMIN", "tony@example.com , pw , READ|WRITE",
				"chief@other.org , pw , READ|WRITE|ADMIN", "bob@other.org , pw , READ|WRITE");
		write(new File(dir, "parley-mail.properties"), "parley.mail.root=" + mail.getPath(),
				"parley.mail.users=" + new File(dir, "users.txt").getPath(), "parley.mail.admin.primaryDomain=example.com");
		String[] own = new String[ftpSettings.length + 2];
		own[0] = "parley.mail.ftp.port=" + port;
		own[1] = "parley.mail.ftp.requireTls=false";
		System.arraycopy(ftpSettings, 0, own, 2, ftpSettings.length);
		write(new File(dir, "parley-mail-ftp.properties"), own);
		launcher = new ServerLauncher("FtpAclTest", new LaunchSettings(new Properties()), Arrays.asList("ftp"), dir, new ServerContext());
		launcher.init();
		launcher.startAll();
		return port;
	}

	private int adminsOnDisk() throws Exception {
		return suite("parley.mail.ftp.loginPermission=ADMIN", "parley.mail.ftp.primaryRoot=" + home().getPath(),
				"parley.mail.ftp.domainRoot=" + home().getPath() + "/{domain}");
	}

	private static int login(RawFtp ftp, String user) throws Exception {
		assertEquals(331, RawFtp.code(ftp.send("USER " + user)));
		return RawFtp.code(ftp.send("PASS pw"));
	}

	@Test
	public void onlyUsersWithTheLoginPermissionLogIn() throws Exception {
		int port = adminsOnDisk();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("ftp"));
		for (String user : List.of("tony@example.com", "bob@other.org")) {
			try (RawFtp ftp = new RawFtp(port)) {
				assertEquals(530, login(ftp, user), user + " has no ADMIN");
			}
		}
		for (String user : List.of("boss@example.com", "chief@other.org")) {
			try (RawFtp ftp = new RawFtp(port)) {
				assertEquals(230, login(ftp, user), user);
			}
		}
	}

	@Test
	public void thePrimaryDomainsAdminIsRootedAtPrimaryRoot() throws Exception {
		int port = adminsOnDisk();
		assertTrue(!home().exists(), "made on the first login");
		byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(230, login(ftp, "boss@example.com"));
			assertTrue(ftp.send("PWD").get(0).contains("\"/\""));
			ftp.sendData("STOR a.txt", data);
		}
		assertArrayEquals(data, Files.readAllBytes(new File(home(), "a.txt").toPath()));
	}

	@Test
	public void anotherDomainsAdminIsRootedInItsDomainsFolder() throws Exception {
		int port = adminsOnDisk();
		byte[] data = "other".getBytes(StandardCharsets.UTF_8);
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(230, login(ftp, "chief@other.org"));
			ftp.sendData("STOR b.txt", data);
			ftp.send("CWD ..");
			ftp.sendData("STOR c.txt", data);
		}
		assertArrayEquals(data, Files.readAllBytes(new File(home(), "other.org/b.txt").toPath()));
		assertTrue(new File(home(), "other.org/c.txt").isFile(), ".. at the root stays at the root");
		assertTrue(!new File(home(), "c.txt").exists());
	}

	@Test
	public void thePrimaryAdminSeesTheOtherDomainsFolders() throws Exception {
		int port = adminsOnDisk();
		new File(home(), "other.org").mkdirs();
		Files.write(new File(home(), "other.org/x.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(230, login(ftp, "boss@example.com"));
			assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), ftp.receive("RETR other.org/x.txt"));
		}
	}

	@Test
	public void withoutTheSettingsEveryUserLogsInToTheStore() throws Exception {
		int port = suite();
		try (RawFtp ftp = new RawFtp(port)) {
			assertEquals(230, login(ftp, "tony@example.com"));
			ftp.sendData("STOR a.txt", "t".getBytes(StandardCharsets.UTF_8));
		}
		assertTrue(new File(dir, "mail/.ftp/example.com/tony/a.txt").isFile());
		assertTrue(!home().exists());
	}

	@Test
	public void oneRootWithoutTheOtherDoesNotStart() throws Exception {
		Exception e = assertThrows(Exception.class, () -> suite("parley.mail.ftp.primaryRoot=" + home().getPath()));
		assertTrue(e.getMessage().contains("go together"), e.getMessage());
		assertNotEquals(ServerLauncher.State.RUNNING, launcher.getState("ftp"));
	}
}
