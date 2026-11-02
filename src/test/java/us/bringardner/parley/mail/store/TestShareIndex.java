package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;

/** The share index the Other Users namespace's LIST reads: kept up to date by the ACL changes. */
public class TestShareIndex {

	private FileSource root;
	private LockDomain domain;
	private ShareIndex index;
	private MailStore tony;
	private MailStore bob;

	@BeforeEach
	public void setUp() throws Exception {
		root = SharedStoreSupport.newRoot();
		domain = SharedStoreSupport.server(root, "a");
		MailboxRegistry.get().useLocks(domain);
		index = new ShareIndex(root, MailboxRegistry.get());
		tony = new MailStore(root.getChild("tony"), MailboxRegistry.get(), "tony@bringardner.us");
		tony.setShareIndex(index);
		tony.init(true);
		bob = new MailStore(root.getChild("bob"), MailboxRegistry.get(), "bob@bringardner.us");
		bob.setShareIndex(index);
		bob.init(true);
	}

	@AfterEach
	public void tearDown() {
		MailboxRegistry.get().stopUsingLocks(domain);
	}

	@Test
	public void aclChangesKeepTheIndex() throws Exception {
		assertEquals(Collections.emptyList(), index.ownersSharingWith("carol@bringardner.us", null));
		tony.create("Projects", null);
		tony.setRights("Projects", "carol@bringardner.us", "lr");
		bob.setRights("INBOX", "$sales@bringardner.us", "l");
		bob.setRights("INBOX", "-carol@bringardner.us", "r");
		assertTrue(root.getChild(ShareIndex.FILE).exists());

		assertEquals(Arrays.asList("tony@bringardner.us"), index.ownersSharingWith("carol@bringardner.us", null));
		assertEquals(Arrays.asList("bob@bringardner.us", "tony@bringardner.us"),
				index.ownersSharingWith("carol@bringardner.us", Arrays.asList("sales@bringardner.us")));
		assertEquals(Collections.emptyList(), index.ownersSharingWith("tony@bringardner.us", null), "not the owner's own");

		tony.setRights("Archive", "anyone", "l");
		assertEquals(Arrays.asList("tony@bringardner.us"), index.ownersSharingWith("dave@other.com", null), "anyone");

		tony.deleteRights("Archive", "anyone");
		assertEquals(Collections.emptyList(), index.ownersSharingWith("dave@other.com", null));
		tony.delete("Projects");
		assertEquals(Collections.emptyList(), index.ownersSharingWith("carol@bringardner.us", null), "DELETE took it away");

		// a mailbox another user creates is shared with them
		bob.create("ForTony", null, "tony@bringardner.us");
		assertEquals(Arrays.asList("bob@bringardner.us"), index.ownersSharingWith("tony@bringardner.us", null));
	}
}
