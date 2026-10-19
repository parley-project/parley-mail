package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.Sharing;

/** One domain per maildrop root, shared by the servers of a JVM and registered for its mailboxes. */
public class TestMailLocks {

	@Test
	public void serversOfOneJvmShareTheirStoresDomain() throws Exception {
		FileSource root = SharedStoreSupport.newRoot();
		LockDomain imap = MailLocks.acquire(root);
		LockDomain pop3 = MailLocks.acquire(root);
		assertSame(imap, pop3);
		FileSource inbox = root.getChild("tony");
		inbox.mkdirs();
		assertSame(imap, MailboxRegistry.get().domainOf(inbox), "its mailboxes lock through it");
		MailLocks.release(imap);
		assertSame(imap, MailboxRegistry.get().domainOf(inbox), "still used by the other server");
		MailLocks.release(pop3);
		assertNull(MailboxRegistry.get().domainOf(inbox), "gone with the last one");
	}

	@Test
	public void theSharingComesFromTheProperty() {
		String before = System.getProperty(MailLocks.SHARED_STORE_PROPERTY);
		try {
			System.clearProperty(MailLocks.SHARED_STORE_PROPERTY);
			assertSame(Sharing.OFF, MailLocks.sharing());
			System.setProperty(MailLocks.SHARED_STORE_PROPERTY, "cluster");
			assertSame(Sharing.CLUSTER, MailLocks.sharing());
		} finally {
			if (before == null) {
				System.clearProperty(MailLocks.SHARED_STORE_PROPERTY);
			} else {
				System.setProperty(MailLocks.SHARED_STORE_PROPERTY, before);
			}
		}
	}

	@Test
	public void lockNamesAreRelativeToTheRoot() throws Exception {
		FileSource root = SharedStoreSupport.newRoot();
		LockDomain d = MailLocks.acquire(root);
		try {
			assertTrue(MailboxRegistry.lockName("mailbox", d, root.getChild("tony").getChild(".mailboxes").getChild("Sent"))
					.equals("mailbox/tony/.mailboxes/Sent"));
		} finally {
			MailLocks.release(d);
		}
	}
}
