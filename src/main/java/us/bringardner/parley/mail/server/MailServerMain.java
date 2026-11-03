package us.bringardner.parley.mail.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import us.bringardner.parley.mail.store.MailStoreAdmin;
import us.bringardner.parley.mail.store.MailStoreAdminMXBean;
import us.bringardner.parley.net.server.IBaseServer;
import us.bringardner.parley.net.server.ParleyConnection;
import us.bringardner.parley.net.server.ServerMain;

/**
 * A mail server run by a server's own main (parley-net's {@link ServerMain#run}): while it runs, its
 * store's administration ({@link AbstractMailServer#getStoreAdmin()}) is the management bean
 * {@code us.bringardner.parley:type=MailStore,name=NAME} on the platform MBean server, so the
 * store's mailbox ACLs can be managed over JMX however the server is run.
 *
 * @since 2.0.0
 */
public final class MailServerMain {

	private MailServerMain() {
	}

	/**
	 * @param server the mail server
	 * @param name for the messages, e.g. "IMAP server"
	 * @param beanName the store bean's name, e.g. {@code imap} or {@code imaps}
	 */
	public static ServerMain.Service service(AbstractMailServer server, String name, String beanName) {
		return service(server, name, beanName, ManagementFactory.getPlatformMBeanServer());
	}

	/** As {@link #service(AbstractMailServer, String, String)}, on this MBean server */
	public static ServerMain.Service service(AbstractMailServer server, String name, String beanName, MBeanServer mbeans) {
		ServerMain.Service s = ServerMain.service(server, name);
		return new ServerMain.Service() {
			private ObjectName bean;

			@Override
			public String name() {
				return s.name();
			}

			@Override
			public void startAndWait(long timeoutMillis) throws IOException {
				s.startAndWait(timeoutMillis);
				register();
			}

			private synchronized void register() {
				MailStoreAdmin admin = server.getStoreAdmin();
				if (bean != null || admin == null) {
					return;
				}
				try {
					ObjectName n = new ObjectName(IBaseServer.JMX_DOMAIN + ":type=" + MailStoreAdminMXBean.TYPE + ",name="
							+ (beanName.matches("[A-Za-z0-9_.\\-]+") ? beanName : ObjectName.quote(beanName)));
					mbeans.registerMBean(admin, n);
					bean = n;
				} catch (JMException | RuntimeException e) {
					System.err.println("Can't register the mail store's JMX bean for " + name + ": " + e);
				}
			}

			private synchronized void unregister() {
				if (bean != null) {
					try {
						if (mbeans.isRegistered(bean)) {
							mbeans.unregisterMBean(bean);
						}
					} catch (JMException e) {
						// gone already
					}
					bean = null;
				}
			}

			@Override
			public int port() {
				return s.port();
			}

			@Override
			public void stop(long timeoutMillis) throws InterruptedException {
				// first, so no change through the store's bean runs once the server gives the store up
				unregister();
				s.stop(timeoutMillis);
			}

			@Override
			public void awaitStop() throws InterruptedException {
				s.awaitStop();
				unregister();
			}

			@Override
			public int connections() {
				return s.connections();
			}

			@Override
			public List<ParleyConnection> connectionList() {
				return s.connectionList();
			}
		};
	}
}
