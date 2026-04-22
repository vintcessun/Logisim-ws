package com.cburch.logisim.headless;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NearbyTextPinLabelTest {
	private static final String CIRCUIT_NAME = "RAM存储器组件电路（分离模式）";

	private LogisimSessionContext session;

	@BeforeEach
	void setUp() throws Exception {
		String root = System.getProperty("project.root", new File("").getAbsolutePath());
		File circFile = new File(root, "test_circuits/存储器组件验证实验.circ");
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

	@Test
	void testNearbyTextCanBeUsedAsPinLabel() {
		Map<String, List<String>> io = session.getIO();
		assertTrue(io.get("inputs").contains("inputDin"),
			"inputDin should be discovered from nearby text");
		assertTrue(io.get("outputs").contains("outputDout"),
			"outputDout should be discovered from nearby text");
	}

	@Test
	void testSeparatedRamWriteReadUsingDinDoutTextLabels() {
		assertDoesNotThrow(() -> session.setValue("inputleftCLR", "0"));
		assertDoesNotThrow(() -> session.setValue("inputrightCLR", "0"));

		// Stage 1: load address 5 into the counter via Din and tick.
		assertDoesNotThrow(() -> session.setValue("Din", "5"));
		assertDoesNotThrow(() -> session.runTick(2));

		// Stage 2: write 0x12345678 to RAM and tick.
		assertDoesNotThrow(() -> session.setValue("Din", "305419896"));
		assertDoesNotThrow(() -> session.setValue("inputSEL", "0"));
		assertDoesNotThrow(() -> session.setValue("inputLD", "1"));
		assertDoesNotThrow(() -> session.setValue("inputSTR", "1"));
		assertDoesNotThrow(() -> session.runTick(2));

		// Stage 3: verify Dout reflects the written value.
		assertDoesNotThrow(() -> session.checkValue("Dout", "305419896"));
	}
}
