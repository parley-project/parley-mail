package us.bringardner.parley.mail.store;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.files.FileSourceCapabilities;
import us.bringardner.parley.net.lock.Mode;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.lock.spi.LockContext;
import us.bringardner.parley.net.lock.spi.LockHandle;
import us.bringardner.parley.net.lock.spi.LockProvider;
import us.bringardner.parley.net.lock.spi.LockService;

/**
 * For tests: locks (in this JVM) that a test can make lost, as a lease is when its holder was
 * paused and another server took it over.
 */
public final class LosableLocks implements LockProvider {

	/** While true, every holding is lost. */
	static volatile boolean lost;

	@Override
	public String id() {
		return "losable-test";
	}

	@Override
	public Fit fit(FileSourceCapabilities capabilities, Sharing sharing) {
		return Fit.SAFE;
	}

	@Override
	public LockService open(LockContext context) {
		Set<String> held = ConcurrentHashMap.newKeySet();
		return new LockService() {
			@Override
			public LockHandle tryAcquire(String name, Mode mode) {
				if( !held.add(name) ) {
					return null;
				}
				return new LockHandle() {
					@Override
					public boolean isValid() {
						return !lost;
					}

					@Override
					public String toString() {
						return name;
					}
				};
			}

			@Override
			public void release(LockHandle h) {
				held.remove(h.toString());
			}

			@Override
			public void close() {
			}
		};
	}
}
