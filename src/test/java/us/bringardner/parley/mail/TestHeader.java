package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;


public class TestHeader  {

		
	@Test()
	public void testHeader() throws IOException {
		class Expect{
			public Expect(String line, String name, String value) {
				this.line = line;
				this.name = name;
				this.value=value;
			}
			
			String line;
			String name;
			String value;
		}
		
		Expect expect[] = {
				new Expect("Date: 23 Oct 81 11:22:33","Date","23 Oct 81 11:22:33"),
				new Expect("From: SMTP@HOSTY.ARPA","From","SMTP@HOSTY.ARPA"),
				new Expect("To: JOE@HOSTW.ARPA","To","JOE@HOSTW.ARPA"),
				new Expect("Subject: Mail System Problem","Subject","Mail System Problem"),
		};
		
		for (int idx = 0; idx < expect.length; idx++) {
			Expect e = expect[idx];
			Header hdr = Header.parseHeader(e.line);
			assertEquals(e.name, hdr.getName());
			assertEquals(e.value, hdr.getValue());
		}
	}
}
