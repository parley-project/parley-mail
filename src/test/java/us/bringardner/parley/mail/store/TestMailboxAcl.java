package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;

/** Mailbox ACLs (RFC 4314): the .imap-acl file, the rights calculation and what CREATE, RENAME and DELETE do. */
public class TestMailboxAcl {

	private static final String TONY = "tony@bringardner.us";
	private static final String BOB = "bob@bringardner.us";
	private static final List<String> NONE = Collections.emptyList();

	private FileSource root;
	private LockDomain domain;
	private MailStore store;

	@BeforeEach
	public void setUp() throws Exception {
		root = SharedStoreSupport.newRoot();
		domain = SharedStoreSupport.server(root, "a");
		MailboxRegistry.get().useLocks(domain);
		store = new MailStore(root.getChild("tony"), MailboxRegistry.get(), TONY);
		store.init(true);
	}

	@AfterEach
	public void tearDown() {
		MailboxRegistry.get().stopUsingLocks(domain);
	}

	@Test
	public void rightsAreCanonical() {
		assertEquals("lrswipkxtea", MailboxAcl.canonical("aetxkpiwsrl"));
		assertEquals("lr", MailboxAcl.canonical("rlrl"));
		assertEquals("kxte", MailboxAcl.canonical("cd"), "obsolete c is k, d is xte");
		assertThrows(IllegalArgumentException.class, () -> MailboxAcl.canonical("R"), "upper case isn't a right");
		assertThrows(IllegalArgumentException.class, () -> MailboxAcl.canonical("lz"));
		assertEquals("lrswipkxteacd", MailboxAcl.withObsolete("lrswipkxtea"));
		assertEquals("lr", MailboxAcl.withObsolete("lr"));
		assertEquals("lrt" + "d", MailboxAcl.withObsolete("lrt"));

		assertEquals("lrw", MailboxAcl.modify("lr", "+w"));
		assertEquals("l", MailboxAcl.modify("lr", "-r"));
		assertEquals("ie", MailboxAcl.modify("lr", "ie"));
		assertEquals("lr", MailboxAcl.modify(null, "rl"));

		assertEquals("", MailboxAcl.limit("lrswi", false, true), "no READ: nothing");
		assertEquals("lr", MailboxAcl.limit("lrswi", true, false), "no WRITE: read only");
		assertEquals("lrswi", MailboxAcl.limit("lrswi", true, true));
	}

	@Test
	public void rightsComeFromUsersGroupsAndAnyoneLessNegativeEntries() {
		MailboxAcl acl = MailboxAcl.empty()
				.with(BOB, "lr")
				.with("$sales@bringardner.us", "lrsw")
				.with("anyone", "l")
				.with("-" + BOB, "w");
		List<String> sales = Arrays.asList("sales@bringardner.us");
		assertEquals("lrs", MailboxAcl.rightsOf(acl, TONY, BOB, sales), "w taken away from bob, whatever gives it");
		assertEquals("lrsw", MailboxAcl.rightsOf(acl, TONY, "carol@bringardner.us", sales));
		assertEquals("l", MailboxAcl.rightsOf(acl, TONY, "dave@other.com", NONE), "anyone is every user, any domain");
		assertEquals("la", MailboxAcl.rightsOf(acl, TONY, TONY, NONE), "the owner always keeps l and a");
		assertEquals("lrswipkxtea", MailboxAcl.rightsOf(null, TONY, TONY, NONE), "no file: the owner's default");
		assertEquals("", MailboxAcl.rightsOf(null, TONY, BOB, NONE), "no file: no one else");
		assertEquals("", MailboxAcl.rightsOf(acl, TONY, null, NONE), "anyone is a logged in user");

		assertThrows(IllegalArgumentException.class, () -> MailboxAcl.empty().with("a\tb", "l"));
		assertThrows(IllegalArgumentException.class, () -> MailboxAcl.empty().with("-", "l"));
		assertThrows(IllegalArgumentException.class, () -> MailboxAcl.empty().with("$", "l"));
		assertNull(MailboxAcl.empty().with(BOB, "").get(BOB), "no rights: no entry");
	}

	@Test
	public void setAndDeleteWriteTheFile() throws Exception {
		assertNull(store.getAcl("INBOX"), "no file until the first SETACL");
		assertEquals("lrswipkxtea", store.rightsOf("INBOX", TONY, NONE));
		assertEquals("", store.rightsOf("INBOX", BOB, NONE));

		assertEquals("lr", store.setRights("INBOX", BOB, "lr"));
		assertEquals("lrw", store.setRights("INBOX", BOB, "+w"));
		assertEquals("l", store.setRights("INBOX", "anyone", "l"));
		assertEquals("i", store.setRights("INBOX", "-" + BOB, "i"));
		FileSource file = store.getInboxDirectory().getChild(MailboxAcl.FILE);
		assertTrue(file.exists());
		assertEquals(Arrays.asList(TONY + "\tlrswipkxtea", BOB + "\tlrw", "anyone\tl", "-" + BOB + "\ti"), lines(file),
				"one line per identifier, the owner's default written first");

		MailboxAcl acl = store.getAcl("INBOX");
		assertEquals("lrw", acl.get(BOB));
		assertEquals("lrw", store.rightsOf("INBOX", BOB, NONE));

		assertTrue(store.deleteRights("INBOX", BOB));
		assertFalse(store.deleteRights("INBOX", BOB));
		assertEquals("i", store.getAcl("INBOX").get("-" + BOB), "DELETEACL bob leaves -bob");
		assertEquals("l", store.rightsOf("INBOX", BOB, NONE));

		// the owner can't lock themselves out
		store.setRights("INBOX", TONY, "");
		assertEquals("la", store.rightsOf("INBOX", TONY, NONE));

		assertThrows(MailStore.StoreException.class, () -> store.setRights("Nope", BOB, "l"));
		assertThrows(IllegalArgumentException.class, () -> store.setRights("INBOX", BOB, "Z"));
		assertEquals("", store.rightsOf("Nope", TONY, NONE));
	}

	@Test
	public void createCopiesTheParentsAndGivesTheCreatorEverything() throws Exception {
		store.create("Projects", null);
		assertNull(store.getAcl("Projects"), "the owner under a parent with no file: none");
		store.setRights("Projects", BOB, "lrk");
		store.create("Projects/Q1", null, BOB);
		MailboxAcl q1 = store.getAcl("Projects/Q1");
		assertNotNull(q1);
		assertEquals("lrswipkxtea", q1.get(TONY));
		assertEquals("lrswipkxtea", q1.get(BOB), "the creator gets every right");

		store.create("Top", null, BOB);
		assertEquals("lrswipkxtea", store.getAcl("Top").get(BOB), "another user's CREATE writes a file");
		assertEquals("lrswipkxtea", store.getAcl("Top").get(TONY));

		store.create("Projects/Q2", null);
		assertEquals("lrk", store.getAcl("Projects/Q2").get(BOB), "copied from the parent");
	}

	@Test
	public void renameMovesItAndDeleteRemovesIt() throws Exception {
		store.create("A", null);
		store.create("A/B", null);
		store.setRights("A", BOB, "lr");
		store.setRights("A/B", BOB, "lrs");
		store.rename("A", "C");
		assertEquals("lr", store.getAcl("C").get(BOB));
		assertEquals("lrs", store.getAcl("C/B").get(BOB), "children keep theirs");
		assertThrows(MailStore.StoreException.class, () -> store.getAcl("A"));

		store.setRights("INBOX", BOB, "l");
		store.rename("INBOX", "Old");
		assertEquals("l", store.getAcl("Old").get(BOB), "renaming INBOX copies its ACL");
		assertEquals("l", store.getAcl("INBOX").get(BOB), "and INBOX keeps it");

		store.delete("C");
		assertNull(store.getAcl("C"), "a parent that stays (\\Noselect) loses its ACL");
		store.delete("C/B");
		store.create("C/B", null);
		assertNull(store.getAcl("C/B"), "a mailbox made again has no ACL left");
	}

	private static List<String> lines(FileSource f) throws Exception {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
			return Arrays.asList(r.lines().toArray(String[]::new));
		}
	}
}
