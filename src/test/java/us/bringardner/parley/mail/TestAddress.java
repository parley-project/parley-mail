package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;


public class TestAddress  {

		
	@Test()
	public void testAdress01() throws IOException {
		Address addr = Address.parseAddress("tony@bringardner.us");
		assertEquals("tony", addr.getUser());
		assertEquals("bringardner.us", addr.getDomain());
		assertNull(addr.getDisplayName());

		addr = Address.parseAddress("<tony@bringardner.us>");
		assertEquals("tony", addr.getUser());
		assertEquals("bringardner.us", addr.getDomain());
		assertNull(addr.getDisplayName());

		addr = Address.parseAddress("Tony Bringardner <tony@bringardner.us>");
		assertEquals("tony", addr.getUser());
		assertEquals("bringardner.us", addr.getDomain());
		assertEquals("Tony Bringardner", addr.getDisplayName());

	}
	
	@Test()
	public void testAdress02() throws IOException {
		class Expect{
			public Expect(String address, String display, String user, String domain) {
				this.address = address;
				this.display = display;
				this.user = user;
				this.domain = domain;
			}
			
			String address;
			String display;
			String user;
			String domain;
		}
		
		Expect expect[] = {
				new Expect("tony@bringardner.us",null,"tony","bringardner.us"),
				new Expect("<John.Smith@USC-ISI.ARPA>", null, "John.Smith", "USC-ISI.ARPA"),				
				new Expect("<Smith@Alpha.ARPA>", null, "Smith", "Alpha.ARPA"),
				new Expect("Smith@Alpha.ARPA", null, "Smith", "Alpha.ARPA"),
				new Expect("Smith@125.321.1.34", null, "Smith", "125.321.1.34"),
				new Expect("Samual Bartimus Smith <Smith@125.321.1.34>", "Samual Bartimus Smith", "Smith", "125.321.1.34"),
		};
		
		for (int idx = 0; idx < expect.length; idx++) {
			Expect e = expect[idx];
			Address addr = Address.parseAddress(e.address);
			assertEquals(e.display, addr.getDisplayName());
			assertEquals(e.user, addr.getUser());
			assertEquals(e.domain, addr.getDomain());
		}
	}
	
	@Test()
	public void testAddressConstructor() {
		// previously the String constructor ignored its argument
		Address addr = new Address("Tony Bringardner <tony@bringardner.us>");
		assertEquals("tony", addr.getUser());
		assertEquals("bringardner.us", addr.getDomain());
		assertEquals("Tony Bringardner", addr.getDisplayName());
	}
	
	@Test()
	public void testQuotedLocalPart() {
		// previously split on the first '@', giving a user of "a
		Address addr = Address.parseAddress("\"a@b\"@example.com");
		assertEquals("\"a@b\"", addr.getUser());
		assertEquals("example.com", addr.getDomain());
	}
}
