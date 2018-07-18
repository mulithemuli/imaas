package at.muli.imaas;

import java.util.Arrays;

import org.apache.commons.lang.StringUtils;
import org.junit.Test;

public class FirstNonNullTest {

	@Test
	public void testFirstNonNull() {
		System.out.println("for");
		long start = System.currentTimeMillis();
		System.out.println(firstNonNull("   ", null, "  a", "b   ", "c"));
		System.out.println(System.currentTimeMillis() - start);
	}
	
	@Test
	public void testFirstNonNullS() {
		System.out.println("stream");
		long start = System.currentTimeMillis();
		System.out.println(firstNonNullS("   ", null, "  a", "b   ", "c"));
		System.out.println(System.currentTimeMillis() - start);
	}
	
	private static String firstNonNullS(String ...strings) {
		return StringUtils.trimToNull(Arrays.asList(strings).stream().filter(StringUtils::isNotBlank).findFirst().orElse(null));
	}
	
	private static String firstNonNull(String ...strings) {
		for (String string : strings) {
			if (StringUtils.isNotBlank(string)) {
				return StringUtils.trimToNull(string);
			}
		}
		return null;
	}
}
