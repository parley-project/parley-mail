package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;

/** The mail store's administration bean: mailbox ACLs changed by an administrator, and a deleted user forgotten. */
public class TestMailStoreAdmin {

	private static final String TONY = "tony@bringardner.us";
	private static final String BOB = "bob@bringardner.us";

	private FileSource root;
	private LockDomain domain;
	private MailStoreAdmin admin;

	@BeforeEach
	public void setUp() throws Exception {
		root = SharedStoreSupport.newRoot();
		domain = SharedStoreSupport.server(root, "a");
		MailboxRegistry.get().useLocks(domain);
		new MailStore(root.getChild("tony"), MailboxRegistry.get(), TONY).init(true);
		new MailStore(root.getChild("bob"), MailboxRegistry.get(), BOB).init(true);
		admin = new MailStoreAdmin(root, "test root", MailboxRegistry.get());
	}

	@AfterEach
	public void tearDown() {
		MailboxRegistry.get().stopUsingLocks(domain);
	}

	@Test
	public void listsAUsersMailboxes() throws IOException {
		List<String> boxes = admin.listMailboxes(TONY, "tony");
		assertEquals("INBOX", boxes.get(0));
		assertTrue(boxes.size() > 1, "the default mailboxes: " + boxes);
		assertTrue(admin.listMailboxes("nobody@bringardner.us", null).isEmpty(), "no maildrop yet: nothing, and nothing made");
		assertFalse(root.getChild("nobody@bringardner.us").exists());
		assertThrows(IOException.class, () -> admin.listMailboxes(TONY, "../x"), "the maildrop stays under the root");
	}

	@Test
	public void setsAndDeletesRightsAsSetaclDoes() throws IOException {
		assertTrue(admin.getAcl(TONY, "tony", "INBOX").isEmpty(), "no file: the owner's default only");
		assertEquals("lr", admin.setRights(TONY, "tony", "INBOX", BOB, "lr"));
		assertEquals("lrs", admin.setRights(TONY, "tony", "INBOX", BOB, "+s"));
		assertEquals("lrs", admin.getAcl(TONY, "tony", "INBOX").get(BOB));
		assertFalse(admin.getAcl(TONY, "tony", "INBOX").containsKey(TONY), "the owner isn't written unless set");

		MailStore bob = new MailStore(root.getChild("bob"), MailboxRegistry.get(), BOB);
		MailStore tony = new MailStore(root.getChild("tony"), MailboxRegistry.get(), TONY);
		assertEquals("lrs", tony.rightsOf("INBOX", BOB, Collections.emptyList()), "the store reads what the admin wrote");
		assertEquals(Arrays.asList(TONY), new ShareIndex(root, MailboxRegistry.get()).ownersSharingWith(BOB, null), "the share index knows");
		assertTrue(bob.getAcl("INBOX") == null);

		assertTrue(admin.deleteRights(TONY, "tony", "INBOX", BOB));
		assertFalse(admin.deleteRights(TONY, "tony", "INBOX", BOB), "nothing left to delete");
		assertTrue(new ShareIndex(root, MailboxRegistry.get()).ownersSharingWith(BOB, null).isEmpty());

		assertThrows(IllegalArgumentException.class, () -> admin.setRights(TONY, "tony", "INBOX", BOB, "lz"), "not a right");
		IOException e = assertThrows(IOException.class, () -> admin.setRights(TONY, "tony", "No such box", BOB, "l"));
		assertEquals(IOException.class, e.getClass(), "a plain IOException crosses JMX without parley-mail's classes");
	}

	@Test
	public void forgetsADeletedUser() throws IOException {
		admin.setRights(TONY, "tony", "INBOX", BOB, "lr");
		admin.setRights(TONY, "tony", "Sent", "-" + BOB, "r");
		admin.setRights(BOB, "bob", "INBOX", TONY, "l");
		ShareIndex shares = new ShareIndex(root, MailboxRegistry.get());
		assertEquals(Arrays.asList(BOB), shares.ownersSharingWith(TONY, null));

		Map<String, String> drops = new LinkedHashMap<>();
		drops.put(TONY, "tony");
		drops.put(BOB, "bob");
		assertEquals(2, admin.forgetUser(BOB, drops), "bob's entry and negative entry on tony's mailboxes");
		assertTrue(admin.getAcl(TONY, "tony", "INBOX").isEmpty());
		assertTrue(admin.getAcl(TONY, "tony", "Sent").isEmpty());
		assertTrue(shares.ownersSharingWith(TONY, null).isEmpty(), "bob's own lines are gone from the share index");
		assertFalse(shares.read().containsKey(BOB));
	}

	@Test
	public void worksAsAnMXBean() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		ObjectName n = new ObjectName("us.bringardner.parley.test:type=" + MailStoreAdminMXBean.TYPE + ",name=t" + System.nanoTime());
		mbs.registerMBean(admin, n);
		try {
			MailStoreAdminMXBean p = JMX.newMXBeanProxy(mbs, n, MailStoreAdminMXBean.class);
			assertEquals("test root", p.getMaildropRoot());
			assertEquals("INBOX", p.listMailboxes(TONY, "tony").get(0));
			assertEquals("lrw", p.setRights(TONY, "tony", "INBOX", "$sales@bringardner.us", "rwl"));
			assertEquals("lrw", p.getAcl(TONY, "tony", "INBOX").get("$sales@bringardner.us"));
			assertThrows(IllegalArgumentException.class, () -> p.setRights(TONY, "tony", "INBOX", BOB, "Q"));
			assertThrows(IOException.class, () -> p.getAcl(TONY, "tony", "Missing"));
		} finally {
			mbs.unregisterMBean(n);
		}
	}
}
