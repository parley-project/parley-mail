package us.bringardner.parley.mail.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.net.lock.StoreClaimException;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.ServerContext;

/**
 * The settings the mail servers share: one users list per file for the servers of one launcher
 * (not the JVM), a server with its own list (for its prefix, also after the prefix changes), and
 * the hints a refused store claim gets.
 */
public class TestSharedSettings {

	/** A mail server that is only made, never started */
	static class Plain extends AbstractMailServer {
		Plain(String prefix) {
			super(0, "Plain", prefix, false);
			finishInit();
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

	private final ServerContext one = new ServerContext(MBeanServerFactory.newMBeanServer());
	private final ServerContext two = new ServerContext(MBeanServerFactory.newMBeanServer());

	@AfterEach
	public void clear() {
		one.close();
		two.close();
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
	public void theServersOfOneLauncherShareTheUsers() throws Exception {
		File users = file("users.txt", "tony,secret,READ|WRITE\n");
		System.setProperty(AbstractMailServer.MAIL_USERS_PROP, users.getPath());
		Plain a = AbstractMailServer.makeIn(one, () -> new Plain("parley.mail.a"));
		Plain b = AbstractMailServer.makeIn(one, () -> new Plain("parley.mail.b"));
		Plain c = AbstractMailServer.makeIn(two, () -> new Plain("parley.mail.a"));
		IAccessControlList acl = a.getAccessControl();
		assertNotNull(acl);
		assertNotNull(acl.getPrincipal("tony"));
		assertSame(acl, b.getAccessControl());
		assertSame(acl, AbstractMailServer.sharedUsers(one, users));
		assertSame(one, a.getServerContext());
		assertNotSame(acl, c.getAccessControl(), "another launcher has its own");
		assertNull(AbstractMailServer.sharedUsers(new ServerContext(MBeanServerFactory.newMBeanServer()), users));

		Plain byCode = new Plain("parley.mail.a");
		assertNull(byCode.getServerContext());
		assertNotNull(byCode.getAccessControl());
		assertNotSame(acl, byCode.getAccessControl(), "made by code: a list of its own");

		// the application changed the file: the shared list reads it now
		Files.write(users.toPath(), "tony,secret,READ\njose,other,READ\n".getBytes(StandardCharsets.UTF_8));
		users.setLastModified(System.currentTimeMillis() + 5000);
		AbstractMailServer.sharedUsers(one, users).reloadNow();
		assertNotNull(b.getAccessControl().getPrincipal("jose"));
	}

	@Test
	public void aServerWithItsOwnUsersKeepsThemAlsoAfterItsPrefixChanges() throws Exception {
		System.setProperty(AbstractMailServer.MAIL_USERS_PROP, file("users.txt", "tony,secret,READ\n").getPath());
		System.setProperty("parley.mail.own.authenticationProvider", FileBasedAcl.class.getName());
		System.setProperty("parley.mail.own.userFile", file("own.txt", "jose,other,READ\n").getPath());
		Plain shared = AbstractMailServer.makeIn(one, () -> new Plain("parley.mail.a"));
		Plain s = AbstractMailServer.makeIn(one, () -> {
			Plain p = new Plain("parley.mail.a");
			p.prefix("parley.mail.own"); // as a submission server's prefix is set after it's made
			return p;
		});
		IAccessControlList acl = s.getAccessControl();
		assertNotSame(shared.getAccessControl(), acl);
		assertNotNull(acl.getPrincipal("jose"));
		assertNull(acl.getPrincipal("tony"));
	}

	@Test
	public void aRefusedStoreSaysWhatToShare() {
		IOException plain = new IOException("disk");
		assertSame(plain, AbstractMailServer.withSharingHint(plain));

		IOException off = AbstractMailServer.withSharingHint(new StoreClaimException("The store /mail is in use by another server"));
		assertTrue(off.getMessage().startsWith("The store /mail is in use by another server"), off.getMessage());
		assertTrue(off.getMessage().contains("parley.mail.sharedStore=host"), off.getMessage());

		System.setProperty("parley.mail.sharedStore", "host");
		IOException on = AbstractMailServer.withSharingHint(new StoreClaimException("The store /mail is claimed by a server that doesn't share it"));
		assertTrue(on.getMessage().contains("runs with parley.mail.sharedStore=off"), on.getMessage());
		assertTrue(on.getMessage().contains("(host)"), on.getMessage());
	}
}
