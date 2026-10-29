package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.Mode;
import us.bringardner.parley.net.lock.NamedLock;

/**
 * MailStore.init runs for every delivery. Once the store is made it takes no lock: another server that
 * holds the user's tree lock (as it does while it makes or changes the tree) does not hold it up.
 */
public class TestMailStoreInit {

	private FileSource root;
	private LockDomain a;
	private LockDomain b;
	private FileSource inbox;
	private MailStore store;

	@BeforeEach
	public void setUp() throws Exception {
		root = SharedStoreSupport.newRoot();
		a = SharedStoreSupport.server(root, "a");
		b = SharedStoreSupport.server(root, "b");
		inbox = root.getChild("tony");
		MailboxRegistry.get().useLocks(a);
		store = new MailStore(inbox, MailboxRegistry.get());
	}

	@AfterEach
	public void tearDown() {
		MailboxRegistry.get().stopUsingLocks(a);
	}

	/** Server b holds the tree lock of the user, exclusive. */
	private NamedLock holdTree() throws IOException {
		return b.lock(MailboxRegistry.lockName("tree", b, inbox), Mode.EXCLUSIVE, Duration.ofSeconds(5));
	}

	private CompletableFuture<Void> initInBackground(boolean createDefaults) {
		return CompletableFuture.runAsync(() -> {
			try {
				store.init(createDefaults);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	@Test
	public void aStoreThatIsMadeTakesNoTreeLock() throws Exception {
		store.init(true);
		assertTrue(inbox.getChild(MailStore.SUBSCRIPTIONS).exists());
		try (NamedLock held = holdTree()) {
			// (a call that took the lock would wait for the 20 seconds of the domain's timeout)
			initInBackground(true).get(3, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aStoreMadeWithoutDefaultsTakesNoTreeLockEither() throws Exception {
		store.init(false);
		try (NamedLock held = holdTree()) {
			initInBackground(false).get(3, TimeUnit.SECONDS);
		}
	}

	@Test
	public void aStoreNotMadeYetWaitsForTheTreeLockAndThenIsMade() throws Exception {
		CompletableFuture<Void> init;
		try (NamedLock held = holdTree()) {
			init = initInBackground(true);
			assertThrows(TimeoutException.class, () -> init.get(1, TimeUnit.SECONDS), "it waits for the lock");
			assertFalse(inbox.getChild(MailStore.SUBSCRIPTIONS).exists(), "and has made nothing yet");
		}
		init.get(10, TimeUnit.SECONDS);
		assertTrue(inbox.getChild(MailStore.MAILBOXES).isDirectory());
		assertTrue(inbox.getChild(MailStore.SUBSCRIPTIONS).exists());
		for (String[] d : MailStore.DEFAULT_MAILBOXES) {
			assertTrue(store.exists(d[0]), d[0]);
		}
	}

	@Test
	public void defaultsAreMadeOnlyTheFirstTime() throws Exception {
		store.init(false);
		store.init(true);   // the store exists without defaults: it keeps none, as before
		assertFalse(inbox.getChild(MailStore.SUBSCRIPTIONS).exists());
		assertFalse(store.exists("Sent"));
		assertEquals(0, countDefaults());
	}

	private int countDefaults() throws IOException {
		int n = 0;
		for (String[] d : MailStore.DEFAULT_MAILBOXES) {
			if (store.exists(d[0])) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void aSecondInitChangesNothing() throws Exception {
		store.init(true);
		long subscriptions = inbox.getChild(MailStore.SUBSCRIPTIONS).lastModified();
		Thread.sleep(1100);
		store.init(true);
		assertEquals(subscriptions, inbox.getChild(MailStore.SUBSCRIPTIONS).lastModified());
		assertEquals(MailStore.DEFAULT_MAILBOXES.length, countDefaults());
	}
}
