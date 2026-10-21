package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.test.MailTestSupport;

/** MailStore.deliver: the one way new mail gets into a store. */
public class TestMailStoreDeliver {

	private FileSource root;
	private MailStore store;

	@BeforeEach
	public void setUp() throws Exception {
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("deliver");
		store = new MailStore(root.getChild("tony"), MailboxRegistry.get());
		store.init(false);
	}

	@AfterEach
	public void tearDown() throws Exception {
		MailTestSupport.deleteAll(root);
	}

	private static InputStream text(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(FileSource f) throws Exception {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void deliversToTheInboxWithCrlfAndAnIndexEntry() throws Exception {
		MessageInfo m = store.deliver(text("Subject: hi\n\nline one\nline two\n"), false);
		assertEquals("Subject: hi\r\n\r\nline one\r\nline two\r\n", read(m.getFile()), "stored with CRLF");
		assertEquals(store.getInboxDirectory().getChild(m.getName()).getAbsolutePath(), m.getFile().getAbsolutePath(),
				"in the INBOX directory, the maildrop POP3 reads");
		assertFalse(m.getName().startsWith("."));
		Mailbox inbox = store.open(MailboxConstants.INBOX);
		try {
			assertEquals(1, inbox.count());
			assertEquals(m.getUid(), inbox.getMessages().get(0).getUid(), "indexed");
			assertTrue(inbox.getMessages().get(0).getFlags(inbox).isEmpty(), "no flags");
		} finally {
			MailboxRegistry.get().release(inbox);
		}
		MessageInfo second = store.deliver(text("Subject: two\r\n\r\nx\r\n"), false);
		assertTrue(second.getName().compareTo(m.getName()) > 0, "name order is delivery order");
		assertTrue(second.getUid() > m.getUid());
	}

	@Test
	public void junkGoesToTheJunkMailboxMadeIfMissing() throws Exception {
		assertFalse(store.exists("Junk"), "init(false) makes no default mailboxes");
		MessageInfo m = store.deliver(text("Subject: spam\r\n\r\nx\r\n"), true);
		assertTrue(store.exists("Junk"));
		assertEquals("Junk", store.junkMailbox());
		Mailbox junk = store.open("Junk");
		try {
			assertEquals(1, junk.count());
			assertEquals(MailboxConstants.JUNK, junk.getSpecialUse(), "marked \\\\Junk");
			assertEquals(m.getUid(), junk.getMessages().get(0).getUid());
		} finally {
			MailboxRegistry.get().release(junk);
		}
		store.deliver(text("Subject: spam 2\r\n\r\nx\r\n"), true);
		Mailbox inbox = store.open(MailboxConstants.INBOX);
		try {
			assertEquals(0, inbox.count(), "junk never reaches INBOX");
		} finally {
			MailboxRegistry.get().release(inbox);
		}
	}

	@Test
	public void junkUsesTheMailboxMarkedJunk() throws Exception {
		store.create("Spam", MailboxConstants.JUNK);
		assertEquals("Spam", store.junkMailbox());
		store.deliver(text("Subject: spam\r\n\r\nx\r\n"), true);
		assertFalse(store.exists("Junk"), "no second Junk mailbox");
	}
}
