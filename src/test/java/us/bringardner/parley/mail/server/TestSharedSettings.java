package us.bringardner.parley.mail.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.lock.StoreClaimException;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.net.server.FileUserRegistry;

/**
 * The settings the mail servers share: each server reads the users file into a registry of its own (an
 * application may give servers one registry instead), a server with its own registry (for its prefix, also
 * after the prefix changes), and the hints a refused store claim gets.
 */
public class TestSharedSettings {

	/** A mail server that is only made, never started */
	static class Plain extends AbstractMailServer {
		Plain(String prefix) {
			super(0, "Plain", prefix, false);
		}

		@Override
		protected String getRootProperty() {
			return MAIL_ROOT_PROP;
		}

		void prefix(String prefix) {
			setPropertyPrefix(prefix);
		}
	}

	@TempDir
	File dir;

	@AfterEach
	public void clear() {
		for (String p : new String[] { AbstractMailServer.MAIL_USERS_PROP, "parley.mail.own.authenticationProvider",
				"parley.mail.own.userFile", "parley.mail.sharedStore" }) {
			System.clearProperty(p);
		}
	}

	private File file(String name, String text) throws IOException {
		File f = new File(dir, name);
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return f;
	}

	@Test
	public void eachServerReadsTheUsersFileAndCanBeGivenAList() throws Exception {
		File users = file("users.txt", "tony,secret,READ|WRITE\n");
		System.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		Plain a = new Plain("parley.mail.a");
		Plain b = new Plain("parley.mail.b");
		UserRegistry acl = a.getUserRegistry();
		assertNotNull(acl);
		assertNotNull(acl.findPrincipal("tony"));
		assertNotSame(acl, b.getUserRegistry(), "a registry of its own");
		assertEquals(FileSource.of(users.getCanonicalFile()), a.getUsersFile(), "the file it read, for an application that shares one list");

		// an application running both gives them one registry
		b.setUserRegistry(acl);
		assertSame(acl, b.getUserRegistry());
	}

	/** A registry the application gave is final: the users file a setting names, read when the server starts, doesn't replace it */
	@Test
	public void aRegistryTheApplicationGaveIsNotReplacedByTheSettings() throws Exception {
		File users = file("users.txt", "tony,secret,READ|WRITE\n");
		FileUserRegistry given = new FileUserRegistry();
		given.initialize(FileSource.of(file("given.txt", "jose,other,READ\n").getCanonicalFile()));
		try {
			Plain a = new Plain("parley.mail.a");
			a.setUserRegistry(given);
			System.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
			a.prefix("parley.mail.b"); // the shared settings are chosen again
			assertSame(given, a.getUserRegistry(), "a new prefix doesn't replace it");
			a.settingsRead(true); // the start has read the settings file
			assertSame(given, a.getUserRegistry(), "the users setting doesn't replace it");
			assertNull(a.getUsersFile());
		} finally {
			given.close();
		}
	}

	@Test
	public void aServerWithItsOwnUsersKeepsThemAlsoAfterItsPrefixChanges() throws Exception {
		System.setProperty(AbstractMailServer.MAIL_USERS_PROP, file("users.txt", "tony,secret,READ\n").getPath());
		System.setProperty("parley.mail.own.authenticationProvider", FileUserRegistry.class.getName());
		System.setProperty("parley.mail.own.userFile", file("own.txt", "jose,other,READ\n").getPath());
		Plain shared = new Plain("parley.mail.a");
		Plain s = new Plain("parley.mail.a");
		s.prefix("parley.mail.own"); // as a submission server's prefix is set after it's made
		UserRegistry acl = s.getUserRegistry();
		assertNotSame(shared.getUserRegistry(), acl);
		assertNotNull(acl.findPrincipal("jose"));
		assertNull(acl.findPrincipal("tony"));
		assertNull(s.getUsersFile(), "its own registry isn't the users file's");
	}

	@Test
	public void aRefusedStoreSaysWhatToShare() {
		IOException plain = new IOException("disk");
		assertSame(plain, AbstractMailServer.withSharingHint(plain, Sharing.OFF));

		IOException off = AbstractMailServer.withSharingHint(new StoreClaimException("The store /mail is in use by another server"), Sharing.OFF);
		assertTrue(off.getMessage().startsWith("The store /mail is in use by another server"), off.getMessage());
		assertTrue(off.getMessage().contains(".parley-store.properties") && off.getMessage().contains("sharing=host"), off.getMessage());

		IOException on = AbstractMailServer.withSharingHint(new StoreClaimException("The store /mail is claimed by a server that doesn't share it"), Sharing.HOST);
		assertTrue(on.getMessage().contains("doesn't share it"), on.getMessage());
	}

	@Test
	public void aServerWithoutAMaildropRootDoesntStart() {
		Plain p = new Plain("parley.mail.a");
		java.io.UncheckedIOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, p::start);
		assertTrue(e.getMessage().contains(AbstractMailServer.MAIL_ROOT_PROP), e.getMessage());
	}

	/** A refused start gives back what it took: the store's lock domain and claim. */
	@Test
	public void aRefusedStartLeavesNothingBehind() throws Exception {
		us.bringardner.parley.files.FileSource root = us.bringardner.parley.files.FileSourceFactory.getDefaultFactory()
				.createFileSource(new File(dir, "store").getPath());
		us.bringardner.parley.mail.store.MailLocks.writeStoreSettings(root, Sharing.OFF);
		Plain first = new Plain("parley.mail.a");
		first.setMaildropRoot(root);
		Plain second = new Plain("parley.mail.b");
		second.setMaildropRoot(root);
		first.start();
		try {
			java.io.UncheckedIOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, second::start,
					"sharing off: one server at a time, in this JVM too");
			assertTrue(e.getMessage().contains("in use by another server"), e.getMessage());
		} finally {
			first.stop();
		}
		root.getChild("tony").mkdirs();
		assertNull(us.bringardner.parley.mail.store.MailboxRegistry.get().domainOf(root.getChild("tony")),
				"the refused server let go of the store's lock domain too");
		second.start(); // and the store is free
		second.stop();
	}

	@Test
	public void theFileSystemsConnectionSettingsComeFromTheFilesTheServerRead() throws Exception {
		File config = file("plain.properties", "parley.mail.fileSource.url=jdbc:mysql://db/store\n"
				+ "parley.mail.fileSource.user=fromFile\n");
		System.setProperty("parley.mail.plain.configFile", config.getPath());
		System.setProperty("parley.mail.fileSource.user", "fromSystem");
		try {
			Plain p = new Plain("parley.mail.plain");
			p.prepareToStart();
			java.util.Properties c = p.connectionSettings("parley.mail.fileSource.");
			assertEquals("jdbc:mysql://db/store", c.getProperty("url"), "from the file");
			assertEquals("fromSystem", c.getProperty("user"), "a -D setting wins");
		} finally {
			System.clearProperty("parley.mail.plain.configFile");
			System.clearProperty("parley.mail.fileSource.user");
			us.bringardner.parley.core.BaseObject.clearSettings();
		}
	}
}
