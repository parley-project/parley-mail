package us.bringardner.parley.mail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Sharing;

/** Another JVM appending to the same mailbox at the same time: the OS's locks keep them apart. */
public class TestSharedStoreAcrossProcesses {

	@Test
	public void twoJvmsAppendingAtOnceGiveEveryMessageItsOwnUid() throws Exception {
		FileSource root = SharedStoreSupport.newRoot();
		int n = 25;
		String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		Process worker = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), MailWorker.class.getName(),
				root.getAbsolutePath(), "tony", String.valueOf(n), Sharing.HOST.name()).redirectErrorStream(true).start();
		try (Locks locks = Locks.open(LockOptions.sharing(Sharing.HOST).withOwnerId("me").withTimeout(Duration.ofSeconds(60)))) {
			LockDomain d = locks.domain("mail", root);
			assertEquals("region", d.provider().id(), "local disk, one machine: the OS's locks");
			Mailbox m = SharedStoreSupport.open(d, root.getChild("tony"));
			Set<Long> mine = new HashSet<>();
			for (int i = 0; i < n; i++) {
				assertTrue(mine.add(SharedStoreSupport.append(m, "me " + i).getUid()));
			}
			assertTrue(worker.waitFor(120, TimeUnit.SECONDS), "the worker finished");
			String out = new String(worker.getInputStream().readAllBytes());
			assertTrue(out.contains("DONE"), out);
			m.refresh(true);
			assertEquals(2 * n, m.count(), "every message, each with its own UID");
			Set<Long> all = new HashSet<>();
			for (MessageInfo i : m.getMessages()) {
				all.add(i.getUid());
			}
			assertEquals(2 * n, all.size());
			assertTrue(all.containsAll(mine));
		} finally {
			worker.destroyForcibly();
		}
	}
}
