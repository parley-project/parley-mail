package us.bringardner.parley.mail.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;
import us.bringardner.parley.net.server.ServerMain;

/**
 * To-Do 2.16 for the suite's FTP servers: every name ftp and ftps read while they start and serve a
 * session (from their configurations) is in a settings list, so a server's start-up check never
 * warns about a real setting.
 */
public class TestFtpKnownSettings {

	@TempDir
	File dir;

	@Test
	public void everyNameTheSuitesServersReadIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		assertTrue(k.prefixes().containsAll(List.of(FtpProviders.FTP_PREFIX, FtpProviders.FTPS_PREFIX)), k.prefixes().toString());
		File mail = new File(dir, "mail");
		MailLocks.writeStoreSettings(FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()), Sharing.OFF);
		File queue = new File(mail, ".smtp-queue/queue.properties");
		queue.getParentFile().mkdirs();
		Files.write(queue.toPath(), "hostname=mx.example.com\ndomains=example.com\n".getBytes(StandardCharsets.UTF_8));
		File users = new File(dir, "users.txt");
		Files.write(users.toPath(), "tony@example.com , secret , READ|WRITE\n".getBytes(StandardCharsets.UTF_8));

		for (String id : List.of("ftp", "ftps")) {
			KnownSettings.RecordingProperties c = new KnownSettings.RecordingProperties();
			c.setProperty("parley.mail.root", mail.getPath());
			c.setProperty("parley.mail.users", users.getPath());
			MailServerProvider p = (MailServerProvider) ServerLauncher.providers().get(id);
			int port = freePort();
			c.setProperty(p.prefix() + ".port", "" + port);
			c.setProperty(p.prefix() + ".requireTls", "false");
			p.enabled(c);
			p.unavailable(c);
			p.warnings(c);
			p.port(c);
			if (!p.implicitTls()) {
				ServerMain.Service s = p.create(new ServerContext(), c);
				s.startAndWait(5000);
				try (RawFtp ftp = new RawFtp(port)) {
					assertEquals(331, RawFtp.code(ftp.send("USER tony@example.com")));
					assertEquals(230, RawFtp.code(ftp.send("PASS secret")));
					ftp.send("PWD");
					ftp.send("FEAT");
					byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
					ftp.sendData("STOR a.txt", data);
					ftp.receive("RETR a.txt");
				} finally {
					s.stop(5000);
				}
			}
			assertEquals(List.of(), k.undeclared(c.asked()), id + " read these");
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
