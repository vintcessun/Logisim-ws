package com.cburch.logisim.headless;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test: verifies that the "贮存地址" and "主存内容" components inside
 * "cache（4路组相联映射）测试电路" are ROM components, and that load_memory only
 * proceeds for ROM/RAM components.
 *
 * Run with: ./gradlew :logisim-headless-api:test
 */
class MemoryComponentTypeTest {
	private static final String CIRCUIT_NAME = "cache（4路组相联映射）测试电路";
	private static final String LABEL_MAIN_CONTENT = "主存内容";
	private static final String LABEL_MAIN_ADDRESS = "主存地址";
	private static final String MAIN_ADDR_TXT =
		"test_circuits/cacahe 测试用例/cacahe 测试用例——主存地址.txt";

	private LogisimSessionContext session;

	@BeforeEach
	void setUp() throws Exception {
		// Resolve the test circuit relative to the project root passed by Gradle,
		// or fall back to running from the workspace root.
		String root = System.getProperty("project.root", new File("").getAbsolutePath());
		File circFile = new File(root, "test_circuits/cache 验证实验.circ");
		assertTrue(circFile.exists(), "Test circuit not found: " + circFile.getAbsolutePath());

		session = new LogisimSessionContext(circFile.getAbsolutePath());
		session.switch_circuit(CIRCUIT_NAME);
	}

	@AfterEach
	void tearDown() {
		if (session != null) {
			session.close();
		}
	}

	// -----------------------------------------------------------------------
	// Type-detection tests
	// -----------------------------------------------------------------------

	@Test
	void testMainContentIsROM() {
		Map<String, Object> info = session.getComponentInfo(LABEL_MAIN_CONTENT);

		assertEquals("ROM", info.get("type"), LABEL_MAIN_CONTENT + " should be a ROM component");
		assertTrue(
			(Boolean) info.get("isMemory"), LABEL_MAIN_CONTENT + " should report isMemory=true");
		assertEquals(
			16, info.get("addrBits"), LABEL_MAIN_CONTENT + " should have 16-bit address space");
		assertEquals(8, info.get("dataBits"), LABEL_MAIN_CONTENT + " should have 8-bit data width");
		assertEquals(
			65536L, info.get("capacity"), LABEL_MAIN_CONTENT + " capacity should be 2^16 = 65536");
	}

	@Test
	void testMainAddressIsROM() {
		Map<String, Object> info = session.getComponentInfo(LABEL_MAIN_ADDRESS);

		assertEquals("ROM", info.get("type"), LABEL_MAIN_ADDRESS + " should be a ROM component");
		assertTrue(
			(Boolean) info.get("isMemory"), LABEL_MAIN_ADDRESS + " should report isMemory=true");
		assertEquals(
			10, info.get("addrBits"), LABEL_MAIN_ADDRESS + " should have 10-bit address space");
		assertEquals(
			16, info.get("dataBits"), LABEL_MAIN_ADDRESS + " should have 16-bit data width");
		assertEquals(
			1024L, info.get("capacity"), LABEL_MAIN_ADDRESS + " capacity should be 2^10 = 1024");
	}

	// -----------------------------------------------------------------------
	// Guard: non-memory components must be rejected
	// -----------------------------------------------------------------------

	@Test
	void testLoadMemoryRejectsNonMemoryComponent() {
		String root = System.getProperty("project.root", new File("").getAbsolutePath());
		File txtFile = new File(root, MAIN_ADDR_TXT);
		assertTrue(txtFile.exists(), "Test txt not found: " + txtFile.getAbsolutePath());

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			()
				-> session.loadMemoryFromTxt("Set", txtFile.getAbsolutePath()),
			"loadMemoryFromTxt should reject non-ROM component");

		assertTrue(ex.getMessage().contains("not ROM"),
			"Error message should mention 'not ROM', got: " + ex.getMessage());
	}

	// -----------------------------------------------------------------------
	// Load-memory functional tests
	// -----------------------------------------------------------------------

	@Test
	void testLoadMemoryIntoMainContent() {
		// Write a small block of custom values.
		Map<String, Integer> entries = new HashMap<>();
		entries.put("0x0000", 0xAB);
		entries.put("0x0001", 0xCD);
		entries.put("0x00FF", 0x42);

		// Type guard: must be ROM before loading
		Map<String, Object> info = session.getComponentInfo(LABEL_MAIN_CONTENT);
		assertEquals(
			"ROM", info.get("type"), "Pre-condition: component must be ROM before loading");

		// Should not throw
		assertDoesNotThrow(()
							   -> session.loadMemory(LABEL_MAIN_CONTENT, entries),
			"loadMemory into a ROM component should succeed");
	}

	@Test
	void testLoadMemoryIntoMainAddressFromTxt() {
		String root = System.getProperty("project.root", new File("").getAbsolutePath());
		File txtFile = new File(root, MAIN_ADDR_TXT);
		assertTrue(txtFile.exists(), "Test txt not found: " + txtFile.getAbsolutePath());

		Map<String, Object> info = session.getComponentInfo(LABEL_MAIN_ADDRESS);
		assertEquals(
			"ROM", info.get("type"), "Pre-condition: component must be ROM before loading");

		assertDoesNotThrow(
			()
				-> session.loadMemoryFromTxt(LABEL_MAIN_ADDRESS, txtFile.getAbsolutePath()),
			"loadMemoryFromTxt into a ROM component should succeed");
	}

	@Test
	void testLoadMemoryAddressOutOfRange() {
		// addrBits=16 for 主存内容, so max valid address is 0xFFFF (65535).
		Map<String, Integer> entries = Map.of("0x10000", 0xFF);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			()
				-> session.loadMemory(LABEL_MAIN_CONTENT, entries),
			"Address 0x10000 exceeds 16-bit ROM capacity");

		assertTrue(ex.getMessage().contains("out of range"),
			"Error message should mention 'out of range', got: " + ex.getMessage());
	}

	@Test
	void testLoadMemoryBadAddressFormat() {
		Map<String, Integer> entries = Map.of("not_an_address", 0x00);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			()
				-> session.loadMemory(LABEL_MAIN_CONTENT, entries),
			"Invalid address string should cause an exception");

		assertTrue(ex.getMessage().contains("Invalid address"),
			"Error message should mention 'Invalid address', got: " + ex.getMessage());
	}
}
