package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;

/** Two servers sharing one store (each its own Locks, as two JVMs would have). */
public class TestSharedStore {

	private FileSource root;
	private LockDomain a;
	private LockDomain b;
	private FileSource inbox;

	@BeforeEach
	public void setUp() throws Exception {
		root = SharedStoreSupport.newRoot();
		a = SharedStoreSupport.server(root, "a");
		b = SharedStoreSupport.server(root, "b");
		inbox = root.getChild("tony");
	}

	private static Set<Long> uids(Mailbox m) {
		Set<Long> ret = new HashSet<>();
		for (MessageInfo i : m.getMessages()) {
			ret.add(i.getUid());
		}
		return ret;
	}

	@Test
	public void twoServersNeverGiveOneUidTwice() throws Exception {
		Mailbox ma = SharedStoreSupport.open(a, inbox);
		Mailbox mb = SharedStoreSupport.open(b, inbox);
		MessageInfo first = SharedStoreSupport.append(ma, "one");
		MessageInfo second = SharedStoreSupport.append(mb, "two"); // b catches up with a's index first
		assertNotEquals(first.getUid(), second.getUid());
		ma.refresh(true);
		assertEquals(Set.of(first.getUid(), second.getUid()), uids(ma), "a sees b's message");
		assertEquals(uids(ma), uids(mb));
	}

	@Test
	public void manyAppendsFromBothServersAtOnce() throws Exception {
		Mailbox ma = SharedStoreSupport.open(a, inbox);
		Mailbox mb = SharedStoreSupport.open(b, inbox);
		ExecutorService ex = Executors.newFixedThreadPool(4);
		List<Future<Long>> all = new ArrayList<>();
		try {
			for (int i = 0; i < 40; i++) {
				Mailbox m = i % 2 == 0 ? ma : mb;
				int n = i;
				all.add(ex.submit(() -> SharedStoreSupport.append(m, "m" + n).getUid()));
			}
			Set<Long> given = new HashSet<>();
			for (Future<Long> f : all) {
				assertTrue(given.add(f.get(60, TimeUnit.SECONDS)), "a UID given twice");
			}
		} finally {
			ex.shutdownNow();
		}
		ma.refresh(true);
		mb.refresh(true);
		assertEquals(40, ma.count());
		assertEquals(uids(ma), uids(mb));
		assertEquals(40, root.getChild("tony").listFiles(f -> !f.getName().startsWith(".")).length, "no message lost or doubled");
	}

	@Test
	public void flagChangesFromBothServersAreKept() throws Exception {
		Mailbox ma = SharedStoreSupport.open(a, inbox);
		MessageInfo m = SharedStoreSupport.append(ma, "flagged");
		Mailbox mb = SharedStoreSupport.open(b, inbox);
		ma.storeFlags(ma.get(m.getUid()), Mailbox.FlagOp.ADD, Set.of("\\Seen"), null);
		mb.storeFlags(mb.get(m.getUid()), Mailbox.FlagOp.ADD, Set.of("\\Flagged"), null);
		ma.saveIfDirty();
		mb.saveIfDirty(); // replayed on a's save, not over it
		ma.refresh(true);
		assertEquals(Set.of("\\Seen", "\\Flagged"), new HashSet<>(ma.get(m.getUid()).getFlags(ma)));
		Mailbox fresh = SharedStoreSupport.open(SharedStoreSupport.server(root, "c"), inbox);
		assertEquals(Set.of("\\Seen", "\\Flagged"), new HashSet<>(fresh.get(m.getUid()).getFlags(fresh)), "as saved");
	}

	@Test
	public void anotherServersExpungeReachesTheViews() throws Exception {
		Mailbox ma = SharedStoreSupport.open(a, inbox);
		MessageInfo m = SharedStoreSupport.append(ma, "gone");
		MailboxView view = ma.newView(false);
		Mailbox mb = SharedStoreSupport.open(b, inbox);
		mb.expunge(List.of(m.getUid()), null);
		ma.refresh(true);
		assertEquals(List.of(1), view.collectUpdates(true).expunged);
		view.close();
	}

	@Test
	public void aMailboxRecreatedByAnotherServerIsNotTakenForTheOldOne() throws Exception {
		Mailbox ma = SharedStoreSupport.open(a, inbox);
		SharedStoreSupport.append(ma, "old");
		long before = ma.getUidValidity();
		MailboxView view = ma.newView(false);
		// b deletes the mailbox and makes it again: a new UIDVALIDITY
		MailStore.deleteTree(inbox);
		Thread.sleep(1100); // UIDVALIDITY is in seconds
		Mailbox mb = SharedStoreSupport.open(b, inbox);
		SharedStoreSupport.append(mb, "new");
		assertNotEquals(before, mb.getUidValidity());
		ma.refresh(true);
		MailboxView.Updates u = view.collectUpdates(true);
		assertTrue(u.uidValidityChanged, "the session must close it");
		assertEquals(mb.getUidValidity(), ma.getUidValidity(), "and a knows the new one");
		view.close();
	}

	@Test
	public void moveKeepsFlagsAndLeavesOneCopy() throws Exception {
		FileSource archive = inbox.getChild(MailStore.MAILBOXES).getChild("Archive");
		Mailbox src = SharedStoreSupport.open(a, inbox);
		Mailbox dst = SharedStoreSupport.open(a, archive);
		MessageInfo m = SharedStoreSupport.append(src, "keep");
		src.storeFlags(m, Mailbox.FlagOp.ADD, Set.of("\\Flagged"), null);
		List<MessageInfo> moved = src.moveTo(dst, List.of(m), null);
		assertEquals(1, moved.size());
		assertEquals(0, src.count());
		assertTrue(Mailbox.hasFlag(moved.get(0).getFlags(dst), "\\Flagged"), "the flags went with it");
		Mailbox otherSrc = SharedStoreSupport.open(b, inbox);
		Mailbox otherDst = SharedStoreSupport.open(b, archive);
		assertEquals(0, otherSrc.count(), "another server sees it gone from the source");
		assertEquals(1, otherDst.count());
		assertTrue(Mailbox.hasFlag(otherDst.getMessages().get(0).getFlags(otherDst), "\\Flagged"));
	}

	@Test
	public void movesBothWaysAtOnceDontWaitForEachOther() throws Exception {
		FileSource other = inbox.getChild(MailStore.MAILBOXES).getChild("Other");
		Mailbox x = SharedStoreSupport.open(a, inbox);
		Mailbox y = SharedStoreSupport.open(b, other);
		for (int i = 0; i < 10; i++) {
			SharedStoreSupport.append(x, "x" + i);
			SharedStoreSupport.append(y, "y" + i);
		}
		Mailbox yOnA = SharedStoreSupport.open(a, other);
		Mailbox xOnB = SharedStoreSupport.open(b, inbox);
		ExecutorService ex = Executors.newFixedThreadPool(2);
		try {
			Future<?> one = ex.submit(() -> {
				for (int i = 0; i < 5; i++) {
					x.refresh(true);
					List<MessageInfo> l = x.getMessages();
					if (!l.isEmpty()) {
						x.moveTo(yOnA, List.of(l.get(0)), null);
					}
				}
				return null;
			});
			Future<?> two = ex.submit(() -> {
				for (int i = 0; i < 5; i++) {
					y.refresh(true);
					List<MessageInfo> l = y.getMessages();
					if (!l.isEmpty()) {
						y.moveTo(xOnB, List.of(l.get(0)), null);
					}
				}
				return null;
			});
			one.get(120, TimeUnit.SECONDS);
			two.get(120, TimeUnit.SECONDS);
		} finally {
			ex.shutdownNow();
		}
		x.refresh(true);
		yOnA.refresh(true);
		assertEquals(20, x.count() + yOnA.count(), "every message is in exactly one mailbox");
	}

	@Test
	public void aDeletedMailboxIsSeenAsDeleted() throws Exception {
		Mailbox ma = SharedStoreSupport.open(a, inbox);
		SharedStoreSupport.append(ma, "x");
		MailStore.deleteTree(inbox);
		ma.refresh(true);
		assertTrue(ma.isDeleted());
		assertFalse(inbox.exists(), "and not made again by the refresh");
	}
}
